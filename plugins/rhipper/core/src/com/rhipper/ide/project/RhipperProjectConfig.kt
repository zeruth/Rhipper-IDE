// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Path

const val RHIPPER_JSON: String = "rhipper.json"

/** The architecture of the reference binary. Each one has its own translator and pipeline in Rhipper. */
enum class RhipperArch(val id: String) {
  X86("x86"),
  X86_64("x86_64");

  companion object {
    fun of(id: String?): RhipperArch = entries.firstOrNull { it.id == id } ?: X86_64
  }
}

/** The `build` section: the CMake build of the port. */
data class RhipperBuildConfig(
  val dir: String,
  val config: String,
  val genTarget: String?,
  val target: String?,
)

/** The `run` section: how the pipeline starts the port. */
data class RhipperRunConfig(
  val exe: String?,
  val cwd: String,
  val args: List<String>,
)

/** The `ghidra` section: the headless Ghidra install and the project in it. */
data class RhipperGhidraConfig(
  val install: String?,
  val projects: String?,
  val project: String?,
  val program: String?,
  val maxmem: String,
)

/**
 * The settings of a Rhipper project, read from its `rhipper.json`.
 *
 * The defaults are those of Rhipper's `project.py`. Every relative path is relative to [root].
 */
data class RhipperProjectConfig(
  val root: Path,
  val name: String,
  val rhipper: String?,
  val exe: String,
  val arch: RhipperArch,
  val toolchain: String?,
  val imageBase: String,
  val sources: List<String>,
  val gen: String,
  val build: RhipperBuildConfig,
  val run: RhipperRunConfig,
  val ghidra: RhipperGhidraConfig,
  val gotchasReviewed: Map<String, String>,
  val translateArgs: List<String>,
  val json: JsonObject,
) {
  val configFile: Path get() = root.resolve(RHIPPER_JSON)
  val exePath: Path get() = resolve(exe)
  val genPath: Path get() = resolve(gen)
  val buildPath: Path get() = root.resolve("build")
  val factsPath: Path get() = buildPath.resolve("facts")
  val pipelineLogPath: Path get() = buildPath.resolve("pipeline")
  val corpusPath: Path get() = buildPath.resolve("corpus.jsonl")
  val fidelityPath: Path get() = buildPath.resolve("fidelity.json")
  val runsPath: Path get() = pipelineLogPath.resolve("runs.jsonl")
  val imageBaseValue: Long get() = parseAddress(imageBase) ?: 0L

  fun resolve(path: String): Path = root.resolve(path).normalize()

  companion object {
    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Reads [text] as the `rhipper.json` of the project at [root]. It throws [RhipperConfigException] when the text is not valid. */
    fun parse(root: Path, text: String): RhipperProjectConfig {
      val element = try {
        JSON.parseToJsonElement(text)
      }
      catch (e: IllegalArgumentException) {
        throw RhipperConfigException(e.message ?: e.toString(), e)
      }
      val obj = element as? JsonObject ?: throw RhipperConfigException("rhipper.json must hold a JSON object")
      val exe = obj.string("exe") ?: throw RhipperConfigException("rhipper.json must set \"exe\", the reference binary")
      val build = obj.obj("build")
      val run = obj.obj("run")
      val ghidra = obj.obj("ghidra")
      return RhipperProjectConfig(
        root = root,
        name = obj.string("name") ?: root.fileName?.toString() ?: "Rhipper",
        rhipper = obj.string("rhipper"),
        exe = exe,
        arch = RhipperArch.of(obj.string("arch")),
        toolchain = obj.string("toolchain"),
        imageBase = obj.string("image_base") ?: "0x140000000",
        sources = obj.strings("sources") ?: listOf("src", "vendor"),
        gen = obj.string("gen") ?: "src/gen",
        build = RhipperBuildConfig(
          dir = build?.string("dir") ?: "build/cmake",
          config = build?.string("config") ?: "Release",
          genTarget = build?.string("gen_target") ?: "gen",
          target = build?.string("target"),
        ),
        run = RhipperRunConfig(
          exe = run?.string("exe"),
          cwd = run?.string("cwd") ?: ".",
          args = run?.strings("args") ?: emptyList(),
        ),
        ghidra = RhipperGhidraConfig(
          install = ghidra?.string("install"),
          projects = ghidra?.string("projects"),
          project = ghidra?.string("project"),
          program = ghidra?.string("program"),
          maxmem = ghidra?.string("maxmem") ?: "14G",
        ),
        gotchasReviewed = obj.obj("gotchas_reviewed")
                            ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
                            ?.toMap()
                          ?: emptyMap(),
        translateArgs = obj.strings("translate_args") ?: listOf("--all", "--depth", "24", "--fix", "--jobs", "4"),
        json = obj,
      )
    }

    fun parseJson(text: String): JsonElement = JSON.parseToJsonElement(text)
  }
}

class RhipperConfigException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Reads `0x401000`, `401000` or `00401000` as a hexadecimal address. */
fun parseAddress(text: String): Long? {
  val digits = text.trim().removePrefix("0x").removePrefix("0X")
  if (digits.isEmpty() || digits.length > 16) return null
  return digits.toULongOrNull(16)?.toLong()
}

/** Writes [address] as `0x` and at least eight hexadecimal digits, as Ghidra and Rhipper's logs do. */
fun formatAddress(address: Long): String = "0x" + java.lang.Long.toHexString(address).padStart(8, '0')

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

private fun JsonObject.strings(key: String): List<String>? {
  val array = this[key] as? JsonArray ?: return null
  return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
}
