// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsSafe
import com.intellij.util.execution.ParametersListUtil
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.RhipperIcons
import com.rhipper.ide.project.rhipperConfig

class RhipperRunConfigurationType : ConfigurationTypeBase(
  ID,
  RhipperBundle.message("run.configuration.type.name"),
  RhipperBundle.message("run.configuration.type.description"),
  RhipperIcons.Rhipper,
) {
  init {
    addFactory(RhipperStepConfigurationFactory(this))
  }

  val factory: ConfigurationFactory get() = configurationFactories.single()

  companion object {
    const val ID: String = "RhipperPipelineStep"

    fun getInstance(): RhipperRunConfigurationType = ConfigurationTypeUtil.findConfigurationType(RhipperRunConfigurationType::class.java)
  }
}

internal class RhipperStepConfigurationFactory(type: RhipperRunConfigurationType) : ConfigurationFactory(type) {
  override fun getId(): String = "RhipperStep"

  override fun createTemplateConfiguration(project: Project): RunConfiguration {
    return RhipperRunConfiguration(project, this, RhipperBundle.message("run.configuration.type.name"))
  }

  override fun getOptionsClass(): Class<out BaseState> = RhipperRunConfigurationOptions::class.java
}

class RhipperRunConfigurationOptions : RunConfigurationOptions() {
  var step: String? by string(RhipperStep.TRANSLATE.id)
  var scope: RhipperStepScope by enum(RhipperStepScope.ONLY)
  var extraArgs: String? by string()

  /** The CMake configuration of a build step, such as `Debug`. `null` means the pipeline's build, in the configuration of `rhipper.json`. */
  var buildConfig: String? by string()
}

/** Runs one step of the Rhipper pipeline, or the pipeline from a step to the end. */
class RhipperRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  RunConfigurationBase<RhipperRunConfigurationOptions>(project, factory, name) {

  public override fun getOptions(): RhipperRunConfigurationOptions = super.getOptions() as RhipperRunConfigurationOptions

  var step: RhipperStep
    get() = RhipperStep.of(options.step) ?: RhipperStep.TRANSLATE
    set(value) {
      options.step = value.id
    }

  var scope: RhipperStepScope
    get() = options.scope
    set(value) {
      options.scope = value
    }

  var extraArgs: String
    get() = options.extraArgs.orEmpty()
    set(value) {
      options.extraArgs = value.ifBlank { null }
    }

  var buildConfig: String?
    get() = options.buildConfig
    set(value) {
      options.buildConfig = value
    }

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = RhipperRunConfigurationEditor()

  override fun checkConfiguration() {
    if (project.rhipperConfig == null) {
      throw RuntimeConfigurationError(RhipperBundle.message("run.configuration.no.project"))
    }
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
    val config = project.rhipperConfig ?: throw ExecutionException(RhipperBundle.message("run.configuration.no.project"))
    val lines = RhipperCommandLines(config)
    val buildConfig = options.buildConfig
    val commandLine = if (step == RhipperStep.BUILD && scope == RhipperStepScope.ONLY && buildConfig != null) lines.buildPort(buildConfig)
    else lines.pipeline(step, scope, ParametersListUtil.parse(extraArgs))
    val step = step
    val scope = scope
    return object : CommandLineState(environment) {
      override fun startProcess(): ProcessHandler {
        val handler = KillableColoredProcessHandler(commandLine)
        handler.setShouldKillProcessSoftly(true)
        ProcessTerminatedListener.attach(handler)
        RhipperPipelineStatus.getInstance(project).track(handler, step, scope, config.arch)
        return handler
      }
    }
  }

  companion object {
    fun presentableName(step: RhipperStep, scope: RhipperStepScope): @NlsSafe String = when (scope) {
      RhipperStepScope.ONLY -> RhipperBundle.message("run.configuration.name.only", step.displayName)
      RhipperStepScope.FROM -> RhipperBundle.message("run.configuration.name.from", step.displayName)
    }
  }
}
