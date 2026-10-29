// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
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

/** Selects the run configuration of [step] and [scope] in the Run widget and runs it. */
fun runStep(project: Project, step: RhipperStep, scope: RhipperStepScope) {
  val settings = findOrCreateStepConfiguration(project, step, scope)
  RunManager.getInstance(project).selectedConfiguration = settings
  ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}

internal fun findOrCreateStepConfiguration(project: Project, step: RhipperStep, scope: RhipperStepScope): RunnerAndConfigurationSettings {
  val runManager = RunManager.getInstance(project)
  val type = RhipperRunConfigurationType.getInstance()
  runManager.getConfigurationSettingsList(type).firstOrNull { settings ->
    val configuration = settings.configuration as? RhipperRunConfiguration
    configuration != null && configuration.step == step && configuration.scope == scope && configuration.extraArgs.isEmpty()
  }?.let { return it }

  val settings = runManager.createConfiguration(RhipperRunConfiguration.presentableName(step, scope), type.factory)
  val configuration = settings.configuration as RhipperRunConfiguration
  configuration.step = step
  configuration.scope = scope
  runManager.addConfiguration(settings)
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
