package com.mclauncher.app.engine

import java.io.File

data class CrashDiagnosis(val title: String, val advice: String)

object CrashAnalyzer {
    fun analyze(file: File?): CrashDiagnosis? {
        if (file == null || !file.isFile) return null
        val text = runCatching { file.readText().takeLast(500_000) }.getOrNull()?.lowercase() ?: return null
        val lines = text.lineSequence().toList()
        val hasVulkanFailure = lines.any { line ->
            "vulkan" in line && listOf("failed", "error", "fatal", "unsupported").any(line::contains)
        }
        return when {
            "a minecraft launch is already running in this process" in text ->
                CrashDiagnosis("Duplicate launch blocked", "Two game screens requested startup together. Close the game screen before retrying.")
            "permission denied" in text && "trying to exec" in text && "/bin/java" in text ->
                CrashDiagnosis("Old JVM launcher blocked", "This build tried to execute Java from Android app storage. Update MCLauncher to the in-process JVM build.")
            "outofmemoryerror" in text || "could not reserve enough space" in text ->
                CrashDiagnosis("Not enough memory", "Lower allocated RAM, close other apps, or use a lighter modpack.")
            "unsatisfiedlinkerror" in text || "no lwjgl" in text || "could not load library" in text ->
                CrashDiagnosis("Native library problem", "Reinstall the matching engine pack and renderer for your device ABI.")
            "glfw error" in text || "opengl" in text && "not supported" in text ->
                CrashDiagnosis("Renderer incompatibility", "Try MobileGlues, ANGLE, Zink, GL4ES or another installed renderer/driver combination.")
            hasVulkanFailure ->
                CrashDiagnosis("Vulkan driver problem", "Try the system driver, a compatible Turnip/PanVK pack, or an OpenGL ES renderer.")
            "unsupportedclassversionerror" in text || "class file version" in text ->
                CrashDiagnosis("Wrong Java version", "Select the Java version required by this Minecraft or mod-loader version.")
            "mixin apply failed" in text || "mod resolution encountered" in text || "incompatible mod set" in text ->
                CrashDiagnosis("Mod conflict", "Disable the most recently installed mod and verify loader/game-version compatibility.")
            "authentication" in text || "invalid token" in text ->
                CrashDiagnosis("Account session expired", "Select the account again or repeat Microsoft sign-in.")
            "exception" in text || "fatal" in text ->
                CrashDiagnosis("Minecraft crashed", "Share this log for detailed diagnosis; the final exception is shown below.")
            else -> null
        }
    }
}
