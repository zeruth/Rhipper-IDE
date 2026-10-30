// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import com.rhipper.ide.pipeline.RhipperPipelineStatus
import com.rhipper.ide.pipeline.RhipperStep
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Path

class RhipperProjectConfigTest {
  private val root = Path.of("/projects/port")

  @Test
  fun `reads the settings and fills the defaults of project py`() {
    val config = RhipperProjectConfig.parse(root, """
      {
        "name": "CrystalCore",
        "exe": "archive/game/ffxiv.exe",
        "arch": "x86",
        "image_base": "0x400000",
        "build": {"dir": "build/cmake", "target": "CrystalCore"},
        "run": {"exe": "build/cmake/bin/Release/CrystalCore.exe", "args": ["DEV.TestSID=0"]},
        "gotchas_reviewed": {"SendMessageA": "one call"}
      }
    """.trimIndent())
    assertEquals("CrystalCore", config.name)
    assertEquals(RhipperArch.X86, config.arch)
    assertEquals(0x400000L, config.imageBaseValue)
    assertEquals(listOf("src", "vendor"), config.sources)
    assertEquals("src/gen", config.gen)
    assertEquals("Release", config.build.config)
    assertEquals("CrystalCore", config.build.target)
    assertEquals(".", config.run.cwd)
    assertEquals(listOf("DEV.TestSID=0"), config.run.args)
    assertEquals("14G", config.ghidra.maxmem)
    assertEquals(mapOf("SendMessageA" to "one call"), config.gotchasReviewed)
    assertEquals(root.resolve("archive/game/ffxiv.exe").normalize(), config.exePath)
  }

  @Test
  fun `an x64 project is the default`() {
    assertEquals(RhipperArch.X86_64, RhipperProjectConfig.parse(root, """{"exe": "a.exe"}""").arch)
  }

  @Test
  fun `a file without exe is not valid`() {
    assertThrows<RhipperConfigException> { RhipperProjectConfig.parse(root, """{"name": "x"}""") }
    assertThrows<RhipperConfigException> { RhipperProjectConfig.parse(root, """[1, 2]""") }
    assertThrows<RhipperConfigException> { RhipperProjectConfig.parse(root, """{"exe": """) }
  }

  @Test
  fun `reads and writes addresses`() {
    assertEquals(0x401db0L, parseAddress("401db0"))
    assertEquals(0x401db0L, parseAddress("0x00401DB0"))
    assertEquals(0x140000000L, parseAddress("0x140000000"))
    assertNull(parseAddress("0x"))
    assertNull(parseAddress("WinMain"))
    assertEquals("0x00401db0", formatAddress(0x401db0L))
    assertEquals("0x140001000", formatAddress(0x140001000L))
  }

  @Test
  fun `maps the progress lines of pipeline32 to steps`() {
    assertEquals(RhipperStep.GHIDRA, RhipperPipelineStatus.stepOfStamp("ghidra.sh all"))
    assertEquals(RhipperStep.TRANSLATE, RhipperPipelineStatus.stepOfStamp("translate32"))
    assertEquals(RhipperStep.BUILD, RhipperPipelineStatus.stepOfStamp("build"))
    assertEquals(RhipperStep.RUN, RhipperPipelineStatus.stepOfStamp("run"))
    assertNull(RhipperPipelineStatus.stepOfStamp("ghidra.sh functions"))
    assertNull(RhipperPipelineStatus.stepOfStamp("protos32 (round 2)"))
  }
}
