// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.ui

import androidx.compose.runtime.Immutable

/** How a pipeline step looks: the state of its last run in this session, or of its log. */
enum class StepPhase { NOT_RUN, DONE, RUNNING, SUCCEEDED, FAILED, STOPPED }

@Immutable
data class StepCard(
  val id: String,
  val name: String,
  val description: String,
  val phase: StepPhase,
  /** "12 min ago", "Running 3:12", or `null` when the step has no run and no log. */
  val timeText: String?,
  val activity: String?,
)

@Immutable
data class ProjectHeader(
  val name: String,
  val arch: String,
  val toolchain: String?,
  val exe: String,
  val imageBase: String,
  /** The Rhipper directory, or `null` when Rhipper is not found. */
  val rhipperHome: String?,
)

@Immutable
data class CorpusStats(val functions: Int, val failed: Int)

@Immutable
data class TranslationStats(val functions: Int, val files: Int, val corpusFunctions: Int)

/** The fidelity report in short: [buckets] counts the scores in ten ranges, 0.0 to 0.1 first. */
@Immutable
data class FidelityStats(val scored: Int, val average: Double, val below: Int, val buckets: List<Int>)

@Immutable
data class PortRunItem(
  val time: String,
  val commit: String,
  val dirtyGen: Boolean,
  val seconds: Int?,
  val succeeded: Boolean,
  val alive: Boolean,
  val exit: String?,
  val fault: String?,
)

@Immutable
data class OverviewState(
  val header: ProjectHeader? = null,
  /** The error in the rhipper.json, when it is not valid. */
  val configError: String? = null,
  val steps: List<StepCard> = emptyList(),
  val corpus: CorpusStats? = null,
  val translation: TranslationStats? = null,
  val fidelity: FidelityStats? = null,
  val runs: List<PortRunItem> = emptyList(),
  val loading: Boolean = true,
)

enum class FunctionStatus { TRANSLATED, NOT_TRANSLATED, DECOMPILER_FAILED }

enum class FunctionFilter { ALL, TRANSLATED, NOT_TRANSLATED, FAILED, LOW_FIDELITY }

@Immutable
data class FunctionItem(
  val address: Long,
  val addressText: String,
  val name: String,
  val size: Int,
  val status: FunctionStatus,
  val kind: String?,
  val score: Double?,
)

/** A reference function that another function calls, as the fidelity report names it: `00c4d3da Global::__CIfmod`. */
@Immutable
data class CallRef(val text: String, val address: Long?)

@Immutable
data class FunctionDetail(
  val item: FunctionItem,
  val refName: String?,
  val translationLocation: String?,
  val missingCalls: List<CallRef>,
  val extraCalls: List<CallRef>,
)

@Immutable
data class FunctionsState(
  val loading: Boolean = true,
  val hasCorpus: Boolean = true,
  val total: Int = 0,
  val filter: FunctionFilter = FunctionFilter.ALL,
  val counts: Map<FunctionFilter, Int> = emptyMap(),
  val items: List<FunctionItem> = emptyList(),
  val selected: FunctionDetail? = null,
)

@Immutable
data class FidelityState(
  val loading: Boolean = true,
  val stats: FidelityStats? = null,
  val kinds: List<String> = emptyList(),
  /** The selected kind, or `null` for every kind. */
  val kind: String? = null,
  /** The selected range of the histogram, 0 to 9, or `null` for every score. */
  val bucket: Int? = null,
  val items: List<FunctionItem> = emptyList(),
  val selected: FunctionDetail? = null,
)
