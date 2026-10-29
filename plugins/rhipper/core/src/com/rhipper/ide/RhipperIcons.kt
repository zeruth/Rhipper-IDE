// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object RhipperIcons {
  @JvmField
  val Rhipper: Icon = IconLoader.getIcon("/icons/rhipper.svg", RhipperIcons::class.java.classLoader)
}
