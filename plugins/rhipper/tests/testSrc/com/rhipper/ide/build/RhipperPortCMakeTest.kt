// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.build

import com.rhipper.ide.project.RhipperProjectConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RhipperPortCMakeTest {
  @TempDir
  lateinit var root: Path

  private val rhipperHome = Path.of("C:/tools/Rhipper")

  private fun config(json: String = """{"name": "sands3", "exe": ".reference/POP.EXE", "arch": "x86", "build": {"target": "sands3"}}""") =
    RhipperProjectConfig.parse(root, json)

  @Test
  fun `the CMakeLists of an x86 project builds the translation with the runtime`() {
    val text = RhipperPortCMake.cmakeLists(config(), rhipperHome)
    assertFalse(Regex("@[A-Z_]+@").containsMatchIn(text), text)
    assertTrue(text.contains("project(sands3 C CXX)"), text)
    assertTrue(text.contains("set(RHIPPER_DIR \"C:/tools/Rhipper\" CACHE PATH"), text)
    assertTrue(text.contains("file(GLOB GEN_SOURCES CONFIGURE_DEPENDS \"\${CMAKE_CURRENT_SOURCE_DIR}/src/gen/*.c\")"), text)
    assertTrue(text.contains("add_executable(sands3 \$<TARGET_OBJECTS:gen>)"), text)
    assertTrue(text.contains("rhipper_runtime32(sands3 NAME \"sands3\" ENV \"SANDS3\" REF \"POP.EXE\")"), text)
  }

  @Test
  fun `the env of rhipper json names the variables of the runtime`() {
    val text = RhipperPortCMake.cmakeLists(config("""{"name": "sands3", "exe": "POP.EXE", "arch": "x86", "env": "POP"}"""), rhipperHome)
    assertTrue(text.contains("rhipper_runtime32(sands3 NAME \"sands3\" ENV \"POP\" REF \"POP.EXE\")"), text)
  }

  @Test
  fun `writes the file only for an x86 project that has none`() {
    RhipperPortCMake.writeIfMissing(config("""{"exe": "a.exe"}"""), rhipperHome)
    assertFalse(Files.exists(root.resolve(RhipperPortCMake.CMAKE_LISTS)))

    RhipperPortCMake.writeIfMissing(config(), rhipperHome)
    assertTrue(Files.readString(root.resolve(RhipperPortCMake.CMAKE_LISTS)).contains("rhipper_runtime32(sands3"))

    Files.writeString(root.resolve(RhipperPortCMake.CMAKE_LISTS), "# mine")
    RhipperPortCMake.writeIfMissing(config(), rhipperHome)
    assertEquals("# mine", Files.readString(root.resolve(RhipperPortCMake.CMAKE_LISTS)))
  }

  @Test
  fun `a new build directory is created from the project root as x64`() {
    val buildDir = root.resolve("build/cmake").toString()
    assertEquals(listOf("cmake", "-S", root.toString(), "-B", buildDir, "-A", "x64"), RhipperPortCMake.configureCommand(config(), "cmake", null))
    assertEquals(listOf("cmake", "-S", root.toString(), "-B", buildDir), RhipperPortCMake.configureCommand(config(), "cmake", "Ninja"))

    Files.createDirectories(root.resolve("build/cmake"))
    Files.writeString(root.resolve("build/cmake/CMakeCache.txt"), "")
    assertEquals(listOf("cmake", buildDir), RhipperPortCMake.configureCommand(config(), "cmake", null))
  }
}
