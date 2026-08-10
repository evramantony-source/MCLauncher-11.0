package com.mclauncher.app.creation

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.ModLoader

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
