// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.build

import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.RhipperProjectConfig
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/**
 * The CMake project of a port. A new project has no `CMakeLists.txt` and no build directory, so the build step writes the one and
 * creates the other.
 */
object RhipperPortCMake {
  const val CMAKE_LISTS: String = "CMakeLists.txt"

  private const val TEMPLATE_X86 = "/rhipper/port/CMakeLists-x86.txt"

  /**
   * Writes the `CMakeLists.txt` of an x86 project that has none, with the runtime of [rhipperHome]. It keeps a file that exists.
   * It writes nothing for an x64 project, because the port of an x64 reference needs a runtime of the project.
   */
  fun writeIfMissing(config: RhipperProjectConfig, rhipperHome: Path) {
    if (config.arch != RhipperArch.X86) return
    val file = config.root.resolve(CMAKE_LISTS)
    if (Files.exists(file)) return
    Files.writeString(file, cmakeLists(config, rhipperHome))
  }

  /** The `CMakeLists.txt` of the port of an x86 project, as the rhtest port builds one. */
  fun cmakeLists(config: RhipperProjectConfig, rhipperHome: Path): String {
    val template = RhipperPortCMake::class.java.getResourceAsStream(TEMPLATE_X86)!!.use { String(it.readAllBytes(), Charsets.UTF_8) }
    val target = config.build.target ?: config.name
    val values = mapOf(
      "@NAME@" to config.name,
      "@TARGET@" to target,
      "@GEN_TARGET@" to (config.build.genTarget ?: "gen"),
      "@GEN_DIR@" to genDir(config),
      "@RHIPPER_DIR@" to slashes(rhipperHome.toString()),
      // the prefix of the variables of the runtime, `<ENV>_NODIALOGS`: the default of pipeline32.py
      "@ENV@" to ((config.json["env"] as? JsonPrimitive)?.contentOrNull ?: config.name.uppercase()),
      "@REF@" to config.exePath.fileName.toString(),
    )
    return values.entries.fold(template) { text, (key, value) -> text.replace(key, value) }
  }

  /**
   * The configure step with [cmake]. It configures a build directory again, and creates a new one from the project root. The port
   * is an x64 program, so a Visual Studio generator gets `-A x64`. [generator] is the `CMAKE_GENERATOR` of the environment.
   */
  fun configureCommand(config: RhipperProjectConfig, cmake: String, generator: String? = System.getenv("CMAKE_GENERATOR")): List<String> {
    val buildDir = config.resolve(config.build.dir)
    if (buildDir.resolve("CMakeCache.txt").isRegularFile()) return listOf(cmake, buildDir.toString())
    val platform = if (generator.isNullOrEmpty() || generator.startsWith("Visual Studio")) listOf("-A", "x64") else emptyList()
    return listOf(cmake, "-S", config.root.toString(), "-B", buildDir.toString()) + platform
  }

  private fun genDir(config: RhipperProjectConfig): String {
    val gen = config.genPath
    val root = config.root.normalize()
    return if (gen.startsWith(root)) "\${CMAKE_CURRENT_SOURCE_DIR}/" + slashes(root.relativize(gen).toString()) else slashes(gen.toString())
  }

  private fun slashes(path: String): String = path.replace('\\', '/')
}
