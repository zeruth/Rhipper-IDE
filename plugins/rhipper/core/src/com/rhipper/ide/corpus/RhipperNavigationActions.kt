// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.RhipperIcons
import com.rhipper.ide.project.rhipperConfig

private val SYMBOL = Regex("""(?:thunk_FUN_|FUN_|F_|LAB_|DAT_)[0-9a-fA-F]{6,16}|0x[0-9a-fA-F]{4,16}""")

/** The reference symbol at the caret of [editor] or in its selection: `F_<address>`, `FUN_<address>` or `0x<address>`. */
internal fun symbolAtCaret(editor: Editor): String? {
  editor.selectionModel.selectedText?.trim()?.takeIf { it.isNotEmpty() && '\n' !in it }?.let { return it }
  val document = editor.document
  val offset = editor.caretModel.offset
  val line = document.getLineNumber(offset)
  val lineStart = document.getLineStartOffset(line)
  val text = document.charsSequence.subSequence(lineStart, document.getLineEndOffset(line)).toString()
  val column = offset - lineStart
  return SYMBOL.findAll(text).firstOrNull { column >= it.range.first && column <= it.range.last + 1 }?.value
}

/** Opens a view of the reference function at the caret. */
internal abstract class RhipperOpenViewAction(private val view: RhipperView) : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    val editor = e.getData(CommonDataKeys.EDITOR)
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null && editor != null && symbolAtCaret(editor) != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val symbol = e.getData(CommonDataKeys.EDITOR)?.let(::symbolAtCaret) ?: return
    RhipperNavigator.getInstance(project).open(symbol, view)
  }
}

internal class RhipperOpenDecompilationAction : RhipperOpenViewAction(RhipperView.DECOMPILATION)

internal class RhipperOpenTranslationAction : RhipperOpenViewAction(RhipperView.TRANSLATION)

internal class RhipperOpenDisassemblyAction : RhipperOpenViewAction(RhipperView.DISASSEMBLY)

/** Asks for an address or a function name and opens Ghidra's decompilation of it. */
internal class RhipperGoToAddressAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun update(e: AnActionEvent) {
    e.presentation.isEnabledAndVisible = e.project?.rhipperConfig != null
  }

  override fun actionPerformed(e: AnActionEvent) {
    val project = e.project ?: return
    val initial = e.getData(CommonDataKeys.EDITOR)?.let(::symbolAtCaret).orEmpty()
    val query = Messages.showInputDialog(
      project,
      RhipperBundle.message("goto.address.message"),
      RhipperBundle.message("goto.address.title"),
      RhipperIcons.Rhipper,
      initial,
      null,
    )?.takeIf { it.isNotBlank() } ?: return
    RhipperNavigator.getInstance(project).open(query, RhipperView.DECOMPILATION)
  }
}
