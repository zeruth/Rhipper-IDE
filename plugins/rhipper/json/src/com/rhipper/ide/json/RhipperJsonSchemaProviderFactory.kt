// Copyright 2026 the Rhipper contributors. Use of this source code is governed by the Apache 2.0 license.
package com.rhipper.ide.json

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.jsonSchema.extension.JsonSchemaFileProvider
import com.jetbrains.jsonSchema.extension.JsonSchemaProviderFactory
import com.jetbrains.jsonSchema.extension.SchemaType
import com.jetbrains.jsonSchema.impl.JsonSchemaVersion
import com.rhipper.ide.project.RHIPPER_JSON

/** Gives every `rhipper.json` its schema: completion, validation and documentation of the settings. */
internal class RhipperJsonSchemaProviderFactory : JsonSchemaProviderFactory {
  override fun getProviders(project: Project): List<JsonSchemaFileProvider> = listOf(RhipperJsonSchemaFileProvider)
}

private object RhipperJsonSchemaFileProvider : JsonSchemaFileProvider {
  override fun isAvailable(file: VirtualFile): Boolean = file.name == RHIPPER_JSON

  override fun getName(): String = "rhipper.json"

  override fun getSchemaFile(): VirtualFile? {
    return JsonSchemaProviderFactory.getResourceFile(RhipperJsonSchemaProviderFactory::class.java, "/jsonSchemas/rhipper.schema.json")
  }

  override fun getSchemaType(): SchemaType = SchemaType.embeddedSchema

  override fun getSchemaVersion(): JsonSchemaVersion = JsonSchemaVersion.SCHEMA_7
}
