// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
// Modified by the Rhipper contributors, 2026: the splash stays on top of other windows, and it can show version lines.
package com.intellij.platform.ide.bootstrap

import com.intellij.openapi.util.SystemInfoRt
import com.intellij.ui.Gray
import com.intellij.util.ui.StartupUiUtil
import java.awt.Color
import java.awt.Dialog
import java.awt.Dimension
import java.awt.Font
import java.awt.Frame
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.image.BufferedImage

/**
 * To customize your IDE splash go to YourIdeNameApplicationInfo.xml and edit 'logo' tag. For more information, see documentation for
 * the tag attributes in ApplicationInfo.xsd file.
 */
internal class Splash(
  private val image: BufferedImage,
  /** Lines drawn right-aligned in the bottom right corner of the image, the last one lowest. Empty for no text. */
  private val versionLines: List<String> = emptyList(),
) : Dialog(null as Frame?, "splash" /* not visible, but available through window properties on Linux */) {
  init {
    isUndecorated = true
    background = Gray.TRANSPARENT
    // makes tiling window managers on a Linux show window as floating
    isResizable = false
    focusableWindowState = false
    // above the window that started the IDE, such as a console: Windows does not raise a window of a background process.
    // SplashManager hides the splash when the first IDE window opens, so it never covers one
    isAlwaysOnTop = true
    val size = Dimension(image.width, image.height)
    this.size = size

    val graphicsConfiguration = graphicsConfiguration
    val bounds = graphicsConfiguration.bounds
    if (SystemInfoRt.isWindows) {
      val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration)
      if (insets != null) {
        bounds.x += insets.left
        bounds.y += insets.top
        bounds.width -= insets.left + insets.right
        bounds.height -= insets.top + insets.bottom
      }
    }
    location = StartupUiUtil.getCenterPoint(bounds, size)
  }

  override fun paint(g: Graphics) {
    StartupUiUtil.drawImage(g, image, x = 0, y = 0)
    if (versionLines.isEmpty()) {
      return
    }

    val g2 = g.create() as Graphics2D
    try {
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
      g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
      g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
      val metrics = g2.fontMetrics
      val right = width - 16
      var baseline = height - 10 - (versionLines.size - 1) * (metrics.height + 1)
      for ((index, line) in versionLines.withIndex()) {
        // the first line reads as the title, the others as captions
        g2.color = if (index == 0) Color(0xFF, 0xD7, 0xD0, 0xE6) else Color(0xE5, 0x73, 0x73, 0xCC)
        g2.drawString(line, right - metrics.stringWidth(line), baseline)
        baseline += metrics.height + 1
      }
    }
    finally {
      g2.dispose()
    }
  }
}
