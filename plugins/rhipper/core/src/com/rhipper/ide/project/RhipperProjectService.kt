// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val LOG = logger<RhipperProjectService>()

/** What the project's `rhipper.json` holds. */
sealed interface RhipperProjectState {
  /** The project has no `rhipper.json` at its root. */
  data object None : RhipperProjectState

  data class Loaded(val config: RhipperProjectConfig) : RhipperProjectState

  data class Invalid(val file: Path, val message: String) : RhipperProjectState
}

/**
 * The Rhipper project of an IDE project: the `rhipper.json` at the project root.
 *
 * The service reads the file again when it changes on disk.
 */
@Service(Service.Level.PROJECT)
class RhipperProjectService(private val project: Project, private val coroutineScope: CoroutineScope) {
  private val _state = MutableStateFlow<RhipperProjectState>(RhipperProjectState.None)
  val state: StateFlow<RhipperProjectState> = _state.asStateFlow()

  /** The loaded settings, or `null` when the project has no valid `rhipper.json`. */
  val config: RhipperProjectConfig?
    get() = (state.value as? RhipperProjectState.Loaded)?.config

  init {
    _state.value = read()
    project.messageBus.connect(coroutineScope).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
      override fun after(events: List<VFileEvent>) {
        val configFile = configFile() ?: return
        val configPath = configFile.toString().replace('\\', '/')
        if (events.any { it.path.equals(configPath, ignoreCase = true) }) {
          coroutineScope.launch { reload() }
        }
      }
    })
  }

  suspend fun reload() {
    _state.value = withContext(Dispatchers.IO) { read() }
  }

  fun configFile(): Path? = project.basePath?.let { Path.of(it, RHIPPER_JSON) }

  private fun read(): RhipperProjectState {
    val file = configFile() ?: return RhipperProjectState.None
    if (!Files.isRegularFile(file)) return RhipperProjectState.None
    return try {
      RhipperProjectState.Loaded(RhipperProjectConfig.parse(file.parent, Files.readString(file)))
    }
    catch (e: IOException) {
      LOG.info("Cannot read $file", e)
      RhipperProjectState.Invalid(file, e.message ?: e.toString())
    }
    catch (e: RhipperConfigException) {
      RhipperProjectState.Invalid(file, e.message ?: e.toString())
    }
  }

  companion object {
    fun getInstance(project: Project): RhipperProjectService = project.service()

    /** The loaded settings of [project], without a service start when no code asked for it yet. */
    fun configIfLoaded(project: Project): RhipperProjectConfig? = project.serviceIfCreated<RhipperProjectService>()?.config
  }
}

/** The loaded settings of the project, or `null` when it is no Rhipper project. */
val Project.rhipperConfig: RhipperProjectConfig?
  get() = if (isDefault) null else RhipperProjectService.getInstance(this).config

/** Whether the project root holds a `rhipper.json`. It reads only the file system. */
fun Project.hasRhipperJson(): Boolean {
  val base = basePath ?: return false
  return Files.isRegularFile(Path.of(base, RHIPPER_JSON))
}
