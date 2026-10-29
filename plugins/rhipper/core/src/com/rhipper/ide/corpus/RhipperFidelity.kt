// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.getLastModifiedTime

/** The fidelity of one port function to its reference function, from `build/fidelity.json`. */
data class FidelityEntry(
  val address: Long,
  val name: String,
  val size: Int,
  /** How the port has the function: `translated`, `same`, a hand port, or a stub. */
  val kind: String,
  /** The blended score from 0 to 1, or `null` when `fidelity.py` scored nothing. */
  val score: Double?,
  val missingCalls: List<String>,
  val extraCalls: List<String>,
)

/** The fidelity report of the project, which Rhipper's `fidelity.py` writes. */
@Service(Service.Level.PROJECT)
class RhipperFidelity(private val project: Project) {
  private val mutex = Mutex()

  @Volatile
  private var cached: Triple<FileTime, List<FidelityEntry>, Map<Long, FidelityEntry>>? = null

  val byAddressIfReady: Map<Long, FidelityEntry>? get() = cached?.third

  suspend fun entries(): List<FidelityEntry> = load()?.second ?: emptyList()

  suspend fun byAddress(): Map<Long, FidelityEntry> = load()?.third ?: emptyMap()

  private suspend fun load(): Triple<FileTime, List<FidelityEntry>, Map<Long, FidelityEntry>>? {
    val path = project.rhipperConfig?.fidelityPath ?: return null
    return withContext(Dispatchers.IO) {
      mutex.withLock {
        if (!Files.isRegularFile(path)) return@withLock null
        val modified = path.getLastModifiedTime()
        cached?.takeIf { it.first == modified }?.let { return@withLock it }
        val array = RhipperProjectConfig.parseJson(Files.readString(path)) as? JsonArray ?: return@withLock null
        val entries = array.mapNotNull { (it as? JsonObject)?.let(::parseEntry) }
        Triple(modified, entries, entries.associateBy { it.address }).also { cached = it }
      }
    }
  }

  private fun parseEntry(obj: JsonObject): FidelityEntry? {
    val address = obj.string("addr")?.let(::parseAddress) ?: return null
    return FidelityEntry(
      address = address,
      name = obj.string("name").orEmpty(),
      size = (obj["size"] as? JsonPrimitive)?.intOrNull ?: 0,
      kind = obj.string("kind").orEmpty(),
      score = (obj["score"] as? JsonPrimitive)?.doubleOrNull,
      missingCalls = obj.strings("missing_calls"),
      extraCalls = obj.strings("extra_calls"),
    )
  }

  companion object {
    fun getInstance(project: Project): RhipperFidelity = project.service()
  }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.strings(key: String): List<String> {
  return (this[key] as? JsonArray)?.map { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() } ?: emptyList()
}
