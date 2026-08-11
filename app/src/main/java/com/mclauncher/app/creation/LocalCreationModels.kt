package com.mclauncher.app.creation

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.ModLoader
import kotlinx.serialization.Serializable

enum class LocalProjectOutput(val label: String, val extension: String) {
    MOD_JAR("Mod JAR", "jar"),
    SHADER_ZIP("Shader pack ZIP", "zip")
}

enum class AttachmentKind(val label: String) {
    IMAGE("Image"),
    MOD_JAR("Mod JAR"),
    SOURCE_ARCHIVE("Source archive"),
    LOG("Log or text"),
    OTHER("File")
}

data class LocalAttachment(
    val id: String,
    val displayName: String,
    val kind: AttachmentKind,
    val sizeBytes: Long,
    val localPath: String,
    val analysis: String
)

data class LocalBuildTarget(
    val instanceId: String,
    val instanceName: String,
    val gameDirectoryName: String,
    val minecraftVersion: String,
    val loader: ModLoader,
    val loaderVersion: String,
    val javaVersion: JavaVersion
)

data class LocalCreationResult(
    val outputPath: String,
    val projectPath: String,
    val installedPath: String? = null,
    val summary: String
)

data class CodeWorkspaceProject(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val loader: ModLoader,
    val loaderVersion: String
)

data class CodeWorkspaceFile(
    val path: String,
    val sizeBytes: Long
)

data class CodeBuildResult(
    val outputPath: String,
    val installedPath: String?,
    val logPath: String?
)

@Serializable
data class LocalBuildStatus(
    val finished: Boolean = false,
    val exitCode: Int? = null,
    val error: String? = null,
    val logPath: String? = null
)
