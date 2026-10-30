// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.ExecutionException
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.build.RhipperPortBuild
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The end of a run: its exit code (`null` when it still runs at the timeout) and the last lines of its output. */
data class RhipperRunResult(val exitCode: Int?, val output: String, val truncated: Boolean)

private const val OUTPUT_LIMIT = 64 * 1024

/**
 * Runs [step] and waits up to [timeoutMillis] for its end. The build of the port shows in the Build tool window, and its result
 * lists the compiler and linker diagnostics. Another step shows in the Run tool window and goes on when the wait ends.
 */
suspend fun runStepAndWait(
  project: Project,
  step: RhipperStep,
  scope: RhipperStepScope,
  timeoutMillis: Long,
): RhipperRunResult {
  val config = project.rhipperConfig ?: throw ExecutionException(RhipperBundle.message("run.configuration.no.project"))
  if (step == RhipperStep.BUILD && scope == RhipperStepScope.ONLY) {
    val build = withTimeoutOrNull(timeoutMillis) { RhipperPortBuild.getInstance(project).build(config.build.config) }
                ?: return RhipperRunResult(null, "", truncated = false)
    val diagnostics = build.diagnostics.joinToString("\n") { d ->
      listOfNotNull(d.file?.let { "$it(${d.line},${d.column ?: 1})" }, "${d.severity.name.lowercase()} ${d.code}: ${d.message}").joinToString(": ")
    }
    val summary = RhipperBundle.message("build.summary", build.errors, build.warnings, build.seconds)
    return RhipperRunResult(build.exitCode, listOf(summary, diagnostics, build.outputTail).filter { it.isNotBlank() }.joinToString("\n\n"),
                            truncated = false)
  }
  val output = OutputTail()
  val finished = CompletableDeferred<Int>()
  withContext(Dispatchers.EDT) {
    val settings = createStepConfiguration(project, step, scope)
    val environment = ExecutionEnvironmentBuilder.create(DefaultRunExecutor.getRunExecutorInstance(), settings).build { descriptor ->
      val handler = descriptor.processHandler
      if (handler == null) {
        finished.complete(-1)
        return@build
      }
      handler.addProcessListener(object : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
          if (outputType != ProcessOutputType.SYSTEM) output.append(event.text)
        }

        override fun processTerminated(event: ProcessEvent) {
          finished.complete(event.exitCode)
        }
      })
      if (handler.isProcessTerminated) finished.complete(handler.exitCode ?: -1)
    }
    ProgramRunnerUtil.executeConfiguration(environment, false, true)
  }
  val exitCode = withTimeoutOrNull(timeoutMillis) { finished.await() }
  return output.result(exitCode)
}

/**
 * Runs the Rhipper script [script] with [args] in the background and returns its output.
 * A script prints a listing, so a long output keeps its start and its end.
 */
suspend fun runRhipperScript(project: Project, script: String, args: List<String>, timeoutMillis: Int = 300_000): RhipperRunResult {
  val config = project.rhipperConfig ?: throw ExecutionException(RhipperBundle.message("run.configuration.no.project"))
  return withContext(Dispatchers.IO) {
    val commandLine = RhipperCommandLines(config).python(script, args)
    val processOutput = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
    val text = processOutput.stdout + processOutput.stderr
    val exitCode = if (processOutput.isTimeout) null else processOutput.exitCode
    if (text.length <= OUTPUT_LIMIT) return@withContext RhipperRunResult(exitCode, text, truncated = false)
    val head = text.substring(0, OUTPUT_LIMIT * 3 / 4).substringBeforeLast('\n')
    val tail = text.substring(text.length - OUTPUT_LIMIT / 4).substringAfter('\n')
    val lines = text.count { it == '\n' }
    RhipperRunResult(exitCode, "$head\n... ($lines lines in all; the middle is left out) ...\n$tail", truncated = true)
  }
}

/** The last [OUTPUT_LIMIT] characters of a process's output. */
private class OutputTail {
  private val text = StringBuilder()
  private var dropped = false

  @Synchronized
  fun append(chunk: String) {
    text.append(chunk)
    if (text.length > OUTPUT_LIMIT * 2) {
      text.delete(0, text.length - OUTPUT_LIMIT)
      dropped = true
    }
  }

  @Synchronized
  fun result(exitCode: Int?): RhipperRunResult {
    val truncated = dropped || text.length > OUTPUT_LIMIT
    return RhipperRunResult(exitCode, if (text.length > OUTPUT_LIMIT) text.substring(text.length - OUTPUT_LIMIT) else text.toString(), truncated)
  }
}
