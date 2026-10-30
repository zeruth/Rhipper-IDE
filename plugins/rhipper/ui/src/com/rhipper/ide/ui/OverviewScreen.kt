// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhipper.ide.RhipperBundle
import org.jetbrains.jewel.ui.component.ActionButton
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icon.IconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** The callbacks of the Overview tab. */
internal class OverviewActions(
  val onRunStep: (stepId: String, fromHere: Boolean) -> Unit,
  val onOpenLog: (stepId: String) -> Unit,
  val onOpenConfig: () -> Unit,
  val onOpenSettings: () -> Unit,
  val onRefresh: () -> Unit,
  val onOpenFault: (fault: String) -> Unit,
  val onShowFunctions: () -> Unit,
  val onShowFidelity: () -> Unit,
)

@Composable
internal fun OverviewScreen(state: OverviewState, actions: OverviewActions) {
  VerticallyScrollableContainer(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      ProjectHeaderCard(state, actions)
      if (state.header != null) {
        PipelineSection(state.steps, actions)
        StatsRow(state, actions)
        PortRunsSection(state.runs, actions)
      }
    }
  }
}

@Composable
private fun ProjectHeaderCard(state: OverviewState, actions: OverviewActions) {
  Card(Modifier.fillMaxWidth(), padding = 16.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      Icon(RhipperIconKey, contentDescription = null, modifier = Modifier.size(32.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val header = state.header
        when {
          header != null -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(header.name, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
              Pill(header.arch, RhipperColors.accent)
              Pill(header.toolchain ?: RhipperBundle.message("overview.toolchain.detected"), RhipperColors.neutral)
            }
            SecondaryText(RhipperBundle.message("overview.reference", header.exe, header.imageBase))
          }
          state.configError != null -> {
            Text(RhipperBundle.message("overview.invalid.title"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(state.configError, color = RhipperColors.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
          }
          else -> Text(RhipperBundle.message("overview.no.project"), fontSize = 16.sp)
        }
      }
      RhipperHomeStatus(state.header?.rhipperHome, actions)
      ActionButton(onClick = actions.onOpenConfig, tooltip = { Text(RhipperBundle.message("overview.open.config")) }) {
        Icon(AllIconsKeys.FileTypes.Json, contentDescription = RhipperBundle.message("overview.open.config"))
      }
      ActionButton(onClick = actions.onRefresh, tooltip = { Text(RhipperBundle.message("overview.refresh")) }) {
        Icon(AllIconsKeys.Actions.Refresh, contentDescription = RhipperBundle.message("overview.refresh"))
      }
    }
  }
}

@Composable
private fun RhipperHomeStatus(home: String?, actions: OverviewActions) {
  Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.widthIn(max = 320.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      StatusDot(if (home != null) RhipperColors.success else RhipperColors.error)
      Text(
        if (home != null) RhipperBundle.message("overview.rhipper.found") else RhipperBundle.message("overview.rhipper.missing"),
        fontSize = 12.sp,
      )
    }
    if (home != null) SecondaryText(home, fontSize = 11)
    else Link(RhipperBundle.message("overview.configure"), onClick = actions.onOpenSettings)
  }
}

@Composable
private fun PipelineSection(steps: List<StepCard>, actions: OverviewActions) {
  Column {
    SectionTitle(RhipperBundle.message("overview.pipeline")) {
      DefaultButton(onClick = { steps.firstOrNull()?.let { actions.onRunStep(it.id, true) } }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(AllIconsKeys.Actions.RunAll, contentDescription = null)
          Text(RhipperBundle.message("overview.run.all"))
        }
      }
    }
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
      steps.forEachIndexed { index, step ->
        StepCardView(step, actions, Modifier.weight(1f).fillMaxHeight())
        if (index < steps.lastIndex) {
          Icon(AllIconsKeys.General.ChevronRight, contentDescription = null, modifier = Modifier.padding(horizontal = 2.dp))
        }
      }
    }
  }
}

@Composable
private fun StepCardView(step: StepCard, actions: OverviewActions, modifier: Modifier) {
  Card(modifier, padding = 10.dp) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      StepPhaseIndicator(step.phase)
      Text(step.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
    Spacer(Modifier.height(4.dp))
    Text(phaseText(step), fontSize = 12.sp, color = phaseColor(step.phase), maxLines = 1)
    SecondaryText(step.timeText ?: step.description, maxLines = 2, fontSize = 11)
    if (step.phase == StepPhase.RUNNING && step.activity != null) {
      SecondaryText(step.activity, fontSize = 11)
    }
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 6.dp)) {
      IconAction(AllIconsKeys.Actions.Execute, RhipperBundle.message("overview.step.run", step.name)) { actions.onRunStep(step.id, false) }
      IconAction(AllIconsKeys.Actions.RunAll, RhipperBundle.message("overview.step.run.from", step.name)) { actions.onRunStep(step.id, true) }
      IconAction(AllIconsKeys.Actions.ShowAsTree, RhipperBundle.message("overview.step.log", step.name)) { actions.onOpenLog(step.id) }
    }
  }
}

@Composable
private fun StepPhaseIndicator(phase: StepPhase) {
  Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
    when (phase) {
      StepPhase.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp))
      StepPhase.SUCCEEDED, StepPhase.DONE -> Icon(AllIconsKeys.RunConfigurations.TestPassed, contentDescription = null)
      StepPhase.FAILED -> Icon(AllIconsKeys.RunConfigurations.TestFailed, contentDescription = null)
      StepPhase.STOPPED -> Icon(AllIconsKeys.General.Warning, contentDescription = null)
      StepPhase.NOT_RUN -> StatusDot(RhipperColors.track, 10.dp)
    }
  }
}

@Composable
private fun phaseColor(phase: StepPhase): Color = when (phase) {
  StepPhase.NOT_RUN -> RhipperColors.neutral
  StepPhase.DONE -> RhipperColors.neutral
  StepPhase.RUNNING -> RhipperColors.accent
  StepPhase.SUCCEEDED -> RhipperColors.success
  StepPhase.FAILED -> RhipperColors.error
  StepPhase.STOPPED -> RhipperColors.warning
}

private fun phaseText(step: StepCard): String = when (step.phase) {
  StepPhase.NOT_RUN -> RhipperBundle.message("overview.phase.not.run")
  StepPhase.DONE -> RhipperBundle.message("overview.phase.done")
  StepPhase.RUNNING -> RhipperBundle.message("overview.phase.running")
  StepPhase.SUCCEEDED -> RhipperBundle.message("overview.phase.succeeded")
  StepPhase.FAILED -> RhipperBundle.message("overview.phase.failed")
  StepPhase.STOPPED -> RhipperBundle.message("overview.phase.stopped")
}

@Composable
private fun IconAction(key: IconKey, tooltip: String, onClick: () -> Unit) {
  ActionButton(onClick = onClick, tooltip = { Text(tooltip) }) {
    Icon(key, contentDescription = tooltip)
  }
}

@Composable
private fun StatsRow(state: OverviewState, actions: OverviewActions) {
  Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
    val cardModifier = Modifier.weight(1f).fillMaxHeight()
    Card(cardModifier) {
      val corpus = state.corpus
      Metric(
        RhipperBundle.message("overview.corpus"),
        corpus?.functions?.let { "%,d".format(it) } ?: placeholder(state.loading),
        corpus?.let { RhipperBundle.message("overview.corpus.failed", it.failed) } ?: RhipperBundle.message("overview.corpus.none"),
        valueColor = if (corpus != null) Color.Unspecified else RhipperColors.neutral,
      )
      Spacer(Modifier.weight(1f))
      Link(RhipperBundle.message("overview.browse.functions"), onClick = actions.onShowFunctions, modifier = Modifier.padding(top = 8.dp))
    }
    Card(cardModifier) {
      val translation = state.translation
      Metric(
        RhipperBundle.message("overview.translation"),
        translation?.functions?.let { "%,d".format(it) } ?: placeholder(state.loading),
        translation?.let { RhipperBundle.message("overview.translation.files", it.files) },
      )
      if (translation != null && translation.corpusFunctions > 0) {
        val share = translation.functions.toDouble() / translation.corpusFunctions
        ScoreBarLike(share)
        SecondaryText(RhipperBundle.message("overview.translation.share", "%.1f".format(share * 100)), fontSize = 11)
      }
    }
    Card(cardModifier) {
      val fidelity = state.fidelity
      Metric(
        RhipperBundle.message("overview.fidelity"),
        fidelity?.average?.let { "%.2f".format(it) } ?: placeholder(state.loading),
        fidelity?.let { RhipperBundle.message("overview.fidelity.below", it.below, "%,d".format(it.scored)) }
        ?: RhipperBundle.message("overview.fidelity.none"),
        valueColor = scoreColor(fidelity?.average),
      )
      if (fidelity != null) {
        Histogram(fidelity.buckets, Modifier.fillMaxWidth().padding(top = 8.dp), height = 36.dp, onBucketClick = { actions.onShowFidelity() })
      }
    }
    Card(cardModifier) {
      val last = state.runs.firstOrNull()
      Metric(
        RhipperBundle.message("overview.last.run"),
        when {
          last == null -> "—"
          last.alive -> RhipperBundle.message("overview.run.alive")
          last.succeeded -> RhipperBundle.message("overview.run.ok")
          else -> last.exit ?: "?"
        },
        last?.let { RhipperBundle.message("overview.run.when", it.time, it.seconds ?: 0) } ?: RhipperBundle.message("overview.runs.none"),
        valueColor = when {
          last == null -> RhipperColors.neutral
          last.succeeded -> RhipperColors.success
          else -> RhipperColors.error
        },
      )
      val fault = last?.fault
      if (fault != null && !last.succeeded) {
        Spacer(Modifier.weight(1f))
        Link(fault, onClick = { actions.onOpenFault(fault) }, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
      }
    }
  }
}

@Composable
private fun ScoreBarLike(share: Double) {
  Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp)) {
    ScoreBar(share, barWidth = 120.dp)
  }
}

private fun placeholder(loading: Boolean): String = if (loading) "…" else "—"

@Composable
private fun PortRunsSection(runs: List<PortRunItem>, actions: OverviewActions) {
  Column {
    SectionTitle(RhipperBundle.message("overview.runs"))
    if (runs.isEmpty()) {
      SecondaryText(RhipperBundle.message("overview.runs.none"))
      return
    }
    Card(Modifier.fillMaxWidth(), padding = 4.dp) {
      runs.forEach { run ->
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          StatusDot(if (run.succeeded) RhipperColors.success else RhipperColors.error)
          Text(run.time, fontFamily = Monospace, fontSize = 12.sp, modifier = Modifier.width(120.dp))
          Text(run.commit + if (run.dirtyGen) "*" else "", fontFamily = Monospace, fontSize = 12.sp, color = RhipperColors.neutral,
               modifier = Modifier.width(72.dp))
          Text(run.seconds?.let { "${it}s" } ?: "", fontSize = 12.sp, color = RhipperColors.neutral, modifier = Modifier.width(44.dp))
          Pill(
            when {
              run.alive -> RhipperBundle.message("overview.run.alive")
              run.succeeded -> RhipperBundle.message("overview.run.ok")
              else -> run.exit ?: "?"
            },
            if (run.succeeded) RhipperColors.success else RhipperColors.error,
          )
          val fault = run.fault
          if (fault != null && !run.succeeded) {
            Link(fault, onClick = { actions.onOpenFault(fault) }, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
          }
          else {
            Spacer(Modifier.weight(1f))
          }
        }
      }
    }
  }
}
