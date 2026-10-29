// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.corpus

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.project.Project
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.project.parseAddress
import com.rhipper.ide.project.rhipperConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.getLastModifiedTime

private val LOG = logger<RhipperCorpus>()

/** A function of the reference in `build/corpus.jsonl`: where its line starts, and what the line says without the C text. */
data class CorpusFunction(
  val address: Long,
  val name: String,
  val size: Int,
  val failed: Boolean,
  internal val offset: Long,
  internal val length: Int,
)

/** One function's decompilation, as Ghidra exported it. [c] is `null` when the decompiler failed, and [error] says why. */
data class CorpusEntry(val function: CorpusFunction, val c: String?, val error: String?, val json: JsonObject)

/** The index of a corpus file: every function, by address and by name. */
class CorpusIndex internal constructor(val path: Path, internal val modified: FileTime, val functions: List<CorpusFunction>) {
  private val byName: Map<String, CorpusFunction> = functions.associateBy { it.name }

  fun byName(name: String): CorpusFunction? = byName[name]

  /** The function that starts at [address], else the function whose bytes hold it. */
  fun containing(address: Long): CorpusFunction? {
    var low = 0
    var high = functions.size - 1
    var best: CorpusFunction? = null
    while (low <= high) {
      val mid = (low + high) ushr 1
      val function = functions[mid]
      if (function.address <= address) {
        best = function
        low = mid + 1
      }
      else {
        high = mid - 1
      }
    }
    return best?.takeIf { address < it.address + maxOf(it.size, 1) }
  }

  /** The functions by the last part of a qualified name: `WinMain` for `Global::WinMain`. */
  private val byShortName: Map<String, List<CorpusFunction>> by lazy {
    functions.filter { "::" in it.name }.groupBy { it.name.substringAfterLast("::") }
  }

  /**
   * The function that [text] names: an address (`0x401000`, `401000`, `FUN_00401000`, `F_00401000`), a name,
   * or the last part of a qualified name when only one function has it.
   */
  fun find(text: String): CorpusFunction? {
    val trimmed = text.trim()
    byName(trimmed)?.let { return it }
    val address = ADDRESS_NAME.matchEntire(trimmed)?.groupValues?.get(1)?.let(::parseAddress) ?: parseAddress(trimmed)
    address?.let(::containing)?.let { return it }
    return byShortName[trimmed.substringAfterLast("::")]?.singleOrNull()
  }

  companion object {
    private val ADDRESS_NAME = Regex("""(?:FUN_|F_|LAB_|thunk_FUN_)([0-9a-fA-F]+)""")
  }
}

/**
 * The project's decompilation corpus, `build/corpus.jsonl`, which Ghidra's `ExportDecompAll` writes.
 *
 * The file holds one JSON line for each function and reaches 100 MB. The index keeps the offset of each line and reads a line when asked.
 * The index is built again when the file changes.
 */
@Service(Service.Level.PROJECT)
class RhipperCorpus(private val project: Project) {
  private val mutex = Mutex()

  @Volatile
  private var cached: CorpusIndex? = null

  /** The current index, or `null` when no code built it yet. It does not read the disk. */
  val indexIfReady: CorpusIndex? get() = cached

  /** The index of the corpus. It reads the corpus again when the file changed. It returns `null` when the project has no corpus. */
  suspend fun index(): CorpusIndex? {
    val config = project.rhipperConfig ?: return null
    return withContext(Dispatchers.IO) {
      mutex.withLock {
        val path = config.corpusPath
        if (!Files.isRegularFile(path)) return@withLock null
        val modified = path.getLastModifiedTime()
        cached?.takeIf { it.path == path && it.modified == modified }?.let { return@withLock it }
        buildIndex(path, modified).also { cached = it }
      }
    }
  }

  suspend fun read(function: CorpusFunction): CorpusEntry? {
    val index = index() ?: return null
    return withContext(Dispatchers.IO) {
      val bytes = ByteArray(function.length)
      RandomAccessFile(index.path.toFile(), "r").use { file ->
        file.seek(function.offset)
        file.readFully(bytes)
      }
      val obj = RhipperProjectConfig.parseJson(String(bytes, Charsets.UTF_8)) as? JsonObject ?: return@withContext null
      CorpusEntry(
        function = function,
        c = (obj["c"] as? JsonPrimitive)?.contentOrNull?.replace("\r\n", "\n"),
        error = (obj["error"] as? JsonPrimitive)?.contentOrNull?.trim(),
        json = obj,
      )
    }
  }

  private suspend fun buildIndex(path: Path, modified: FileTime): CorpusIndex {
    val started = System.nanoTime()
    val functions = ArrayList<CorpusFunction>(100_000)
    val line = ByteArrayOutputStream(4096)
    var offset = 0L
    var lineStart = 0L
    BufferedInputStream(Files.newInputStream(path), 1 shl 20).use { input ->
      val buffer = ByteArray(1 shl 16)
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        checkCanceled()
        for (i in 0 until read) {
          val b = buffer[i]
          if (b == '\n'.code.toByte()) {
            parseHead(line, lineStart, (offset + i - lineStart).toInt())?.let(functions::add)
            line.reset()
            lineStart = offset + i + 1
          }
          else if (line.size() < HEAD_BYTES) {
            line.write(b.toInt())
          }
        }
        offset += read
      }
    }
    if (line.size() > 0) parseHead(line, lineStart, (offset - lineStart).toInt())?.let(functions::add)
    functions.sortBy { it.address }
    LOG.info("Indexed ${functions.size} corpus functions of $path in ${(System.nanoTime() - started) / 1_000_000} ms")
    return CorpusIndex(path, modified, functions)
  }

  /** Reads the address, name and size off the start of a line, `{"addr": "401db0", "name": "FUN_00401db0", "size": 86, ...`. */
  private fun parseHead(head: ByteArrayOutputStream, offset: Long, length: Int): CorpusFunction? {
    val text = head.toString(Charsets.UTF_8)
    val address = ADDR.find(text)?.groupValues?.get(1)?.let(::parseAddress) ?: return null
    val name = NAME.find(text)?.groupValues?.get(1)?.let(::unescapeJson) ?: return null
    val size = SIZE.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    return CorpusFunction(address, name, size, failed = text.contains("\"error\""), offset = offset, length = length)
  }

  companion object {
    private const val HEAD_BYTES = 512
    private val ADDR = Regex(""""addr"\s*:\s*"([0-9a-fA-Fx]+)"""")
    private val NAME = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val SIZE = Regex(""""size"\s*:\s*(\d+)""")

    fun getInstance(project: Project): RhipperCorpus = project.service()

    private fun unescapeJson(text: String): String {
      if ('\\' !in text) return text
      return (RhipperProjectConfig.parseJson("\"$text\"") as JsonPrimitive).content
    }
  }
}
