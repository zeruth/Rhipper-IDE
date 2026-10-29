// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.pipeline.RhipperCommandLines
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.formatAddress
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

/** What the navigator opens for a function. */
enum class RhipperView {
  /** Ghidra's C, from the corpus. */
  DECOMPILATION,

  /** The translator's C, in the generated sources. */
  TRANSLATION,

  /** The instructions of the reference binary, from Rhipper's `disasm.py`. */
  DISASSEMBLY,
}

/** Opens the views of a reference function in editors. */
@Service(Service.Level.PROJECT)
class RhipperNavigator(private val project: Project, private val coroutineScope: CoroutineScope) {
  /** Opens [view] of the function that [query] names: an address, `FUN_<address>`, `F_<address>`, or a name. */
  fun open(query: String, view: RhipperView = RhipperView.DECOMPILATION) {
    coroutineScope.launch {
      val corpus = RhipperCorpus.getInstance(project)
      val index = withBackgroundProgress(project, RhipperBundle.message("progress.indexing.corpus")) { corpus.index() }
      val function = index?.find(query)
      if (function != null) {
        open(function, view)
        return@launch
      }
      val address = Regex("""(?:F_|FUN_)?(?:0x)?([0-9a-fA-F]{4,16})""").matchEntire(query.trim())?.groupValues?.get(1)?.toLongOrNull(16)
      if (address != null && view != RhipperView.DECOMPILATION) {
        openAddress(address, null, view)
      }
      else {
        notify(RhipperBundle.message("navigate.not.found", query))
      }
    }
  }

  fun open(function: CorpusFunction, view: RhipperView = RhipperView.DECOMPILATION) {
    coroutineScope.launch { openAddress(function.address, function, view) }
  }

  private suspend fun openAddress(address: Long, function: CorpusFunction?, view: RhipperView) {
    when (view) {
      RhipperView.DECOMPILATION -> openDecompilation(function ?: return)
      RhipperView.TRANSLATION -> openTranslation(address)
      RhipperView.DISASSEMBLY -> openDisassembly(address, function?.name)
    }
  }

  private suspend fun openDecompilation(function: CorpusFunction) {
    val entry = RhipperCorpus.getInstance(project).read(function) ?: return
    val header = "/* ${function.name} at ${formatAddress(function.address)}, ${function.size} bytes: Ghidra's decompilation (read-only) */\n"
    val text = header + (entry.c ?: "/* the decompiler failed: ${entry.error.orEmpty()} */\n")
    openView(RhipperView.DECOMPILATION, "${function.name}.ghidra.c", text)
  }

  private suspend fun openTranslation(address: Long) {
    val gen = withBackgroundProgress(project, RhipperBundle.message("progress.indexing.gen")) {
      RhipperGenIndex.getInstance(project).find(address)
    }
    if (gen == null) {
      notify(RhipperBundle.message("navigate.not.translated", formatAddress(address)))
      return
    }
    val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(gen.file) } ?: return
    withContext(Dispatchers.EDT) {
      OpenFileDescriptor(project, file, gen.line, 0).navigate(true)
    }
  }

  private suspend fun openDisassembly(address: Long, name: String?) {
    val config = project.rhipperConfig ?: return
    val output = withBackgroundProgress(project, RhipperBundle.message("progress.disassembling", formatAddress(address))) {
      withContext(Dispatchers.IO) {
        try {
          val commandLine = RhipperCommandLines(config).python("disasm.py", listOf(java.lang.Long.toHexString(address)))
          CapturingProcessHandler(commandLine).runProcess(120_000)
        }
        catch (e: ExecutionException) {
          notify(e.message ?: e.toString())
          null
        }
      }
    } ?: return
    val text = if (output.exitCode == 0) output.stdout else output.stdout + output.stderr
    openView(RhipperView.DISASSEMBLY, "${name ?: "FUN_" + formatAddress(address).removePrefix("0x")}.asm", text)
  }

  /** Opens [path] in an editor without the focus, so that clangd analyzes it. It returns `false` when no file is there. */
  suspend fun openInBackground(path: Path): Boolean {
    val file = withContext(Dispatchers.IO) { LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) } ?: return false
    withContext(Dispatchers.EDT) { OpenFileDescriptor(project, file).navigate(false) }
    return true
  }

  /**
   * Opens [text] as a read-only file in `build/ide/views`. A file on disk, unlike one in memory, reopens with the project.
   * The build directory is excluded, so the views stay out of the index and out of clangd.
   */
  private suspend fun openView(view: RhipperView, name: String, text: String) {
    val config = project.rhipperConfig ?: return
    val file = withContext(Dispatchers.IO) {
      val path = viewsPath(config).resolve(view.name.lowercase()).resolve(safeFileName(name))
      if (!Files.isRegularFile(path) || Files.readString(path) != text) {
        Files.createDirectories(path.parent)
        path.toFile().setWritable(true)
        Files.writeString(path, text)
        path.toFile().setWritable(false)
      }
      LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.also { it.refresh(false, false) }
    } ?: return
    withContext(Dispatchers.EDT) {
      OpenFileDescriptor(project, file).navigate(true)
    }
  }

  private suspend fun notify(message: String) {
    withContext(Dispatchers.EDT) {
      ToolWindowManager.getInstance(project).notifyByBalloon("Rhipper", MessageType.WARNING, message)
    }
  }

  companion object {
    fun getInstance(project: Project): RhipperNavigator = project.service()
  }
}

/** The directory of the views that the navigator writes, inside the excluded build directory. */
internal fun viewsPath(config: RhipperProjectConfig): Path = config.buildPath.resolve("ide").resolve("views")

/** [name] with the characters that Windows forbids in a file name, such as the :: of a qualified name, replaced by _. */
private fun safeFileName(name: String): String = name.replace(Regex("[<>:\"/\\\\|?*]"), "_")
