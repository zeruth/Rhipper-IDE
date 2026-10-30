// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.RhipperIcons
import com.rhipper.ide.project.formatAddress
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.project.rhipperConfig
import java.nio.file.Path
import javax.swing.Icon

private val MARKED: Key<Boolean> = Key.create("rhipper.gen.gutter.marked")
private val DEFINITION = Regex("""^GEN_.*\bF_([0-9a-fA-F]{6,16})\(""")

/**
 * Marks each translated function in a generated source with a gutter icon.
 *
 * The icon names the reference function and its fidelity score. A click opens Ghidra's decompilation of it.
 * The marks show the lines at the time the editor opened. Open the file again after a new translation.
 */
internal class RhipperGenGutterListener(private val project: Project) : FileEditorManagerListener {
  override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
    val gen = project.rhipperConfig?.genPath ?: return
    if (file.extension != "c" || !file.isInLocalFileSystem) return
    if (!Path.of(file.path).startsWith(gen)) return
    for (editor in source.getEditors(file).filterIsInstance<TextEditor>().map { it.editor }) {
      mark(editor)
    }
  }

  private fun mark(editor: Editor) {
    if (editor.getUserData(MARKED) == true) return
    editor.putUserData(MARKED, true)
    val document = editor.document
    val fidelity = RhipperFidelity.getInstance(project).byAddressIfReady
    val corpus = RhipperCorpus.getInstance(project).indexIfReady
    for (line in 0 until document.lineCount) {
      val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
      if (text.length < 5 || text[0] != 'G' || text.endsWith(";")) continue
      val address = DEFINITION.find(text)?.groupValues?.get(1)?.let(::parseAddress) ?: continue
      val name = corpus?.containing(address)?.name
      val highlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
      highlighter.gutterIconRenderer = RhipperFunctionGutterRenderer(project, address, name, fidelity?.get(address))
    }
  }
}

private class RhipperFunctionGutterRenderer(
  private val project: Project,
  private val address: Long,
  private val name: String?,
  private val fidelity: FidelityEntry?,
) : GutterIconRenderer() {
  override fun getIcon(): Icon = RhipperIcons.Rhipper

  override fun getTooltipText(): String {
    val score = fidelity?.score?.let { RhipperBundle.message("gutter.fidelity", "%.2f".format(it), fidelity.kind) }.orEmpty()
    return RhipperBundle.message("gutter.reference", name ?: "?", formatAddress(address)) + score
  }

  override fun getClickAction(): AnAction = openAction(RhipperView.DECOMPILATION)

  override fun getPopupMenuActions(): ActionGroup = DefaultActionGroup(
    openAction(RhipperView.DECOMPILATION),
    openAction(RhipperView.DISASSEMBLY),
  )

  private fun openAction(view: RhipperView): AnAction {
    val text = when (view) {
      RhipperView.DECOMPILATION -> RhipperBundle.message("action.Rhipper.OpenDecompilation.text")
      RhipperView.TRANSLATION -> RhipperBundle.message("action.Rhipper.OpenTranslation.text")
      RhipperView.DISASSEMBLY -> RhipperBundle.message("action.Rhipper.OpenDisassembly.text")
    }
    return DumbAwareAction.create(text) { _: AnActionEvent ->
      RhipperNavigator.getInstance(project).open(formatAddress(address), view)
    }
  }

  override fun isNavigateAction(): Boolean = true

  override fun equals(other: Any?): Boolean = other is RhipperFunctionGutterRenderer && other.address == address && other.fidelity == fidelity

  override fun hashCode(): Int = address.hashCode()
}
