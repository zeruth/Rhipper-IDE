// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.newproject

import com.rhipper.ide.project.RhipperArch
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path
import java.time.Instant

/** A section of a PE file. [executable] and [writable] come from its characteristics. */
data class PeSection(val name: String, val virtualAddress: Long, val virtualSize: Long, val executable: Boolean, val writable: Boolean)

/** What a new project needs from a Windows PE file, and what the new project dialog shows of it. */
data class PeHeader(
  val arch: RhipperArch,
  val imageBase: Long,
  /** The virtual address of the entry point: the image base plus its RVA. */
  val entryPoint: Long,
  val subsystem: Int,
  val linkerVersion: String,
  val timestamp: Instant,
  val sections: List<PeSection>,
  /** The names of the imported DLLs, in the order of the import directory. */
  val imports: List<String>,
  /** The PDB path that the CodeView debug record names, or `null` when the file names none. */
  val pdb: String?,
) {
  val subsystemName: String
    get() = when (subsystem) {
      1 -> "Native"
      2 -> "Windows GUI"
      3 -> "Windows console"
      else -> "Subsystem $subsystem"
    }

  /** The section that holds the entry point, or `null` when no section holds it. */
  val entrySection: PeSection?
    get() = sections.firstOrNull { entryPoint - imageBase in it.virtualAddress until it.virtualAddress + maxOf(it.virtualSize, 1) }

  /**
   * The entry point is outside the first executable section, or in a writable section. A packer or a protector does this: it runs
   * its own stub first, and the stub unpacks the program.
   */
  val entryLooksPacked: Boolean
    get() {
      val section = entrySection ?: return true
      return section.writable || section != sections.firstOrNull { it.executable }
    }
}

/** Reads the headers of the PE file at [path]. It returns `null` when the file is no x86 or x64 PE file. */
fun readPeHeader(path: Path): PeHeader? {
  try {
    RandomAccessFile(path.toFile(), "r").use { file ->
      if (file.length() < 0x40) return null
      val dos = read(file, 0, 0x40)
      if (dos.getShort(0).toInt() != 0x5A4D) return null
      val peOffset = dos.getInt(0x3C).toLong()
      if (peOffset <= 0 || peOffset + 24 > file.length()) return null
      val coff = read(file, peOffset, 24)
      if (coff.getInt(0) != 0x00004550) return null
      val arch = when (coff.getShort(4).toInt() and 0xFFFF) {
        0x14C -> RhipperArch.X86
        0x8664 -> RhipperArch.X86_64
        else -> return null
      }
      val sectionCount = coff.getShort(6).toInt() and 0xFFFF
      val timestamp = Instant.ofEpochSecond(coff.getInt(8).toLong() and 0xFFFFFFFFL)
      val optionalSize = coff.getShort(20).toInt() and 0xFFFF
      val optionalOffset = peOffset + 24
      if (optionalSize < 96 || optionalOffset + optionalSize > file.length()) return null
      val optional = read(file, optionalOffset, optionalSize)

      val pe32Plus = when (optional.getShort(0).toInt() and 0xFFFF) {
        0x10B -> false
        0x20B -> true
        else -> return null
      }
      val linkerVersion = "%d.%02d".format(optional.get(2).toInt() and 0xFF, optional.get(3).toInt() and 0xFF)
      val entryRva = optional.getInt(16).toLong() and 0xFFFFFFFFL
      val imageBase = if (pe32Plus) optional.getLong(24) else optional.getInt(28).toLong() and 0xFFFFFFFFL
      val subsystem = optional.getShort(68).toInt() and 0xFFFF
      val directories = if (pe32Plus) 112 else 96
      val directoryCount = if (optionalSize >= directories) optional.getInt(directories - 4) else 0

      val sectionsOffset = optionalOffset + optionalSize
      val table = read(file, sectionsOffset, sectionCount * 40)
      val raw = ArrayList<RawSection>()
      for (i in 0 until sectionCount) {
        val at = i * 40
        val name = String(ByteArray(8) { table.get(at + it) }, Charsets.US_ASCII).trimEnd('\u0000')
        val characteristics = table.getInt(at + 36)
        raw.add(RawSection(
          PeSection(name, table.getInt(at + 12).toLong() and 0xFFFFFFFFL, table.getInt(at + 8).toLong() and 0xFFFFFFFFL,
                    executable = characteristics and 0x20000000 != 0, writable = characteristics and 0x80000000.toInt() != 0),
          rawSize = table.getInt(at + 16).toLong() and 0xFFFFFFFFL,
          rawOffset = table.getInt(at + 20).toLong() and 0xFFFFFFFFL,
        ))
      }

      fun directory(index: Int): Pair<Long, Long>? {
        if (index >= directoryCount || directories + index * 8 + 8 > optionalSize) return null
        val rva = optional.getInt(directories + index * 8).toLong() and 0xFFFFFFFFL
        val size = optional.getInt(directories + index * 8 + 4).toLong() and 0xFFFFFFFFL
        return if (rva == 0L) null else rva to size
      }

      fun offsetOf(rva: Long): Long? = raw.firstOrNull { rva >= it.section.virtualAddress && rva < it.section.virtualAddress + maxOf(it.rawSize, it.section.virtualSize) }
        ?.let { it.rawOffset + (rva - it.section.virtualAddress) }

      val imports = runCatching { directory(1)?.let { (rva, _) -> readImports(file, rva, ::offsetOf) } }.getOrNull().orEmpty()
      val pdb = runCatching { directory(6)?.let { (rva, size) -> readPdb(file, rva, size, ::offsetOf) } }.getOrNull()

      return PeHeader(arch, imageBase, imageBase + entryRva, subsystem, linkerVersion, timestamp, raw.map { it.section }, imports, pdb)
    }
  }
  catch (_: IOException) {
    return null
  }
}

private class RawSection(val section: PeSection, val rawSize: Long, val rawOffset: Long)

private fun readImports(file: RandomAccessFile, rva: Long, offsetOf: (Long) -> Long?): List<String> {
  val start = offsetOf(rva) ?: return emptyList()
  val names = ArrayList<String>()
  // an import descriptor is 20 bytes, and a descriptor of zeros ends the list
  for (i in 0 until 1024) {
    val descriptor = read(file, start + i * 20L, 20)
    val nameRva = descriptor.getInt(12).toLong() and 0xFFFFFFFFL
    if (nameRva == 0L) break
    val nameOffset = offsetOf(nameRva) ?: continue
    names.add(readCString(file, nameOffset, 256))
  }
  return names
}

private fun readPdb(file: RandomAccessFile, rva: Long, size: Long, offsetOf: (Long) -> Long?): String? {
  val start = offsetOf(rva) ?: return null
  // a debug directory entry is 28 bytes; type 2 is CodeView, whose RSDS record ends with the PDB path
  for (i in 0 until (size / 28).toInt().coerceAtMost(32)) {
    val entry = read(file, start + i * 28L, 28)
    if (entry.getInt(12) != 2) continue
    val dataOffset = entry.getInt(24).toLong() and 0xFFFFFFFFL
    val record = read(file, dataOffset, 4)
    if (record.getInt(0) != 0x53445352) continue
    return readCString(file, dataOffset + 24, 1024).takeIf { it.isNotEmpty() }
  }
  return null
}

private fun readCString(file: RandomAccessFile, offset: Long, limit: Int): String {
  val length = minOf(limit.toLong(), file.length() - offset).toInt().coerceAtLeast(0)
  val bytes = ByteArray(length)
  file.seek(offset)
  file.readFully(bytes)
  val end = bytes.indexOf(0).let { if (it < 0) length else it }
  return String(bytes, 0, end, Charsets.UTF_8)
}

private fun read(file: RandomAccessFile, offset: Long, length: Int): ByteBuffer {
  val bytes = ByteArray(length)
  file.seek(offset)
  file.readFully(bytes)
  return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
