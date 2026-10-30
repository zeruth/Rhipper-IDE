// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.

import org.jetbrains.intellij.build.BuildLifetime
import org.jetbrains.intellij.build.BuildOptions
import org.jetbrains.intellij.build.BuildPaths.Companion.COMMUNITY_ROOT
import org.jetbrains.intellij.build.OsFamily
import org.jetbrains.intellij.build.RhipperProperties
import org.jetbrains.intellij.build.impl.buildDistributions
import org.jetbrains.intellij.build.impl.createBuildContext
import kotlinx.collections.immutable.persistentListOf

/**
 * Builds the Rhipper Workbench installers into `out/rhipper/artifacts`: the Windows `.exe` installer and the `.zip`.
 *
 * Without `-Dintellij.build.target.os`, it builds for Windows only. `-Dbuild.number=271.<n>` sets the build number,
 * which the product shows as `RH-271.<n>`.
 */
object RhipperInstallersBuildTarget {
  @JvmStatic
  fun main(args: Array<String>) {
    BuildLifetime().use { lifetime ->
      val options = OpenSourceCommunityInstallersBuildTarget.OPTIONS.copy(
        buildStepsToSkip = OpenSourceCommunityInstallersBuildTarget.OPTIONS.buildStepsToSkip + setOf(
          BuildOptions.CROSS_PLATFORM_DISTRIBUTION_STEP,
          BuildOptions.SOURCES_ARCHIVE_STEP,
          BuildOptions.ARCHIVE_PLUGINS,
        ),
      )
      if (System.getProperty(BuildOptions.TARGET_OS_PROPERTY) == null) {
        options.targetOs = persistentListOf(OsFamily.WINDOWS)
      }
      val context = createBuildContext(
        projectHome = COMMUNITY_ROOT.communityRoot,
        productProperties = RhipperProperties(COMMUNITY_ROOT.communityRoot),
        setupTracer = true,
        options = options,
        lifetime = lifetime,
      )
      context.compileModules(moduleNames = null)
      buildDistributions(context)
    }
  }
}
