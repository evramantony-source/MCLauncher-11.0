package com.mclauncher.app.engine

import com.mclauncher.model.JavaVersion

data class RuntimeStatus(
    val version: JavaVersion,
    val architecture: String,
    val installed: Boolean,
    val javaHome: String,
    val detail: String
)

data class EnginePackStatus(
    val installed: Boolean,
    val architecture: String,
    val jarCount: Int,
    val nativeCount: Int,
    val rendererCount: Int,
    val detail: String
)

data class EngineEnvironmentState(
    val nativeBridgeAvailable: Boolean,
    val nativeBridgeDetail: String,
    val runtimes: List<RuntimeStatus>,
    val enginePack: EnginePackStatus,
    val renderers: List<RendererStatus> = emptyList(),
    val drivers: List<DriverStatus> = emptyList()
)


data class LauncherBundleImportResult(
    val installedJavaVersions: List<JavaVersion>,
    val missingJavaVersions: List<JavaVersion>,
    val environment: EngineEnvironmentState
)
