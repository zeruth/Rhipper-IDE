// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.pipeline

import com.intellij.icons.AllIcons
import com.rhipper.ide.RhipperBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey
import javax.swing.Icon

/** A step of the Rhipper pipeline, in the order the pipeline runs them. The [id] is the name `pipeline32.py` uses. */
enum class RhipperStep(
  val id: String,
  @param:PropertyKey(resourceBundle = "messages.RhipperBundle") private val nameKey: String,
  @param:PropertyKey(resourceBundle = "messages.RhipperBundle") private val descriptionKey: String,
  val icon: Icon,
) {
  FACTS("facts", "step.facts", "step.facts.description", AllIcons.Actions.Find),
  GHIDRA("ghidra", "step.ghidra", "step.ghidra.description", AllIcons.Actions.Compile),
  TRANSLATE("translate", "step.translate", "step.translate.description", AllIcons.Actions.Refresh),
  BUILD("build", "step.build", "step.build.description", AllIcons.Actions.BuildLoadChanges),
  RUN("run", "step.run", "step.run.description", AllIcons.Actions.Execute);

  val displayName: @Nls String get() = RhipperBundle.message(nameKey)
  val description: @Nls String get() = RhipperBundle.message(descriptionKey)

  /** The log that the pipeline writes for this step, relative to `build/pipeline`. */
  val logName: String get() = "$id.log"

  companion object {
    fun of(id: String?): RhipperStep? = entries.firstOrNull { it.id == id }
  }
}

/** Which steps a pipeline run includes. */
enum class RhipperStepScope {
  /** The one step. */
  ONLY,

  /** The step and every step after it. */
  FROM,
}
