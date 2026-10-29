// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import org.jetbrains.jewel.ui.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhipper.ide.RhipperBundle
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.ToggleableChip

@Composable
internal fun FidelityScreen(
  state: FidelityState,
  actions: FunctionListActions,
  onKindChange: (String?) -> Unit,
  onBucketClick: (Int) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    when {
      state.loading && state.stats == null -> Centered { CircularProgressIndicator() }
      state.stats == null && state.kind == null -> Centered { SecondaryText(RhipperBundle.message("fidelity.none"), fontSize = 13) }
      else -> {
        FidelitySummary(state, onKindChange, onBucketClick)
        Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
        Row(Modifier.fillMaxSize()) {
          FunctionList(state.items, state.selected?.item?.address, actions, Modifier.weight(0.62f).fillMaxHeight())
          Divider(Orientation.Vertical, Modifier.fillMaxHeight())
          FunctionDetailPanel(state.selected, actions, Modifier.weight(0.38f).fillMaxHeight())
        }
      }
    }
  }
}

@Composable
private fun FidelitySummary(state: FidelityState, onKindChange: (String?) -> Unit, onBucketClick: (Int) -> Unit) {
  val stats = state.stats
  Row(
    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(12.dp),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Card(Modifier.width(150.dp).fillMaxHeight()) {
      Metric(
        RhipperBundle.message("fidelity.average"),
        stats?.average?.let { "%.3f".format(it) } ?: "—",
        stats?.let { RhipperBundle.message("fidelity.scored", "%,d".format(it.scored)) },
        valueColor = scoreColor(stats?.average),
      )
    }
    Card(Modifier.width(150.dp).fillMaxHeight()) {
      Metric(
        RhipperBundle.message("fidelity.low"),
        stats?.below?.let { "%,d".format(it) } ?: "—",
        RhipperBundle.message("fidelity.low.caption"),
        valueColor = if ((stats?.below ?: 0) > 0) RhipperColors.error else RhipperColors.success,
      )
    }
    Card(Modifier.weight(1f).fillMaxHeight()) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryText(RhipperBundle.message("fidelity.distribution").uppercase(), fontSize = 11)
        Spacer(Modifier.weight(1f))
        if (state.bucket != null) {
          Link(RhipperBundle.message("fidelity.clear.range", "%.1f".format(state.bucket / 10.0), "%.1f".format((state.bucket + 1) / 10.0)),
               onClick = { onBucketClick(state.bucket) })
        }
        else {
          SecondaryText(RhipperBundle.message("fidelity.click.range"), fontSize = 11)
        }
      }
      Spacer(Modifier.height(6.dp))
      if (stats != null) Histogram(stats.buckets, Modifier.fillMaxWidth(), height = 64.dp, selected = state.bucket, onBucketClick = onBucketClick)
      Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text("0.0", fontSize = 10.sp, color = RhipperColors.neutral)
        Spacer(Modifier.weight(1f))
        Text("0.5", fontSize = 10.sp, color = RhipperColors.neutral)
        Spacer(Modifier.weight(1f))
        Text("1.0", fontSize = 10.sp, color = RhipperColors.neutral)
      }
    }
  }
  if (state.kinds.size > 1) {
    Row(
      Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      SecondaryText(RhipperBundle.message("fidelity.kind"))
      ToggleableChip(checked = state.kind == null, onClick = { onKindChange(null) }) {
        Text(RhipperBundle.message("fidelity.kind.all"), fontSize = 12.sp)
      }
      state.kinds.forEach { kind ->
        ToggleableChip(checked = state.kind == kind, onClick = { onKindChange(kind) }) {
          Text(kind, fontSize = 12.sp)
        }
      }
      Spacer(Modifier.weight(1f))
      SecondaryText(RhipperBundle.message("fidelity.shown", "%,d".format(state.items.size)))
    }
  }
}
