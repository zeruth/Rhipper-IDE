// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.PlatformProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor
import com.intellij.projectImport.ProjectOpenProcessor.ProjectOpenOptions
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.RhipperIcons
import org.jetbrains.annotations.Nls
import javax.swing.Icon

/** Opens a `rhipper.json`, or a directory that holds one, as the project of its directory. */
internal class RhipperProjectOpenProcessor : ProjectOpenProcessor() {
  override val name: @Nls String
    get() = RhipperBundle.message("open.processor.name")

  override val icon: Icon
    get() = RhipperIcons.Rhipper

  override fun canOpenProject(file: VirtualFile): Boolean {
    return if (file.isDirectory) file.findChild(RHIPPER_JSON)?.isDirectory == false else file.name == RHIPPER_JSON
  }

  override fun isProjectFile(file: VirtualFile): Boolean = !file.isDirectory && file.name == RHIPPER_JSON

  override fun lookForProjectsInDirectory(): Boolean = false

  override suspend fun openProjectAsync(virtualFile: VirtualFile, projectOpenOptions: ProjectOpenOptions): Project? {
    val dir = if (virtualFile.isDirectory) virtualFile else virtualFile.parent ?: return null
    return PlatformProjectOpenProcessor.getInstance().openProjectAsync(dir, projectOpenOptions)
  }
}
