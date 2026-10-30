// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.lsp.ClangdLspIntegrationProvider
import com.rhipper.ide.tools.showToolsDialog

internal class RhipperConfigurable : BoundConfigurable(RhipperBundle.message("settings.display.name")) {
  override fun createPanel(): DialogPanel {
    val state = RhipperSettings.getInstance().state
    return panel {
      group(RhipperBundle.message("settings.group.rhipper")) {
        row(RhipperBundle.message("settings.rhipper.home")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor())
            .bindText({ state.rhipperHome.orEmpty() }, { state.rhipperHome = it.ifBlank { null } })
            .align(AlignX.FILL)
            .comment(RhipperBundle.message("settings.rhipper.home.comment"))
        }
        row(RhipperBundle.message("settings.python")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
            .bindText({ state.python.orEmpty() }, { state.python = it.ifBlank { null } })
            .align(AlignX.FILL)
            .comment(RhipperBundle.message("settings.python.comment"))
        }
        row(RhipperBundle.message("settings.bash")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
            .bindText({ state.bash.orEmpty() }, { state.bash = it.ifBlank { null } })
            .align(AlignX.FILL)
            .comment(RhipperBundle.message("settings.bash.comment"))
        }
      }
      group(RhipperBundle.message("settings.group.tools")) {
        row(RhipperBundle.message("settings.tools.dir")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor())
            .bindText({ state.toolsDir.orEmpty() }, { state.toolsDir = it.ifBlank { null } })
            .align(AlignX.FILL)
            .comment(RhipperBundle.message("settings.tools.dir.comment"))
        }
        row {
          checkBox(RhipperBundle.message("settings.check.updates"))
            .bindSelected({ state.checkForUpdates }, { state.checkForUpdates = it })
        }
        row {
          button(RhipperBundle.message("settings.manage.tools")) { showToolsDialog(null) }
        }
      }
      group(RhipperBundle.message("settings.group.clangd")) {
        row {
          checkBox(RhipperBundle.message("settings.clangd.enable"))
            .bindSelected({ state.enableClangd }, { state.enableClangd = it })
        }
        row(RhipperBundle.message("settings.clangd.path")) {
          textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
            .bindText({ state.clangd.orEmpty() }, { state.clangd = it.ifBlank { null } })
            .align(AlignX.FILL)
            .comment(RhipperBundle.message("settings.clangd.path.comment"))
        }
        row(RhipperBundle.message("settings.clangd.args")) {
          textField()
            .bindText({ state.clangdArgs.orEmpty() }, { state.clangdArgs = it })
            .align(AlignX.FILL)
        }
      }
    }
  }

  override fun apply() {
    super.apply()
    for (project in ProjectManager.getInstance().openProjects) {
      LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(ClangdLspIntegrationProvider::class.java)
    }
  }
}
