// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.newproject

import com.intellij.icons.AllIcons
import com.intellij.ide.impl.OpenProjectTask
import com.intellij.ide.impl.ProjectUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.transform
import com.intellij.openapi.util.text.HtmlBuilder
import com.intellij.openapi.util.text.HtmlChunk
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.rhipper.ide.RhipperBundle
import com.rhipper.ide.project.RHIPPER_JSON
import com.rhipper.ide.project.RhipperArch
import com.rhipper.ide.project.formatAddress
import com.rhipper.ide.settings.RhipperSettings
import com.rhipper.ide.tools.RhipperTool
import com.rhipper.ide.tools.RhipperTools
import com.rhipper.ide.tools.RhipperToolsInstaller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.swing.JComponent
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

/** Creates a Rhipper project: a directory with a `rhipper.json` for a reference binary, and the source tree of its translation. */
internal class RhipperNewProjectAction : DumbAwareAction() {
  override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

  override fun actionPerformed(e: AnActionEvent) {
    val dialog = RhipperNewProjectDialog(e.project)
    if (!dialog.showAndGet()) return
    val request = dialog.request()
    val projectToClose = e.project.takeIf { request.closeCurrent }
    service<RhipperNewProjectScope>().coroutineScope.launch {
      val root = withContext(Dispatchers.IO) { createProject(request) }
      ProjectUtil.openOrImportAsync(root, OpenProjectTask { this.projectToClose = projectToClose })
    }
  }
}

@Service(Service.Level.APP)
internal class RhipperNewProjectScope(val coroutineScope: CoroutineScope)

/** What the new project dialog asks for. */
internal data class RhipperNewProjectRequest(
  val location: Path,
  val name: String,
  val exe: Path,
  val copyExe: Boolean,
  val arch: RhipperArch,
  val imageBase: String,
  val gen: String,
  val rhipperHome: String,
  val ghidraInstall: String,
  val ghidraProjects: String,
  val closeCurrent: Boolean,
  /** The headers of [exe], or `null` when it is no PE file that the dialog could read. */
  val header: PeHeader? = null,
)

internal class RhipperNewProjectDialog(project: Project?) : DialogWrapper(project, true) {
  private val graph = PropertyGraph()
  private val location = graph.property("")
  private val name = graph.property("")
  private val exe = graph.property("")
  private val copyExe = graph.property(true)
  private val arch = graph.property(RhipperArch.X86_64.id)
  private val imageBase = graph.property("0x140000000")
  private val gen = graph.property("src/gen")
  // the tools that the IDE found or installed: a first-time user then has nothing to fill in
  private val rhipperHome = graph.property("")
  private val ghidraInstall = graph.property("")
  private val ghidraProjects = graph.property("")
  private val missingTools = graph.property("")
  private val installing = graph.property(false)
  private val analysis = graph.property(RhipperBundle.message("new.project.analysis.none"))
  private var header: PeHeader? = null
  private val closeCurrent = graph.property(false)
  private val hasProject = project != null

  init {
    title = RhipperBundle.message("new.project.title")
    fillTools()
    exe.afterChange { path ->
      header = runCatching { readPeHeader(Path.of(path)) }.getOrNull()
      val header = header
      if (header == null) {
        analysis.set(RhipperBundle.message(if (path.isBlank()) "new.project.analysis.none" else "new.project.analysis.not.pe"))
        return@afterChange
      }
      arch.set(header.arch.id)
      imageBase.set(formatAddress(header.imageBase))
      analysis.set(describe(header))
      if (name.get().isBlank()) name.set(Path.of(path).nameWithoutExtension)
    }
    location.afterChange { path ->
      if (name.get().isBlank()) runCatching { Path.of(path).name }.getOrNull()?.let(name::set)
    }
    init()
  }

  /** Fills the empty tool fields with the tools that the IDE knows, and lists the tools that it can install. */
  private fun fillTools() {
    val settings = RhipperSettings.getInstance()
    if (rhipperHome.get().isBlank()) rhipperHome.set(settings.rhipperHome(null)?.toString().orEmpty())
    if (ghidraInstall.get().isBlank()) ghidraInstall.set(settings.ghidraInstall()?.toString().orEmpty())
    if (ghidraProjects.get().isBlank()) ghidraProjects.set(settings.ghidraProjects()?.toString().orEmpty())
    missingTools.set(missingInstallableTools().joinToString { it.displayName })
  }

  private fun missingInstallableTools(): List<RhipperTool> {
    val tools = RhipperTools.getInstance()
    return buildList {
      if (rhipperHome.get().isBlank()) add(RhipperTool.RHIPPER)
      if (tools.managedPython == null && RhipperSettings.getInstance().state.python.isNullOrBlank()) add(RhipperTool.PYTHON)
      if (ghidraInstall.get().isBlank()) add(RhipperTool.GHIDRA)
    }
  }

  private fun installMissingTools() {
    val tools = missingInstallableTools()
    if (tools.isEmpty()) return
    installing.set(true)
    RhipperToolsInstaller.getInstance().install(null, tools) {
      installing.set(false)
      fillTools()
    }
  }

  override fun createCenterPanel(): JComponent = panel {
    group(RhipperBundle.message("new.project.group.project")) {
      row(RhipperBundle.message("new.project.location")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor()).bindText(location).align(AlignX.FILL)
      }
      row(RhipperBundle.message("new.project.name")) {
        textField().bindText(name).align(AlignX.FILL)
      }
    }
    group(RhipperBundle.message("new.project.group.reference")) {
      row(RhipperBundle.message("new.project.exe")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor())
          .bindText(exe)
          .align(AlignX.FILL)
          .comment(RhipperBundle.message("new.project.exe.comment"))
      }
      row {
        checkBox(RhipperBundle.message("new.project.copy.exe")).bindSelected(copyExe)
      }
      row(RhipperBundle.message("new.project.arch")) {
        comboBox(RhipperArch.entries.map { it.id }).bindItem(arch)
      }
      row(RhipperBundle.message("new.project.image.base")) {
        textField().bindText(imageBase)
      }
    }
    group(RhipperBundle.message("new.project.group.analysis")) {
      row {
        label("").bindText(analysis).align(AlignX.FILL)
      }
    }
    group(RhipperBundle.message("new.project.group.output")) {
      row(RhipperBundle.message("new.project.gen")) {
        textField().bindText(gen).comment(RhipperBundle.message("new.project.gen.comment"))
      }
    }
    group(RhipperBundle.message("new.project.group.tools")) {
      row {
        icon(AllIcons.General.Warning)
        label("").bindText(missingTools.transform { RhipperBundle.message("new.project.tools.missing", it) })
        button(RhipperBundle.message("tools.install.all")) { installMissingTools() }.enabledIf(installing.transform { !it })
        label(RhipperBundle.message("new.project.tools.installing")).visibleIf(installing)
      }.visibleIf(missingTools.transform { it.isNotEmpty() })
      row(RhipperBundle.message("settings.rhipper.home")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor()).bindText(rhipperHome).align(AlignX.FILL)
      }
      row(RhipperBundle.message("new.project.ghidra.install")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor()).bindText(ghidraInstall).align(AlignX.FILL)
      }
      row(RhipperBundle.message("new.project.ghidra.projects")) {
        textFieldWithBrowseButton(FileChooserDescriptorFactory.createSingleFolderDescriptor()).bindText(ghidraProjects).align(AlignX.FILL)
      }
    }
    if (hasProject) {
      row {
        checkBox(RhipperBundle.message("new.project.close.current")).bindSelected(closeCurrent)
      }
    }
  }

  override fun doValidateAll(): List<ValidationInfo> {
    val problems = ArrayList<ValidationInfo>()
    if (location.get().isBlank()) problems.add(ValidationInfo(RhipperBundle.message("new.project.error.location")))
    else if (Files.isRegularFile(Path.of(location.get(), RHIPPER_JSON))) problems.add(ValidationInfo(RhipperBundle.message("new.project.error.exists")))
    if (exe.get().isBlank() || !Files.isRegularFile(Path.of(exe.get()))) problems.add(ValidationInfo(RhipperBundle.message("new.project.error.exe")))
    if (name.get().isBlank()) problems.add(ValidationInfo(RhipperBundle.message("new.project.error.name")))
    return problems
  }

  /** The analysis of the reference binary, as HTML for the dialog. */
  private fun describe(header: PeHeader): String {
    val entrySection = header.entrySection?.name ?: RhipperBundle.message("new.project.analysis.no.section")
    val rows = buildList {
      add(RhipperBundle.message("new.project.analysis.entry") to "${formatAddress(header.entryPoint)} ($entrySection)")
      add(RhipperBundle.message("new.project.analysis.subsystem") to header.subsystemName)
      add(RhipperBundle.message("new.project.analysis.linker") to header.linkerVersion)
      add(RhipperBundle.message("new.project.analysis.timestamp") to DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(header.timestamp))
      add(RhipperBundle.message("new.project.analysis.sections") to header.sections.joinToString(" ") { it.name.ifEmpty { "?" } })
      add(RhipperBundle.message("new.project.analysis.imports") to
            RhipperBundle.message("new.project.analysis.imports.value", header.imports.size, header.imports.take(8).joinToString(", ")) +
            if (header.imports.size > 8) ", …" else "")
      add(RhipperBundle.message("new.project.analysis.pdb") to (header.pdb ?: RhipperBundle.message("new.project.analysis.pdb.none")))
    }
    return HtmlBuilder().apply {
      val table = rows.map { (key, value) ->
        HtmlChunk.tag("tr").children(HtmlChunk.tag("td").addText(key).wrapWith("b"), HtmlChunk.tag("td").addText(value))
      }
      append(HtmlChunk.tag("table").children(table))
      if (header.entryLooksPacked) append(HtmlChunk.text(RhipperBundle.message("new.project.analysis.packed")).wrapWith("b"))
    }.wrapWithHtmlBody().toString()
  }

  fun request(): RhipperNewProjectRequest = RhipperNewProjectRequest(
    location = Path.of(location.get()),
    name = name.get().trim(),
    exe = Path.of(exe.get()),
    copyExe = copyExe.get(),
    arch = RhipperArch.of(arch.get()),
    imageBase = imageBase.get().trim(),
    gen = gen.get().trim().ifEmpty { "src/gen" },
    rhipperHome = rhipperHome.get().trim(),
    ghidraInstall = ghidraInstall.get().trim(),
    ghidraProjects = ghidraProjects.get().trim(),
    closeCurrent = closeCurrent.get(),
    header = header,
  )
}

private val PRETTY_JSON = Json { prettyPrint = true; prettyPrintIndent = "  " }

/** Writes the project directory: the `rhipper.json`, the source tree and, when asked, a copy of the reference binary. Returns the root. */
internal fun createProject(request: RhipperNewProjectRequest): Path {
  val root = request.location
  Files.createDirectories(root)
  val exe = if (request.copyExe) {
    val target = root.resolve(".reference").resolve(request.exe.name)
    Files.createDirectories(target.parent)
    if (request.exe.toAbsolutePath() != target.toAbsolutePath()) Files.copy(request.exe, target, StandardCopyOption.REPLACE_EXISTING)
    target
  }
  else {
    request.exe
  }
  Files.createDirectories(root.resolve(request.gen))
  Files.createDirectories(root.resolve("data"))

  val settings = RhipperSettings.getInstance()
  if (settings.state.rhipperHome.isNullOrBlank() && request.rhipperHome.isNotBlank()) settings.state.rhipperHome = request.rhipperHome
  Files.writeString(root.resolve(RHIPPER_JSON), PRETTY_JSON.encodeToString(JsonObject.serializer(), rhipperJson(request, root, exe, settings)) + "\n")
  return root
}

private fun rhipperJson(request: RhipperNewProjectRequest, root: Path, exe: Path, settings: RhipperSettings): JsonObject = buildJsonObject {
  put("name", request.name)
  if (request.rhipperHome.isNotBlank() && request.rhipperHome != settings.state.rhipperHome) put("rhipper", request.rhipperHome.replace('\\', '/'))
  put("exe", relativeOrAbsolute(root, exe))
  put("arch", request.arch.id)
  put("image_base", request.imageBase)
  request.header?.let { put("entry", formatAddress(it.entryPoint)) }
  put("process", exe.name)
  putJsonArray("sources") { add("src") }
  put("gen", request.gen)
  putJsonObject("build") {
    put("dir", "build/cmake")
    put("config", "Release")
    put("gen_target", "gen")
    put("target", request.name)
  }
  putJsonObject("run") {
    put("exe", "build/cmake/bin/Release/${request.name}.exe")
    put("cwd", relativeOrAbsolute(root, exe.parent))
  }
  putJsonObject("ghidra") {
    if (request.ghidraInstall.isNotBlank()) put("install", request.ghidraInstall.replace('\\', '/'))
    if (request.ghidraProjects.isNotBlank()) put("projects", request.ghidraProjects.replace('\\', '/'))
    put("project", request.name)
    put("program", exe.name)
    put("maxmem", ghidraMaxMemory())
  }
}

/** Half the memory of the machine for Ghidra's analysis, from 4 GB to the 14 GB of Rhipper's default. */
private fun ghidraMaxMemory(): String {
  val total = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
  val gigabytes = (total / 2 / (1L shl 30)).coerceIn(4, 14)
  return "${gigabytes}G"
}

private fun relativeOrAbsolute(root: Path, path: Path): String {
  val absolute = path.toAbsolutePath().normalize()
  val base = root.toAbsolutePath().normalize()
  val text = if (absolute.startsWith(base)) base.relativize(absolute).toString().ifEmpty { "." } else absolute.toString()
  return text.replace('\\', '/')
}
