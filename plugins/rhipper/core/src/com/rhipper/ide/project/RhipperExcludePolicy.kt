// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Excludes the outputs of a Rhipper project from the index.
 *
 * `build/` holds the facts, the corpus, the logs and the port's objects. It reaches hundreds of megabytes.
 * The IDE reads the files in it directly, so they need no index. The generated sources under `gen` stay indexed.
 */
internal class RhipperExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {
  override fun getExcludeUrlsForProject(): Array<String> {
    val config = project.rhipperConfig ?: return emptyArray()
    val excluded = LinkedHashSet<Path>()
    excluded.add(config.buildPath)
    excluded.add(config.resolve(config.build.dir))
    excluded.add(config.resolve("archive"))
    try {
      Files.newDirectoryStream(config.root) { it.isDirectory() && it.name.startsWith("cmake-build-") }.use { dirs ->
        dirs.forEach { excluded.add(it) }
      }
    }
    catch (_: java.io.IOException) {
    }
    val gen = config.genPath
    return excluded
      .filter { !gen.startsWith(it) }
      .map { VfsUtilCore.pathToUrl(it.toString()) }
      .toTypedArray()
  }
}
