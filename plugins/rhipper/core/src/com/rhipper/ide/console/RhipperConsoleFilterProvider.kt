// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.console

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.openapi.project.Project
import com.rhipper.ide.corpus.RhipperNavigator
import com.rhipper.ide.corpus.RhipperView
import com.rhipper.ide.project.rhipperConfig

/**
 * Links the reference functions in the output of a Rhipper project: `F_<address>` opens the translation,
 * `FUN_<address>` and `0x<address>` open Ghidra's decompilation.
 */
internal class RhipperConsoleFilterProvider : ConsoleFilterProvider {
  override fun getDefaultFilters(project: Project): Array<Filter> {
    if (project.rhipperConfig == null) return Filter.EMPTY_ARRAY
    return arrayOf(RhipperAddressFilter(project))
  }
}

internal class RhipperAddressFilter(private val project: Project) : Filter {
  override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
    val lineStart = entireLength - line.length
    val items = PATTERN.findAll(line).mapNotNull { match ->
      val symbol = match.value
      val view = if (symbol.startsWith("F_")) RhipperView.TRANSLATION else RhipperView.DECOMPILATION
      val link = HyperlinkInfo { RhipperNavigator.getInstance(project).open(symbol, view) }
      Filter.ResultItem(lineStart + match.range.first, lineStart + match.range.last + 1, link)
    }.toList()
    return if (items.isEmpty()) null else Filter.Result(items)
  }

  companion object {
    /** A function name with its address, or a bare address of at least six digits, so that small numbers stay plain text. */
    private val PATTERN = Regex("""\b(?:thunk_FUN_|FUN_|F_)[0-9a-fA-F]{6,16}\b|\b0x[0-9a-fA-F]{6,16}\b""")
  }
}
