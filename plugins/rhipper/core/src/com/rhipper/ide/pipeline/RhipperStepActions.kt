// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.rhipper.ide.build.RhipperPortBuild
import com.rhipper.ide.project.rhipperConfig

/** Runs [step] of the pipeline through its run configuration, which the action creates when the project has none. */
internal class RhipperRunStepAction(private val step: RhipperStep, private val scope: RhipperStepScope) : AnAction(), DumbAware {
  init {
    templatePresentation.text = RhipperRunConfiguration.presentableName(step, scope)
    templatePresentation.description = step.description
    templatePresentation.icon = step.icon
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    runStep(project, step, scope)
  }
}

/** Build | Build Project: the Rhipper product has no Java build, so the action builds the port of the Rhipper project. */
internal class RhipperBuildPortAction : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    runStep(project, RhipperStep.BUILD, RhipperStepScope.ONLY)
  }
}

/** Build | Rebuild Project and Build | Clean Port: build the port of the Rhipper project in [mode]. */
internal sealed class RhipperPortBuildModeAction(private val mode: PortBuildMode) : AnAction(), DumbAware {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabled = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val config = project.rhipperConfig ?: return
    RhipperPortBuild.getInstance(project).buildAsync(config.build.config, mode)
  }
}

internal class RhipperRebuildPortAction : RhipperPortBuildModeAction(PortBuildMode.REBUILD)

internal class RhipperCleanPortAction : RhipperPortBuildModeAction(PortBuildMode.CLEAN)

/**
 * Runs [step] and [scope]. The build of the port shows in the Build tool window. Another step shows in the Run tool window,
 * through a run configuration that the project does not keep.
 */
fun runStep(project: Project, step: RhipperStep, scope: RhipperStepScope) {
  val config = project.rhipperConfig ?: return
  if (step == RhipperStep.BUILD && scope == RhipperStepScope.ONLY) {
    RhipperPortBuild.getInstance(project).buildAsync(config.build.config)
    return
  }
  ProgramRunnerUtil.executeConfiguration(createStepConfiguration(project, step, scope), DefaultRunExecutor.getRunExecutorInstance())
}

/** A run configuration of [step] and [scope] that the Run Manager does not keep, so a step run adds no entry to the Run widget. */
internal fun createStepConfiguration(project: Project, step: RhipperStep, scope: RhipperStepScope): RunnerAndConfigurationSettings {
  val type = RhipperRunConfigurationType.getInstance()
  val settings = RunManager.getInstance(project).createConfiguration(RhipperRunConfiguration.presentableName(step, scope), type.factory)
  val configuration = settings.configuration as RhipperRunConfiguration
  configuration.step = step
  configuration.scope = scope
  return settings
}

/** The step buttons beside the Run widget: one button for each step, which runs that step only. */
internal class RhipperStepButtonsGroup : ActionGroup(), DumbAware {
  private val children: Array<AnAction> = RhipperStep.entries.map { RhipperRunStepAction(it, RhipperStepScope.ONLY) }.toTypedArray()

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}

/** The drop-down that runs the pipeline from a step to the end. */
internal class RhipperRunFromGroup : ActionGroup(), DumbAware {
  private val children: Array<AnAction> = RhipperStep.entries.map { RhipperRunStepAction(it, RhipperStepScope.FROM) }.toTypedArray()

  init {
    templatePresentation.isPopupGroup = true
  }

  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun getChildren(e: AnActionEvent?): Array<AnAction> = children
}
