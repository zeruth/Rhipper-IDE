// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.tools

import com.intellij.diagnostic.rethrowControlFlowException
import com.intellij.icons.AllIcons
import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.rhipper.ide.RhipperBundle
import kotlin.io.path.isRegularFile
import com.rhipper.ide.project.RhipperProjectConfig
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ApplicationManager
import com.rhipper.ide.lsp.RHIPPER_NOTIFICATION_GROUP
import com.rhipper.ide.project.RhipperProjectService
import com.rhipper.ide.project.hasRhipperJson
import com.rhipper.ide.project.rhipperConfig
import com.rhipper.ide.settings.RhipperSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent

private val LOG = logger<RhipperToolsInstaller>()

/** Installs tools in the background and reports the result in a notification. */
@Service(Service.Level.APP)
class RhipperToolsInstaller(private val coroutineScope: CoroutineScope) {
  fun install(project: Project?, tools: List<RhipperTool>, onDone: () -> Unit = {}) {
    if (tools.isEmpty()) return
    // Rhipper first: the Python environment installs Rhipper's requirements.txt
    val ordered = tools.sortedBy { it.ordinal }
    coroutineScope.launch {
      // any modality: an install from a modal dialog, such as the new project dialog, shows its progress at once
      val dialog = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { RhipperInstallDialog(project, ordered).also { it.show() } }
      val failures = LinkedHashMap<RhipperTool, String>()
      val run: suspend () -> Unit = {
        reportSequentialProgress(ordered.size) { reporter ->
          for (tool in ordered) {
            reporter.itemStep(RhipperBundle.message("tool.installing", tool.displayName)) {
              try {
                RhipperTools.getInstance().install(tool) { stage, done, total ->
                  ApplicationManager.getApplication().invokeLater({ dialog.update(tool, stage, done, total) }, ModalityState.any())
                }
              }
              catch (e: Throwable) {
                rethrowControlFlowException(e)
                LOG.warn("Cannot install ${tool.name}", e)
                failures[tool] = e.message ?: e.toString()
              }
              val error = failures[tool]
              withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { dialog.finish(tool, error) }
            }
          }
        }
      }
      if (project != null) withBackgroundProgress(project, RhipperBundle.message("tool.installing.title")) { run() } else run()
      withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) { dialog.allDone() }
      val installed = tools.filter { it !in failures }
      val content = buildString {
        if (installed.isNotEmpty()) append(RhipperBundle.message("tool.installed", installed.joinToString { it.displayName }))
        for ((tool, error) in failures) {
          if (isNotEmpty()) append("<br/>")
          append(RhipperBundle.message("tool.install.failed", tool.displayName, StringUtil.escapeXmlEntities(error.take(400))))
        }
      }
      notify(project, content, if (failures.isEmpty()) NotificationType.INFORMATION else NotificationType.ERROR)
      withContext(Dispatchers.EDT) { onDone() }
    }
  }

  companion object {
    fun getInstance(): RhipperToolsInstaller = service()
  }
}

private fun notify(project: Project?, content: String, type: NotificationType, configure: (com.intellij.notification.Notification) -> Unit = {}) {
  val notification = NotificationGroupManager.getInstance()
    .getNotificationGroup(RHIPPER_NOTIFICATION_GROUP)
    .createNotification(RhipperBundle.message("tool.notification.title"), content, type)
  configure(notification)
  notification.notify(project)
}

/** The state of each tool, with checkboxes for the tools to install or update, and links to the tools that need a manual install. */
class RhipperToolsDialog(project: Project?, private val statuses: List<ToolStatus>, private val update: RhipperUpdate?) : DialogWrapper(project, true) {
  private val checkBoxes = LinkedHashMap<RhipperTool, JBCheckBox>()

  init {
    title = RhipperBundle.message("tools.dialog.title")
    setOKButtonText(RhipperBundle.message("tools.dialog.install"))
    init()
  }

  override fun createCenterPanel(): JComponent = panel {
    row {
      comment(RhipperBundle.message("tools.dialog.comment", RhipperTools.getInstance().toolsDir.toString()))
    }
    for (status in statuses) {
      row {
        icon(if (status.ok) AllIcons.General.InspectionsOK else if (status.tool.installable) AllIcons.General.Warning else AllIcons.General.Error)
        label(status.tool.displayName).bold()
        val description = describe(status)
        label(description).applyToComponent { toolTipText = status.path?.toString() }
        if (status.tool.installable) {
          val outdated = status.tool == RhipperTool.RHIPPER && update != null
          val text = when {
            !status.ok -> RhipperBundle.message("tools.dialog.action.install")
            outdated -> RhipperBundle.message("tools.dialog.action.update", update.latest)
            else -> RhipperBundle.message("tools.dialog.action.reinstall")
          }
          // a configured or system tool stays unless the person asks: a managed copy would not replace it
          val box = checkBox(text).component
          box.isSelected = !status.ok || outdated
          box.isEnabled = status.source != ToolSource.CONFIGURED || status.tool == RhipperTool.PYTHON
          checkBoxes[status.tool] = box
        }
        else if (!status.ok && status.tool.downloadPage != null) {
          browserLink(RhipperBundle.message("tools.dialog.download"), status.tool.downloadPage)
        }
      }
      status.problem?.let { problem -> row { comment(problem) } }
    }
  }

  fun selectedTools(): List<RhipperTool> = checkBoxes.filter { it.value.isSelected && it.value.isEnabled }.keys.toList()

  private fun describe(status: ToolStatus): String {
    val version = status.version?.let { " $it" }.orEmpty()
    return when (status.source) {
      ToolSource.MANAGED -> RhipperBundle.message("tools.source.managed", version)
      ToolSource.CONFIGURED -> RhipperBundle.message("tools.source.configured", version)
      ToolSource.SYSTEM -> RhipperBundle.message("tools.source.system", version)
      ToolSource.MISSING -> RhipperBundle.message("tools.source.missing")
    }
  }
}

/** Tools | Rhipper | Tools: the state of the tools, and the installs. */
class RhipperToolsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    showToolsDialog(e.project)
  }
}

/** Reads the state of the tools in the background, then shows [RhipperToolsDialog]. */
fun showToolsDialog(requested: Project?) {
  // Settings has no project: the open Rhipper project then gives its rhipper.json, whose keys can name Rhipper and Ghidra
  val project = requested?.takeIf { it.rhipperConfig != null }
                ?: ProjectManager.getInstance().openProjects.firstOrNull { !it.isDisposed && it.rhipperConfig != null }
                ?: requested
  RhipperToolsScope.getInstance().coroutineScope.launch {
    val config = project?.rhipperConfig
    val load: suspend () -> Pair<List<ToolStatus>, RhipperUpdate?> = {
      val statuses = RhipperTools.getInstance().status(config)
      val update = try {
        RhipperTools.getInstance().rhipperUpdate(config)
      }
      catch (e: IOException) {
        LOG.info("Cannot check for a Rhipper update", e)
        null
      }
      statuses to update
    }
    val (statuses, update) = if (project != null) withBackgroundProgress(project, RhipperBundle.message("tools.checking")) { load() } else load()
    withContext(Dispatchers.EDT) {
      val dialog = RhipperToolsDialog(project, statuses, update)
      if (dialog.showAndGet()) RhipperToolsInstaller.getInstance().install(project, dialog.selectedTools())
    }
  }
}

@Service(Service.Level.APP)
internal class RhipperToolsScope(val coroutineScope: CoroutineScope) {
  /** Whether this IDE session already checked the tools and the Rhipper release. */
  val checked = AtomicBoolean(false)

  companion object {
    fun getInstance(): RhipperToolsScope = service()
  }
}

/**
 * Installs the missing tools at the first launch of the IDE, before the user opens or creates a project, so a first-time user
 * finds Rhipper, Python, the JDK, Ghidra and clangd ready. A tool that the IDE cannot install, such as CMake, gets a download link.
 */
internal class RhipperFirstLaunchSetup : AppLifecycleListener {
  override fun appStarted() {
    // a headless run, such as the search index step of the installer build, installs nothing
    if (ApplicationManager.getApplication().isHeadlessEnvironment) return
    RhipperToolsScope.getInstance().coroutineScope.launch { setUp() }
  }

  private suspend fun setUp() {
    val settings = RhipperSettings.getInstance()
    if (settings.state.firstLaunchSetupDone || settings.state.setupDismissed) return
    settings.state.firstLaunchSetupDone = true
    val missing = RhipperTools.getInstance().status(null).filter { !it.ok }
    if (missing.isEmpty()) return
    // the project activity must not offer the same tools again while they install
    RhipperToolsScope.getInstance().checked.set(true)

    // the JDK is a large download for Ghidra only, so the user decides on it in a warning
    val installable = missing.filter { it.tool.installable && it.tool != RhipperTool.JDK }.map { it.tool }
    val manual = missing.filter { !it.tool.installable }
    if (installable.isNotEmpty()) {
      notify(null, RhipperBundle.message("tools.first.launch", installable.joinToString { it.displayName }), NotificationType.INFORMATION)
      RhipperToolsInstaller.getInstance().install(null, installable)
    }
    missing.firstOrNull { it.tool == RhipperTool.JDK }?.let { jdk ->
      notify(null, jdk.problem ?: RhipperBundle.message("tools.jdk.missing"), NotificationType.WARNING) { notification ->
        notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.install.own")) {
          RhipperToolsInstaller.getInstance().install(null, listOf(RhipperTool.JDK))
        })
        notification.addAction(NotificationAction.createSimple(RhipperBundle.message("tools.download.tool", jdk.tool.displayName)) {
          BrowserUtil.browse("https://adoptium.net/temurin/releases/?version=21")
        })
      }
    }
    if (manual.isNotEmpty()) {
      notify(null, RhipperBundle.message("tools.missing", manual.joinToString { it.tool.displayName }), NotificationType.WARNING) { notification ->
        for (status in manual) {
          val page = status.tool.downloadPage ?: continue
          notification.addAction(NotificationAction.createSimple(RhipperBundle.message("tools.download.tool", status.tool.displayName)) {
            BrowserUtil.browse(page)
          })
        }
      }
    }
  }
}

/**
 * Tells the user when the `rhipper.json` of [config] sets up Rhipper or Ghidra that the IDE has no setting for. A new project
 * then finds neither. The notification offers to use the project's tools for every project, or to install the IDE's own.
 */
private fun offerProjectToolsForAll(project: Project, config: RhipperProjectConfig) {
  val settings = RhipperSettings.getInstance()
  val projectHome = config.rhipper?.let { config.resolve(it) }?.takeIf { it.resolve("project.py").isRegularFile() }
  val projectGhidra = config.ghidra.install?.let { RhipperTools.resolveGlob(config.root, it) }
  val home = projectHome?.takeIf { settings.rhipperHome(null) == null }
  val ghidra = projectGhidra?.takeIf { settings.ghidraInstall() == null }
  val tools = listOfNotNull(home?.let { RhipperTool.RHIPPER }, ghidra?.let { RhipperTool.GHIDRA })
  if (tools.isEmpty()) return

  val content = RhipperBundle.message("tools.project.only", config.name, tools.joinToString { it.displayName })
  notify(project, content, NotificationType.INFORMATION) { notification ->
    notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.use.for.all")) {
      if (home != null) settings.state.rhipperHome = home.toString()
      if (ghidra != null) {
        settings.state.ghidraInstall = ghidra.toString()
        settings.state.ghidraProjects = config.ghidra.projects?.let { config.resolve(it).toString() }
      }
    })
    notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.install.own")) {
      RhipperToolsInstaller.getInstance().install(project, tools)
    })
    notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.dont.ask")) {
      settings.state.setupDismissed = true
    })
  }
}

/**
 * Checks the tools once for each IDE session, when the first Rhipper project opens.
 *
 * A first-time user gets an offer to install every missing tool at once. A user of the managed Rhipper gets the newer release.
 */
internal class RhipperToolsStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!project.hasRhipperJson()) return
    if (!RhipperToolsScope.getInstance().checked.compareAndSet(false, true)) return
    val settings = RhipperSettings.getInstance()
    val config = RhipperProjectService.getInstance(project).config
    val tools = RhipperTools.getInstance()

    val missing = tools.status(config).filter { !it.ok }
    if (missing.isNotEmpty() && !settings.state.setupDismissed) {
      val installable = missing.filter { it.tool.installable }.map { it.tool }
      val manual = missing.filter { !it.tool.installable }
      val content = RhipperBundle.message("tools.missing", missing.joinToString { it.tool.displayName })
      notify(project, content, NotificationType.WARNING) { notification ->
        if (installable.isNotEmpty()) {
          notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.install.all")) {
            RhipperToolsInstaller.getInstance().install(project, installable)
          })
        }
        for (status in manual) {
          val page = status.tool.downloadPage ?: continue
          notification.addAction(NotificationAction.createSimple(RhipperBundle.message("tools.download.tool", status.tool.displayName)) {
            BrowserUtil.browse(page)
          })
        }
        notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.set.up")) { showToolsDialog(project) })
        notification.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.dont.ask")) {
          settings.state.setupDismissed = true
        })
      }
    }

    if (config != null && !settings.state.setupDismissed) offerProjectToolsForAll(project, config)

    if (settings.state.checkForUpdates) {
      val update = try {
        tools.rhipperUpdate(config)
      }
      catch (e: IOException) {
        LOG.info("Cannot check for a Rhipper update", e)
        null
      }
      if (update != null && update.installed != null) {
        notify(project, RhipperBundle.message("tools.update.available", update.latest, update.installed), NotificationType.INFORMATION) {
          it.addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("tools.update")) {
            RhipperToolsInstaller.getInstance().install(project, listOf(RhipperTool.RHIPPER))
          })
          it.addAction(NotificationAction.createSimple(RhipperBundle.message("tools.release.notes")) {
            BrowserUtil.browse("https://github.com/zeruth/Rhipper/releases/tag/v${update.latest}")
          })
        }
      }
    }
  }
}
