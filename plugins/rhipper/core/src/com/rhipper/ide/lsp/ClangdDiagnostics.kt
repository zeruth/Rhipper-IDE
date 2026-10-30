// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.lsp

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import kotlinx.coroutines.CoroutineScope
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The severity of a clangd diagnostic, most severe first. */
enum class ClangdSeverity { ERROR, WARNING, INFORMATION, HINT }

/** A diagnostic that clangd published for a file. [line] and [column] start at 1. */
data class ClangdDiagnostic(
  val path: String,
  val line: Int,
  val column: Int,
  val severity: ClangdSeverity,
  /** The diagnostic name, such as `-Wint-conversion` or `typecheck_convert_int_pointer`, when clangd sends one. */
  val code: String?,
  val source: String?,
  val message: String,
)

/** The diagnostics of one file from the last highlighting pass that had any. */
private class FileDiagnostics(val session: Any, val updated: Instant, val items: MutableList<ClangdDiagnostic>)

/** All diagnostics that clangd published, by file, and when each file was last analyzed. */
data class ClangdDiagnosticsSnapshot(val files: Map<String, Pair<Instant, List<ClangdDiagnostic>>>)

/**
 * The diagnostics that clangd published for the files it analyzed in this session.
 *
 * clangd analyzes only the files that are open in an editor. A highlighting pass hands every diagnostic of a file to
 * [ClangdDiagnosticsSupport], with a new annotation session each time, so the store keeps the set of the latest pass.
 * A pass without diagnostics leaves no trace, so the store forgets a file when its content changes on disk.
 */
@Service(Service.Level.PROJECT)
class ClangdDiagnostics(project: Project, coroutineScope: CoroutineScope) {
  private val files = ConcurrentHashMap<String, FileDiagnostics>()

  init {
    project.messageBus.connect(coroutineScope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        for (event in events) {
          if (event is VFileContentChangeEvent || event is VFileDeleteEvent) files.remove(event.path)
        }
      }
    })
  }

  internal fun record(path: String, session: Any, diagnostic: Diagnostic) {
    val item = ClangdDiagnostic(
      path = path,
      line = diagnostic.range.start.line + 1,
      column = diagnostic.range.start.character + 1,
      severity = when (diagnostic.severity) {
        DiagnosticSeverity.Error -> ClangdSeverity.ERROR
        DiagnosticSeverity.Warning -> ClangdSeverity.WARNING
        DiagnosticSeverity.Information -> ClangdSeverity.INFORMATION
        DiagnosticSeverity.Hint -> ClangdSeverity.HINT
        null -> ClangdSeverity.ERROR
      },
      code = diagnostic.code?.get()?.toString(),
      source = diagnostic.source,
      message = if (diagnostic.message.isLeft) diagnostic.message.left else diagnostic.message.right?.value.orEmpty(),
    )
    files.compute(path) { _, old ->
      val current = if (old != null && old.session === session) old else FileDiagnostics(session, Instant.now(), ArrayList())
      synchronized(current) {
        if (item !in current.items) current.items.add(item)
      }
      current
    }
  }

  fun snapshot(): ClangdDiagnosticsSnapshot {
    return ClangdDiagnosticsSnapshot(files.mapValues { (_, file) -> file.updated to synchronized(file) { file.items.toList() } })
  }

  /** Whether clangd published diagnostics for [path] in this session. */
  fun hasDiagnostics(path: String): Boolean = files.containsKey(path)

  companion object {
    fun getInstance(project: Project): ClangdDiagnostics = project.service()
  }
}

/** Shows clangd's diagnostics as usual and records them in [ClangdDiagnostics]. */
internal class ClangdDiagnosticsSupport(private val project: Project) : LspDiagnosticsSupport() {
  override fun createAnnotation(holder: AnnotationHolder, diagnostic: Diagnostic, textRange: TextRange, quickFixes: List<IntentionAction>) {
    val session = holder.currentAnnotationSession
    session.file.virtualFile?.path?.let { ClangdDiagnostics.getInstance(project).record(it, session, diagnostic) }
    super.createAnnotation(holder, diagnostic, textRange, quickFixes)
  }
}
