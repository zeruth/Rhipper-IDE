// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.corpus.RhipperView
import org.jetbrains.jewel.ui.component.CircularProgressIndicator
import org.jetbrains.jewel.ui.component.Divider
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.Link
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.ToggleableChip
import org.jetbrains.jewel.ui.component.VerticallyScrollableContainer
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.jetbrains.jewel.ui.Orientation

/** The callbacks of the Functions and Fidelity tabs. */
internal class FunctionListActions(
  val onSelect: (address: Long) -> Unit,
  val onOpen: (address: Long, view: RhipperView) -> Unit,
)

@Composable
internal fun FunctionsScreen(
  state: FunctionsState,
  actions: FunctionListActions,
  onQueryChange: (String) -> Unit,
  onFilterChange: (FunctionFilter) -> Unit,
) {
  Column(Modifier.fillMaxSize()) {
    FunctionsToolbar(state, onQueryChange, onFilterChange)
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    when {
      state.loading && state.items.isEmpty() -> Centered { CircularProgressIndicator() }
      !state.hasCorpus -> Centered { SecondaryText(RhipperBundle.message("functions.no.corpus"), fontSize = 13) }
      else -> Row(Modifier.fillMaxSize()) {
        FunctionList(state.items, state.selected?.item?.address, actions, Modifier.weight(0.62f).fillMaxHeight())
        Divider(Orientation.Vertical, Modifier.fillMaxHeight())
        FunctionDetailPanel(state.selected, actions, Modifier.weight(0.38f).fillMaxHeight())
      }
    }
  }
}

@Composable
private fun FunctionsToolbar(state: FunctionsState, onQueryChange: (String) -> Unit, onFilterChange: (FunctionFilter) -> Unit) {
  val queryState = rememberTextFieldState()
  LaunchedEffect(queryState) {
    snapshotFlow { queryState.text.toString() }.collect(onQueryChange)
  }
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    TextField(
      state = queryState,
      modifier = Modifier.width(280.dp),
      placeholder = { Text(RhipperBundle.message("functions.search.empty")) },
      leadingIcon = { Icon(AllIconsKeys.Actions.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp)) },
    )
    FunctionFilter.entries.forEach { filter ->
      ToggleableChip(checked = state.filter == filter, onClick = { onFilterChange(filter) }) {
        Text(RhipperBundle.message(filterKey(filter), "%,d".format(state.counts[filter] ?: 0)), fontSize = 12.sp)
      }
    }
    Spacer(Modifier.weight(1f))
    SecondaryText(RhipperBundle.message("functions.count", "%,d".format(state.items.size), "%,d".format(state.total)))
  }
}

private fun filterKey(filter: FunctionFilter): String = when (filter) {
  FunctionFilter.ALL -> "functions.filter.all"
  FunctionFilter.TRANSLATED -> "functions.filter.translated"
  FunctionFilter.NOT_TRANSLATED -> "functions.filter.not.translated"
  FunctionFilter.FAILED -> "functions.filter.failed"
  FunctionFilter.LOW_FIDELITY -> "functions.filter.low.fidelity"
}

/** A list of functions with a header row. A click selects a function, a double click opens Ghidra's decompilation of it. */
@Composable
internal fun FunctionList(
  items: List<FunctionItem>,
  selected: Long?,
  actions: FunctionListActions,
  modifier: Modifier = Modifier,
  listState: LazyListState = rememberLazyListState(),
) {
  Column(modifier) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      HeaderCell(RhipperBundle.message("functions.column.address"), Modifier.width(96.dp))
      HeaderCell(RhipperBundle.message("functions.column.name"), Modifier.weight(1f))
      HeaderCell(RhipperBundle.message("functions.column.size"), Modifier.width(64.dp))
      HeaderCell(RhipperBundle.message("functions.column.state"), Modifier.width(120.dp))
      HeaderCell(RhipperBundle.message("functions.column.fidelity"), Modifier.width(100.dp))
    }
    Divider(Orientation.Horizontal, Modifier.fillMaxWidth())
    if (items.isEmpty()) {
      Centered { SecondaryText(RhipperBundle.message("functions.empty"), fontSize = 13) }
      return
    }
    VerticallyScrollableContainer(listState, Modifier.fillMaxSize()) {
      LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.address }) { item ->
          FunctionRow(item, item.address == selected, actions)
        }
      }
    }
  }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
  Text(text.uppercase(), modifier = modifier, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = RhipperColors.neutral, maxLines = 1)
}

@Composable
private fun FunctionRow(item: FunctionItem, selected: Boolean, actions: FunctionListActions) {
  Row(
    Modifier
      .fillMaxWidth()
      .background(if (selected) RhipperColors.selection else androidx.compose.ui.graphics.Color.Transparent)
      .combinedClickable(
        onClick = { actions.onSelect(item.address) },
        onDoubleClick = { actions.onOpen(item.address, RhipperView.DECOMPILATION) },
      )
      .padding(horizontal = 12.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(item.addressText, fontFamily = Monospace, fontSize = 12.sp, color = RhipperColors.neutral, modifier = Modifier.width(96.dp))
    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
    Text("%,d".format(item.size), fontSize = 12.sp, color = RhipperColors.neutral, modifier = Modifier.width(64.dp))
    Box(Modifier.width(120.dp)) { StatusPill(item) }
    ScoreBar(item.score, Modifier.width(100.dp))
  }
}

@Composable
internal fun StatusPill(item: FunctionItem) {
  when (item.status) {
    FunctionStatus.TRANSLATED -> Pill(item.kind?.takeIf { it.isNotEmpty() } ?: RhipperBundle.message("functions.state.translated"), RhipperColors.success)
    FunctionStatus.NOT_TRANSLATED -> Pill(RhipperBundle.message("functions.state.not.translated"), RhipperColors.neutral)
    FunctionStatus.DECOMPILER_FAILED -> Pill(RhipperBundle.message("functions.state.failed"), RhipperColors.error)
  }
}

/** The selected function: its identity, its state, buttons for its views, and the calls that differ from the reference. */
@Composable
internal fun FunctionDetailPanel(detail: FunctionDetail?, actions: FunctionListActions, modifier: Modifier = Modifier) {
  if (detail == null) {
    Box(modifier, contentAlignment = Alignment.Center) { SecondaryText(RhipperBundle.message("functions.select"), fontSize = 13) }
    return
  }
  val item = detail.item
  VerticallyScrollableContainer(modifier) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(item.addressText, fontFamily = Monospace, fontSize = 12.sp, color = RhipperColors.neutral)
          SecondaryText(RhipperBundle.message("functions.detail.size", "%,d".format(item.size)))
          StatusPill(item)
        }
        if (detail.refName != null && detail.refName != item.name) SecondaryText(RhipperBundle.message("functions.detail.ref", detail.refName))
      }
      Card(Modifier.fillMaxWidth()) {
        SecondaryText(RhipperBundle.message("functions.column.fidelity").uppercase(), fontSize = 11)
        Spacer(Modifier.height(4.dp))
        ScoreBar(item.score, barWidth = 160.dp)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { actions.onOpen(item.address, RhipperView.DECOMPILATION) }) {
          Text(RhipperBundle.message("functions.open.decompilation"))
        }
        OutlinedButton(onClick = { actions.onOpen(item.address, RhipperView.TRANSLATION) }, enabled = item.status == FunctionStatus.TRANSLATED) {
          Text(RhipperBundle.message("functions.open.translation"))
        }
        OutlinedButton(onClick = { actions.onOpen(item.address, RhipperView.DISASSEMBLY) }) {
          Text(RhipperBundle.message("functions.open.disassembly"))
        }
      }
      detail.translationLocation?.let { location ->
        Link(location, onClick = { actions.onOpen(item.address, RhipperView.TRANSLATION) })
      }
      CallList(RhipperBundle.message("functions.detail.missing.calls"), detail.missingCalls, RhipperColors.error, actions)
      CallList(RhipperBundle.message("functions.detail.extra.calls"), detail.extraCalls, RhipperColors.warning, actions)
    }
  }
}

@Composable
private fun CallList(title: String, calls: List<CallRef>, color: androidx.compose.ui.graphics.Color, actions: FunctionListActions) {
  if (calls.isEmpty()) return
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      StatusDot(color)
      Text(title, fontWeight = FontWeight.Medium)
      SecondaryText(calls.size.toString())
    }
    calls.forEach { call ->
      val address = call.address
      if (address != null) Link(call.text, onClick = { actions.onOpen(address, RhipperView.DECOMPILATION) }, overflow = TextOverflow.Ellipsis)
      else Text(call.text, fontFamily = Monospace, fontSize = 12.sp, color = RhipperColors.neutral, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
