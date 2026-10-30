// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.hasRhipperJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import org.jetbrains.jewel.bridge.addComposeTab

/** The Rhipper tool window: the Overview of the project and its pipeline, the Functions of the corpus, and the Fidelity report. */
internal class RhipperToolWindowFactory : ToolWindowFactory, DumbAware {
  override suspend fun isApplicableAsync(project: Project): Boolean = project.hasRhipperJson()

  override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
    val parent = RhipperUiScope.getInstance(project).coroutineScope
    val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    Disposer.register(toolWindow.disposable) { scope.cancel() }
    val presenter = RhipperDashboardPresenter(project, scope)

    fun selectTab(index: Int) {
      val manager = toolWindow.contentManager
      manager.getContent(index)?.let { manager.setSelectedContent(it, true) }
    }

    val listActions = FunctionListActions(
      onSelect = presenter::selectFunction,
      onOpen = presenter::open,
    )
    val fidelityActions = FunctionListActions(
      onSelect = presenter::selectFidelity,
      onOpen = presenter::open,
    )
    val overviewActions = OverviewActions(
      onRunStep = presenter::runStep,
      onOpenLog = presenter::openLog,
      onOpenConfig = presenter::openConfig,
      onOpenSettings = presenter::openSettings,
      onRefresh = presenter::refresh,
      onOpenFault = presenter::openFault,
      onShowFunctions = { selectTab(1) },
      onShowFidelity = { selectTab(2) },
    )

    toolWindow.addComposeTab(RhipperBundle.message("toolwindow.tab.overview")) {
      val state by presenter.overview.collectAsState()
      OverviewScreen(state, overviewActions)
    }
    toolWindow.addComposeTab(RhipperBundle.message("toolwindow.tab.functions")) {
      val state by presenter.functions.collectAsState()
      FunctionsScreen(state, listActions, onQueryChange = presenter::setQuery, onFilterChange = presenter::setFilter)
    }
    toolWindow.addComposeTab(RhipperBundle.message("toolwindow.tab.fidelity")) {
      val state by presenter.fidelity.collectAsState()
      FidelityScreen(state, fidelityActions, onKindChange = presenter::setFidelityKind, onBucketClick = presenter::setFidelityBucket)
    }
  }
}

@Service(Service.Level.PROJECT)
internal class RhipperUiScope(val coroutineScope: CoroutineScope) {
  companion object {
    fun getInstance(project: Project): RhipperUiScope = project.service()
  }
}
