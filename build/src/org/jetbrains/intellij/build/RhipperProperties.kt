// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.intellij.build

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.plus
import org.jetbrains.intellij.build.impl.PlatformJarNames.PLATFORM_CORE_NIO_FS
import org.jetbrains.intellij.build.io.copyFileToDir
import org.jetbrains.intellij.build.productLayout.CommunityModuleSets
import org.jetbrains.intellij.build.productLayout.CommunityProductFragments
import org.jetbrains.intellij.build.productLayout.DEFAULT_BUNDLED_PLUGINS
import org.jetbrains.intellij.build.productLayout.ProductModulesContentSpec
import org.jetbrains.intellij.build.productLayout.productModules
import java.nio.file.Path

/**
 * The plugins that the Rhipper workbench bundles in addition to [DEFAULT_BUNDLED_PLUGINS].
 *
 * The list has no language plugin that needs the Java plugin.
 */
val RHIPPER_BUNDLED_PLUGINS: List<String> = listOf(
  "intellij.vcs.git",
  "intellij.vcs.github",
  "intellij.terminal",
  "intellij.textmate.plugin",
  "intellij.editorconfig.plugin",
  "intellij.markdown",
  "intellij.json",
  "intellij.yaml",
  "intellij.toml",
  "intellij.sh.plugin",
  "intellij.mcpserver.plugin",
  "intellij.rhipper",
)

/**
 * The Rhipper decompilation workbench: the IntelliJ Platform with no Java support.
 *
 * The platform prefix is `Rhipper`. The root descriptor is `META-INF/RhipperPlugin.xml` in `intellij.rhipper.ide`.
 */
class RhipperProperties(private val communityHomeDir: Path) : ProductProperties() {
  init {
    platformPrefix = "Rhipper"
    applicationInfoModule = "intellij.rhipper.ide"
    scrambleMainJar = false
    useSplash = true
    buildCrossPlatformDistribution = false
    // the launcher reads the JNA, pty4j and Skiko natives from these trees
    presignedNativeLibs = PRESIGNED_NATIVE_LIBS
    // `IJENT_BOOT_CLASSPATH_MODULE`, as in `JetBrainsProductProperties`
    productLayout.addPlatformSpec { layout -> layout.withModule("intellij.platform.core.nio.fs", PLATFORM_CORE_NIO_FS) }

    // the product images of every platform: icons, installer bitmaps and the macOS disk image background
    imagesDirectoryPath = communityHomeDir.resolve("rhipper/ide/images")
    customJvmMemoryOptions = persistentMapOf("-Xms" to "256m", "-Xmx" to "2048m")

    productLayout.productImplementationModules = listOf(
      "intellij.platform.starter",
      "intellij.rhipper.ide",
    )
    productLayout.bundledPluginModules = DEFAULT_BUNDLED_PLUGINS + RHIPPER_BUNDLED_PLUGINS
    productLayout.skipUnresolvedContentModules = true

    additionalVmOptions = persistentListOf(
      "-Dllm.show.ai.promotion.window.on.start=false",
      // the splash shows the Rhipper build and the upstream platform version it is based on
      "-Dide.splash.show.version=true",
      // no usage statistics: the platform would collect them and send them to the servers of the upstream vendor
      "-Didea.suppress.statistics.report=true",
      "-Didea.disable.collect.statistics=true",
    )
  }

  override val baseFileName: String
    get() = "rhipper"

  override fun getProductContentDescriptor(): ProductModulesContentSpec = productModules {
    alias("com.rhipper.modules.workbench")

    module("intellij.platform.remoteServers.impl")
    module("intellij.platform.ide.newUiOnboarding")
    module("intellij.platform.customization.min")

    moduleSet(CommunityModuleSets.ideCommon())
    // the bundled Compose plugin needs the Compose runtime
    moduleSet(CommunityModuleSets.composeRuntime())
    moduleSet(CommunityModuleSets.platformResourceDefaults())
    moduleSet(CommunityModuleSets.rdCommon())

    include(CommunityProductFragments.platformCoreFragment())

    allowMissingDependencies(knownMissingModuleDependencies)
    allowMissingDependencies("intellij.platform.commercial.dependencies")
    bundledPlugins(productLayout.bundledPluginModules)
  }

  /**
   * The license files of the distribution. Apache 2.0 requires the license and the NOTICE of IntelliJ IDEA in every copy.
   * RHIPPER-NOTICE.txt says what Rhipper changed and who owns the trademarks.
   */
  override fun copyAdditionalFiles(targetDir: Path, context: BuildContext) {
    super.copyAdditionalFiles(targetDir, context)
    val home = context.paths.communityHomeDir
    copyFileToDir(home.resolve("LICENSE.txt"), targetDir)
    copyFileToDir(home.resolve("NOTICE.txt"), targetDir)
    copyFileToDir(home.resolve("rhipper/ide/RHIPPER-NOTICE.txt"), targetDir)
  }

  override fun getBaseArtifactName(appInfo: ApplicationInfoProperties, buildNumber: String): String = "rhipper-$buildNumber"

  override fun getSystemSelector(appInfo: ApplicationInfoProperties, buildNumber: String): String {
    return "Rhipper${appInfo.majorVersion}.${appInfo.minorVersionMainPart}"
  }

  override fun getOutputDirectoryName(appInfo: ApplicationInfoProperties): String = "rhipper"

  override fun createWindowsCustomizer(projectHome: Path): WindowsDistributionCustomizer = windowsCustomizer(communityHomeDir) {
    fullName { "Rhipper Workbench" }
    installDirNameHandler { "Rhipper" }
    // .ipr is the project file of IntelliJ IDEA, not of Rhipper
    associateIpr = false
  }

  override fun createLinuxCustomizer(projectHome: Path): LinuxDistributionCustomizer = linuxCustomizer(communityHomeDir) {
    rootDirectoryName { _, _ -> "rhipper" }
  }

  override fun createMacCustomizer(projectHome: Path): MacDistributionCustomizer = macCustomizer(communityHomeDir) {
    bundleIdentifier = "io.github.zeruth.rhipper"
    rootDirectoryName { _, _ -> "Rhipper.app" }
  }
}
