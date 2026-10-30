// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.mcp

import com.intellij.execution.ExecutionException
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.project.Project
import com.rhipper.ide.corpus.FidelityChange
import com.rhipper.ide.corpus.FidelityEntry
import com.rhipper.ide.corpus.FunctionQuery
import com.rhipper.ide.corpus.FunctionSort
import com.rhipper.ide.corpus.RhipperCorpus
import com.rhipper.ide.corpus.RhipperFidelity
import com.rhipper.ide.corpus.RhipperFidelityWatch
import com.rhipper.ide.corpus.RhipperGenIndex
import com.rhipper.ide.corpus.RhipperNavigator
import com.rhipper.ide.corpus.RhipperView
import com.rhipper.ide.corpus.TranslationState
import com.rhipper.ide.corpus.queryFunctions as runFunctionQuery
import com.rhipper.ide.pipeline.RhipperPipelineStatus
import com.rhipper.ide.pipeline.RhipperRunResult
import com.rhipper.ide.pipeline.RhipperStep
import com.rhipper.ide.pipeline.RhipperStepScope
import com.rhipper.ide.pipeline.runRhipperScript
import com.rhipper.ide.pipeline.runStepAndWait
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.formatAddress
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.project.rhipperConfig
import com.rhipper.ide.settings.RhipperSettings
import com.rhipper.ide.lsp.ClangdDiagnostics
import com.rhipper.ide.lsp.ClangdSeverity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Files
import kotlin.io.path.isRegularFile

/**
 * The tools of the Rhipper workflow: the project, the pipeline steps, the corpus, the translation, and Rhipper's inspection scripts.
 *
 * Each tool works on the Rhipper project of the IDE project that the MCP call names.
 */
internal class RhipperMcpToolset : McpToolset {
  override fun isExperimental(): Boolean = false

  @McpTool(name = "rhipper_project")
  @McpDescription("""
    Returns the Rhipper project of the IDE project: the rhipper.json settings, the Rhipper directory, the state of each pipeline step
    (facts, ghidra, translate, build, run), and which outputs exist (the corpus, the fidelity report, the generated sources).
    Call it first to learn the reference binary, its architecture and where the outputs are.
  """)
  suspend fun project(): String {
    val project = currentCoroutineContext().project
    val config = requireConfig(project)
    val steps = RhipperPipelineStatus.getInstance(project).steps.value
    return buildJsonObject {
      put("root", config.root.toString())
      put("rhipperHome", RhipperSettings.getInstance().rhipperHome(config)?.toString())
      put("config", config.json)
      putJsonObject("outputs") {
        put("corpus", outputPath(config.corpusPath))
        put("fidelity", outputPath(config.fidelityPath))
        put("facts", config.factsPath.takeIf { Files.isDirectory(it) }?.toString())
        put("gen", config.genPath.takeIf { Files.isDirectory(it) }?.toString())
        put("pipelineLogs", config.pipelineLogPath.toString())
      }
      putJsonArray("steps") {
        for (step in RhipperStep.entries) {
          val status = steps.getValue(step)
          add(buildJsonObject {
            put("step", step.id)
            put("state", status.state.name.lowercase())
            put("started", status.started?.toString())
            put("finished", status.finished?.toString())
            put("activity", status.activity)
          })
        }
      }
    }.toString()
  }

  @McpTool(name = "rhipper_run_step")
  @McpDescription("""
    Runs a step of the Rhipper pipeline in the IDE's Run tool window, where the user can watch it, and waits for its end.
    Steps in order: facts, ghidra, translate, build, run. With from=true it runs the step and every step after it.
    Returns the exit code and the end of the output. The exit code is null when the wait timed out; the run goes on, and
    rhipper_project shows its state. The facts and ghidra steps can take many minutes on a large binary.
  """)
  suspend fun runStep(
    @McpDescription("The step: facts, ghidra, translate, build or run") step: String,
    @McpDescription("Run every step after this one too") from: Boolean = false,
    @McpDescription("How long to wait for the end, in seconds") timeoutSeconds: Int = 1800,
  ): String {
    val project = currentCoroutineContext().project
    requireConfig(project)
    val pipelineStep = RhipperStep.of(step) ?: throw McpExpectedError("Unknown step '$step'. Use one of: ${RhipperStep.entries.joinToString { it.id }}")
    val scope = if (from) RhipperStepScope.FROM else RhipperStepScope.ONLY
    return runResult(runStepAndWait(project, pipelineStep, scope, timeoutSeconds.coerceIn(1, 24 * 3600) * 1000L))
  }

  @McpTool(name = "rhipper_function")
  @McpDescription("""
    Returns a function of the reference binary: its address, size, Ghidra's decompilation from the corpus, the translated C from the
    generated sources, and its fidelity score. The query is an address (0x401000, 401000), a name with an address
    (FUN_00401000, F_00401000), or a function name.
  """)
  suspend fun function(@McpDescription("An address or a function name") query: String): String {
    val project = currentCoroutineContext().project
    requireConfig(project)
    val corpus = RhipperCorpus.getInstance(project)
    val index = corpus.index()
    val function = index?.find(query)
    val address = function?.address ?: queryAddress(query) ?: throw McpExpectedError("No function in the corpus matches '$query'")
    val entry = function?.let { corpus.read(it) }
    val genIndex = RhipperGenIndex.getInstance(project)
    val translation = genIndex.find(address)
    val fidelity = RhipperFidelity.getInstance(project).byAddress()[address]
    return buildJsonObject {
      put("address", formatAddress(address))
      put("name", function?.name)
      put("size", function?.size)
      put("decompilation", entry?.c)
      put("decompilerError", entry?.error)
      val translationText = translation?.let { genIndex.text(it) }
      put("translation", translationText)
      if (translation != null && translationText != null) {
        val path = translation.file.toString().replace('\\', '/')
        val firstLine = translation.line
        val lastLine = translation.line + translationText.count { it == '\n' } + 1
        val fileDiagnostics = ClangdDiagnostics.getInstance(project).snapshot().files.entries
          .firstOrNull { it.key.equals(path, ignoreCase = true) }?.value?.second
        put("clangdAnalyzed", fileDiagnostics != null)
        val diagnostics = fileDiagnostics.orEmpty().filter { it.line in firstLine..lastLine }
        put("clangdDiagnostics", JsonArray(diagnostics.map { d ->
          JsonPrimitive("${d.line}:${d.column} ${d.severity.name.lowercase()} ${d.code.orEmpty()} ${d.message}")
        }))
      }
      put("translationFile", translation?.let { "${it.file}:${it.line + 1}" })
      put("translationRefName", translation?.refName)
      if (fidelity != null) {
        putJsonObject("fidelity") {
          put("kind", fidelity.kind)
          put("score", fidelity.score)
          putJsonArray("missingCalls") { fidelity.missingCalls.forEach { add(JsonPrimitive(it)) } }
          putJsonArray("extraCalls") { fidelity.extraCalls.forEach { add(JsonPrimitive(it)) } }
        }
      }
    }.toString()
  }

  @McpTool(name = "rhipper_query_functions")
  @McpDescription("""
    Finds functions of the reference by any mix of filters over the corpus, the translation and the fidelity report, and returns a page
    of them with the total that matched. Every filter is optional; a function matches when it matches every filter that is set.
    Examples: the worst translated functions: states=["translated"], scored=true, sort="score".
    Functions below 0.5 that miss a call to free: maxScore=0.5, missingCall="free".
    Large functions that the decompiler failed on: states=["decompiler_failed"], minSize=1000, sort="size", descending=true.
    The functions of a range: addressFrom="0x9e0000", addressTo="0xa00000".
    Page with offset and limit; the result says the total, so you can tell when there are more.
  """)
  suspend fun queryFunctions(
    @McpDescription("Text in the name, the translation's ref: name, or an address inside the function (0x401000, FUN_00401000)")
    text: String? = null,
    @McpDescription("A regular expression that must match a part of the name or the ref: name, for example ^Apricot::Engine::")
    nameRegex: String? = null,
    @McpDescription("The lowest address, inclusive") addressFrom: String? = null,
    @McpDescription("The first address after the range") addressTo: String? = null,
    @McpDescription("The smallest size in bytes") minSize: Int? = null,
    @McpDescription("The largest size in bytes") maxSize: Int? = null,
    @McpDescription("Any of: translated, not_translated, decompiler_failed") states: List<String>? = null,
    @McpDescription("The lowest fidelity score, inclusive, from 0 to 1") minScore: Double? = null,
    @McpDescription("The highest fidelity score, exclusive: 0.5 means below 0.5") maxScore: Double? = null,
    @McpDescription("true: only functions the fidelity report scored; false: only functions it did not score") scored: Boolean? = null,
    @McpDescription("Any of the fidelity kinds, for example translated or same") kinds: List<String>? = null,
    @McpDescription("Text in one of the missing calls: callees the reference calls and the port does not") missingCall: String? = null,
    @McpDescription("Text in one of the extra calls: callees only the port calls") extraCall: String? = null,
    @McpDescription("true: only functions whose calls differ from the reference; false: only functions whose calls agree")
    callsDiffer: Boolean? = null,
    @McpDescription("address, name, size or score") sort: String = "address",
    @McpDescription("Sort from the largest value") descending: Boolean = false,
    @McpDescription("The number of matches to skip") offset: Int = 0,
    @McpDescription("The most matches to return, up to 5000") limit: Int = 50,
    @McpDescription("Also return the missing and the extra calls of each function") includeCalls: Boolean = false,
  ): String {
    val project = currentCoroutineContext().project
    requireConfig(project)
    val query = FunctionQuery(
      text = text,
      nameRegex = nameRegex?.takeIf { it.isNotBlank() }?.let {
        try {
          Regex(it, RegexOption.IGNORE_CASE)
        }
        catch (e: IllegalArgumentException) {
          throw McpExpectedError("Not a valid regular expression: ${e.message}")
        }
      },
      addressFrom = addressFrom?.let { queryAddress(it) ?: throw McpExpectedError("Not an address: '$it'") },
      addressTo = addressTo?.let { queryAddress(it) ?: throw McpExpectedError("Not an address: '$it'") },
      minSize = minSize,
      maxSize = maxSize,
      states = states?.map { name ->
        TranslationState.entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        ?: throw McpExpectedError("Unknown state '$name'. Use translated, not_translated or decompiler_failed.")
      }?.toSet(),
      minScore = minScore,
      maxScore = maxScore,
      scored = scored,
      kinds = kinds?.toSet(),
      missingCall = missingCall?.takeIf { it.isNotBlank() },
      extraCall = extraCall?.takeIf { it.isNotBlank() },
      callsDiffer = callsDiffer,
      sort = FunctionSort.entries.firstOrNull { it.name.equals(sort, ignoreCase = true) }
             ?: throw McpExpectedError("Unknown sort '$sort'. Use address, name, size or score."),
      descending = descending,
      offset = offset,
      limit = limit,
    )
    val result = runFunctionQuery(project, query)
    return buildJsonObject {
      put("total", result.total)
      put("offset", query.offset)
      put("returned", result.functions.size)
      putJsonArray("functions") {
        for (record in result.functions) {
          add(buildJsonObject {
            put("address", formatAddress(record.address))
            put("name", record.name)
            record.translation?.refName?.takeIf { it != record.name }?.let { put("refName", it) }
            put("size", record.size)
            put("state", record.state.name.lowercase())
            put("score", record.score)
            put("kind", record.fidelity?.kind)
            if (includeCalls && record.fidelity != null) {
              put("missingCalls", JsonArray(record.fidelity!!.missingCalls.map { JsonPrimitive(it) }))
              put("extraCalls", JsonArray(record.fidelity!!.extraCalls.map { JsonPrimitive(it) }))
            }
          })
        }
      }
    }.toString()
  }

  @McpTool(name = "rhipper_fidelity_summary")
  @McpDescription("""
    Summarizes the fidelity report: the number of scored functions, the average score, how many are below the threshold,
    a histogram of the scores in ten ranges (0.0-0.1 first), and the same numbers for each fidelity kind.
  """)
  suspend fun fidelitySummary(
    @McpDescription("The score below which a function counts as low") threshold: Double = 0.5,
  ): String {
    val project = currentCoroutineContext().project
    requireConfig(project)
    val entries = RhipperFidelity.getInstance(project).entries()
    if (entries.isEmpty()) throw McpExpectedError("The project has no fidelity report. Run the rhipper_fidelity_changes tool with refresh=true.")
    fun summary(list: List<FidelityEntry>) = buildJsonObject {
      val scores = list.mapNotNull { it.score }
      put("functions", list.size)
      put("scored", scores.size)
      put("average", if (scores.isEmpty()) null else scores.average())
      put("below", scores.count { it < threshold })
      put("perfect", scores.count { it >= 0.999 })
      val buckets = IntArray(10)
      scores.forEach { buckets[(it * 10).toInt().coerceIn(0, 9)]++ }
      put("histogram", JsonArray(buckets.map { JsonPrimitive(it) }))
    }
    return buildJsonObject {
      put("threshold", threshold)
      put("all", summary(entries))
      putJsonObject("byKind") {
        entries.groupBy { it.kind }.toSortedMap().forEach { (kind, list) -> put(kind.ifEmpty { "(none)" }, summary(list)) }
      }
    }.toString()
  }

  @McpTool(name = "rhipper_fidelity_changes")
  @McpDescription("""
    Polls the fidelity report for changes. Each call compares the report with the snapshot that the last call of the same snapshot
    name took, then takes a new snapshot. It returns the functions that regressed or improved by more than the tolerance, that fell
    below the threshold, or that rose above it, and the functions that the report added or removed. The first call of a name only takes
    the snapshot. Use it around a fix: call it, run the translate and build steps, call it again with refresh=true.
    refresh=true runs Rhipper's fidelity script first, which measures the built port again; this takes minutes on a large port.
  """)
  suspend fun fidelityChanges(
    @McpDescription("The score below which a function counts as low") threshold: Double = 0.5,
    @McpDescription("The name of the snapshot to compare with, so that several watches can run side by side") snapshot: String = "default",
    @McpDescription("The smallest change of a score that counts") tolerance: Double = 0.005,
    @McpDescription("Measure the port again before the comparison") refresh: Boolean = false,
    @McpDescription("Compare without replacing the snapshot, so that the next call compares with the same one") keepSnapshot: Boolean = false,
    @McpDescription("The most functions to return in each list") limit: Int = 50,
  ): String {
    val project = currentCoroutineContext().project
    val config = requireConfig(project)
    var refreshResult: RhipperRunResult? = null
    if (refresh) {
      val (script, args) = if (config.arch == RhipperArch.X86) "fidelity32.py" to listOf("--quiet") else "fidelity.py" to listOf("--summary")
      refreshResult = runScript(project, script, args, timeoutMillis = 3_600_000)
      if (refreshResult.exitCode != 0) return runResult(refreshResult)
    }
    val changes = RhipperFidelityWatch.getInstance(project).changes(snapshot, threshold, tolerance, keepSnapshot)
    val max = limit.coerceIn(1, 5000)
    fun list(name: String, changes: List<FidelityChange>) = buildJsonObject {
      put("count", changes.size)
      put("functions", JsonArray(changes.take(max).map { change ->
        buildJsonObject {
          put("address", formatAddress(change.address))
          put("name", change.name)
          put("before", change.before)
          put("after", change.after)
        }
      }))
    }.let { name to it }
    return buildJsonObject {
      put("snapshot", snapshot)
      put("comparedWith", changes.since?.toString())
      if (changes.since == null) put("note", "First call for this snapshot name: the snapshot is taken, there is nothing to compare yet.")
      put("threshold", changes.threshold)
      put("belowNow", changes.belowNow)
      refreshResult?.let { put("refreshOutputTail", it.output.takeLast(2000)) }
      for ((name, value) in listOf(
        list("regressed", changes.regressed),
        list("improved", changes.improved),
        list("newlyBelow", changes.newlyBelow),
        list("recovered", changes.recovered),
        list("added", changes.added),
        list("removed", changes.removed),
      )) {
        put(name, value)
      }
    }.toString()
  }

  @McpTool(name = "rhipper_clangd_diagnostics")
  @McpDescription("""
    Reports the errors and warnings that clangd found in the C and C++ files of the project, grouped by diagnostic code, so that a
    problem that the translator repeats stands out. A diagnostic in the generated sources can be a translation bug, or a place where
    the translator or the runtime headers can improve.
    clangd analyzes only the files that are open in an editor. Pass openFiles to open more files in the background and wait for their
    analysis; a large generated file can take a minute. A file with no diagnostics after the wait is listed in pending.
  """)
  suspend fun clangdDiagnostics(
    @McpDescription("The least severity to report: error, warning, information or hint") minSeverity: String = "warning",
    @McpDescription("Text in the file path, for example src/gen/translated_01") pathContains: String? = null,
    @McpDescription("Text in the diagnostic code, for example -Wint-conversion") code: String? = null,
    @McpDescription("Text in the message") messageContains: String? = null,
    @McpDescription("Files to open for clangd first, absolute or relative to the project root") openFiles: List<String>? = null,
    @McpDescription("How long to wait for the analysis of openFiles, in seconds") waitSeconds: Int = 60,
    @McpDescription("The most diagnostics to list; the groups and the file totals count all of them") limit: Int = 100,
  ): String {
    val project = currentCoroutineContext().project
    val config = requireConfig(project)
    val severity = ClangdSeverity.entries.firstOrNull { it.name.equals(minSeverity, ignoreCase = true) }
                   ?: throw McpExpectedError("Unknown severity '$minSeverity'. Use error, warning, information or hint.")
    val store = ClangdDiagnostics.getInstance(project)
    val pending = ArrayList<String>()
    if (!openFiles.isNullOrEmpty()) {
      val paths = openFiles.map { config.root.resolve(it).normalize() }
      for (path in paths) {
        if (!RhipperNavigator.getInstance(project).openInBackground(path)) throw McpExpectedError("No file at $path")
      }
      val keys = paths.map { it.toString().replace('\\', '/') }
      withTimeoutOrNull(waitSeconds.coerceIn(0, 1800) * 1000L) {
        while (!keys.all { store.hasDiagnostics(it) }) delay(500)
      }
      keys.filterNotTo(pending) { store.hasDiagnostics(it) }
    }

    val snapshot = store.snapshot()
    val all = snapshot.files.values.flatMap { it.second }.filter { diagnostic ->
      diagnostic.severity.ordinal <= severity.ordinal &&
      (pathContains == null || diagnostic.path.contains(pathContains.replace('\\', '/'), ignoreCase = true)) &&
      (code == null || diagnostic.code?.contains(code, ignoreCase = true) == true) &&
      (messageContains == null || diagnostic.message.contains(messageContains, ignoreCase = true))
    }
    val root = config.root.toString().replace('\\', '/')
    fun relative(path: String) = path.removePrefix("$root/")
    return buildJsonObject {
      put("analyzedFiles", snapshot.files.size)
      put("note", "Only files that clangd analyzed in this session count. Open more with openFiles.")
      putJsonObject("totals") {
        ClangdSeverity.entries.forEach { s -> put(s.name.lowercase(), all.count { it.severity == s }) }
      }
      putJsonArray("byCode") {
        all.groupBy { it.code ?: it.message.take(60) }
          .entries.sortedWith(compareBy({ it.value.minOf { d -> d.severity.ordinal } }, { -it.value.size }))
          .forEach { (key, items) ->
            val example = items.first()
            add(buildJsonObject {
              put("code", key)
              put("severity", items.minBy { it.severity.ordinal }.severity.name.lowercase())
              put("count", items.size)
              put("files", items.map { it.path }.distinct().size)
              put("example", "${relative(example.path)}:${example.line}:${example.column}: ${example.message}")
            })
          }
      }
      putJsonArray("files") {
        all.groupBy { it.path }.entries.sortedByDescending { it.value.size }.forEach { (path, items) ->
          add(buildJsonObject {
            put("path", relative(path))
            put("errors", items.count { it.severity == ClangdSeverity.ERROR })
            put("warnings", items.count { it.severity == ClangdSeverity.WARNING })
            put("analyzed", snapshot.files[path]?.first?.toString())
          })
        }
      }
      putJsonArray("diagnostics") {
        all.sortedWith(compareBy({ it.severity.ordinal }, { it.path }, { it.line })).take(limit.coerceIn(1, 5000)).forEach { d ->
          add(buildJsonObject {
            put("location", "${relative(d.path)}:${d.line}:${d.column}")
            put("severity", d.severity.name.lowercase())
            put("code", d.code)
            put("message", d.message)
          })
        }
      }
      if (pending.isNotEmpty()) put("pending", JsonArray(pending.map { JsonPrimitive(relative(it)) }))
    }.toString()
  }

  @McpTool(name = "rhipper_port_runs")
  @McpDescription("Returns the latest runs of the port from build/pipeline/runs.jsonl, newest first: the exit code and the fault address of each.")
  suspend fun portRuns(@McpDescription("The most runs to return") limit: Int = 10): String {
    val project = currentCoroutineContext().project
    val config = requireConfig(project)
    if (!config.runsPath.isRegularFile()) throw McpExpectedError("No runs of the port are logged yet")
    return Files.readAllLines(config.runsPath).asReversed().take(limit.coerceIn(1, 200)).joinToString("\n")
  }

  @McpTool(name = "rhipper_disassemble")
  @McpDescription("Disassembles the reference binary at an address with Rhipper's disasm.py: the whole function, or length bytes.")
  suspend fun disassemble(
    @McpDescription("The address, for example 0x401000") address: String,
    @McpDescription("The number of bytes in hexadecimal; empty for the whole function") length: String = "",
  ): String {
    val project = currentCoroutineContext().project
    val start = queryAddress(address) ?: throw McpExpectedError("Not an address: '$address'")
    val args = listOf(java.lang.Long.toHexString(start)) + listOfNotNull(length.trim().removePrefix("0x").takeIf { it.isNotEmpty() })
    return runResult(runScript(project, "disasm.py", args))
  }

  @McpTool(name = "rhipper_callers")
  @McpDescription("Lists every direct call or jump to a function of the reference, with the caller and its source file (Rhipper's callers.py).")
  suspend fun callers(@McpDescription("The address of the callee") address: String): String {
    val project = currentCoroutineContext().project
    val target = queryAddress(address) ?: throw McpExpectedError("Not an address: '$address'")
    return runResult(runScript(project, "callers.py", listOf(java.lang.Long.toHexString(target))))
  }

  @McpTool(name = "rhipper_xrefs")
  @McpDescription("Lists every instruction of the reference that references an address range [lo, hi), with its function (Rhipper's xrefs.py).")
  suspend fun xrefs(
    @McpDescription("The first address") lo: String,
    @McpDescription("The address after the range; empty for lo + 1") hi: String = "",
  ): String {
    val project = currentCoroutineContext().project
    val low = queryAddress(lo) ?: throw McpExpectedError("Not an address: '$lo'")
    val args = listOf(java.lang.Long.toHexString(low)) + listOfNotNull(queryAddress(hi)?.let { java.lang.Long.toHexString(it) })
    return runResult(runScript(project, "xrefs.py", args))
  }

  @McpTool(name = "rhipper_script")
  @McpDescription("""
    Runs any Python tool of the Rhipper directory with arguments, in the project root with RHIPPER_PROJECT set, and returns its output.
    Examples: shadow_diff.py build/shadow-ref.bin run-port.bin; corpus.py --addr 401000; fidelity.py --file src/model2/.
    The script is a file name in the Rhipper directory or a path under it, such as tests/rhtest/rhtest.py.
  """)
  suspend fun script(
    @McpDescription("The script, relative to the Rhipper directory") script: String,
    @McpDescription("The arguments") args: List<String> = emptyList(),
    @McpDescription("How long to wait, in seconds") timeoutSeconds: Int = 600,
  ): String {
    val project = currentCoroutineContext().project
    val config = requireConfig(project)
    val home = RhipperSettings.getInstance().rhipperHome(config) ?: throw McpExpectedError("Rhipper is not found. Set it in Settings | Tools | Rhipper.")
    val path = home.resolve(script).normalize()
    if (!path.startsWith(home) || !script.endsWith(".py") || !path.isRegularFile()) {
      throw McpExpectedError("'$script' is no Python tool in $home")
    }
    return runResult(runScript(project, home.relativize(path).toString(), args, timeoutSeconds.coerceIn(1, 7200) * 1000))
  }

  @McpTool(name = "rhipper_open")
  @McpDescription("Opens a function in the user's editor: view decompilation (Ghidra's C), translation (the generated C) or disassembly.")
  suspend fun open(
    @McpDescription("An address or a function name") query: String,
    @McpDescription("decompilation, translation or disassembly") view: String = "decompilation",
  ): String {
    val project = currentCoroutineContext().project
    requireConfig(project)
    val rhipperView = RhipperView.entries.firstOrNull { it.name.equals(view, ignoreCase = true) }
                      ?: throw McpExpectedError("Unknown view '$view'. Use decompilation, translation or disassembly.")
    RhipperNavigator.getInstance(project).open(query, rhipperView)
    return "opened"
  }

  private fun requireConfig(project: Project): RhipperProjectConfig {
    return project.rhipperConfig ?: throw McpExpectedError("The project has no valid rhipper.json at its root")
  }

  private suspend fun runScript(project: Project, script: String, args: List<String>, timeoutMillis: Int = 300_000): RhipperRunResult {
    requireConfig(project)
    return try {
      runRhipperScript(project, script, args, timeoutMillis)
    }
    catch (e: ExecutionException) {
      throw McpExpectedError(e.message ?: e.toString())
    }
  }

  private fun runResult(result: RhipperRunResult): String = buildJsonObject {
    put("exitCode", result.exitCode)
    put("outputTruncated", result.truncated)
    put("output", result.output)
  }.toString()

  private fun queryAddress(text: String): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val digits = Regex("""(?:thunk_FUN_|FUN_|F_)?(?:0x)?([0-9a-fA-F]+)""").matchEntire(trimmed)?.groupValues?.get(1) ?: return null
    return parseAddress(digits)
  }

  private fun outputPath(path: java.nio.file.Path): String? = path.takeIf { it.isRegularFile() }?.toString()

}
