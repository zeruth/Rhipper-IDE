// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NlsSafe
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.file.Files
import java.time.Instant
import kotlin.io.path.getLastModifiedTime

enum class RhipperStepState { NOT_RUN, RUNNING, SUCCEEDED, FAILED, STOPPED }

data class RhipperStepStatus(
  val step: RhipperStep,
  val state: RhipperStepState = RhipperStepState.NOT_RUN,
  /** When the step last started, in this IDE session or by the time of its log. */
  val started: Instant? = null,
  val finished: Instant? = null,
  /** The last progress line the pipeline printed for the step. */
  val activity: @NlsSafe String? = null,
)

/**
 * The state of each pipeline step: from the runs that this IDE started, else from the step logs in `build/pipeline`.
 *
 * It reads the progress lines of `pipeline32.py`, `[  12s] translate32`, to know which step runs.
 */
@Service(Service.Level.PROJECT)
class RhipperPipelineStatus {
  private val _steps = MutableStateFlow(RhipperStep.entries.associateWith { RhipperStepStatus(it) })
  val steps: StateFlow<Map<RhipperStep, RhipperStepStatus>> = _steps.asStateFlow()

  /** Fills the steps that no run of this session touched from the times of their logs. */
  fun refreshFromLogs(config: RhipperProjectConfig) {
    val logs = config.pipelineLogPath
    _steps.update { current ->
      current.mapValues { (step, status) ->
        if (status.state != RhipperStepState.NOT_RUN) return@mapValues status
        val log = logs.resolve(if (step == RhipperStep.TRANSLATE && config.arch == RhipperArch.X86) "translate32.log" else step.logName)
        if (!Files.isRegularFile(log)) return@mapValues status
        status.copy(finished = log.getLastModifiedTime().toInstant())
      }
    }
  }

  /** The pipeline process this IDE runs now, or `null`. */
  @Volatile
  private var live: ProcessHandler? = null

  /** Stops the pipeline run this IDE started, its child processes too. `false` when none runs. */
  fun stop(): Boolean {
    val handler = live?.takeUnless { it.isProcessTerminated || it.isProcessTerminating } ?: return false
    handler.destroyProcess()
    return true
  }

  internal fun track(handler: ProcessHandler, first: RhipperStep, scope: RhipperStepScope, arch: RhipperArch) {
    live = handler
    val planned = if (scope == RhipperStepScope.ONLY) listOf(first) else RhipperStep.entries.drop(first.ordinal)
    var current = first
    val now = Instant.now()
    _steps.update { steps ->
      steps.mapValues { (step, status) ->
        when {
          step == first -> status.copy(state = RhipperStepState.RUNNING, started = now, finished = null, activity = null)
          step in planned -> status.copy(state = RhipperStepState.NOT_RUN, activity = null)
          else -> status
        }
      }
    }
    handler.addProcessListener(object : ProcessListener {
      private val line = StringBuilder()

      override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        if (outputType != ProcessOutputType.STDOUT || arch != RhipperArch.X86) return
        for (ch in event.text) {
          if (ch == '\n') {
            onLine(line.toString().trimEnd('\r'))
            line.setLength(0)
          }
          else {
            line.append(ch)
          }
        }
      }

      private fun onLine(text: String) {
        val stamp = STAMP.matchEntire(text)?.groupValues?.get(1) ?: return
        val failed = FAILED.matchEntire(stamp)?.groupValues?.get(1)
        when {
          failed != null -> finish(stepOfStamp(failed) ?: current, RhipperStepState.FAILED)
          stamp == "done" -> finish(current, RhipperStepState.SUCCEEDED)
          else -> {
            val next = stepOfStamp(stamp)
            if (next != null && next != current && next in planned) {
              finish(current, RhipperStepState.SUCCEEDED)
              current = next
              start(next)
            }
            setActivity(current, stamp)
          }
        }
      }

      override fun processTerminated(event: ProcessEvent) {
        if (live === handler) live = null
        val state = if (event.exitCode == 0) RhipperStepState.SUCCEEDED else RhipperStepState.FAILED
        _steps.update { steps ->
          steps.mapValues { (_, status) ->
            if (status.state == RhipperStepState.RUNNING) status.copy(state = state, finished = Instant.now()) else status
          }
        }
      }

      override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
        if (!willBeDestroyed) return
        _steps.update { steps ->
          steps.mapValues { (_, status) ->
            if (status.state == RhipperStepState.RUNNING) status.copy(state = RhipperStepState.STOPPED, finished = Instant.now()) else status
          }
        }
      }
    })
  }

  private fun start(step: RhipperStep) = updateStep(step) { it.copy(state = RhipperStepState.RUNNING, started = Instant.now(), finished = null) }

  private fun finish(step: RhipperStep, state: RhipperStepState) = updateStep(step) {
    if (it.state == RhipperStepState.RUNNING) it.copy(state = state, finished = Instant.now()) else it
  }

  private fun setActivity(step: RhipperStep, activity: String) = updateStep(step) { it.copy(activity = activity) }

  private fun updateStep(step: RhipperStep, transform: (RhipperStepStatus) -> RhipperStepStatus) {
    _steps.update { steps -> steps + (step to transform(steps.getValue(step))) }
  }

  companion object {
    private val STAMP = Regex("""\[\s*\d+s] (.+)""")
    private val FAILED = Regex("""(\S+) failed: stopping""")

    fun getInstance(project: Project): RhipperPipelineStatus = project.service()

    /** The step that a progress line of `pipeline32.py` starts, or `null` when the line is a part of the current step. */
    fun stepOfStamp(stamp: String): RhipperStep? = when {
      stamp == "ghidra.sh all" || stamp == "ghidra" -> RhipperStep.GHIDRA
      stamp.startsWith("translate") -> RhipperStep.TRANSLATE
      stamp == "build" -> RhipperStep.BUILD
      stamp == "run" -> RhipperStep.RUN
      stamp == "facts" -> RhipperStep.FACTS
      else -> null
    }
  }
}
