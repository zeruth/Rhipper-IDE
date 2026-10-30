// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

/** One line of the reports popup: a kept report, or measuring the port now. */
private sealed interface ReportItem {
  data object Measure : ReportItem
  data class Kept(val report: FidelityReport, val previous: FidelityReport?) : ReportItem
}

/** Lists the fidelity reports the IDE keeps for the project, newest first, and opens the one chosen; or measures the port again. */
internal class RhipperFidelityReportsAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val reports = RhipperFidelityReports.getInstance(project)
    reports.scope.launch {
      val all = reports.list()
      val items = listOf<ReportItem>(ReportItem.Measure) +
                  all.indices.reversed().map { ReportItem.Kept(all[it], all.getOrNull(it - 1)) }
      withContext(Dispatchers.EDT) {
        JBPopupFactory.getInstance().createPopupChooserBuilder(items)
          .setTitle(RhipperBundle.message("fidelity.reports.title"))
          .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { item -> label(item) })
          .setItemChosenCallback { item ->
            when (item) {
              is ReportItem.Measure -> measure(project, reports)
              is ReportItem.Kept -> reports.scope.launch { reports.open(item.report) }
            }
          }
          .createPopup()
          .showCenteredInCurrentWindow(project)
      }
    }
  }

  private fun label(item: ReportItem): String = when (item) {
    is ReportItem.Measure -> RhipperBundle.message("fidelity.reports.measure")
    is ReportItem.Kept -> {
      val r = item.report
      val s = r.summary
      val delta = item.previous?.summary?.average?.let { before -> s.average?.let { "  (%+.4f)".format(it - before) } }.orEmpty()
      val commit = r.commit + if (r.genDirty) "*" else ""
      val note = if (r.label.isNotEmpty()) "  ${r.label}" else ""
      "${SHOWN.format(r.created)}  $commit  mean ${s.average?.let { "%.4f".format(it) } ?: "-"}$delta  " +
      "below ${s.below}  stubs ${s.stubs}${if (r.hasBytes) "  bytes" else ""}$note"
    }
  }

  private fun measure(project: Project, reports: RhipperFidelityReports) {
    reports.scope.launch {
      val result = withBackgroundProgress(project, RhipperBundle.message("fidelity.reports.measuring")) {
        reports.make(bytes = true, label = "")
      }
      val report = result.report
      val group = NotificationGroupManager.getInstance().getNotificationGroup("Rhipper")
      if (report == null) {
        group.createNotification(RhipperBundle.message("fidelity.reports.failed", result.failedStep.orEmpty()), NotificationType.ERROR).notify(project)
        return@launch
      }
      reports.open(report)
    }
  }
}
