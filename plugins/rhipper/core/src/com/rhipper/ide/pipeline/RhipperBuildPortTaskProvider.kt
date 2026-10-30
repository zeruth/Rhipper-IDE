// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.util.Key
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.build.RhipperPortBuild
import javax.swing.Icon

class RhipperBuildPortTask : BeforeRunTask<RhipperBuildPortTask>(RhipperBuildPortTaskProvider.ID)

/**
 * Builds the port before a [RhipperApplicationRunConfiguration] of the port starts it, in the build configuration of that run.
 * The build shows in the Build tool window, and a failed build stops the launch.
 */
class RhipperBuildPortTaskProvider : BeforeRunTaskProvider<RhipperBuildPortTask>() {
  override fun getId(): Key<RhipperBuildPortTask> = ID

  override fun getName(): String = RhipperBundle.message("build.port.task.name")

  override fun getIcon(): Icon = AllIcons.Actions.Compile

  override fun getDescription(task: RhipperBuildPortTask): String = RhipperBundle.message("build.port.task.name")

  /** A new Rhipper application configuration gets the task, switched on. Other configurations do not get it. */
  override fun createTask(runConfiguration: RunConfiguration): RhipperBuildPortTask? {
    if (runConfiguration !is RhipperApplicationRunConfiguration) return null
    return RhipperBuildPortTask().apply { isEnabled = true }
  }

  override fun canExecuteTask(configuration: RunConfiguration, task: RhipperBuildPortTask): Boolean {
    return configuration is RhipperApplicationRunConfiguration
  }

  override fun executeTask(context: DataContext, configuration: RunConfiguration, environment: ExecutionEnvironment, task: RhipperBuildPortTask): Boolean {
    val app = configuration as? RhipperApplicationRunConfiguration ?: return true
    // the reference binary needs no build
    if (app.options.target != RhipperRunTarget.PORT) return true
    val buildConfig = app.options.buildConfig ?: "Release"
    val result = runBlockingCancellable { RhipperPortBuild.getInstance(app.project).build(buildConfig) }
    return result.succeeded
  }

  companion object {
    val ID: Key<RhipperBuildPortTask> = Key.create("RhipperBuildPort")
  }
}
