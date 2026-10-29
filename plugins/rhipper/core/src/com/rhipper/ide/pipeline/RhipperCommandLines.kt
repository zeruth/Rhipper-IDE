// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.settings.RhipperSettings
import com.rhipper.ide.tools.RhipperTools
import java.io.File
import java.nio.file.Path

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

  fun buildCommand(): List<String> {
    val command = arrayListOf("cmake", "--build", config.resolve(config.build.dir).toString(), "--config", config.build.config)
    config.build.target?.let { command.addAll(listOf("--target", it)) }
    return command
  }

  fun runCommand(): List<String> {
    val exe = config.run.exe ?: throw ExecutionException(RhipperBundle.message("run.exe.not.set"))
    return listOf(config.resolve(exe).toString()) + config.run.args
  }

  private fun prepare(commandLine: GeneralCommandLine): GeneralCommandLine {
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
