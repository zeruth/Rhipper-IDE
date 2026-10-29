// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.corpus.RhipperCorpus
import com.rhipper.ide.corpus.RhipperFidelity
import com.rhipper.ide.corpus.RhipperGenIndex
import com.rhipper.ide.lsp.RHIPPER_NOTIFICATION_GROUP
import com.rhipper.ide.pipeline.RhipperPipelineStatus
import com.rhipper.ide.settings.RhipperConfigurable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Loads a Rhipper project: it reports a bad `rhipper.json`, and it builds the indexes in the background. */
internal class RhipperStartupActivity : ProjectActivity {
  override suspend fun execute(project: Project) {
    if (!project.hasRhipperJson()) return
    when (val state = RhipperProjectService.getInstance(project).state.value) {
      RhipperProjectState.None -> return
      is RhipperProjectState.Invalid -> {
        notify(project, RhipperBundle.message("notification.invalid.config", state.message), NotificationType.ERROR)
        return
      }
      is RhipperProjectState.Loaded -> {
        val config = state.config
        // RhipperToolsStartupActivity reports a missing Rhipper, with the offer to install it
        RhipperPipelineStatus.getInstance(project).refreshFromLogs(config)
        coroutineScope {
          launch { RhipperCorpus.getInstance(project).index() }
          launch { RhipperFidelity.getInstance(project).entries() }
          launch { RhipperGenIndex.getInstance(project).index() }
        }
      }
    }
  }

  private fun notify(project: Project, content: String, type: NotificationType) {
    NotificationGroupManager.getInstance()
      .getNotificationGroup(RHIPPER_NOTIFICATION_GROUP)
      .createNotification(RhipperBundle.message("notification.title"), content, type)
      .addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("action.open.settings")) {
        ShowSettingsUtil.getInstance().showSettingsDialog(project, RhipperConfigurable::class.java)
      })
      .notify(project)
  }
}
