// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.tools

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.NioFiles
import com.intellij.util.system.CpuArch
import com.intellij.util.io.Decompressor
import com.intellij.util.io.HttpRequests
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.settings.RhipperSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.annotations.Nls
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

private val LOG = logger<RhipperTools>()

private const val RHIPPER_RELEASES = "https://github.com/zeruth/Rhipper/releases"
const val GHIDRA_VERSION: String = "12.1.3"
private const val GHIDRA_RELEASE_API = "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/tags/Ghidra_${GHIDRA_VERSION}_build"
private const val CLANGD_RELEASE_API = "https://api.github.com/repos/clangd/clangd/releases/latest"
private const val JDK_FEATURE_VERSION = 21
private const val PYTHON_VERSION = "3.12"
private val REQUIRED_MODULES = listOf("capstone", "pefile", "unicorn", "numpy")

/** A tool that the Rhipper workflow needs. The IDE installs the [installable] ones, and points to the download of the rest. */
enum class RhipperTool(val installable: Boolean, val downloadPage: String?) {
  RHIPPER(true, RHIPPER_RELEASES),
  PYTHON(true, null),
  JDK(true, "https://adoptium.net/temurin/releases/?version=21"),
  GHIDRA(true, "https://github.com/NationalSecurityAgency/ghidra/releases"),
  CLANGD(true, "https://github.com/clangd/clangd/releases"),
  GIT_BASH(false, "https://git-scm.com/download/win"),
  CMAKE(false, "https://cmake.org/download/");

  val displayName: @Nls String get() = RhipperBundle.message("tool.${name.lowercase()}")
}

/** Where a tool comes from. */
enum class ToolSource { MANAGED, CONFIGURED, SYSTEM, MISSING }

data class ToolStatus(
  val tool: RhipperTool,
  val source: ToolSource,
  val version: String? = null,
  val path: Path? = null,
  /** Why the tool counts as missing, for example the Python modules that do not import. */
  val problem: String? = null,
) {
  val ok: Boolean get() = source != ToolSource.MISSING
}

/** A newer Rhipper release than the managed one. */
data class RhipperUpdate(val installed: String?, val latest: String)

/**
 * The tools of the Rhipper workflow: where each one is, and the downloads that install the missing ones into the tools directory.
 *
 * The tools directory holds the managed installs, each under its own folder:
 * `rhipper/<version>` with `rhipper/current` naming the version in use, `python/uv` and `python/venv`, `jdk`, `ghidra`,
 * `ghidra-projects` and `clangd`. [RhipperSettings] prefers a path that a person set, then the managed install, then the system.
 */
@Service(Service.Level.APP)
class RhipperTools {
  private val mutex = Mutex()

  val toolsDir: Path get() = RhipperSettings.getInstance().toolsDir()

  // region The managed installs

  val managedRhipperHome: Path?
    get() {
      val version = managedRhipperVersion ?: return null
      return toolsDir.resolve("rhipper").resolve(version).takeIf { it.resolve("project.py").isRegularFile() }
    }

  val managedRhipperVersion: String?
    get() = runCatching { Files.readString(toolsDir.resolve("rhipper").resolve("current")).trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

  val managedPython: Path?
    get() = toolsDir.resolve("python").resolve("venv").resolve(if (SystemInfo.isWindows) "Scripts/python.exe" else "bin/python").takeIf { it.isRegularFile() }

  val managedJdk: Path?
    get() = findChild(toolsDir.resolve("jdk")) { it.resolve("bin").resolve(exe("java")).isRegularFile() }

  val managedGhidra: Path?
    get() = findChild(toolsDir.resolve("ghidra")) { it.resolve("support").resolve(if (SystemInfo.isWindows) "analyzeHeadless.bat" else "analyzeHeadless").isRegularFile() }

  val managedGhidraProjects: Path get() = toolsDir.resolve("ghidra-projects")

  val managedClangd: Path?
    get() = findChild(toolsDir.resolve("clangd")) { it.resolve("bin").resolve(exe("clangd")).isRegularFile() }?.resolve("bin")?.resolve(exe("clangd"))

  // endregion

  // region Status

  /** The status of every tool for [config]. It runs Python and Java to check them, so call it off the EDT. */
  suspend fun status(config: RhipperProjectConfig?): List<ToolStatus> = withContext(Dispatchers.IO) {
    RhipperTool.entries.map { tool ->
      // one broken check must not hide the state of the other tools
      try {
        status(tool, config)
      }
      catch (e: Throwable) {
        rethrowControlFlowException(e)
        LOG.warn("Cannot check ${tool.name}", e)
        ToolStatus(tool, ToolSource.MISSING, problem = e.message ?: e.toString())
      }
    }
  }

  suspend fun status(tool: RhipperTool, config: RhipperProjectConfig?): ToolStatus = withContext(Dispatchers.IO) {
    val settings = RhipperSettings.getInstance()
    when (tool) {
      RhipperTool.RHIPPER -> {
        val home = settings.rhipperHome(config)
        when {
          home == null -> ToolStatus(tool, ToolSource.MISSING)
          home == managedRhipperHome -> ToolStatus(tool, ToolSource.MANAGED, managedRhipperVersion, home)
          else -> ToolStatus(tool, ToolSource.CONFIGURED, bundleVersion(home) ?: RhipperBundle.message("tool.version.checkout"), home)
        }
      }
      RhipperTool.PYTHON -> pythonStatus(settings.pythonExecutable())
      RhipperTool.JDK -> {
        val managed = managedJdk
        if (managed != null) ToolStatus(tool, ToolSource.MANAGED, javaVersion(managed.resolve("bin").resolve(exe("java")).toString()), managed)
        else {
          val version = javaVersion(System.getenv("JAVA_HOME")?.let { Path.of(it, "bin", exe("java")).toString() } ?: "java")
          if (version != null && majorVersion(version) >= JDK_FEATURE_VERSION) ToolStatus(tool, ToolSource.SYSTEM, version)
          else ToolStatus(tool, ToolSource.MISSING, version, problem = version?.let { RhipperBundle.message("tool.jdk.too.old", it) })
        }
      }
      RhipperTool.GHIDRA -> {
        val configured = config?.ghidra?.install?.let { resolveGlob(config.root, it) }
        val global = settings.ghidraInstall()
        when {
          configured != null -> ToolStatus(tool, ToolSource.CONFIGURED, ghidraVersion(configured), configured)
          global != null && global == managedGhidra -> ToolStatus(tool, ToolSource.MANAGED, ghidraVersion(global), global)
          global != null -> ToolStatus(tool, ToolSource.CONFIGURED, ghidraVersion(global), global)
          else -> ToolStatus(tool, ToolSource.MISSING, problem = config?.ghidra?.install?.let { RhipperBundle.message("tool.ghidra.no.match", it) })
        }
      }
      RhipperTool.CLANGD -> {
        val clangd = settings.clangdExecutable()
        when {
          clangd == null -> ToolStatus(tool, ToolSource.MISSING)
          clangd == managedClangd -> ToolStatus(tool, ToolSource.MANAGED, null, clangd)
          else -> ToolStatus(tool, ToolSource.SYSTEM, null, clangd)
        }
      }
      RhipperTool.GIT_BASH -> {
        val bash = settings.bashExecutable()
        val path = if (Path.of(bash).isAbsolute) Path.of(bash).takeIf { it.isRegularFile() } else PathEnvironmentVariableUtil.findFirst("bash")
        if (path != null) ToolStatus(tool, ToolSource.SYSTEM, path = path) else ToolStatus(tool, ToolSource.MISSING)
      }
      RhipperTool.CMAKE -> {
        val cmake = PathEnvironmentVariableUtil.findFirst("cmake")
        if (cmake != null) ToolStatus(tool, ToolSource.SYSTEM, path = cmake) else ToolStatus(tool, ToolSource.MISSING)
      }
    }
  }

  private fun pythonStatus(python: String): ToolStatus {
    val probe = "import sys\nmissing = []\nfor m in ${REQUIRED_MODULES.joinToString(",", "(", ")") { "'$it'" }}:\n" +
                "    try:\n        __import__(m)\n    except Exception:\n        missing.append(m)\n" +
                "print(sys.version.split()[0]); print(','.join(missing))"
    val output = try {
      CapturingProcessHandler(GeneralCommandLine(python, "-c", probe)).runProcess(30_000)
    }
    catch (_: ExecutionException) {
      return ToolStatus(RhipperTool.PYTHON, ToolSource.MISSING, problem = RhipperBundle.message("tool.python.not.found", python))
    }
    val lines = output.stdout.lines()
    if (output.exitCode != 0 || lines.isEmpty()) {
      return ToolStatus(RhipperTool.PYTHON, ToolSource.MISSING, problem = RhipperBundle.message("tool.python.not.found", python))
    }
    val version = lines[0].trim()
    val missing = lines.getOrNull(1)?.trim().orEmpty()
    val source = if (managedPython?.toString() == python) ToolSource.MANAGED else ToolSource.SYSTEM
    return if (missing.isEmpty()) ToolStatus(RhipperTool.PYTHON, source, version, Path.of(python).takeIf { it.isAbsolute })
    else ToolStatus(RhipperTool.PYTHON, ToolSource.MISSING, version, problem = RhipperBundle.message("tool.python.missing.modules", missing))
  }

  // endregion

  // region Rhipper updates

  /** The newest Rhipper release, from its `version.txt`. */
  suspend fun latestRhipperVersion(): String = withContext(Dispatchers.IO) {
    HttpRequests.request("$RHIPPER_RELEASES/latest/download/version.txt").productNameAsUserAgent().connectTimeout(10_000).readString().trim()
  }

  /** The update of the managed Rhipper, or `null` when it is current or a person set another Rhipper. */
  suspend fun rhipperUpdate(config: RhipperProjectConfig?): RhipperUpdate? {
    val home = RhipperSettings.getInstance().rhipperHome(config)
    if (home != null && home != managedRhipperHome) return null
    val latest = latestRhipperVersion()
    val installed = managedRhipperVersion
    return if (installed == null || compareVersions(latest, installed) > 0) RhipperUpdate(installed, latest) else null
  }

  // endregion

  // region Installs

  /** Installs [tool] into the tools directory, and reports each stage and each downloaded byte to [progress]. */
  suspend fun install(tool: RhipperTool, progress: InstallProgress = InstallProgress { _, _, _ -> }) {
    mutex.withLock {
      this.progress = progress
      withContext(Dispatchers.IO) {
        Files.createDirectories(toolsDir)
        when (tool) {
          RhipperTool.RHIPPER -> installRhipper(latestRhipperVersion())
          RhipperTool.PYTHON -> installPython()
          RhipperTool.JDK -> installJdk()
          RhipperTool.GHIDRA -> installGhidra()
          RhipperTool.CLANGD -> installClangd()
          RhipperTool.GIT_BASH, RhipperTool.CMAKE -> throw IOException(RhipperBundle.message("tool.not.installable", tool.displayName))
        }
      }
      this.progress = InstallProgress { _, _, _ -> }
    }
  }

  /** The progress of the install in work. The [mutex] lets one install run at a time. */
  private var progress = InstallProgress { _, _, _ -> }

  private suspend fun installRhipper(version: String) {
    val base = "$RHIPPER_RELEASES/download/v$version/rhipper-$version.zip"
    val zip = download(base, "rhipper-$version.zip")
    val expected = HttpRequests.request("$base.sha256").productNameAsUserAgent().readString().trim().substringBefore(' ').lowercase()
    progress.update(RhipperBundle.message("tool.stage.checking"), -1, -1)
    val actual = sha256(zip)
    if (expected.isNotEmpty() && expected != actual) {
      Files.deleteIfExists(zip)
      throw IOException(RhipperBundle.message("tool.checksum.mismatch", "rhipper-$version.zip", expected, actual))
    }
    val target = toolsDir.resolve("rhipper").resolve(version)
    extractInto(zip, target) { it.resolve("project.py").isRegularFile() }
    Files.writeString(toolsDir.resolve("rhipper").resolve("current"), version)
    Files.deleteIfExists(zip)
  }

  private suspend fun installPython() {
    val uvDir = toolsDir.resolve("python").resolve("uv")
    val uv = uvDir.resolve(exe("uv"))
    if (!uv.isRegularFile()) {
      val asset = when {
        SystemInfo.isWindows -> "uv-x86_64-pc-windows-msvc.zip"
        SystemInfo.isMac -> if (CpuArch.isArm64()) "uv-aarch64-apple-darwin.tar.gz" else "uv-x86_64-apple-darwin.tar.gz"
        else -> if (CpuArch.isArm64()) "uv-aarch64-unknown-linux-gnu.tar.gz" else "uv-x86_64-unknown-linux-gnu.tar.gz"
      }
      val archive = download("https://github.com/astral-sh/uv/releases/latest/download/$asset", asset)
      extractInto(archive, uvDir) { it.resolve(exe("uv")).isRegularFile() }
      Files.deleteIfExists(archive)
      uv.toFile().setExecutable(true)
    }
    val venv = toolsDir.resolve("python").resolve("venv")
    val env = mapOf("UV_PYTHON_INSTALL_DIR" to toolsDir.resolve("python").resolve("interpreters").toString(),
                    "UV_CACHE_DIR" to toolsDir.resolve("python").resolve("cache").toString())
    progress.update(RhipperBundle.message("tool.stage.venv"), -1, -1)
    if (managedPython == null) run(GeneralCommandLine(uv.toString(), "venv", venv.toString(), "--python", PYTHON_VERSION).withEnvironment(env))
    val python = managedPython ?: throw IOException(RhipperBundle.message("tool.python.venv.failed", venv))
    val requirements = RhipperSettings.getInstance().rhipperHome(null)?.resolve("requirements.txt")?.takeIf { it.isRegularFile() }
    val install = GeneralCommandLine(uv.toString(), "pip", "install", "--python", python.toString()).withEnvironment(env)
    if (requirements != null) install.addParameters("-r", requirements.toString())
    else install.addParameters(REQUIRED_MODULES)
    progress.update(RhipperBundle.message("tool.stage.packages"), -1, -1)
    run(install)
  }

  private suspend fun installJdk() {
    val os = when {
      SystemInfo.isWindows -> "windows"
      SystemInfo.isMac -> "mac"
      else -> "linux"
    }
    val arch = if (CpuArch.isArm64()) "aarch64" else "x64"
    val name = if (SystemInfo.isWindows) "jdk-$JDK_FEATURE_VERSION.zip" else "jdk-$JDK_FEATURE_VERSION.tar.gz"
    val archive = download("https://api.adoptium.net/v3/binary/latest/$JDK_FEATURE_VERSION/ga/$os/$arch/jdk/hotspot/normal/eclipse", name)
    extractInto(archive, toolsDir.resolve("jdk"), keepTopDirectory = true) { true }
    Files.deleteIfExists(archive)
    managedJdk ?: throw IOException(RhipperBundle.message("tool.unpack.failed", "JDK"))
  }

  private suspend fun installGhidra() {
    progress.update(RhipperBundle.message("tool.stage.release"), -1, -1)
    val release = githubJson(GHIDRA_RELEASE_API)
    val asset = assets(release).firstOrNull { (name, _) -> name.startsWith("ghidra_${GHIDRA_VERSION}_PUBLIC") && name.endsWith(".zip") }
                ?: throw IOException(RhipperBundle.message("tool.asset.missing", "Ghidra $GHIDRA_VERSION"))
    val archive = download(asset.second, asset.first)
    extractInto(archive, toolsDir.resolve("ghidra"), keepTopDirectory = true) { true }
    Files.deleteIfExists(archive)
    Files.createDirectories(managedGhidraProjects)
    managedGhidra ?: throw IOException(RhipperBundle.message("tool.unpack.failed", "Ghidra"))
  }

  private suspend fun installClangd() {
    val prefix = when {
      SystemInfo.isWindows -> "clangd-windows-"
      SystemInfo.isMac -> "clangd-mac-"
      else -> "clangd-linux-"
    }
    val asset = assets(githubJson(CLANGD_RELEASE_API)).firstOrNull { (name, _) -> name.startsWith(prefix) && name.endsWith(".zip") }
                ?: throw IOException(RhipperBundle.message("tool.asset.missing", "clangd"))
    val archive = download(asset.second, asset.first)
    extractInto(archive, toolsDir.resolve("clangd"), keepTopDirectory = true) { true }
    Files.deleteIfExists(archive)
    managedClangd?.toFile()?.setExecutable(true) ?: throw IOException(RhipperBundle.message("tool.unpack.failed", "clangd"))
  }

  // endregion

  // region Helpers

  private suspend fun download(url: String, name: String): Path {
    val target = toolsDir.resolve("downloads").resolve(name)
    Files.createDirectories(target.parent)
    val stage = RhipperBundle.message("tool.downloading", name)
    progress.update(stage, 0, -1)
    coroutineToIndicator { indicator ->
      indicator.text = stage
      HttpRequests.request(url).productNameAsUserAgent().connectTimeout(30_000).connect { request ->
        val total = request.connection.contentLengthLong
        request.inputStream.use { input ->
          Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(1 shl 16)
            var done = 0L
            var reported = 0L
            while (true) {
              indicator.checkCanceled()
              val read = input.read(buffer)
              if (read < 0) break
              output.write(buffer, 0, read)
              done += read
              // a report for each 512 KB keeps the UI updates few
              if (done - reported >= 512 * 1024) {
                reported = done
                if (total > 0) indicator.fraction = done.toDouble() / total
                progress.update(stage, done, total)
              }
            }
            progress.update(stage, done, total)
          }
        }
      }
    }
    return target
  }

  /**
   * Unpacks [archive] into [target]. With [keepTopDirectory], the archive's own top directory stays, as a JDK or Ghidra has one.
   * Without it, a single top directory is taken off when [isRoot] does not hold for [target] itself.
   */
  private fun extractInto(archive: Path, target: Path, keepTopDirectory: Boolean = false, isRoot: (Path) -> Boolean) {
    progress.update(RhipperBundle.message("tool.stage.unpacking", archive.name), -1, -1)
    val staging = target.resolveSibling(target.name + ".unpacking")
    NioFiles.deleteRecursively(staging)
    val decompressor = if (archive.name.endsWith(".zip")) Decompressor.Zip(archive) else Decompressor.Tar(archive)
    decompressor.extract(staging)
    var root = staging
    if (!keepTopDirectory && !isRoot(staging)) {
      val children = Files.list(staging).use { it.toList() }
      root = children.singleOrNull { it.isDirectory() && isRoot(it) } ?: staging
    }
    if (keepTopDirectory) {
      Files.createDirectories(target)
      Files.list(staging).use { it.toList() }.forEach { child ->
        val destination = target.resolve(child.name)
        NioFiles.deleteRecursively(destination)
        Files.move(child, destination)
      }
      NioFiles.deleteRecursively(staging)
      return
    }
    NioFiles.deleteRecursively(target)
    Files.createDirectories(target.parent)
    Files.move(root, target)
    if (root != staging) NioFiles.deleteRecursively(staging)
  }

  private fun run(commandLine: GeneralCommandLine) {
    val output = CapturingProcessHandler(commandLine.withCharset(Charsets.UTF_8)).runProcess(30 * 60_000)
    if (output.exitCode != 0 || output.isTimeout) {
      throw IOException(RhipperBundle.message("tool.command.failed", commandLine.commandLineString, (output.stderr + output.stdout).takeLast(2000)))
    }
  }

  private fun githubJson(url: String): JsonObject {
    val text = HttpRequests.request(url).productNameAsUserAgent().accept("application/vnd.github+json").readString()
    return RhipperProjectConfig.parseJson(text) as? JsonObject ?: throw IOException("Unexpected answer from $url")
  }

  private fun assets(release: JsonObject): List<Pair<String, String>> {
    val assets = release["assets"] as? JsonArray ?: return emptyList()
    return assets.mapNotNull { asset ->
      val obj = asset as? JsonObject ?: return@mapNotNull null
      val name = (obj["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      val url = (obj["browser_download_url"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
      name to url
    }
  }

  private fun sha256(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun bundleVersion(home: Path): String? {
    val build = home.resolve("BUILD.json").takeIf { it.isRegularFile() } ?: return null
    val json = runCatching { RhipperProjectConfig.parseJson(Files.readString(build)) as? JsonObject }.getOrNull() ?: return null
    return (json["version"] as? JsonPrimitive)?.contentOrNull
  }

  private fun javaVersion(java: String): String? {
    val output = try {
      CapturingProcessHandler(GeneralCommandLine(java, "-version")).runProcess(20_000)
    }
    catch (_: ExecutionException) {
      return null
    }
    return Regex("""version "([^"]+)"""").find(output.stderr + output.stdout)?.groupValues?.get(1)
  }

  private fun ghidraVersion(install: Path): String? {
    val properties = install.resolve("Ghidra").resolve("application.properties").takeIf { it.isRegularFile() } ?: return null
    return Files.readAllLines(properties).firstOrNull { it.startsWith("application.version=") }?.substringAfter('=')
  }

  private fun findChild(dir: Path, predicate: (Path) -> Boolean): Path? {
    if (!dir.isDirectory()) return null
    return Files.list(dir).use { stream -> stream.filter { it.isDirectory() && predicate(it) }.sorted(Comparator.reverseOrder()).findFirst().orElse(null) }
  }

  companion object {
    fun getInstance(): RhipperTools = service()

    fun exe(name: String): String = if (SystemInfo.isWindows) "$name.exe" else name

    /** The first directory that the `ghidra.install` setting [pattern] matches, as Rhipper's `project.py` reads it: a glob. */
    fun resolveGlob(root: Path, pattern: String): Path? {
      // a Windows path cannot hold `*` or `?`, so the pattern splits into its directory and its last name before any Path exists
      val text = FileUtil.toSystemIndependentName(pattern.trim()).trimEnd('/')
      val slash = text.lastIndexOf('/')
      val parentText = if (slash >= 0) text.substring(0, slash) else ""
      val name = text.substring(slash + 1)
      if (name.isEmpty() || parentText.any { it in GLOB_CHARS }) return null
      val parent = try {
        root.resolve(parentText.ifEmpty { "." }).normalize()
      }
      catch (_: InvalidPathException) {
        return null
      }
      if (!parent.isDirectory()) return null
      if (name.none { it in GLOB_CHARS }) return parent.resolve(name).takeIf { it.isDirectory() }
      val matcher = FileSystems.getDefault().getPathMatcher("glob:$name")
      return Files.list(parent).use { stream -> stream.filter { it.isDirectory() && matcher.matches(it.fileName) }.sorted().findFirst().orElse(null) }
    }

    private val GLOB_CHARS = setOf('*', '?', '[', '{')

    fun majorVersion(version: String): Int {
      val parts = version.split('.', '-', '_', '+')
      val first = parts.firstOrNull()?.toIntOrNull() ?: return 0
      return if (first == 1) parts.getOrNull(1)?.toIntOrNull() ?: 1 else first
    }

    /** Compares dotted versions number by number: `0.10.0` is newer than `0.9.2`. */
    fun compareVersions(a: String, b: String): Int {
      val left = a.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
      val right = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
      for (i in 0 until maxOf(left.size, right.size)) {
        val c = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (c != 0) return c
      }
      return 0
    }
  }
}

/** Receives the progress of an install: the [stage] in work and, for a download, the bytes. A negative count is not known. */
fun interface InstallProgress {
  fun update(stage: String, done: Long, total: Long)
}
