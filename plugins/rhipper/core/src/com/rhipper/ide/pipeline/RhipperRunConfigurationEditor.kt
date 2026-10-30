// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.ui.components.fields.ExpandableTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.rhipper.ide.RhipperBundle
import javax.swing.JComponent

internal class RhipperRunConfigurationEditor : SettingsEditor<RhipperRunConfiguration>() {
  private val stepCombo = ComboBox(RhipperStep.entries.toTypedArray()).apply {
    renderer = listCellRenderer {
      icon(value.icon)
      text(value.displayName)
    }
  }
  private val scopeCombo = ComboBox(RhipperStepScope.entries.toTypedArray()).apply {
    renderer = listCellRenderer {
      text(when (value) {
        RhipperStepScope.ONLY -> RhipperBundle.message("run.configuration.scope.only")
        RhipperStepScope.FROM -> RhipperBundle.message("run.configuration.scope.from")
        null -> ""
      })
    }
  }
  private val extraArgsField = ExpandableTextField()

  override fun createEditor(): JComponent = panel {
    row(RhipperBundle.message("run.configuration.step")) {
      cell(stepCombo)
    }
    row(RhipperBundle.message("run.configuration.scope")) {
      cell(scopeCombo)
    }
    row(RhipperBundle.message("run.configuration.extra.args")) {
      cell(extraArgsField)
        .align(AlignX.FILL)
        .comment(RhipperBundle.message("run.configuration.extra.args.comment"))
    }
  }

  override fun resetEditorFrom(configuration: RhipperRunConfiguration) {
    stepCombo.selectedItem = configuration.step
    scopeCombo.selectedItem = configuration.scope
    extraArgsField.text = configuration.extraArgs
  }

  override fun applyEditorTo(configuration: RhipperRunConfiguration) {
    configuration.step = stepCombo.selectedItem as? RhipperStep ?: RhipperStep.TRANSLATE
    configuration.scope = scopeCombo.selectedItem as? RhipperStepScope ?: RhipperStepScope.ONLY
    configuration.extraArgs = extraArgsField.text
  }
}
