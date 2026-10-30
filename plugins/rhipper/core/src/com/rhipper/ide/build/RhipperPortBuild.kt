// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.build

import com.intellij.build.BuildContentManager
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.MessageEvent
import com.intellij.build.progress.BuildProgress
import com.intellij.build.progress.BuildProgressDescriptor
import com.intellij.icons.AllIcons
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.pipeline.PortBuildMode
import com.rhipper.ide.pipeline.RhipperCommandLines
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

/** A diagnostic of the compiler or the linker. [file] and [line] are `null` for a message that names no source position. */
data class BuildDiagnostic(
  val severity: MessageEvent.Kind,
  val code: String,
  val message: String,
  val file: Path?,
  /** 1-based, as the compiler writes it. */
  val line: Int?,
  val column: Int?,
)

data class PortBuildResult(
  val buildConfig: String,
  val exitCode: Int?,
  val diagnostics: List<BuildDiagnostic>,
  val outputTail: String,
  val seconds: Long,
) {
  val succeeded: Boolean get() = exitCode == 0
  val errors: Int get() = diagnostics.count { it.severity == MessageEvent.Kind.ERROR }
  val warnings: Int get() = diagnostics.count { it.severity == MessageEvent.Kind.WARNING }
}

/**
 * Builds the port of a Rhipper project with CMake, and shows the build in the Build tool window: the configure step, the compile
 * step with the file in work, one node for each finished target, and the compiler and linker diagnostics with their source
 * positions. One build runs at a time.
 */
@Service(Service.Level.PROJECT)
class RhipperPortBuild(private val project: Project, private val coroutineScope: CoroutineScope) {
  private val mutex = Mutex()

  /** Builds in the background and returns at once. */
  fun buildAsync(buildConfig: String, mode: PortBuildMode = PortBuildMode.BUILD) {
    coroutineScope.launch { build(buildConfig, mode) }
  }

  suspend fun build(buildConfig: String, mode: PortBuildMode = PortBuildMode.BUILD): PortBuildResult = mutex.withLock {
    val config = project.rhipperConfig ?: throw ExecutionException(RhipperBundle.message("run.configuration.no.project"))
    withBackgroundProgress(project, RhipperBundle.message("build.progress", config.name, buildConfig)) {
      runBuild(config, buildConfig, mode)
    }
  }

  private suspend fun runBuild(config: RhipperProjectConfig, buildConfig: String, mode: PortBuildMode): PortBuildResult {
    val started = System.currentTimeMillis()
    val title = RhipperBundle.message(when (mode) {
      PortBuildMode.BUILD -> "build.title"
      PortBuildMode.REBUILD -> "build.title.rebuild"
      PortBuildMode.CLEAN -> "build.title.clean"
    }, config.name, buildConfig)
    val buildDescriptor = DefaultBuildDescriptor(Any(), title, config.root.toString(), started)
      .withRestartAction(DumbAwareAction.create(RhipperBundle.message("build.rerun"), AllIcons.Actions.Compile) { _: AnActionEvent ->
        buildAsync(buildConfig, mode)
      })
    val root = BuildViewManager.createBuildProgress(project)
    root.start(object : BuildProgressDescriptor {
      override fun getTitle(): String = title
      override fun getBuildDescriptor() = buildDescriptor
    })
    // the user sees the result without a search for it; the editor keeps the focus
    withContext(Dispatchers.EDT) {
      BuildContentManager.getInstance(project).getOrCreateToolWindow().activate(null, false, false)
    }

    val parser = BuildOutputParser(config, root, configures = mode != PortBuildMode.CLEAN)
    var exitCode = 0
    try {
      for (commandLine in RhipperCommandLines(config).buildPortSteps(buildConfig, msbuildArgs(config), mode)) {
        parser.command(commandLine.commandLineString)
        exitCode = runProcess(commandLine, parser)
        if (exitCode != 0) break
      }
    }
    catch (e: ExecutionException) {
      parser.finish(false)
      root.fail(System.currentTimeMillis(), e.message ?: e.toString())
      throw e
    }
    catch (e: CancellationException) {
      parser.finish(false)
      root.cancel(System.currentTimeMillis(), RhipperBundle.message("build.cancelled"))
      throw e
    }
    parser.finish(exitCode == 0)

    val seconds = (System.currentTimeMillis() - started) / 1000
    val result = PortBuildResult(buildConfig, exitCode, parser.diagnostics, parser.outputTail(), seconds)
    val summary = RhipperBundle.message("build.summary", result.errors, result.warnings, seconds)
    parser.banner(result.succeeded, summary)
    if (result.succeeded) root.finish(System.currentTimeMillis(), false, summary)
    else root.fail(System.currentTimeMillis(), RhipperBundle.message("build.failed", summary))
    return result
  }

  /** Runs [commandLine], gives its output to [parser], and returns its exit code. A cancellation kills the process. */
  private suspend fun runProcess(commandLine: GeneralCommandLine, parser: BuildOutputParser): Int {
    val handler = KillableProcessHandler(commandLine)
    val exit = CompletableDeferred<Int>()
    handler.addProcessListener(object : ProcessListener {
      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (ProcessOutputType.SYSTEM == outputType) return
        parser.accept(event.text)
      }

      override fun processTerminated(event: ProcessEvent) {
        exit.complete(event.exitCode)
      }
    })
    handler.startNotify()
    try {
      return exit.await()
    }
    catch (e: CancellationException) {
      handler.destroyProcess()
      throw e
    }
  }

  /** MSBuild switches for a Visual Studio generator: the short output that the parser reads. Other generators get none. */
  private fun msbuildArgs(config: RhipperProjectConfig): List<String> {
    val cache = config.resolve(config.build.dir).resolve("CMakeCache.txt")
    val generator = runCatching { Files.readAllLines(cache).firstOrNull { it.startsWith("CMAKE_GENERATOR:") } }.getOrNull()
    return if (generator?.contains("Visual Studio") == true) listOf("/nologo", "/v:minimal") else emptyList()
  }

  companion object {
    fun getInstance(project: Project): RhipperPortBuild = project.service()
  }
}

/** ANSI styles for the Build console, which decodes them. */
private object Ansi {
  const val RESET = "\u001B[0m"
  const val BOLD = "\u001B[1m"
  const val DIM = "\u001B[2m"
  const val RED = "\u001B[1;91m"
  const val YELLOW = "\u001B[1;93m"
  const val GREEN = "\u001B[1;92m"
  const val CYAN = "\u001B[36m"
  const val MAGENTA = "\u001B[1;95m"

  fun style(style: String, text: String): String = "$style$text$RESET"
}

/**
 * Reads the build output line by line. It turns each line into Build tool window events, and prints a styled form of it to the
 * Build console: the milestones, a file counter, and the warnings and errors stand out.
 */
private class BuildOutputParser(
  private val config: RhipperProjectConfig,
  private val root: BuildProgress<BuildProgressDescriptor>,
  configures: Boolean,
) {
  val diagnostics = ArrayList<BuildDiagnostic>()
  private val buffer = StringBuilder()
  private val tail = ArrayDeque<String>()
  private var configure: BuildProgress<BuildProgressDescriptor>? =
    if (configures) root.startChildProgress(RhipperBundle.message("build.configure")) else null
  private var compile: BuildProgress<BuildProgressDescriptor>? = null
  private var compiled = 0
  /** The number of files to compile: Ninja states it, and for MSBuild it is the count of sources in the projects. */
  private var total = 0

  @Synchronized
  fun accept(text: String) {
    buffer.append(text)
    while (true) {
      val newline = buffer.indexOf('\n')
      if (newline < 0) break
      val line = buffer.substring(0, newline).trimEnd('\r')
      buffer.delete(0, newline + 1)
      onLine(line)
    }
  }

  /** Prints the start of a process of the build. */
  @Synchronized
  fun command(commandLine: String) {
    print(Ansi.style(Ansi.MAGENTA, "\u25B6 ") + Ansi.style(Ansi.BOLD, commandLine))
  }

  @Synchronized
  fun finish(succeeded: Boolean) {
    if (buffer.isNotEmpty()) onLine(buffer.toString().trimEnd('\r'))
    buffer.setLength(0)
    configure?.let { if (succeeded) it.finish() else it.fail() }
    compile?.let { if (succeeded) it.finish(System.currentTimeMillis(), false, RhipperBundle.message("build.compiled", compiled)) else it.fail() }
  }

  /** Prints the result of the build as the last line. */
  @Synchronized
  fun banner(succeeded: Boolean, summary: String) {
    print("")
    print(if (succeeded) Ansi.style(Ansi.GREEN, "\u2714 " + RhipperBundle.message("build.banner.succeeded", summary))
          else Ansi.style(Ansi.RED, "\u2716 " + RhipperBundle.message("build.banner.failed", summary)))
  }

  fun outputTail(): String = tail.joinToString("\n")

  private fun print(line: String) {
    root.output(line + "\n", ProcessOutputType.STDOUT)
  }

  private fun onLine(line: String) {
    tail.addLast(line)
    if (tail.size > 60) tail.removeFirst()
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return

    if (trimmed.startsWith("-- ")) {
      if (configure != null && trimmed.startsWith("-- Build files have been written")) {
        configure?.finish()
        configure = null
        compile = root.startChildProgress(RhipperBundle.message("build.compile"))
        total = countSources()
        print(Ansi.style(Ansi.GREEN, "\u2714 " + RhipperBundle.message("build.configured")))
      }
      else {
        print(Ansi.style(Ansi.DIM, trimmed))
      }
      return
    }

    COMPILER_MESSAGE.matchEntire(trimmed)?.let { match ->
      val (file, lineNumber, column, severity, code, message) = match.destructured
      val path = resolve(file)
      val diagnostic = BuildDiagnostic(kind(severity), code, message.substringBeforeLast(" [").trim(), path, lineNumber.toIntOrNull(),
                                       column.toIntOrNull())
      val location = "${path?.fileName ?: file}:$lineNumber" + (column.takeIf { it.isNotEmpty() }?.let { ":$it" } ?: "")
      printDiagnostic(diagnostic, severity, location)
      if (diagnostic.severity == MessageEvent.Kind.INFO) return
      diagnostics.add(diagnostic)
      val target = compile ?: root
      if (path != null && diagnostic.line != null) {
        target.fileMessage("$code: ${diagnostic.message}", trimmed, diagnostic.severity,
                           FilePosition(path, diagnostic.line - 1, (diagnostic.column ?: 1) - 1))
      }
      else {
        target.message("$code: ${diagnostic.message}", trimmed, diagnostic.severity, null)
      }
      return
    }

    LINKER_MESSAGE.matchEntire(trimmed)?.let { match ->
      val (source, severity, code, message) = match.destructured
      val diagnostic = BuildDiagnostic(kind(severity), code, message.substringBeforeLast(" [").trim(), null, null, null)
      diagnostics.add(diagnostic)
      printDiagnostic(diagnostic, severity, source.trim())
      (compile ?: root).message("$code: ${diagnostic.message}", trimmed, diagnostic.severity, null)
      return
    }

    TARGET_DONE.matchEntire(trimmed)?.let { match ->
      val (project, output) = match.destructured
      val name = RhipperBundle.message("build.target", project, Path.of(output.trim()).fileName.toString())
      root.startChildProgress(name).finish()
      print(Ansi.style(Ansi.GREEN, "\u2714 $name"))
      return
    }

    NINJA_STEP.matchEntire(trimmed)?.let { match ->
      val (step, steps, what) = match.destructured
      total = steps.toInt()
      compiled = step.toInt()
      printProgress(what)
      return
    }

    SOURCE_FILE.matchEntire(trimmed)?.let { match ->
      compiled++
      printProgress(match.groupValues[1])
      return
    }

    print(Ansi.style(Ansi.DIM, trimmed))
  }

  /** Prints `[ 30/400] translated_000.c` and moves the progress of the compile node. */
  private fun printProgress(what: String) {
    val shownTotal = maxOf(total, compiled)
    val counter = if (shownTotal > 0) "[%${shownTotal.toString().length}d/%d]".format(compiled, shownTotal) else "[$compiled]"
    print("  " + Ansi.style(Ansi.CYAN, counter) + " " + what)
    val node = compile ?: root
    if (shownTotal > 0) node.progress(RhipperBundle.message("build.compiling", what, compiled), shownTotal.toLong(), compiled.toLong(), "")
    else node.progress(RhipperBundle.message("build.compiling", what, compiled))
  }

  private fun printDiagnostic(diagnostic: BuildDiagnostic, severity: String, location: String) {
    val (style, mark) = when (diagnostic.severity) {
      MessageEvent.Kind.ERROR -> Ansi.RED to "\u2716"
      MessageEvent.Kind.WARNING -> Ansi.YELLOW to "\u26A0"
      else -> Ansi.DIM to "\u00B7"
    }
    print("  " + Ansi.style(style, "$mark $severity ${diagnostic.code}") + " " + Ansi.style(Ansi.CYAN, location) + " " +
          if (diagnostic.severity == MessageEvent.Kind.INFO) Ansi.style(Ansi.DIM, diagnostic.message) else diagnostic.message)
  }

  /** The number of sources in the Visual Studio projects of the build directory, or 0 for another generator. */
  private fun countSources(): Int = runCatching {
    Files.walk(config.resolve(config.build.dir), 3).use { paths ->
      paths.filter { it.fileName.toString().endsWith(".vcxproj") }.toList().sumOf { project ->
        CL_COMPILE.findAll(Files.readString(project)).count()
      }
    }
  }.getOrDefault(0)

  private fun resolve(file: String): Path? = runCatching {
    val path = Path.of(file.trim())
    if (path.isAbsolute) path else config.resolve(config.build.dir).resolve(path).normalize()
  }.getOrNull()

  private fun kind(severity: String): MessageEvent.Kind = when {
    severity.contains("error") -> MessageEvent.Kind.ERROR
    severity.contains("warning") -> MessageEvent.Kind.WARNING
    else -> MessageEvent.Kind.INFO
  }

  companion object {
    /** `C:\src\gen\translated_000.c(1912,5): warning C4244: '=': conversion ... [C:\build\gen.vcxproj]` */
    private val COMPILER_MESSAGE =
      Regex("""(.+?)\((\d+)(?:,(\d+))?\)\s*:\s*(fatal error|error|warning|note)\s+([A-Z]+\d+)\s*:\s*(.*)""")

    /** `gen.lib(translated_000.obj) : error LNK2005: ...` or `LINK : fatal error LNK1104: ...` */
    private val LINKER_MESSAGE = Regex("""(.+?)\s*:\s*(fatal error|error|warning)\s+(LNK\d+)\s*:\s*(.*)""")

    /** `gen.vcxproj -> C:\build\cmake\gen.dir\Release\gen.lib` */
    private val TARGET_DONE = Regex("""(\S+?)\.(?:vcxproj|csproj)\s*->\s*(.+)""")

    /** `[30/400] Building C object gen/CMakeFiles/gen.dir/translated_000.c.obj` of Ninja. */
    private val NINJA_STEP = Regex("""\[(\d+)/(\d+)]\s+(.*)""")

    /** The name of a source file that cl.exe prints when it starts to compile it. */
    private val SOURCE_FILE = Regex("""([\w.\-]+\.(?:c|cc|cpp|cxx))""")

    /** A source of a Visual Studio project. */
    private val CL_COMPILE = Regex("""<ClCompile\s+Include=""")
  }
}
