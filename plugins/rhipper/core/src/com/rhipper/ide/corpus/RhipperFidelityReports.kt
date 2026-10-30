// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.Project
import com.rhipper.ide.pipeline.RhipperRunResult
import com.rhipper.ide.pipeline.runRhipperScript
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.rhipperConfig
import com.rhipper.ide.settings.RhipperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * The numbers of a fidelity report, as fidelity32.py totals them: the translated functions' scores apart from the stubs (each
 * scored 0), and a histogram of ten ranges (0.0-0.1 first).
 */
data class FidelitySummary(
  val functions: Int,
  /** Translated functions with a score. */
  val scored: Int,
  val stubs: Int,
  val perfect: Int,
  /** Translated functions scored below 0.5 (the stubs not counted). */
  val below: Int,
  val average: Double?,
  val histogram: List<Int>,
)

/**
 * A fidelity report the IDE keeps: Rhipper's `fidelity.json` (and `bytes32.json`, `fidelity.html`) as they were when it was made,
 * so that the next pipeline run, which writes them again, does not lose them.
 */
data class FidelityReport(
  /** The report's folder name, `yyyyMMdd-HHmmss-<commit>`: the newest sorts last. */
  val id: String,
  val dir: Path,
  val created: Instant,
  val label: String,
  /** The project's commit and whether its generated sources differed from it. */
  val commit: String,
  val genDirty: Boolean,
  /** Rhipper's commit and whether its tree differed from it. */
  val rhipperCommit: String,
  val rhipperDirty: Boolean,
  val summary: FidelitySummary,
  val hasBytes: Boolean,
) {
  val htmlPath: Path get() = dir.resolve(HTML)
  val hasHtml: Boolean get() = htmlPath.isRegularFile()
}

/** The end of making a report: the report, or the step that failed and its output. */
data class FidelityReportResult(val report: FidelityReport?, val failedStep: String?, val output: String)

private const val META = "report.json"
private const val FIDELITY = "fidelity.json.gz"
private const val BYTES = "bytes32.json.gz"
private const val HTML = "fidelity.html"

/** How many reports a project keeps; the oldest go first. */
private const val KEPT = 30

/**
 * The project's fidelity reports, kept in the IDE's system folder (`rhipper/reports/<project>-<hash>/<id>/`): each one Rhipper's
 * measure of the port at one time, with the commits it measured. The MCP tools make, list, compare and open them, and the IDE shows
 * their pages.
 */
@Service(Service.Level.PROJECT)
class RhipperFidelityReports(private val project: Project, val scope: kotlinx.coroutines.CoroutineScope) {
  private val mutex = Mutex()

  private fun config(): RhipperProjectConfig = project.rhipperConfig ?: error("The project has no valid rhipper.json at its root")

  /** The folder the project's reports are in. */
  fun storage(config: RhipperProjectConfig = config()): Path {
    val key = "${config.name}-${Integer.toHexString(config.root.toString().lowercase().hashCode())}"
    return Path.of(PathManager.getSystemPath(), "rhipper", "reports", key)
  }

  /**
   * Measures the port with Rhipper's fidelity script, its bytes with `bytes32.py` when [bytes] is set (a 32-bit project), writes the
   * page, and keeps the three as a new report.
   */
  suspend fun make(bytes: Boolean, label: String, timeoutMillis: Int = 3_600_000): FidelityReportResult = mutex.withLock {
    val config = config()
    val x86 = config.arch == RhipperArch.X86
    val log = StringBuilder()
    suspend fun step(name: String, script: String, args: List<String>): Boolean {
      val result: RhipperRunResult = runRhipperScript(project, script, args, timeoutMillis)
      log.append("== ").append(name).append(" (exit ").append(result.exitCode).append(")\n").append(result.output.takeLast(4000)).append('\n')
      return result.exitCode == 0
    }
    val jobs = (config.json["jobs"] as? JsonPrimitive)?.intOrNull ?: 4
    if (x86) {
      if (!step("fidelity32", "fidelity32.py", listOf("--quiet"))) return@withLock FidelityReportResult(null, "fidelity32", log.toString())
      if (bytes && !step("bytes32", "bytes32.py", listOf("--json", "--jobs", jobs.toString()))) {
        return@withLock FidelityReportResult(null, "bytes32", log.toString())
      }
      if (!step("html", "fidelity32.py", listOf("--html-only"))) return@withLock FidelityReportResult(null, "html", log.toString())
    }
    else {
      if (!step("fidelity", "fidelity.py", listOf("--summary"))) return@withLock FidelityReportResult(null, "fidelity", log.toString())
      if (!step("html", "fidelity_report.py", emptyList())) return@withLock FidelityReportResult(null, "html", log.toString())
    }
    val report = withContext(Dispatchers.IO) { keep(config, label, bytes && x86) }
    FidelityReportResult(report, null, log.toString())
  }

  /** Keeps the project's current `build/fidelity.json` (and `bytes32.json`, `fidelity.html`) as a report, without measuring. */
  suspend fun keepCurrent(label: String): FidelityReport? = mutex.withLock {
    val config = config()
    withContext(Dispatchers.IO) { if (config.fidelityPath.isRegularFile()) keep(config, label, bytes = false) else null }
  }

  private fun keep(config: RhipperProjectConfig, label: String, bytes: Boolean): FidelityReport {
    val text = Files.readString(config.fidelityPath)
    val entries = parseFidelityEntries(text) ?: error("build/fidelity.json is no fidelity report")
    val created = Instant.now()
    val (commit, genDirty) = git(config.root, config.genPath)
    val (rhipperCommit, rhipperDirty) = RhipperSettings.getInstance().rhipperHome(config)?.let { git(it, null) } ?: ("" to false)
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault()).format(created)
    val id = "$stamp-${commit.ifEmpty { "nogit" }}"
    val dir = storage(config).resolve(id)
    Files.createDirectories(dir)
    gzip(text, dir.resolve(FIDELITY))
    // (bytes32.json only when it measured this translation: one the report just made, or newer than the generated sources)
    val bytesJson = config.buildPath.resolve("bytes32.json")
    val hasBytes = bytesJson.isRegularFile() && (bytes || Files.getLastModifiedTime(bytesJson) >= newestGen(config))
    if (hasBytes) gzip(Files.readString(bytesJson), dir.resolve(BYTES))
    val html = config.buildPath.resolve("fidelity.html")
    if (html.isRegularFile()) Files.copy(html, dir.resolve(HTML))
    val summary = summarize(entries)
    val report = FidelityReport(id, dir, created, label, commit, genDirty, rhipperCommit, rhipperDirty, summary, hasBytes)
    Files.writeString(dir.resolve(META), meta(report).toString())
    prune(config)
    return report
  }

  /** The project's reports, oldest first. */
  suspend fun list(): List<FidelityReport> = withContext(Dispatchers.IO) {
    val dir = storage()
    if (!dir.isDirectory()) return@withContext emptyList()
    Files.list(dir).use { stream -> stream.filter { it.resolve(META).isRegularFile() }.toList() }
      .sortedBy { it.fileName.toString() }
      .mapNotNull { read(it) }
  }

  /** A report by its id, a prefix of it, `latest`, or `previous` (the one before the latest). */
  suspend fun find(query: String): FidelityReport? {
    val all = list()
    return when (query.trim().lowercase()) {
      "", "latest" -> all.lastOrNull()
      "previous" -> all.getOrNull(all.size - 2)
      else -> all.lastOrNull { it.id == query.trim() } ?: all.lastOrNull { it.id.startsWith(query.trim()) }
    }
  }

  /** A report's functions and their scores. */
  suspend fun entries(report: FidelityReport): List<FidelityEntry> = withContext(Dispatchers.IO) {
    parseFidelityEntries(gunzip(report.dir.resolve(FIDELITY))) ?: emptyList()
  }

  /** What changed from [before] to [after]. */
  suspend fun compare(before: FidelityReport, after: FidelityReport, threshold: Double = 0.5, tolerance: Double = 0.005): FidelityChanges {
    val a = entries(before)
    val b = entries(after)
    return compareFidelity(before.created, a.associate { it.address to it.score }, a.associate { it.address to it.name },
                           b.associate { it.address to it.score }, b.associate { it.address to it.name }, threshold, tolerance)
  }

  /** Shows a report's page in an editor tab. */
  suspend fun open(report: FidelityReport): Boolean {
    if (!report.hasHtml) return false
    withContext(Dispatchers.EDT) {
      HTMLEditorProvider.openEditor(project, "Fidelity ${report.id}", report.htmlPath.toUri().toString())
    }
    return true
  }

  private fun prune(config: RhipperProjectConfig) {
    val dir = storage(config)
    val all = Files.list(dir).use { stream -> stream.filter { it.isDirectory() }.toList() }.sortedBy { it.fileName.toString() }
    for (old in all.dropLast(KEPT)) {
      Files.walk(old).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
    }
  }

  private fun read(dir: Path): FidelityReport? {
    val obj = try {
      RhipperProjectConfig.parseJson(Files.readString(dir.resolve(META))) as? JsonObject
    }
    catch (_: Exception) {
      null
    } ?: return null
    fun s(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
    fun b(key: String) = (obj[key] as? JsonPrimitive)?.booleanOrNull == true
    val sum = obj["summary"] as? JsonObject ?: return null
    fun i(key: String) = (sum[key] as? JsonPrimitive)?.intOrNull ?: 0
    val summary = FidelitySummary(
      functions = i("functions"), scored = i("scored"), stubs = i("stubs"), perfect = i("perfect"), below = i("below"),
      average = (sum["average"] as? JsonPrimitive)?.doubleOrNull,
      histogram = (sum["histogram"] as? JsonArray)?.map { (it as? JsonPrimitive)?.intOrNull ?: 0 } ?: emptyList(),
    )
    return FidelityReport(
      id = dir.fileName.toString(), dir = dir, created = runCatching { Instant.parse(s("created")) }.getOrElse { Instant.EPOCH },
      label = s("label"), commit = s("commit"), genDirty = b("genDirty"), rhipperCommit = s("rhipperCommit"),
      rhipperDirty = b("rhipperDirty"), summary = summary, hasBytes = dir.resolve(BYTES).isRegularFile(),
    )
  }

  companion object {
    fun getInstance(project: Project): RhipperFidelityReports = project.service()
  }
}

/** The summary of a report's entries. */
fun summarize(entries: List<FidelityEntry>): FidelitySummary {
  val scores = entries.filter { it.kind != "stub" }.mapNotNull { it.score }
  val buckets = IntArray(10)
  scores.forEach { buckets[(it * 10).toInt().coerceIn(0, 9)]++ }
  return FidelitySummary(
    functions = entries.size,
    scored = scores.size,
    stubs = entries.count { it.kind == "stub" },
    perfect = scores.count { it >= 0.999 },
    below = scores.count { it < 0.5 },
    average = if (scores.isEmpty()) null else scores.average(),
    histogram = buckets.toList(),
  )
}

private fun meta(report: FidelityReport): JsonObject = buildJsonObject {
  put("created", report.created.toString())
  put("label", report.label)
  put("commit", report.commit)
  put("genDirty", report.genDirty)
  put("rhipperCommit", report.rhipperCommit)
  put("rhipperDirty", report.rhipperDirty)
  put("summary", buildJsonObject {
    val s = report.summary
    put("functions", s.functions)
    put("scored", s.scored)
    put("stubs", s.stubs)
    put("perfect", s.perfect)
    put("below", s.below)
    put("average", s.average)
    putJsonArray("histogram") { s.histogram.forEach { add(JsonPrimitive(it)) } }
  })
}

/** The short commit of the repository at [dir], and whether [path] (or the whole tree) differs from it. Empty when it is no repository. */
private fun git(dir: Path, path: Path?): Pair<String, Boolean> {
  fun run(vararg args: String): String? {
    val out = CapturingProcessHandler(GeneralCommandLine("git", *args).withWorkingDirectory(dir)).runProcess(20_000)
    return if (out.exitCode == 0) out.stdout.trim() else null
  }
  val commit = run("rev-parse", "--short", "HEAD") ?: return "" to false
  val status = (if (path != null) run("status", "--porcelain", "--", path.toString()) else run("status", "--porcelain", "-uno")).orEmpty()
  return commit to status.isNotEmpty()
}

/** When the newest generated source was written. */
private fun newestGen(config: RhipperProjectConfig): java.nio.file.attribute.FileTime {
  if (!config.genPath.isDirectory()) return java.nio.file.attribute.FileTime.fromMillis(0)
  return Files.list(config.genPath).use { stream ->
    stream.filter { it.isRegularFile() }.map { Files.getLastModifiedTime(it) }.max(Comparator.naturalOrder()).orElse(java.nio.file.attribute.FileTime.fromMillis(0))
  }
}

private fun gzip(text: String, to: Path) {
  GZIPOutputStream(Files.newOutputStream(to)).use { it.write(text.toByteArray(Charsets.UTF_8)) }
}

private fun gunzip(from: Path): String {
  if (!from.isRegularFile()) return "[]"
  return GZIPInputStream(Files.newInputStream(from)).use { String(it.readAllBytes(), Charsets.UTF_8) }
}
