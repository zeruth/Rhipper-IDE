// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsCustomizer
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import com.intellij.util.execution.ParametersListUtil
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.RhipperIcons
import com.rhipper.ide.project.rhipperConfig
import com.rhipper.ide.settings.RhipperConfigurable
import com.rhipper.ide.settings.RhipperSettings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name

internal const val RHIPPER_NOTIFICATION_GROUP: String = "Rhipper"

private val C_EXTENSIONS = setOf("c", "h")
private val CPP_EXTENSIONS = setOf("cc", "cpp", "cxx", "c++", "hh", "hpp", "hxx", "h++", "inl", "ipp")

/** Whether clangd serves [file]: a C or C++ file on the local disk. The corpus views are in memory, so clangd cannot read them. */
internal fun isCFamilyFile(file: VirtualFile): Boolean {
  if (!file.isInLocalFileSystem) return false
  val extension = file.extension?.lowercase() ?: return false
  return extension in C_EXTENSIONS || extension in CPP_EXTENSIONS
}

/**
 * C and C++ support through clangd: completion, navigation, diagnostics, hover and the rest of the LSP features.
 *
 * The TextMate `cpp` bundle highlights the syntax. clangd adds the semantics.
 */
/** Whether clangd serves [file] in [project]. The views of the corpus in the build directory are Ghidra's C, which does not compile. */
private fun isClangdFile(project: Project, file: VirtualFile): Boolean {
  if (!isCFamilyFile(file)) return false
  val buildPath = project.rhipperConfig?.buildPath ?: return true
  return !Path.of(file.path).startsWith(buildPath)
}

internal class ClangdLspIntegrationProvider : LspIntegrationProvider {
  override fun fileOpened(project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter) {
    if (!RhipperSettings.getInstance().state.enableClangd || !isClangdFile(project, file)) return
    clientStarter.ensureClientStarted(ClangdLspClientDescriptor(project))
  }

  override fun createWidgetItem(lspClient: LspClient, currentFile: VirtualFile?): LspClientWidgetItem {
    return LspClientWidgetItem(lspClient, currentFile, RhipperIcons.Rhipper, RhipperConfigurable::class.java)
  }
}

internal class ClangdLspClientDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "clangd") {
  override fun isSupportedFile(file: VirtualFile): Boolean = isClangdFile(project, file)

  override val lspCustomization: LspCustomization = object : LspCustomization() {
    override val diagnosticsCustomizer: LspDiagnosticsCustomizer = ClangdDiagnosticsSupport(project)
  }

  override fun getLanguageId(file: VirtualFile): String {
    return if (file.extension?.lowercase() in CPP_EXTENSIONS) "cpp" else "c"
  }

  override fun createCommandLine(): GeneralCommandLine {
    val settings = RhipperSettings.getInstance()
    val clangd = settings.clangdExecutable() ?: notifyMissingClangd(project)
    val commandLine = GeneralCommandLine(clangd.toString())
    commandLine.addParameters(ParametersListUtil.parse(settings.state.clangdArgs.orEmpty()))
    findCompileCommandsDir(project)?.let { commandLine.addParameter("--compile-commands-dir=$it") }
    project.basePath?.let { commandLine.withWorkingDirectory(Path.of(it)) }
    return commandLine
  }
}

/**
 * The directory of the `compile_commands.json` that clangd reads.
 *
 * It looks at the project root, the Rhipper build directory, `build`, and the newest `cmake-build-*` directory.
 * When it finds none, clangd searches the parents of each file and falls back to its default flags.
 */
internal fun findCompileCommandsDir(project: Project): Path? {
  val root = project.basePath?.let { Path.of(it) } ?: return null
  val config = project.rhipperConfig
  val candidates = buildList {
    add(root)
    config?.let { add(it.resolve(it.build.dir)) }
    add(root.resolve("build"))
    try {
      Files.newDirectoryStream(root) { it.isDirectory() && it.name.startsWith("cmake-build-") }.use { dirs ->
        addAll(dirs.sortedByDescending { it.resolve("compile_commands.json").takeIf(Files::isRegularFile)?.getLastModifiedTime() })
      }
    }
    catch (_: java.io.IOException) {
    }
  }
  return candidates.firstOrNull { Files.isRegularFile(it.resolve("compile_commands.json")) }
}

private fun notifyMissingClangd(project: Project): Nothing {
  NotificationGroupManager.getInstance()
    .getNotificationGroup(RHIPPER_NOTIFICATION_GROUP)
    .createNotification(RhipperBundle.message("clangd.not.found.title"), RhipperBundle.message("clangd.not.found.content"), NotificationType.WARNING)
    .addAction(NotificationAction.createSimpleExpiring(RhipperBundle.message("action.open.settings")) {
      ShowSettingsUtil.getInstance().showSettingsDialog(project, RhipperConfigurable::class.java)
    })
    .notify(project)
  throw ExecutionException(RhipperBundle.message("clangd.not.found.title"))
}
