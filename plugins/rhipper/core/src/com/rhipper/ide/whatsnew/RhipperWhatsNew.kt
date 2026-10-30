// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.whatsnew

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.HTMLEditorProvider
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.text.StringUtil
import com.rhipper.ide.RhipperBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<RhipperWhatsNew>()

/** The build whose page the IDE showed last. A new build shows the page once. */
private const val SHOWN_BUILD_KEY = "rhipper.whats.new.shown.build"

/** The What's New page of Rhipper Workbench: what the project is, what it can do, the MCP tools and the roadmap. */
internal object RhipperWhatsNew {
  private const val PAGE = "whatsNew/whats-new.html"

  /** The splash image of the product module, `intellij.rhipper.ide`, on the class path of the core. */
  private const val SPLASH = "rhipper_splash.svg"

  /** Opens the page in an editor tab. Call it on the EDT. */
  fun open(project: Project) {
    val html = RhipperWhatsNew::class.java.classLoader.getResourceAsStream(PAGE)?.use { it.readAllBytes().toString(Charsets.UTF_8) }
    if (html == null) {
      LOG.warn("The IDE has no resource $PAGE")
      return
    }
    val build = StringUtil.escapeXmlEntities(ApplicationInfo.getInstance().build.asString())
    val page = html.replace("{{BUILD}}", build).replace("{{HERO_IMAGE}}", heroImage())
    HTMLEditorProvider.openEditor(project, RhipperBundle.message("whats.new.title"), page)
  }

  /** The splash image of the product as a data URI, so the page needs no file and no network. Empty when the product has none. */
  private fun heroImage(): String {
    val svg = RhipperWhatsNew::class.java.getResourceAsStream("/$SPLASH")?.use { it.readAllBytes() } ?: return ""
    return "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(svg)
  }

  /** Whether this build has not shown the page yet. It records the build, so the page shows once for each build. */
  fun takeFirstShowOfBuild(): Boolean {
    val build = ApplicationInfo.getInstance().build.asString()
    val properties = PropertiesComponent.getInstance()
    if (properties.getValue(SHOWN_BUILD_KEY) == build) return false
    properties.setValue(SHOWN_BUILD_KEY, build)
    return true
  }
}

/** Shows the What's New page on the first project open of a new build. */
internal class RhipperWhatsNewActivity : ProjectActivity {
  private val started = AtomicBoolean(false)

  override suspend fun execute(project: Project) {
    val app = ApplicationManager.getApplication()
    if (app.isHeadlessEnvironment || app.isUnitTestMode || app.isCommandLine) return
    if (started.getAndSet(true) || !RhipperWhatsNew.takeFirstShowOfBuild()) return
    withContext(Dispatchers.EDT) { RhipperWhatsNew.open(project) }
  }
}

/**
 * Closes the What's New tab before the project saves its open editors. The page lives in memory, so the tab could not come back
 * when the project opens again.
 */
internal class RhipperWhatsNewCloser : ProjectCloseListener {
  override fun projectClosingBeforeSave(project: Project) {
    val title = RhipperBundle.message("whats.new.title")
    val manager = FileEditorManager.getInstance(project)
    manager.openFiles.filter { it.name == title && !it.isInLocalFileSystem }.forEach(manager::closeFile)
  }
}

/** Help | What's New in Rhipper. */
internal class RhipperWhatsNewAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    RhipperWhatsNew.open(e.project ?: return)
  }
}
