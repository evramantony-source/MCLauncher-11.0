package com.mclauncher.minecraft

import com.mclauncher.model.JavaVersion
import java.io.File

/**
 * Validates unpacked Android runtime packages without assuming a specific vendor.
 * A future RuntimeInstaller will populate the same directory layout.
 */
class RuntimeInspector(private val layout: MinecraftLayout) {
    fun inspect(javaVersion: JavaVersion, architecture: String = "arm64-v8a"): RuntimeStatus {
        val home = layout.runtimeHome(javaVersion.major, architecture)
        val javaBinary = File(home, "bin/java")
        val serverJvmCandidates = listOf(
            File(home, "lib/server/libjvm.so"),
            File(home, "lib/${architecture}/server/libjvm.so"),
            File(home, "jre/lib/${architecture}/server/libjvm.so")
        )
        val missing = buildList {
            if (!javaBinary.isFile) add(javaBinary.absolutePath)
            if (serverJvmCandidates.none(File::isFile)) add("libjvm.so")
        }
        return RuntimeStatus(
            javaVersion = javaVersion,
            home = home,
            ready = missing.isEmpty(),
            missing = missing
        )
    }
}

data class RuntimeStatus(
    val javaVersion: JavaVersion,
    val home: File,
    val ready: Boolean,
    val missing: List<String>
)
