package com.mclauncher.app.engine

import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.Renderer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class GraphicsPackKind {
    @SerialName("renderer") RENDERER,
    @SerialName("driver") DRIVER
}

/**
 * Manifest accepted at the root of an imported renderer/driver archive.
 * Libraries may also be discovered automatically when this file is omitted.
 */
@Serializable
data class GraphicsPackManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String = "unknown",
    val kind: GraphicsPackKind,
    val architecture: String? = null,
    val renderer: Renderer? = null,
    val driver: GraphicsDriver? = null,
    val pojavRenderer: String? = null,
    val preload: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val sourceProject: String? = null,
    val license: String? = null
)

@Serializable
data class InstalledGraphicsPackManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    val kind: GraphicsPackKind,
    val architecture: String,
    val renderer: Renderer? = null,
    val driver: GraphicsDriver? = null,
    val pojavRenderer: String? = null,
    val preload: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val files: List<String>,
    val sourceName: String,
    val sourceProject: String? = null,
    val license: String? = null,
    val importedAtEpochMs: Long
)

@Serializable
data class RendererStatus(
    val renderer: Renderer,
    val installed: Boolean,
    val libraryCount: Int,
    val detail: String
)

@Serializable
data class DriverStatus(
    val driver: GraphicsDriver,
    val installed: Boolean,
    val libraryCount: Int,
    val detail: String
)

data class ResolvedGraphicsStack(
    val renderer: Renderer,
    val driver: GraphicsDriver,
    val pojavRenderer: String,
    val searchDirectories: List<File>,
    val preloadTokens: List<String>,
    val environment: Map<String, String>
)
