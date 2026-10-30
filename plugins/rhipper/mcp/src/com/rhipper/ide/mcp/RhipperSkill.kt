// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.mcp

import com.intellij.mcpserver.skills.BundledSkill
import com.intellij.mcpserver.skills.BundledSkillsProvider
import com.intellij.mcpserver.skills.SkillWorkspaceContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.rhipper.ide.project.rhipperConfig
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

internal const val RHIPPER_SKILL_ID: String = "rhipper"

/** The MCP tools that the Rhipper skill uses. */
internal val RHIPPER_SKILL_TOOLS: Set<String> = setOf(
  "rhipper_project", "rhipper_run_step", "rhipper_function", "rhipper_query_functions", "rhipper_fidelity_summary",
  "rhipper_fidelity_changes", "rhipper_clangd_diagnostics", "rhipper_port_runs", "rhipper_disassemble", "rhipper_callers",
  "rhipper_xrefs", "rhipper_script", "rhipper_open",
)

/** The files of the skill, relative to its directory. */
private val SKILL_FILES = listOf("SKILL.md", "references/mcp-tools.md")

/** The file in the installed skill directory that records the hash of each file the IDE wrote. */
private const val STATE_FILE = ".rhipper-ide-skill"

/** Bundles the Rhipper skill for an agent that the MCP server serves, in a Rhipper project. */
internal class RhipperSkillsProvider : BundledSkillsProvider {
  override fun registeredSkills(): List<BundledSkill> {
    return listOf(BundledSkill(RHIPPER_SKILL_ID, tools = RHIPPER_SKILL_TOOLS, requiredTools = setOf("rhipper_project")))
  }

  override suspend fun isProviderAvailable(context: SkillWorkspaceContext): Boolean = context.project?.rhipperConfig != null
}

/** How the skill in a project compares with the one in the IDE. */
internal enum class SkillState {
  /** The project has no Rhipper skill. */
  MISSING,

  /** The project has the skill of this IDE. */
  CURRENT,

  /** The project has an older skill that the IDE wrote and nobody changed. */
  OUTDATED,

  /** Somebody changed the skill in the project, or wrote it without the IDE. */
  MODIFIED,
}

/**
 * Installs the Rhipper skill into `.claude/skills/rhipper` of a project, where Claude Code reads it.
 *
 * The IDE records the hash of each file it writes, so it can update a copy that nobody changed and leave a changed copy alone.
 */
internal object RhipperSkillInstaller {
  fun skillDir(project: Project): Path? = project.basePath?.let { Path.of(it, ".claude", "skills", RHIPPER_SKILL_ID) }

  fun state(project: Project): SkillState {
    val dir = skillDir(project) ?: return SkillState.MISSING
    if (!dir.resolve("SKILL.md").isRegularFile()) return SkillState.MISSING
    val written = readState(dir)
    val bundled = SKILL_FILES.associateWith { sha256(bundled(it)) }
    val onDisk = SKILL_FILES.associateWith { file -> dir.resolve(file).takeIf { it.isRegularFile() }?.let { sha256(Files.readAllBytes(it)) } }
    return when {
      onDisk == bundled -> SkillState.CURRENT
      written.isNotEmpty() && SKILL_FILES.all { onDisk[it] == null || onDisk[it] == written[it] } -> SkillState.OUTDATED
      else -> SkillState.MODIFIED
    }
  }

  /** Writes the skill into the project. It overwrites a changed copy only with [overwriteModified]. */
  fun install(project: Project, overwriteModified: Boolean = false): Boolean {
    val dir = skillDir(project) ?: return false
    if (!overwriteModified && state(project) == SkillState.MODIFIED) return false
    val hashes = LinkedHashMap<String, String>()
    for (file in SKILL_FILES) {
      val bytes = bundled(file)
      val target = dir.resolve(file)
      Files.createDirectories(target.parent)
      Files.write(target, bytes)
      hashes[file] = sha256(bytes)
    }
    Files.writeString(dir.resolve(STATE_FILE), hashes.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
    LocalFileSystem.getInstance().refreshNioFiles(listOf(dir), true, true, null)
    return true
  }

  private fun bundled(file: String): ByteArray {
    val path = "${BundledSkill.DEFAULT_RESOURCE_ROOT}/$RHIPPER_SKILL_ID/$file"
    return RhipperSkillInstaller::class.java.classLoader.getResourceAsStream(path)?.use { it.readAllBytes() }
           ?: throw IOException("The IDE has no resource $path")
  }

  private fun readState(dir: Path): Map<String, String> {
    val file = dir.resolve(STATE_FILE).takeIf { it.isRegularFile() } ?: return emptyMap()
    return Files.readAllLines(file).mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
