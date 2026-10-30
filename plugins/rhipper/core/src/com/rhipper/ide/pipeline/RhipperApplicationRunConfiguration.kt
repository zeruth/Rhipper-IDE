// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configuration.EnvironmentVariablesData
import com.intellij.execution.configuration.EnvironmentVariablesTextFieldWithBrowseButton
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationTypeBase
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunConfigurationOptions
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.execution.ParametersListUtil
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.rhipperConfig
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JComponent

private val LOG = logger<RhipperApplicationRunConfiguration>()

/** What a Rhipper application run starts. */
enum class RhipperRunTarget {
  /** The translated program that the project builds. */
  PORT,

  /** The original binary, the `exe` of the `rhipper.json`. */
  REFERENCE,
}

/** The CMake configurations a port builds in. */
val RHIPPER_BUILD_CONFIGS: List<String> = listOf("Release", "Debug", "RelWithDebInfo")

class RhipperApplicationRunConfigurationType : ConfigurationTypeBase(
  ID,
  RhipperBundle.message("app.run.configuration.type.name"),
  RhipperBundle.message("app.run.configuration.type.description"),
  AllIcons.RunConfigurations.Application,
) {
  init {
    addFactory(object : ConfigurationFactory(this) {
      override fun getId(): String = "RhipperApplication"

      override fun createTemplateConfiguration(project: Project): RunConfiguration {
        return RhipperApplicationRunConfiguration(project, this, RhipperBundle.message("app.run.configuration.type.name"))
      }

      override fun getOptionsClass(): Class<out BaseState> = RhipperApplicationOptions::class.java
    })
  }

  val factory: ConfigurationFactory get() = configurationFactories.single()

  companion object {
    const val ID: String = "RhipperApplication"

    fun getInstance(): RhipperApplicationRunConfigurationType =
      ConfigurationTypeUtil.findConfigurationType(RhipperApplicationRunConfigurationType::class.java)
  }
}

class RhipperApplicationOptions : RunConfigurationOptions() {
  var target: RhipperRunTarget by enum(RhipperRunTarget.PORT)
  var buildConfig: String? by string("Release")
  /** The arguments, or `null` for the `run.args` of the `rhipper.json`. */
  var programArgs: String? by string()
  /** The working directory, or `null` for the `run.cwd` of the `rhipper.json`. */
  var workingDirectory: String? by string()
  var envs: MutableMap<String, String> by linkedMap()
  var passParentEnvs: Boolean by property(true)
  /** Start the side processes of `run.with`, such as a server the program talks to, and stop them after the program. */
  var startSideProcesses: Boolean by property(true)
}

/**
 * Runs the port of a Rhipper project in a build configuration, or the reference binary. The before-launch task
 * [RhipperBuildPortTaskProvider] builds the port first.
 */
class RhipperApplicationRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
  RunConfigurationBase<RhipperApplicationOptions>(project, factory, name) {

  public override fun getOptions(): RhipperApplicationOptions = super.getOptions() as RhipperApplicationOptions

  override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> = RhipperApplicationRunConfigurationEditor(project)

  override fun checkConfiguration() {
    val config = project.rhipperConfig ?: throw RuntimeConfigurationError(RhipperBundle.message("run.configuration.no.project"))
    if (options.target == RhipperRunTarget.PORT && config.run.exe == null) {
      throw RuntimeConfigurationError(RhipperBundle.message("run.exe.not.set"))
    }
  }

  /** The executable that this configuration starts. */
  fun executable(config: RhipperProjectConfig): Path = when (options.target) {
    RhipperRunTarget.PORT -> RhipperCommandLines(config).portExe(options.buildConfig ?: config.build.config)
    RhipperRunTarget.REFERENCE -> config.exePath
  }

  override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
    val config = project.rhipperConfig ?: throw ExecutionException(RhipperBundle.message("run.configuration.no.project"))
    val exe = executable(config)
    if (!Files.isRegularFile(exe)) throw ExecutionException(RhipperBundle.message("app.run.configuration.no.exe", exe))
    val args = options.programArgs?.let(ParametersListUtil::parse) ?: config.run.args
    val workingDirectory = options.workingDirectory?.takeIf { it.isNotBlank() }?.let(config::resolve) ?: config.resolve(config.run.cwd)
    val commandLine = GeneralCommandLine(listOf(exe.toString()) + args)
      .withWorkingDirectory(workingDirectory)
      .withEnvironment(options.envs)
      .withParentEnvironmentType(if (options.passParentEnvs) GeneralCommandLine.ParentEnvironmentType.CONSOLE else GeneralCommandLine.ParentEnvironmentType.NONE)
    val sideCommands = if (options.startSideProcesses) config.run.with else emptyList()

    return object : CommandLineState(environment) {
      override fun startProcess(): ProcessHandler {
        val sideProcesses = sideCommands.mapNotNull { command -> startSideProcess(config, command) }
        val handler = try {
          KillableColoredProcessHandler(commandLine)
        }
        catch (e: ExecutionException) {
          sideProcesses.forEach { it.destroyForcibly() }
          throw e
        }
        ProcessTerminatedListener.attach(handler)
        if (sideProcesses.isNotEmpty()) {
          handler.addProcessListener(object : ProcessListener {
            override fun processTerminated(event: ProcessEvent) {
              sideProcesses.forEach { it.descendants().forEach(ProcessHandle::destroyForcibly); it.destroyForcibly() }
            }
          })
        }
        return handler
      }
    }
  }

  /** Starts a side process of `run.with`, as `pipeline32.py` does: `{out}` is the log directory and `{root}` the project. */
  private fun startSideProcess(config: RhipperProjectConfig, command: List<String>): Process? {
    val out = config.pipelineLogPath
    val resolved = command.map { it.replace("{out}", out.toString()).replace("{root}", config.root.toString()) }
    return try {
      Files.createDirectories(out)
      val commandLine = RhipperCommandLines(config).prepare(GeneralCommandLine(resolved))
      commandLine.createProcess()
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      LOG.warn("Cannot start the side process $resolved", e)
      null
    }
  }
}

private class RhipperApplicationRunConfigurationEditor(private val project: Project) : SettingsEditor<RhipperApplicationRunConfiguration>() {
  private val targetCombo = ComboBox(RhipperRunTarget.entries.toTypedArray()).apply {
    renderer = listCellRenderer {
      text(when (value) {
        RhipperRunTarget.PORT -> RhipperBundle.message("app.run.configuration.target.port")
        RhipperRunTarget.REFERENCE -> RhipperBundle.message("app.run.configuration.target.reference")
        null -> ""
      })
    }
  }
  private val buildConfigCombo = ComboBox(RHIPPER_BUILD_CONFIGS.toTypedArray())
  private val argsField = ExpandableTextField()
  private val workingDirectoryField = TextFieldWithBrowseButton().apply {
    addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
  }
  private val envsField = EnvironmentVariablesTextFieldWithBrowseButton(project)
  private val sideProcessesBox = JBCheckBox(RhipperBundle.message("app.run.configuration.side.processes"))

  init {
    targetCombo.addActionListener { buildConfigCombo.isEnabled = targetCombo.selectedItem == RhipperRunTarget.PORT }
  }

  override fun createEditor(): JComponent {
    val config = project.rhipperConfig
    argsField.emptyText.text = config?.run?.args?.let(ParametersListUtil::join).orEmpty()
    workingDirectoryField.textField.let { (it as? com.intellij.ui.components.JBTextField)?.emptyText?.text = config?.run?.cwd.orEmpty() }
    return panel {
      row(RhipperBundle.message("app.run.configuration.target")) { cell(targetCombo) }
      row(RhipperBundle.message("app.run.configuration.build.config")) {
        cell(buildConfigCombo).comment(RhipperBundle.message("app.run.configuration.build.config.comment"))
      }
      row(RhipperBundle.message("app.run.configuration.args")) {
        cell(argsField).align(AlignX.FILL).comment(RhipperBundle.message("app.run.configuration.args.comment"))
      }
      row(RhipperBundle.message("app.run.configuration.working.directory")) { cell(workingDirectoryField).align(AlignX.FILL) }
      row(RhipperBundle.message("app.run.configuration.envs")) { cell(envsField).align(AlignX.FILL) }
      row { cell(sideProcessesBox) }
    }
  }

  override fun resetEditorFrom(configuration: RhipperApplicationRunConfiguration) {
    val options = configuration.options
    targetCombo.selectedItem = options.target
    buildConfigCombo.selectedItem = options.buildConfig ?: "Release"
    buildConfigCombo.isEnabled = options.target == RhipperRunTarget.PORT
    argsField.text = options.programArgs.orEmpty()
    workingDirectoryField.text = options.workingDirectory.orEmpty()
    envsField.data = EnvironmentVariablesData.create(options.envs, options.passParentEnvs)
    sideProcessesBox.isSelected = options.startSideProcesses
  }

  override fun applyEditorTo(configuration: RhipperApplicationRunConfiguration) {
    val options = configuration.options
    options.target = targetCombo.selectedItem as? RhipperRunTarget ?: RhipperRunTarget.PORT
    options.buildConfig = buildConfigCombo.selectedItem as? String ?: "Release"
    options.programArgs = argsField.text.ifBlank { null }
    options.workingDirectory = workingDirectoryField.text.ifBlank { null }
    options.envs = LinkedHashMap(envsField.envs)
    options.passParentEnvs = envsField.isPassParentEnvs
    options.startSideProcesses = sideProcessesBox.isSelected
  }
}
