// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.settings.RhipperSettings
import com.rhipper.ide.tools.RhipperTools
import java.io.File
import java.nio.file.Path

/** What a build of the port does. */
enum class PortBuildMode {
  /** Compiles what changed. */
  BUILD,

  /** Deletes the build outputs, then compiles everything. */
  REBUILD,

  /** Deletes the build outputs only. */
  CLEAN,
}

/**
 * Builds the command lines that run Rhipper's tools for a project.
 *
 * Every command runs in the project root with `RHIPPER_PROJECT` set, so Rhipper's `project.py` finds the `rhipper.json`.
 */
class RhipperCommandLines(private val config: RhipperProjectConfig, private val settings: RhipperSettings = RhipperSettings.getInstance()) {
  val rhipperHome: Path
    get() = settings.rhipperHome(config) ?: throw ExecutionException(RhipperBundle.message("rhipper.home.not.found"))

  /** A command that runs the Rhipper script [script] with [args], for example `callers.py 0x401000`. */
  fun python(script: String, args: List<String> = emptyList()): GeneralCommandLine {
    return prepare(GeneralCommandLine(listOf(settings.pythonExecutable(), "-u", rhipperHome.resolve(script).toString()) + args))
  }

  /** A command that runs [steps] of the pipeline. [extraArgs] go to `pipeline32.py` for a 32-bit project. */
  fun pipeline(step: RhipperStep, scope: RhipperStepScope, extraArgs: List<String> = emptyList()): GeneralCommandLine {
    if (config.arch == RhipperArch.X86) {
      val selector = if (scope == RhipperStepScope.ONLY) "--only" else "--from"
      return python("pipeline32.py", listOf(selector, step.id) + extraArgs)
    }
    val steps = if (scope == RhipperStepScope.ONLY) listOf(step) else RhipperStep.entries.drop(step.ordinal)
    val commands = x64Commands(steps)
    if (steps == listOf(RhipperStep.RUN)) {
      return prepare(GeneralCommandLine(commands.single())).withWorkingDirectory(config.resolve(config.run.cwd))
    }
    if (commands.size == 1) return prepare(GeneralCommandLine(commands.single()))
    val runCommand = if (RhipperStep.RUN in steps) commands.last() else null
    val script = commands.joinToString(" && ") { command ->
      val line = command.joinToString(" ") { shellQuote(it) }
      if (command === runCommand) "(cd ${shellQuote(config.resolve(config.run.cwd).toString())} && $line)" else line
    }
    return prepare(GeneralCommandLine(settings.bashExecutable(), "-c", script))
  }

  /**
   * The commands of [steps] for an x64 project. `pipeline.sh` runs facts, Ghidra and the translation.
   * The build and the run of the port come from the `build` and `run` sections of the `rhipper.json`.
   */
  private fun x64Commands(steps: List<RhipperStep>): List<List<String>> {
    val python = settings.pythonExecutable()
    val bash = settings.bashExecutable()
    val home = rhipperHome
    val pipelineSh = home.resolve("pipeline.sh").toString()
    val result = ArrayList<List<String>>()
    val first = steps.first()
    val pipelineSteps = steps.filter { it == RhipperStep.FACTS || it == RhipperStep.GHIDRA || it == RhipperStep.TRANSLATE }
    when {
      pipelineSteps.isEmpty() -> {}
      pipelineSteps == listOf(RhipperStep.FACTS) -> result.add(listOf(bash, pipelineSh, "--facts"))
      pipelineSteps == listOf(RhipperStep.GHIDRA) -> result.add(listOf(bash, home.resolve("ghidra/ghidra.sh").toString(), "all"))
      pipelineSteps == listOf(RhipperStep.TRANSLATE) -> result.add(listOf(python, "-u", home.resolve("translate64.py").toString()) + config.translateArgs)
      first == RhipperStep.GHIDRA -> result.add(listOf(bash, pipelineSh, "--from-ghidra"))
      else -> result.add(listOf(bash, pipelineSh))
    }
    if (RhipperStep.BUILD in steps) result.add(buildCommand())
    if (RhipperStep.RUN in steps) result.add(runCommand())
    return result
  }

  fun buildCommand(buildConfig: String = config.build.config): List<String> {
    val command = arrayListOf("cmake", "--build", config.resolve(config.build.dir).toString(), "--config", buildConfig, "--parallel")
    config.build.target?.let { command.addAll(listOf("--target", it)) }
    return command
  }

  /**
   * Builds the port in [buildConfig], such as `Release` or `Debug`. CMake configures first, because a new translation changes the
   * list of the generated files, as `pipeline32.py` does it.
   */
  fun buildPort(buildConfig: String): GeneralCommandLine {
    val configure = listOf("cmake", config.resolve(config.build.dir).toString())
    val script = listOf(configure, buildCommand(buildConfig)).joinToString(" && ") { command -> command.joinToString(" ") { shellQuote(it) } }
    return prepare(GeneralCommandLine(settings.bashExecutable(), "-c", script))
  }

  /**
   * The two processes of [buildPort], with no shell between them: the configure step, then the build. [nativeArgs] go to the
   * native build tool, such as MSBuild. A shell would turn an MSBuild switch such as `/v:minimal` into a path.
   */
  fun buildPortSteps(buildConfig: String, nativeArgs: List<String> = emptyList(), mode: PortBuildMode = PortBuildMode.BUILD): List<GeneralCommandLine> {
    val cmake = PathEnvironmentVariableUtil.findFirst("cmake")?.toString() ?: "cmake"
    val native = if (nativeArgs.isEmpty()) emptyList() else listOf("--") + nativeArgs
    val buildDir = config.resolve(config.build.dir).toString()
    if (mode == PortBuildMode.CLEAN) {
      return listOf(prepare(GeneralCommandLine(listOf(cmake, "--build", buildDir, "--config", buildConfig, "--target", "clean") + native)))
    }
    val configure = listOf(cmake, buildDir)
    val cleanFirst = if (mode == PortBuildMode.REBUILD) listOf("--clean-first") else emptyList()
    val build = listOf(cmake) + buildCommand(buildConfig).drop(1) + cleanFirst + native
    return listOf(configure, build).map { prepare(GeneralCommandLine(it)) }
  }

  /**
   * The executable of the port in [buildConfig]. The `run.exe` of the `rhipper.json` names the build configuration in its path,
   * such as `build/cmake/bin/Release/Game.exe`, so the path of another configuration replaces that segment.
   */
  fun portExe(buildConfig: String): Path {
    val exe = config.run.exe ?: throw ExecutionException(RhipperBundle.message("run.exe.not.set"))
    val segments = exe.replace('\\', '/').split('/')
    val replaced = segments.map { if (it.equals(config.build.config, ignoreCase = true)) buildConfig else it }
    return config.resolve(replaced.joinToString("/"))
  }

  fun runCommand(): List<String> {
    val exe = config.run.exe ?: throw ExecutionException(RhipperBundle.message("run.exe.not.set"))
    return listOf(config.resolve(exe).toString()) + config.run.args
  }

  /** Sets the working directory and the environment that every Rhipper tool expects. */
  fun prepare(commandLine: GeneralCommandLine): GeneralCommandLine {
    commandLine
      .withWorkingDirectory(config.root)
      .withEnvironment("RHIPPER_PROJECT", config.root.toString())
      .withEnvironment("PYTHONUNBUFFERED", "1")
      .withEnvironment("PYTHONIOENCODING", "utf-8")
      .withCharset(Charsets.UTF_8)
    // Rhipper's scripts run `python` and Ghidra runs `java` from the PATH: the IDE's installs go first
    val tools = RhipperTools.getInstance()
    val pathPrefix = buildList {
      Path.of(settings.pythonExecutable()).takeIf { it.isAbsolute }?.parent?.let { add(it.toString()) }
      tools.managedJdk?.let { add(it.resolve("bin").toString()) }
    }
    if (pathPrefix.isNotEmpty()) {
      val path = System.getenv("PATH").orEmpty()
      commandLine.withEnvironment("PATH", (pathPrefix + path).joinToString(File.pathSeparator))
    }
    tools.managedJdk?.let { commandLine.withEnvironment("JAVA_HOME", it.toString()) }
    return commandLine
  }
}

private fun shellQuote(arg: String): String {
  val path = arg.replace('\\', '/')
  if (path.isNotEmpty() && path.all { it.isLetterOrDigit() || it in "-_./:=+," }) return path
  return "'" + path.replace("'", "'\\''") + "'"
}
