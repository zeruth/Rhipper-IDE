// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.mcp

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.Messages
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.hasRhipperJson
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

private val LOG = logger<RhipperSkillActivity>()

/** The notification group of the Rhipper plugin, which the core module registers. */
private const val NOTIFICATION_GROUP = "Rhipper"
private const val OFFERED_KEY = "rhipper.claude.skill.offered"

/**
 * Keeps the Rhipper skill of a project current: it updates a copy that the IDE wrote and nobody changed,
 * and it offers the skill once to a project that has none.
 */
internal class RhipperSkillActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!project.hasRhipperJson()) return
    val state = withContext(Dispatchers.IO) { RhipperSkillInstaller.state(project) }
    when (state) {
      SkillState.CURRENT, SkillState.MODIFIED -> return
      SkillState.OUTDATED -> {
        try {
          withContext(Dispatchers.IO) { RhipperSkillInstaller.install(project) }
          LOG.info("Updated the Rhipper skill of ${project.basePath}")
        }
        catch (e: IOException) {
          LOG.warn("Cannot update the Rhipper skill", e)
        }
      }
      SkillState.MISSING -> {
        val properties = PropertiesComponent.getInstance(project)
        if (properties.getBoolean(OFFERED_KEY)) return
        properties.setValue(OFFERED_KEY, true)
        NotificationGroupManager.getInstance()
          .getNotificationGroup(NOTIFICATION_GROUP)
          .createNotification(RhipperBundle.message("skill.offer.title"), RhipperBundle.message("skill.offer.content"), NotificationType.INFORMATION)
          .addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("skill.offer.install")) { installWithFeedback(project, false) })
          .notify(project)
      }
    }
  }
}

/** Tools | Rhipper | Install Claude Skill: writes the Rhipper skill into `.claude/skills/rhipper` of the project. */
internal class RhipperInstallSkillAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val modified = RhipperSkillInstaller.state(project) == SkillState.MODIFIED
    if (modified) {
      val answer = Messages.showYesNoDialog(
        project,
        RhipperBundle.message("skill.overwrite.message", RhipperSkillInstaller.skillDir(project).toString()),
        RhipperBundle.message("skill.overwrite.title"),
        Messages.getWarningIcon(),
      )
      if (answer != Messages.YES) return
    }
    installWithFeedback(project, overwrite = modified)
  }
}

private fun installWithFeedback(project: Project, overwrite: Boolean) {
  val (type, content) = try {
    if (RhipperSkillInstaller.install(project, overwriteModified = overwrite)) {
      NotificationType.INFORMATION to RhipperBundle.message("skill.installed", RhipperSkillInstaller.skillDir(project).toString())
    }
    else {
      NotificationType.WARNING to RhipperBundle.message("skill.modified.kept")
    }
  }
  catch (e: IOException) {
    LOG.warn("Cannot install the Rhipper skill", e)
    NotificationType.ERROR to RhipperBundle.message("skill.install.failed", e.message ?: e.toString())
  }
  NotificationGroupManager.getInstance()
    .getNotificationGroup(NOTIFICATION_GROUP)
    .createNotification(RhipperBundle.message("skill.offer.title"), content, type)
    .notify(project)
}
