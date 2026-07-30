package com.mclauncher.app.engine

import java.io.File

data class CrashDiagnosis(val title: String, val advice: String)

object CrashAnalyzer {
    fun analyze(file: File?): CrashDiagnosis? {
        if (file == null || !file.isFile) return null
        val text = runCatching { file.readText().takeLast(500_000) }.getOrNull() ?: return null
        return analyzeText(text)
    }

    internal fun analyzeText(content: String): CrashDiagnosis? {
        val text = content.lowercase()
        val lines = text.lineSequence().toList()
        val hasVulkanFailure = lines.any { line ->
            "vulkan" in line && listOf("failed", "error", "fatal", "unsupported").any(line::contains)
        }
        val hasOpenGlFailure = lines.any { line ->
            ("glfw error" in line || "opengl" in line) &&
                listOf("failed", "error", "fatal", "unsupported", "not supported").any(line::contains)
        }
        return when {
            "a minecraft launch is already running in this process" in text ->
                CrashDiagnosis("Duplicate launch blocked", "Two game screens requested startup together. Close the game screen before retrying.")
            "can't load library" in text && "libawt_xawt.so" in text ->
                CrashDiagnosis("AWT compatibility missing", "Update MCLauncher so it can repair the Java runtime's Android AWT overlay.")
            "pointer tag for" in text && "was truncated" in text ->
                CrashDiagnosis("Native pointer-tag conflict", "Update MCLauncher to a build that disables Android heap pointer tagging for the embedded desktop-native stack.")
            "permission denied" in text && "trying to exec" in text && "/bin/java" in text ->
                CrashDiagnosis("Old JVM launcher blocked", "This build tried to execute Java from Android app storage. Update MCLauncher to the in-process JVM build.")
            "outofmemoryerror" in text || "could not reserve enough space" in text ->
                CrashDiagnosis("Not enough memory", "Lower allocated RAM, close other apps, or use a lighter modpack.")
            ("renderer=openltw" in text || "libltw.so" in text) &&
                ("gl11c.nglgetfloatv" in text || "glgetfloatv" in text) ->
                CrashDiagnosis(
                    "OpenLTW needs the 26.2 compatibility build",
                    "Repair the bundled engine after updating MCLauncher. The older LTW package does not export the OpenGL query used by Minecraft 26.2."
                )
            "no context is current or a function that is not available" in text ||
                "nglgensamplers" in text ->
                CrashDiagnosis(
                    "OpenGL renderer too old",
                    "Use the bundled MobileGlues renderer; this Minecraft version needs modern OpenGL functions."
                )
            "incompatible jna native library" in text ->
                CrashDiagnosis(
                    "JNA native mismatch",
                    "Update MCLauncher so it can select the Android JNA native matching this Minecraft version."
                )
            "no android opengl library matched" in text ->
                CrashDiagnosis(
                    "Renderer package metadata mismatch",
                    "Update MCLauncher and reinstall the selected renderer so its native library and renderer identity are repaired."
                )
            "sodium" in text &&
                ("it appears that you are using pojavlauncher" in text ||
                    "sodium-postlaunchchecks" in text && "pojav_renderer" in text) ->
                CrashDiagnosis(
                    "Sodium blocked the Android launch",
                    "This desktop Sodium build rejected the Android renderer marker. Update MCLauncher or disable Sodium and Iris if this still appears."
                )
            "unsatisfiedlinkerror" in text || "no lwjgl" in text || "could not load library" in text ->
                CrashDiagnosis("Native library problem", "Reinstall the matching engine pack and renderer for your device ABI.")
            hasOpenGlFailure ->
                CrashDiagnosis("Renderer incompatibility", "Try MobileGlues, ANGLE, Zink, GL4ES or another installed renderer/driver combination.")
            hasVulkanFailure ->
                CrashDiagnosis("Vulkan driver problem", "Try the system driver, a compatible Turnip/PanVK pack, or an OpenGL ES renderer.")
            "unsupportedclassversionerror" in text || "class file version" in text ->
                CrashDiagnosis("Wrong Java version", "Select the Java version required by this Minecraft or mod-loader version.")
            "mixin apply failed" in text || "mod resolution encountered" in text || "incompatible mod set" in text ->
                CrashDiagnosis("Mod conflict", "Disable the most recently installed mod and verify loader/game-version compatibility.")
            "invalid token" in text ||
                "invalid session" in text ||
                "session expired" in text ||
                "expired access token" in text ||
                ("http 401" in text && ("authlib" in text || "minecraftservices" in text)) ->
                CrashDiagnosis("Account session expired", "Select the account again or repeat Microsoft sign-in.")
            "exception" in text || "fatal" in text ->
                CrashDiagnosis("Minecraft crashed", "Share this log for detailed diagnosis; the final exception is shown below.")
            else -> null
        }
    }
}
