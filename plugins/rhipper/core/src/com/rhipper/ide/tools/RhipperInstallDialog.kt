// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.tools

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.BottomGap
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.rhipper.ide.RhipperBundle
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JProgressBar

/**
 * Shows each tool of an install with its own progress: waiting, the download with its size, the unpack, and the result.
 * The window does not block the IDE. Its close button hides it, and the install goes on.
 */
internal class RhipperInstallDialog(project: Project?, private val tools: List<RhipperTool>) :
  DialogWrapper(project, null, true, IdeModalityType.MODELESS) {
  private class ToolRow(val bar: JProgressBar, val status: JBLabel, val icon: JBLabel)

  private val rows = tools.associateWith {
    ToolRow(JProgressBar(0, 1000).apply { isStringPainted = false },
            JBLabel(RhipperBundle.message("tool.stage.waiting")).apply { foreground = UIUtil.getContextHelpForeground() },
            JBLabel(AllIcons.Process.Step_passive))
  }

  init {
    title = RhipperBundle.message("tool.installing.title")
    setOKButtonText(RhipperBundle.message("tool.install.hide"))
    init()
  }

  override fun createActions(): Array<Action> = arrayOf(okAction)

  override fun createCenterPanel(): JComponent = panel {
    for (tool in tools) {
      val toolRow = rows.getValue(tool)
      row {
        cell(toolRow.icon)
        label(tool.displayName).bold()
        cell(toolRow.bar).align(AlignX.FILL).resizableColumn()
      }
      row("") { cell(toolRow.status) }.bottomGap(BottomGap.SMALL)
    }
  }.apply { preferredSize = JBUI.size(520, preferredSize.height) }

  /** Call it on the EDT. */
  fun update(tool: RhipperTool, stage: String, done: Long, total: Long) {
    val row = rows[tool] ?: return
    row.icon.icon = AnimatedIcon.Default.INSTANCE
    if (total > 0) {
      row.bar.isIndeterminate = false
      row.bar.value = (done * 1000 / total).toInt().coerceIn(0, 1000)
      row.status.text = "$stage · ${StringUtil.formatFileSize(done)} / ${StringUtil.formatFileSize(total)}"
    }
    else {
      row.bar.isIndeterminate = true
      row.status.text = if (done > 0) "$stage · ${StringUtil.formatFileSize(done)}" else stage
    }
  }

  /** Call it on the EDT. [error] is `null` for a tool that installed. */
  fun finish(tool: RhipperTool, error: String?) {
    val row = rows[tool] ?: return
    row.bar.isIndeterminate = false
    row.bar.value = if (error == null) 1000 else 0
    row.icon.icon = if (error == null) AllIcons.General.InspectionsOK else AllIcons.General.Error
    row.status.text = error?.let { RhipperBundle.message("tool.stage.failed", it.lineSequence().first().take(120)) }
                      ?: RhipperBundle.message("tool.stage.done")
  }

  /** Call it on the EDT when every tool ended. */
  fun allDone() {
    setOKButtonText(RhipperBundle.message("tool.install.close"))
  }
}
