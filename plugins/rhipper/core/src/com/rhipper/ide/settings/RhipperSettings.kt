// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.settings

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.util.SystemInfo
import com.rhipper.ide.project.RhipperProjectConfig
import com.rhipper.ide.tools.RhipperTools
import java.nio.file.Files
import java.nio.file.Path

class RhipperSettingsState : BaseState() {
  /** The Rhipper checkout or release. A `rhipper` key in a project's `rhipper.json` wins over it. */
  var rhipperHome: String? by string()
  var python: String? by string()
  var bash: String? by string()
  var clangd: String? by string()
  var clangdArgs: String? by string("--background-index --header-insertion=never")
  var enableClangd: Boolean by property(true)
  /** The directory of the tools that the IDE installs. Empty means `.rhipper` in the home directory. */
  var toolsDir: String? by string()
  var checkForUpdates: Boolean by property(true)
  /** Do not offer to install the missing tools at startup. */
  var setupDismissed: Boolean by property(false)
}

/** The IDE-wide Rhipper settings: where Rhipper and the tools it runs are. */
@Service(Service.Level.APP)
@State(name = "RhipperSettings", storages = [Storage("rhipper.xml")])
class RhipperSettings : SimplePersistentStateComponent<RhipperSettingsState>(RhipperSettingsState()) {
  /**
   * The Rhipper directory for [config]: the project's `rhipper` key, else this setting, else `RHIPPER_HOME`,
   * else the Rhipper release that the IDE installed.
   */
  fun rhipperHome(config: RhipperProjectConfig?): Path? {
    val candidates = sequenceOf(config?.rhipper, state.rhipperHome, System.getenv("RHIPPER_HOME"))
    return candidates.filterNotNull()
      .filter { it.isNotBlank() }
      .map { config?.root?.resolve(it)?.normalize() ?: Path.of(it) }
      .firstOrNull { Files.isRegularFile(it.resolve("project.py")) }
           ?: RhipperTools.getInstance().managedRhipperHome
  }

  /** The tools directory: the setting, else `.rhipper` in the home directory. */
  fun toolsDir(): Path = state.toolsDir?.takeIf { it.isNotBlank() }?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".rhipper")

  /** The Python of the Rhipper scripts: the setting, else the IDE's virtual environment, else `python` on the `PATH`. */
  fun pythonExecutable(): String {
    state.python?.takeIf { it.isNotBlank() }?.let { return it }
    return RhipperTools.getInstance().managedPython?.toString() ?: "python"
  }

  /** Git's bash. A `bash` on the `PATH` may be WSL's, which cannot run Rhipper's scripts with Windows paths. */
  fun bashExecutable(): String {
    state.bash?.takeIf { it.isNotBlank() }?.let { return it }
    if (SystemInfo.isWindows) {
      val gitBash = listOf("C:\\Program Files\\Git\\bin\\bash.exe", "C:\\Program Files (x86)\\Git\\bin\\bash.exe")
        .firstOrNull { Files.isRegularFile(Path.of(it)) }
      if (gitBash != null) return gitBash
    }
    return "bash"
  }

  fun clangdExecutable(): Path? {
    state.clangd?.takeIf { it.isNotBlank() }?.let { return Path.of(it) }
    PathEnvironmentVariableUtil.findFirst("clangd")?.let { return it }
    if (SystemInfo.isWindows) {
      val llvm = Path.of("C:\\Program Files\\LLVM\\bin\\clangd.exe")
      if (Files.isRegularFile(llvm)) return llvm
    }
    return RhipperTools.getInstance().managedClangd
  }

  companion object {
    fun getInstance(): RhipperSettings = service()
  }
}
