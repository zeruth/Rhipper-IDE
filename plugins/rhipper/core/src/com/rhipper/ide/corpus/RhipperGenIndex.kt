// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile

private val LOG = logger<RhipperGenIndex>()

/** A translated function in the generated sources: its file, its 0-based definition line, and the `ref:` name the translator wrote. */
data class GenFunction(val address: Long, val file: Path, val line: Int, val refName: String?)

/**
 * The translated functions in the project's `gen` directory, by reference address.
 *
 * The translator names each function `F_<address>` and writes a `/* ref: <name> */` comment above it.
 */
@Service(Service.Level.PROJECT)
class RhipperGenIndex(private val project: Project) {
  private val mutex = Mutex()

  @Volatile
  private var cached: Pair<Long, Map<Long, GenFunction>>? = null

  val indexIfReady: Map<Long, GenFunction>? get() = cached?.second

  suspend fun index(): Map<Long, GenFunction> {
    val gen = project.rhipperConfig?.genPath ?: return emptyMap()
    return withContext(Dispatchers.IO) {
      mutex.withLock {
        val files = sourceFiles(gen)
        val stamp = files.fold(files.size.toLong()) { acc, file -> acc * 31 + file.getLastModifiedTime().toMillis() }
        cached?.takeIf { it.first == stamp }?.let { return@withLock it.second }
        build(files).also { cached = stamp to it }
      }
    }
  }

  suspend fun find(address: Long): GenFunction? = index()[address]

  /** The C text of [function]: its `ref:` comment, its definition, and its body up to the closing brace in column 0. */
  suspend fun text(function: GenFunction): String = withContext(Dispatchers.IO) {
    val result = StringBuilder()
    Files.newBufferedReader(function.file).useLines { lines ->
      val iterator = lines.drop(maxOf(0, function.line - 1)).iterator()
      var first = true
      while (iterator.hasNext()) {
        val line = iterator.next()
        if (first && REF.matchEntire(line) == null) {
          first = false
          if (function.line > 0) continue
        }
        first = false
        result.append(line).append('\n')
        if (line == "}") break
      }
    }
    result.toString()
  }

  private fun sourceFiles(gen: Path): List<Path> {
    if (!Files.isDirectory(gen)) return emptyList()
    return Files.list(gen).use { stream -> stream.filter { it.isRegularFile() && it.extension == "c" }.sorted().toList() }
  }

  private suspend fun build(files: List<Path>): Map<Long, GenFunction> {
    val started = System.nanoTime()
    val result = HashMap<Long, GenFunction>(100_000)
    for (file in files) {
      checkCanceled()
      var refName: String? = null
      Files.newBufferedReader(file).useLines { lines ->
        lines.forEachIndexed { number, line ->
          val ref = REF.matchEntire(line)
          if (ref != null) {
            refName = ref.groupValues[1]
            return@forEachIndexed
          }
          if (line.startsWith("GEN_") && !line.trimEnd().endsWith(";")) {
            val address = DEFINITION.find(line)?.groupValues?.get(1)?.let(::parseAddress)
            if (address != null) result.putIfAbsent(address, GenFunction(address, file, number, refName))
          }
          refName = null
        }
      }
    }
    LOG.info("Indexed ${result.size} translated functions in ${files.size} files in ${(System.nanoTime() - started) / 1_000_000} ms")
    return result
  }

  companion object {
    private val REF = Regex("""/\* ref: (.+) \*/\s*""")
    private val DEFINITION = Regex("""\bF_([0-9a-fA-F]{6,16})\(""")

    fun getInstance(project: Project): RhipperGenIndex = project.service()
  }
}
