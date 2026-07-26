package com.mclauncher.model

import kotlinx.serialization.Serializable

@Serializable
enum class InstallStage {
    IDLE,
    VERSION_METADATA,
    CLIENT,
    LIBRARIES,
    LOGGING_CONFIG,
    ASSET_INDEX,
    ASSETS,
    FINISHED,
    FAILED
}

@Serializable
data class InstallProgress(
    val stage: InstallStage = InstallStage.IDLE,
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
    val currentFile: String = "",
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val message: String = ""
) {
    val fraction: Float
        get() = when {
            totalFiles <= 0 -> 0f
            else -> (completedFiles.toFloat() / totalFiles.toFloat()).coerceIn(0f, 1f)
        }
}
