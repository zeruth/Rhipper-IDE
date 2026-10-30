// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.RhipperProjectService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

private val LOG = logger<RhipperRunConfigurationsSetup>()
private const val DEFAULTS_CREATED_KEY = "rhipper.run.configurations.created"
private const val IMPORTED_KEY = "rhipper.run.configurations.imported"
private const val STEP_CONFIGURATIONS_REMOVED_KEY = "rhipper.step.configurations.removed"
/** The name prefix of the step configurations that an earlier version saved, such as `Rhipper: Build (Release)`. */
private const val GENERATED_NAME_PREFIX = "Rhipper: "

/** A CMake run configuration of CLion, as an XML file in the `.run` directory of the project holds it. */
internal data class CMakeRunConfiguration(
  val name: String,
  val programArgs: String?,
  val workingDirectory: String?,
  val buildConfig: String?,
  val envs: Map<String, String>,
  val passParentEnvs: Boolean,
)

/**
 * Gives a Rhipper project its application run configurations once: the port in Release and in Debug, and the reference.
 * It also imports the CMake run configurations of CLion in `.run`, which this IDE cannot run, as Rhipper application configurations.
 */
internal class RhipperRunConfigurationsSetup : ProjectActivity {
  override suspend fun execute(project: Project) {
    val config = RhipperProjectService.getInstance(project).config ?: return
    val properties = PropertiesComponent.getInstance(project)
    if (!properties.getBoolean(STEP_CONFIGURATIONS_REMOVED_KEY)) {
      withContext(Dispatchers.EDT) { removeStepConfigurations(project) }
      properties.setValue(STEP_CONFIGURATIONS_REMOVED_KEY, true)
    }
    val imports = if (properties.getBoolean(IMPORTED_KEY)) emptyList() else withContext(Dispatchers.IO) { readCMakeRunConfigurations(config.root) }
    if (properties.getBoolean(DEFAULTS_CREATED_KEY) && imports.isEmpty()) return

    val imported = withContext(Dispatchers.EDT) {
      if (!properties.getBoolean(DEFAULTS_CREATED_KEY)) {
        createDefaults(project, config)
        properties.setValue(DEFAULTS_CREATED_KEY, true)
      }
      val names = imports.map { importConfiguration(project, it) }
      properties.setValue(IMPORTED_KEY, true)
      names
    }
    if (imported.isNotEmpty()) {
      NotificationGroupManager.getInstance().getNotificationGroup("Rhipper")
        .createNotification(RhipperBundle.message("app.run.configuration.imported", imported.joinToString()), NotificationType.INFORMATION)
        .notify(project)
    }
  }

  /**
   * Removes the step configurations that an earlier version saved for each step button. A step run now keeps no configuration.
   * A configuration with a changed name or with extra arguments is the user's, so it stays.
   */
  private fun removeStepConfigurations(project: Project) {
    val runManager = RunManager.getInstance(project)
    for (settings in runManager.getConfigurationSettingsList(RhipperRunConfigurationType.getInstance())) {
      val configuration = settings.configuration as? RhipperRunConfiguration ?: continue
      if (configuration.extraArgs.isEmpty() && settings.name.startsWith(GENERATED_NAME_PREFIX)) runManager.removeConfiguration(settings)
    }
  }

  private fun createDefaults(project: Project, config: RhipperProjectConfig) {
    val runManager = RunManager.getInstance(project)
    val type = RhipperApplicationRunConfigurationType.getInstance()
    val existing = runManager.getConfigurationSettingsList(type).map { it.name }.toSet()
    fun add(name: String, target: RhipperRunTarget, buildConfig: String): RunnerAndConfigurationSettings? {
      if (name in existing) return null
      val settings = runManager.createConfiguration(name, type.factory)
      val options = (settings.configuration as RhipperApplicationRunConfiguration).options
      options.target = target
      options.buildConfig = buildConfig
      runManager.addConfiguration(settings)
      return settings
    }
    val release = add(RhipperBundle.message("app.run.configuration.port.named", config.build.config), RhipperRunTarget.PORT, config.build.config)
    val other = if (config.build.config.equals("Debug", ignoreCase = true)) "Release" else "Debug"
    add(RhipperBundle.message("app.run.configuration.port.named", other), RhipperRunTarget.PORT, other)
    add(RhipperBundle.message("app.run.configuration.target.reference"), RhipperRunTarget.REFERENCE, config.build.config)
    if (release != null && runManager.selectedConfiguration == null) runManager.selectedConfiguration = release
  }

  private fun importConfiguration(project: Project, cmake: CMakeRunConfiguration): String {
    val runManager = RunManager.getInstance(project)
    val type = RhipperApplicationRunConfigurationType.getInstance()
    val name = RhipperBundle.message("app.run.configuration.imported.name", cmake.name)
    if (runManager.getConfigurationSettingsList(type).any { it.name == name }) return name
    val settings = runManager.createConfiguration(name, type.factory)
    val options = (settings.configuration as RhipperApplicationRunConfiguration).options
    options.target = RhipperRunTarget.PORT
    options.buildConfig = cmake.buildConfig ?: "Release"
    options.programArgs = cmake.programArgs
    options.workingDirectory = cmake.workingDirectory
    options.envs = LinkedHashMap(cmake.envs)
    options.passParentEnvs = cmake.passParentEnvs
    runManager.addConfiguration(settings)
    return name
  }
}

/** Reads the CMake run configurations of CLion in the `.run` directory of [root]. */
internal fun readCMakeRunConfigurations(root: Path): List<CMakeRunConfiguration> {
  val dir = root.resolve(".run")
  if (!Files.isDirectory(dir)) return emptyList()
  val files = Files.list(dir).use { stream -> stream.filter { it.isRegularFile() && it.extension == "xml" }.toList() }
  return files.flatMap { file ->
    try {
      parseCMakeRunConfigurations(Files.readAllBytes(file), root)
    }
    catch (e: Exception) {
      rethrowControlFlowException(e)
      LOG.info("Cannot read the run configuration $file", e)
      emptyList()
    }
  }
}

internal fun parseCMakeRunConfigurations(xml: ByteArray, root: Path): List<CMakeRunConfiguration> {
  val factory = DocumentBuilderFactory.newInstance().apply {
    isNamespaceAware = false
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
  }
  val document = factory.newDocumentBuilder().parse(xml.inputStream())
  val configurations = document.getElementsByTagName("configuration")
  return (0 until configurations.length).mapNotNull { index ->
    val element = configurations.item(index) as Element
    if (element.getAttribute("type") != "CMakeRunConfiguration") return@mapNotNull null
    val envs = LinkedHashMap<String, String>()
    val envElements = element.getElementsByTagName("env")
    for (i in 0 until envElements.length) {
      val env = envElements.item(i) as Element
      envs[env.getAttribute("name")] = env.getAttribute("value")
    }
    CMakeRunConfiguration(
      name = element.getAttribute("name"),
      programArgs = element.getAttribute("PROGRAM_PARAMS").ifBlank { null },
      workingDirectory = element.getAttribute("WORKING_DIR").ifBlank { null }?.let { relativeWorkingDirectory(it, root) },
      buildConfig = element.getAttribute("CONFIG_NAME").ifBlank { null },
      envs = envs,
      passParentEnvs = element.getAttribute("PASS_PARENT_ENVS_2") != "false",
    )
  }
}

/** Turns CLion's `file://$PROJECT_DIR$/game` into `game`, relative to the project, as the `rhipper.json` writes paths. */
private fun relativeWorkingDirectory(value: String, root: Path): String {
  val path = value.removePrefix("file://").replace("\$PROJECT_DIR\$", root.toString().replace('\\', '/'))
  val absolute = Path.of(path).normalize()
  return if (absolute.startsWith(root)) root.relativize(absolute).toString().replace('\\', '/').ifEmpty { "." } else path
}
