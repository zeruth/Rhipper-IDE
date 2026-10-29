// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.text.DateFormatUtil
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.corpus.CorpusFunction
import com.rhipper.ide.corpus.FidelityEntry
import com.rhipper.ide.corpus.GenFunction
import com.rhipper.ide.corpus.RhipperCorpus
import com.rhipper.ide.corpus.RhipperFidelity
import com.rhipper.ide.corpus.RhipperGenIndex
import com.rhipper.ide.corpus.RhipperNavigator
import com.rhipper.ide.corpus.RhipperView
import com.rhipper.ide.pipeline.PortRun
import com.rhipper.ide.pipeline.RhipperPipelineStatus
import com.rhipper.ide.pipeline.RhipperStep
import com.rhipper.ide.pipeline.RhipperStepScope
import com.rhipper.ide.pipeline.RhipperStepState
import com.rhipper.ide.pipeline.RhipperStepStatus
import com.rhipper.ide.pipeline.readPortRuns
import com.rhipper.ide.pipeline.runStep
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.RhipperProjectService
import com.rhipper.ide.project.RhipperProjectState
import com.rhipper.ide.project.formatAddress
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.settings.RhipperSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** What the tool window shows of the project's outputs, read once and again on each refresh. */
private data class ProjectData(
  val config: RhipperProjectConfig,
  val functions: List<CorpusFunction>?,
  val translations: Map<Long, GenFunction>,
  val fidelity: List<FidelityEntry>,
  val fidelityByAddress: Map<Long, FidelityEntry>,
  val runs: List<PortRun>,
) {
  val corpusStats: CorpusStats? = functions?.let { list -> CorpusStats(list.size, list.count { it.failed }) }
  val translationStats: TranslationStats =
    TranslationStats(translations.size, translations.values.mapTo(HashSet()) { it.file }.size, functions?.size ?: 0)
  val fidelityStats: FidelityStats? = fidelityStatsOf(fidelity)
}

private const val LOW_FIDELITY = 0.5

private fun bucketOf(score: Double): Int = (score * 10).toInt().coerceIn(0, 9)

private fun fidelityStatsOf(entries: List<FidelityEntry>): FidelityStats? {
  val scores = entries.mapNotNull { it.score }
  if (scores.isEmpty()) return null
  val buckets = IntArray(10)
  scores.forEach { buckets[bucketOf(it)]++ }
  return FidelityStats(scores.size, scores.average(), scores.count { it < LOW_FIDELITY }, buckets.toList())
}

/**
 * The boundary between the Rhipper services and the composables of the tool window.
 *
 * It maps the services to the immutable states of the three tabs, and turns the callbacks of the composables into platform work.
 * The composables never touch the platform.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
internal class RhipperDashboardPresenter(private val project: Project, private val scope: CoroutineScope) {
  private val projectService = RhipperProjectService.getInstance(project)
  private val pipelineStatus = RhipperPipelineStatus.getInstance(project)

  private val data = MutableStateFlow<ProjectData?>(null)
  private val loading = MutableStateFlow(true)

  private val query = MutableStateFlow("")
  private val filter = MutableStateFlow(FunctionFilter.ALL)
  private val selectedFunction = MutableStateFlow<Long?>(null)

  private val fidelityKind = MutableStateFlow<String?>(null)
  private val fidelityBucket = MutableStateFlow<Int?>(null)
  private val selectedFidelity = MutableStateFlow<Long?>(null)

  /** A tick each second, so that the time of a running step counts up. */
  private val clock = flow {
    while (true) {
      emit(Instant.now())
      delay(1000)
    }
  }

  val overview: StateFlow<OverviewState> =
    combine(projectService.state, pipelineStatus.steps, data, loading, clock) { state, steps, data, loading, now ->
      overviewState(state, steps, data, loading, now)
    }
      .distinctUntilChanged()
      .flowOn(Dispatchers.Default)
      .stateIn(scope, SharingStarted.Eagerly, OverviewState())

  val functions: StateFlow<FunctionsState> =
    combine(data, loading, query.debounce(150), filter, selectedFunction) { data, loading, query, filter, selected ->
      functionsState(data, loading, query, filter, selected)
    }
      .flowOn(Dispatchers.Default)
      .stateIn(scope, SharingStarted.Eagerly, FunctionsState())

  val fidelity: StateFlow<FidelityState> =
    combine(data, loading, fidelityKind, fidelityBucket, selectedFidelity) { data, loading, kind, bucket, selected ->
      fidelityState(data, loading, kind, bucket, selected)
    }
      .flowOn(Dispatchers.Default)
      .stateIn(scope, SharingStarted.Eagerly, FidelityState())

  init {
    scope.launch {
      projectService.state.map { (it as? RhipperProjectState.Loaded)?.config }.distinctUntilChanged().collect { load() }
    }
  }

  fun refresh() {
    scope.launch {
      projectService.reload()
      load()
    }
  }

  private suspend fun load() {
    loading.value = true
    val config = projectService.config
    if (config == null) {
      data.value = null
      loading.value = false
      return
    }
    pipelineStatus.refreshFromLogs(config)
    val corpus = RhipperCorpus.getInstance(project).index()
    val translations = RhipperGenIndex.getInstance(project).index()
    val fidelityService = RhipperFidelity.getInstance(project)
    val fidelity = fidelityService.entries()
    val runs = withContext(Dispatchers.IO) { readPortRuns(config, limit = 50) }
    data.value = ProjectData(config, corpus?.functions, translations, fidelity, fidelityService.byAddress(), runs)
    loading.value = false
  }

  // region Callbacks of the composables

  fun runStep(stepId: String, fromHere: Boolean) {
    val step = RhipperStep.of(stepId) ?: return
    scope.launch(Dispatchers.EDT) { runStep(project, step, if (fromHere) RhipperStepScope.FROM else RhipperStepScope.ONLY) }
  }

  fun openLog(stepId: String) {
    val step = RhipperStep.of(stepId) ?: return
    val config = projectService.config ?: return
    val name = if (step == RhipperStep.TRANSLATE && config.arch == RhipperArch.X86) "translate32.log" else step.logName
    openFile(config.pipelineLogPath.resolve(name))
  }

  fun openConfig() {
    projectService.configFile()?.let(::openFile)
  }

  fun openSettings() {
    scope.launch(Dispatchers.EDT) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, RhipperBundle.message("settings.display.name"))
    }
  }

  fun open(address: Long, view: RhipperView) {
    RhipperNavigator.getInstance(project).open(formatAddress(address), view)
  }

  /** Opens the function of a fault such as `F_00e14a10+0x126 c0000005 reading 0`: its translation. */
  fun openFault(fault: String) {
    val symbol = FAULT_SYMBOL.find(fault)?.value ?: return
    RhipperNavigator.getInstance(project).open(symbol, if (symbol.startsWith("F_")) RhipperView.TRANSLATION else RhipperView.DECOMPILATION)
  }

  fun setQuery(text: String) {
    query.value = text
  }

  fun setFilter(value: FunctionFilter) {
    filter.value = value
  }

  fun selectFunction(address: Long) {
    selectedFunction.value = address
  }

  fun setFidelityKind(kind: String?) {
    fidelityKind.value = kind
  }

  fun setFidelityBucket(bucket: Int?) {
    fidelityBucket.value = if (fidelityBucket.value == bucket) null else bucket
  }

  fun selectFidelity(address: Long) {
    selectedFidelity.value = address
  }

  // endregion

  private fun openFile(path: Path) {
    scope.launch {
      val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) } ?: return@launch
      withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file).navigate(true) }
    }
  }

  // region State mapping

  private fun overviewState(
    state: RhipperProjectState,
    steps: Map<RhipperStep, RhipperStepStatus>,
    data: ProjectData?,
    loading: Boolean,
    now: Instant,
  ): OverviewState {
    val config = (state as? RhipperProjectState.Loaded)?.config
    val header = config?.let {
      ProjectHeader(
        name = it.name,
        arch = it.arch.id,
        toolchain = it.toolchain,
        exe = it.exe,
        imageBase = it.imageBase,
        rhipperHome = RhipperSettings.getInstance().rhipperHome(it)?.toString(),
      )
    }
    return OverviewState(
      header = header,
      configError = (state as? RhipperProjectState.Invalid)?.message,
      steps = RhipperStep.entries.map { stepCard(steps.getValue(it), now) },
      corpus = data?.corpusStats,
      translation = data?.translationStats,
      fidelity = data?.fidelityStats,
      runs = data?.runs.orEmpty().take(12).map(::portRunItem),
      loading = loading,
    )
  }

  private fun stepCard(status: RhipperStepStatus, now: Instant): StepCard {
    val phase = when (status.state) {
      RhipperStepState.NOT_RUN -> if (status.finished != null) StepPhase.DONE else StepPhase.NOT_RUN
      RhipperStepState.RUNNING -> StepPhase.RUNNING
      RhipperStepState.SUCCEEDED -> StepPhase.SUCCEEDED
      RhipperStepState.FAILED -> StepPhase.FAILED
      RhipperStepState.STOPPED -> StepPhase.STOPPED
    }
    val timeText = when {
      phase == StepPhase.RUNNING && status.started != null ->
        RhipperBundle.message("overview.step.running.for", elapsed(Duration.between(status.started, now)))
      status.finished != null -> DateFormatUtil.formatPrettyDateTime(status.finished!!.toEpochMilli())
      else -> null
    }
    return StepCard(status.step.id, status.step.displayName, status.step.description, phase, timeText, status.activity)
  }

  private fun portRunItem(run: PortRun) = PortRunItem(
    time = run.time,
    commit = run.commit,
    dirtyGen = run.dirtyGen,
    seconds = run.seconds,
    succeeded = run.succeeded,
    alive = run.alive,
    exit = run.exit,
    fault = run.fault,
  )

  private fun functionsState(data: ProjectData?, loading: Boolean, query: String, filter: FunctionFilter, selected: Long?): FunctionsState {
    val functions = data?.functions ?: return FunctionsState(loading = loading, hasCorpus = data == null && loading)
    val all = functions.map { functionItem(it, data) }
    val counts = FunctionFilter.entries.associateWith { f -> if (f == FunctionFilter.ALL) all.size else all.count { matches(it, f) } }
    val text = query.trim()
    val address = parseAddress(text.removePrefix("FUN_").removePrefix("F_"))
    val items = all.filter { item ->
      matches(item, filter) && (text.isEmpty() || item.name.contains(text, ignoreCase = true) ||
                                (address != null && (item.address == address || item.addressText.contains(text.lowercase()))))
    }
    return FunctionsState(
      loading = loading,
      hasCorpus = true,
      total = all.size,
      filter = filter,
      counts = counts,
      items = items,
      selected = selected?.let { address -> all.firstOrNull { it.address == address } }?.let { detail(it, data) },
    )
  }

  private fun fidelityState(data: ProjectData?, loading: Boolean, kind: String?, bucket: Int?, selected: Long?): FidelityState {
    if (data == null) return FidelityState(loading = loading)
    val scored = data.fidelity.filter { it.score != null }
    val ofKind = if (kind == null) scored else scored.filter { it.kind == kind }
    val items = ofKind
      .filter { bucket == null || bucketOf(it.score!!) == bucket }
      .sortedBy { it.score }
      .map { entry -> functionItem(entry, data) }
    return FidelityState(
      loading = loading,
      stats = fidelityStatsOf(ofKind),
      kinds = scored.map { it.kind }.distinct().sorted(),
      kind = kind,
      bucket = bucket,
      items = items,
      selected = selected?.let { address -> items.firstOrNull { it.address == address } }?.let { detail(it, data) },
    )
  }

  private fun functionItem(function: CorpusFunction, data: ProjectData): FunctionItem {
    val translation = data.translations[function.address]
    val fidelity = data.fidelityByAddress[function.address]
    return FunctionItem(
      address = function.address,
      addressText = formatAddress(function.address),
      name = translation?.refName?.takeIf { function.name.startsWith("FUN_") } ?: function.name,
      size = function.size,
      status = when {
        function.failed -> FunctionStatus.DECOMPILER_FAILED
        translation != null -> FunctionStatus.TRANSLATED
        else -> FunctionStatus.NOT_TRANSLATED
      },
      kind = fidelity?.kind,
      score = fidelity?.score,
    )
  }

  private fun functionItem(entry: FidelityEntry, data: ProjectData): FunctionItem = FunctionItem(
    address = entry.address,
    addressText = formatAddress(entry.address),
    name = entry.name,
    size = entry.size,
    status = if (data.translations.containsKey(entry.address)) FunctionStatus.TRANSLATED else FunctionStatus.NOT_TRANSLATED,
    kind = entry.kind,
    score = entry.score,
  )

  private fun detail(item: FunctionItem, data: ProjectData): FunctionDetail {
    val translation = data.translations[item.address]
    val fidelity = data.fidelityByAddress[item.address]
    return FunctionDetail(
      item = item,
      refName = translation?.refName,
      translationLocation = translation?.let { "${it.file.fileName}:${it.line + 1}" },
      missingCalls = fidelity?.missingCalls.orEmpty().map(::callRef),
      extraCalls = fidelity?.extraCalls.orEmpty().map(::callRef),
    )
  }

  // endregion

  companion object {
    private val FAULT_SYMBOL = Regex("""\b(F_[0-9a-fA-F]+|FUN_[0-9a-fA-F]+|0x[0-9a-fA-F]{5,})""")
    private val CALL_ADDRESS = Regex("""^([0-9a-fA-F]{6,16})\b""")

    private fun matches(item: FunctionItem, filter: FunctionFilter): Boolean = when (filter) {
      FunctionFilter.ALL -> true
      FunctionFilter.TRANSLATED -> item.status == FunctionStatus.TRANSLATED
      FunctionFilter.NOT_TRANSLATED -> item.status == FunctionStatus.NOT_TRANSLATED
      FunctionFilter.FAILED -> item.status == FunctionStatus.DECOMPILER_FAILED
      FunctionFilter.LOW_FIDELITY -> item.score != null && item.score < LOW_FIDELITY
    }

    private fun callRef(text: String) = CallRef(text, CALL_ADDRESS.find(text)?.groupValues?.get(1)?.let(::parseAddress))

    private fun elapsed(duration: Duration): String {
      val seconds = duration.seconds.coerceAtLeast(0)
      return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds % 3600 / 60, seconds % 60)
      else "%d:%02d".format(seconds / 60, seconds % 60)
    }
  }
}
