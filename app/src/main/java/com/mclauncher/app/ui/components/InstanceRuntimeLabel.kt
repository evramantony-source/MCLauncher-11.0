package com.mclauncher.app.ui.components

import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader

fun MinecraftInstance.runtimeLabel(includeJava: Boolean = false): String = buildString {
    val gameVersion = when (loader) {
        ModLoader.FABRIC -> loaderVersion?.let { version ->
            versionId.removePrefix("fabric-loader-$version-")
        } ?: versionId
        ModLoader.QUILT -> loaderVersion?.let { version ->
            versionId.removePrefix("quilt-loader-$version-")
        } ?: versionId
        else -> versionId
            .substringBefore("-forge")
            .substringBefore("-neoforge")
    }
    if (loader == ModLoader.VANILLA) {
        append("Vanilla • Minecraft ").append(gameVersion)
    } else {
        append(loader.displayName)
        loaderVersion?.let { append(' ').append(it) }
        append(" • Minecraft ").append(gameVersion)
    }
    if (includeJava) append(" • Java ").append(javaVersion.major)
}
