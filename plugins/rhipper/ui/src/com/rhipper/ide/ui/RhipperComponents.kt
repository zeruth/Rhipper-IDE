// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhipper.ide.RhipperIcons
import org.jetbrains.jewel.foundation.theme.JewelTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icon.PathIconKey

/**
 * The colors of the Rhipper views. The surfaces and the accent come from the current theme, so the views follow Blood Moon or any
 * other theme. The status colors have a light and a dark variant, as the New UI status colors do.
 */
internal object RhipperColors {
  val success: Color @Composable get() = pick(Color(0xFF208A3C), Color(0xFF5FB865))
  val error: Color @Composable get() = pick(Color(0xFFDB3B4B), Color(0xFFE55765))
  val warning: Color @Composable get() = pick(Color(0xFFC27D04), Color(0xFFF2C55C))

  /** The accent of the theme: the color of a focused control. */
  val accent: Color @Composable get() = JewelTheme.globalColors.outlines.focused
  val neutral: Color @Composable get() = JewelTheme.globalColors.text.info

  /** A card stands a little above the panel: the panel color, a step toward the text color. */
  val cardBackground: Color @Composable get() = lerp(JewelTheme.globalColors.panelBackground, JewelTheme.globalColors.text.normal, 0.04f)
  val track: Color @Composable get() = lerp(JewelTheme.globalColors.panelBackground, JewelTheme.globalColors.text.normal, 0.12f)
  val border: Color @Composable get() = JewelTheme.globalColors.borders.normal
  val selection: Color @Composable get() = accent.copy(alpha = 0.18f)

  @Composable
  private fun pick(light: Color, dark: Color): Color = if (JewelTheme.isDark) dark else light
}

internal val RhipperIconKey = PathIconKey("icons/rhipper.svg", RhipperIcons::class.java)

internal val Monospace: FontFamily = FontFamily.Monospace

/** The color of a fidelity score: red below 0.5, amber below 0.8, green from 0.8. */
@Composable
internal fun scoreColor(score: Double?): Color = when {
  score == null -> RhipperColors.neutral
  score < 0.5 -> RhipperColors.error
  score < 0.8 -> RhipperColors.warning
  else -> RhipperColors.success
}

/** A rounded panel on the card background. */
@Composable
internal fun Card(modifier: Modifier = Modifier, padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
  val shape = RoundedCornerShape(8.dp)
  Column(
    modifier = modifier
      .clip(shape)
      .background(RhipperColors.cardBackground)
      .border(1.dp, RhipperColors.border, shape)
      .padding(padding),
    content = content,
  )
}

/** A short label on a tinted background, for a state or a kind. */
@Composable
internal fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(50))
      .background(color.copy(alpha = 0.16f))
      .padding(horizontal = 8.dp, vertical = 1.dp),
  ) {
    Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1)
  }
}

@Composable
internal fun StatusDot(color: Color, size: Dp = 8.dp) {
  Box(Modifier.size(size).clip(CircleShape).background(color))
}

@Composable
internal fun SecondaryText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1, fontSize: Int = 12) {
  Text(text, modifier = modifier, color = RhipperColors.neutral, fontSize = fontSize.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
  Row(modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
    Text(text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    Spacer(Modifier.weight(1f))
    trailing()
  }
}

/** A fidelity score as a short bar and its number. */
@Composable
internal fun ScoreBar(score: Double?, modifier: Modifier = Modifier, barWidth: Dp = 56.dp) {
  Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.width(barWidth).height(6.dp).clip(RoundedCornerShape(3.dp)).background(RhipperColors.track)) {
      if (score != null) {
        Box(
          Modifier.fillMaxWidth(score.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight()
            .clip(RoundedCornerShape(3.dp)).background(scoreColor(score)),
        )
      }
    }
    Text(score?.let { "%.2f".format(it) } ?: "—", fontFamily = Monospace, fontSize = 12.sp, color = scoreColor(score))
  }
}

/**
 * The fidelity scores in ten ranges, 0.0 to 0.1 on the left. A click on a bar selects its range, and [selected] is drawn stronger.
 */
@Composable
internal fun Histogram(
  buckets: List<Int>,
  modifier: Modifier = Modifier,
  height: Dp = 56.dp,
  selected: Int? = null,
  onBucketClick: ((Int) -> Unit)? = null,
) {
  val max = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)
  Row(modifier.height(height), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
    buckets.forEachIndexed { index, count ->
      val color = scoreColor((index + 0.5) / 10)
      val alpha = if (selected == null || selected == index) 1f else 0.35f
      Box(
        Modifier
          .weight(1f)
          .fillMaxHeight()
          .let { if (onBucketClick != null) it.clickable { onBucketClick(index) } else it },
        contentAlignment = Alignment.BottomCenter,
      ) {
        Box(
          Modifier
            .fillMaxWidth()
            .fillMaxHeight((count.toFloat() / max).coerceAtLeast(if (count > 0) 0.04f else 0.01f))
            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            .background(color.copy(alpha = alpha)),
        )
      }
    }
  }
}

/** A label above a large number, with an optional caption below it. */
@Composable
internal fun Metric(label: String, value: String, caption: String? = null, valueColor: Color = Color.Unspecified) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    SecondaryText(label.uppercase(), fontSize = 11)
    Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = valueColor, maxLines = 1)
    if (caption != null) SecondaryText(caption)
  }
}
