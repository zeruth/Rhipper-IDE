// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.rhipper.ide.project.RhipperProjectConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Files

/** A run of the port, as `pipeline32.py` logs it in `build/pipeline/runs.jsonl`. */
data class PortRun(
  val time: String,
  val commit: String,
  val dirtyGen: Boolean,
  val seconds: Int?,
  val alive: Boolean,
  val exit: String?,
  /** Where the port faulted, for example `F_00e14a10+0x126 c0000005 reading 0000000000000000`. */
  val fault: String?,
  val frames: Int?,
) {
  /** Whether the port ran until the pipeline stopped it, or ended with exit code 0. */
  val succeeded: Boolean get() = alive || exit == "0" || exit == "0x0"
}

/** Reads the newest [limit] runs of the port of [config], newest first. */
fun readPortRuns(config: RhipperProjectConfig, limit: Int = 200): List<PortRun> {
  val path = config.runsPath
  if (!Files.isRegularFile(path)) return emptyList()
  return Files.readAllLines(path).asReversed().asSequence().mapNotNull(::parsePortRun).take(limit).toList()
}

private fun parsePortRun(line: String): PortRun? {
  val obj = try {
    RhipperProjectConfig.parseJson(line) as? JsonObject
  }
  catch (_: IllegalArgumentException) {
    null
  } ?: return null
  fun string(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
  fun primitive(key: String) = obj[key] as? JsonPrimitive
  return PortRun(
    time = string("when").orEmpty(),
    commit = string("commit").orEmpty(),
    dirtyGen = primitive("dirty_gen")?.booleanOrNull == true,
    seconds = primitive("secs")?.intOrNull,
    alive = primitive("alive")?.booleanOrNull == true,
    exit = string("exit"),
    fault = string("fault"),
    frames = primitive("frames")?.intOrNull,
  )
}
