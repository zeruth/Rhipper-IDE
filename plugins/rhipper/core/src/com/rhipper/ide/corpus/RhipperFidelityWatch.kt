// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The fidelity scores at one time, by address. A `null` score means that the report listed the function without a score. */
private data class FidelitySnapshot(val taken: Instant, val scores: Map<Long, Double?>, val names: Map<Long, String>)

/** A function whose score changed between two snapshots. */
data class FidelityChange(val address: Long, val name: String, val before: Double?, val after: Double?)

/** What changed in the fidelity report since the last snapshot of the same name. */
data class FidelityChanges(
  /** When the compared snapshot was taken, or `null` when this call took the first one. */
  val since: Instant?,
  val threshold: Double,
  /** Scores that fell by more than the tolerance. */
  val regressed: List<FidelityChange>,
  /** Scores that rose by more than the tolerance. */
  val improved: List<FidelityChange>,
  /** Functions now below the threshold that were at or above it, or not scored. */
  val newlyBelow: List<FidelityChange>,
  /** Functions now at or above the threshold that were below it. */
  val recovered: List<FidelityChange>,
  /** Functions that the report did not list before. */
  val added: List<FidelityChange>,
  /** Functions that the report no longer lists. */
  val removed: List<FidelityChange>,
  /** All functions below the threshold now. */
  val belowNow: Int,
)

/**
 * Snapshots of the fidelity report, so that an agent can ask what a translation round changed.
 *
 * Each call of [changes] compares the report with the last snapshot of the same name and then takes a new snapshot.
 * The snapshots live as long as the project is open.
 */
@Service(Service.Level.PROJECT)
class RhipperFidelityWatch(private val project: Project) {
  private val snapshots = ConcurrentHashMap<String, FidelitySnapshot>()

  suspend fun changes(name: String, threshold: Double, tolerance: Double = 0.005, keepSnapshot: Boolean = false): FidelityChanges {
    val entries = RhipperFidelity.getInstance(project).entries()
    val current = FidelitySnapshot(Instant.now(), entries.associate { it.address to it.score }, entries.associate { it.address to it.name })
    val previous = snapshots[name]
    if (!keepSnapshot || previous == null) snapshots[name] = current
    val belowNow = current.scores.values.count { it != null && it < threshold }
    if (previous == null) {
      return FidelityChanges(null, threshold, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), belowNow)
    }

    val regressed = ArrayList<FidelityChange>()
    val improved = ArrayList<FidelityChange>()
    val newlyBelow = ArrayList<FidelityChange>()
    val recovered = ArrayList<FidelityChange>()
    val added = ArrayList<FidelityChange>()
    for ((address, after) in current.scores) {
      val functionName = current.names[address].orEmpty()
      if (address !in previous.scores) {
        added.add(FidelityChange(address, functionName, null, after))
        if (after != null && after < threshold) newlyBelow.add(FidelityChange(address, functionName, null, after))
        continue
      }
      val before = previous.scores[address]
      val change = FidelityChange(address, functionName, before, after)
      if (before != null && after != null) {
        if (after < before - tolerance) regressed.add(change)
        if (after > before + tolerance) improved.add(change)
      }
      val wasBelow = before != null && before < threshold
      val isBelow = after != null && after < threshold
      if (isBelow && !wasBelow) newlyBelow.add(change)
      if (wasBelow && !isBelow && after != null) recovered.add(change)
    }
    val removed = previous.scores.keys.filter { it !in current.scores }
      .map { FidelityChange(it, previous.names[it].orEmpty(), previous.scores[it], null) }

    return FidelityChanges(
      since = previous.taken,
      threshold = threshold,
      regressed = regressed.sortedBy { (it.after ?: 0.0) - (it.before ?: 0.0) },
      improved = improved.sortedByDescending { (it.after ?: 0.0) - (it.before ?: 0.0) },
      newlyBelow = newlyBelow.sortedBy { it.after },
      recovered = recovered.sortedByDescending { it.after },
      added = added,
      removed = removed,
      belowNow = belowNow,
    )
  }

  companion object {
    fun getInstance(project: Project): RhipperFidelityWatch = project.service()
  }
}
