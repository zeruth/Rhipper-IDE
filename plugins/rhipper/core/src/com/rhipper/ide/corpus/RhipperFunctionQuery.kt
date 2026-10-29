// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.project.Project

/** Whether the translation has a function, and whether Ghidra decompiled it. */
enum class TranslationState { TRANSLATED, NOT_TRANSLATED, DECOMPILER_FAILED }

/** The order of the results of a [FunctionQuery]. */
enum class FunctionSort { ADDRESS, NAME, SIZE, SCORE }

/**
 * A filter over the functions of the corpus, the translation and the fidelity report. Each `null` field matches every function.
 *
 * A function matches when it matches every field that is set.
 */
data class FunctionQuery(
  /** Text in the name, the `ref:` name of the translation, or the address. Case does not matter. */
  val text: String? = null,
  /** A regular expression that the name or the `ref:` name must contain a match of. */
  val nameRegex: Regex? = null,
  val addressFrom: Long? = null,
  /** The first address after the range. */
  val addressTo: Long? = null,
  val minSize: Int? = null,
  val maxSize: Int? = null,
  val states: Set<TranslationState>? = null,
  /** The lowest fidelity score, inclusive. A function with no score does not match a score bound. */
  val minScore: Double? = null,
  /** The highest fidelity score, exclusive, so that `maxScore = 0.5` means "below 0.5". */
  val maxScore: Double? = null,
  /** Only functions that the fidelity report scored, or, with `false`, only functions it did not score. */
  val scored: Boolean? = null,
  /** The fidelity kinds, as `fidelity.py` names them: `translated`, `same`, and so on. */
  val kinds: Set<String>? = null,
  /** Text in a missing call of the fidelity report: a callee that the port does not call. */
  val missingCall: String? = null,
  /** Text in an extra call of the fidelity report: a callee that only the port calls. */
  val extraCall: String? = null,
  /** Only functions with calls that differ from the reference, or, with `false`, only functions without. */
  val callsDiffer: Boolean? = null,
  val sort: FunctionSort = FunctionSort.ADDRESS,
  val descending: Boolean = false,
  val offset: Int = 0,
  val limit: Int = 50,
)

/** A function of the reference with what the translation and the fidelity report say about it. */
data class FunctionRecord(
  val address: Long,
  val name: String,
  val size: Int,
  val state: TranslationState,
  val translation: GenFunction?,
  val fidelity: FidelityEntry?,
) {
  val score: Double? get() = fidelity?.score
}

/** The functions of a page of results, and the number of all functions that matched. */
data class FunctionQueryResult(val total: Int, val functions: List<FunctionRecord>)

/** Runs [query] over the corpus. Functions that only the fidelity report knows, such as hand ports, take part too. */
suspend fun queryFunctions(project: Project, query: FunctionQuery): FunctionQueryResult {
  val records = functionRecords(project)
  val text = query.text?.trim()?.takeIf { it.isNotEmpty() }
  val textAddress = text?.let { parseQueryAddress(it) }
  val matched = records.filter { record ->
    (text == null || record.name.contains(text, ignoreCase = true) ||
     record.translation?.refName?.contains(text, ignoreCase = true) == true ||
     (textAddress != null && textAddress in record.address until record.address + maxOf(record.size, 1))) &&
    (query.nameRegex == null || query.nameRegex.containsMatchIn(record.name) ||
     record.translation?.refName?.let { query.nameRegex.containsMatchIn(it) } == true) &&
    (query.addressFrom == null || record.address >= query.addressFrom) &&
    (query.addressTo == null || record.address < query.addressTo) &&
    (query.minSize == null || record.size >= query.minSize) &&
    (query.maxSize == null || record.size <= query.maxSize) &&
    (query.states == null || record.state in query.states) &&
    (query.minScore == null || (record.score != null && record.score!! >= query.minScore)) &&
    (query.maxScore == null || (record.score != null && record.score!! < query.maxScore)) &&
    (query.scored == null || (record.score != null) == query.scored) &&
    (query.kinds == null || record.fidelity?.kind in query.kinds) &&
    (query.missingCall == null || record.fidelity?.missingCalls?.any { it.contains(query.missingCall, ignoreCase = true) } == true) &&
    (query.extraCall == null || record.fidelity?.extraCalls?.any { it.contains(query.extraCall, ignoreCase = true) } == true) &&
    (query.callsDiffer == null ||
     (record.fidelity?.let { it.missingCalls.isNotEmpty() || it.extraCalls.isNotEmpty() } == true) == query.callsDiffer)
  }
  val comparator: Comparator<FunctionRecord> = when (query.sort) {
    FunctionSort.ADDRESS -> compareBy { it.address }
    FunctionSort.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    FunctionSort.SIZE -> compareBy { it.size }
    // a function with no score goes last in both orders
    FunctionSort.SCORE -> compareBy<FunctionRecord> { it.score == null }.thenBy { it.score }
  }
  val sorted = if (query.descending && query.sort == FunctionSort.SCORE) {
    matched.sortedWith(compareBy<FunctionRecord> { it.score == null }.thenByDescending { it.score })
  }
  else {
    matched.sortedWith(if (query.descending) comparator.reversed() else comparator)
  }
  val page = sorted.drop(query.offset.coerceAtLeast(0)).take(query.limit.coerceIn(1, 5000))
  return FunctionQueryResult(matched.size, page)
}

/** Every function of the corpus with its translation and fidelity, and the scored functions that the corpus does not have. */
suspend fun functionRecords(project: Project): List<FunctionRecord> {
  val corpus = RhipperCorpus.getInstance(project).index()?.functions.orEmpty()
  val translations = RhipperGenIndex.getInstance(project).index()
  val fidelity = RhipperFidelity.getInstance(project).byAddress()
  val records = ArrayList<FunctionRecord>(corpus.size + 16)
  val seen = HashSet<Long>(corpus.size * 2)
  for (function in corpus) {
    seen.add(function.address)
    val translation = translations[function.address]
    records.add(FunctionRecord(
      address = function.address,
      name = function.name,
      size = function.size,
      state = when {
        function.failed -> TranslationState.DECOMPILER_FAILED
        translation != null -> TranslationState.TRANSLATED
        else -> TranslationState.NOT_TRANSLATED
      },
      translation = translation,
      fidelity = fidelity[function.address],
    ))
  }
  for (entry in fidelity.values) {
    if (entry.address in seen) continue
    val translation = translations[entry.address]
    records.add(FunctionRecord(entry.address, entry.name, entry.size,
                               if (translation != null) TranslationState.TRANSLATED else TranslationState.NOT_TRANSLATED,
                               translation, entry))
  }
  return records
}

private val QUERY_ADDRESS = Regex("""(?:thunk_FUN_|FUN_|F_)?0x([0-9a-fA-F]+)|(?:thunk_FUN_|FUN_|F_)?([0-9a-fA-F]{6,16})""")

/** An address in a query: `0x401000`, `FUN_00401000` or `F_00401000`. Bare hexadecimal text of six digits or more counts too. */
fun parseQueryAddress(text: String): Long? {
  val match = QUERY_ADDRESS.matchEntire(text.trim()) ?: return null
  val digits = match.groupValues[1].ifEmpty { match.groupValues[2] }
  return digits.toLongOrNull(16)
}
