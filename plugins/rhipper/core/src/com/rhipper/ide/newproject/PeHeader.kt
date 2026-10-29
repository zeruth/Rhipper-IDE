// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.newproject

import com.rhipper.ide.project.RhipperArch
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

/** What a new project needs from the headers of a Windows PE file. */
data class PeHeader(val arch: RhipperArch, val imageBase: Long)

/** Reads the machine and the image base of the PE file at [path]. It returns `null` when the file is no x86 or x64 PE file. */
fun readPeHeader(path: Path): PeHeader? {
  try {
    RandomAccessFile(path.toFile(), "r").use { file ->
      if (file.length() < 0x40) return null
      val dos = read(file, 0, 0x40)
      if (dos.getShort(0).toInt() != 0x5A4D) return null
      val peOffset = dos.getInt(0x3C).toLong()
      if (peOffset <= 0 || peOffset + 24 + 32 > file.length()) return null
      val nt = read(file, peOffset, 24 + 32)
      if (nt.getInt(0) != 0x00004550) return null
      val arch = when (nt.getShort(4).toInt() and 0xFFFF) {
        0x14C -> RhipperArch.X86
        0x8664 -> RhipperArch.X86_64
        else -> return null
      }
      val optional = 24
      val imageBase = when (nt.getShort(optional).toInt() and 0xFFFF) {
        0x10B -> nt.getInt(optional + 28).toLong() and 0xFFFFFFFFL
        0x20B -> nt.getLong(optional + 24)
        else -> return null
      }
      return PeHeader(arch, imageBase)
    }
  }
  catch (_: IOException) {
    return null
  }
}

private fun read(file: RandomAccessFile, offset: Long, length: Int): ByteBuffer {
  val bytes = ByteArray(length)
  file.seek(offset)
  file.readFully(bytes)
  return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}
