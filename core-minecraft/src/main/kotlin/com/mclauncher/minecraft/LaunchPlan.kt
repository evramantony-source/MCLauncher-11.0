package com.mclauncher.minecraft

import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.MinecraftGraphicsApi
import com.mclauncher.model.Renderer
import kotlinx.serialization.Serializable

@Serializable
data class LaunchPlan(
    val instanceId: String,
    val versionId: String,
    /**
     * The inherited base game version (for example 26.3-snapshot-6 for a
     * fabric-loader-0.19.3-26.3-snapshot-6 instance). Version capability gates
     * must use this instead of parsing numbers out of a loader profile ID.
     * Older saved plans do not carry it; callers fall back to [versionId].
     */
    val baseVersionId: String = "",
    val runtime: RuntimeSelection,
    val workingDirectory: String,
    val nativesDirectory: String,
    val classpath: List<String>,
    val jvmArguments: List<String>,
    val mainClass: String,
    val gameArguments: List<String>,
    val environment: Map<String, String>,
    val renderer: Renderer,
    val graphicsDriver: GraphicsDriver = GraphicsDriver.AUTO,
    val minecraftGraphicsApi: MinecraftGraphicsApi = MinecraftGraphicsApi.DEFAULT,
    val windowWidth: Int = 1280,
    val windowHeight: Int = 720,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

@Serializable
data class RuntimeSelection(
    val javaVersion: JavaVersion,
    val javaHome: String,
    val javaBinary: String,
    val architecture: String
)
