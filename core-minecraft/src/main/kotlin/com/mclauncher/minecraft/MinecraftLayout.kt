package com.mclauncher.minecraft

import java.io.File

class MinecraftLayout(val root: File) {
    val metadataDirectory = File(root, "metadata")
    val versionsDirectory = File(root, "versions")
    val librariesDirectory = File(root, "libraries")
    val assetsDirectory = File(root, "assets")
    val indexesDirectory = File(assetsDirectory, "indexes")
    val objectsDirectory = File(assetsDirectory, "objects")
    val virtualLegacyDirectory = File(assetsDirectory, "virtual/legacy")
    val loggingDirectory = File(assetsDirectory, "log_configs")
    val nativesDirectory = File(root, "natives")
    val instancesDirectory = File(root, "instances")
    val runtimesDirectory = File(root, "runtimes")
    val launchPlansDirectory = File(root, "launch-plans")
    val engineDirectory = File(root, "engine")
    val engineJarsDirectory = File(engineDirectory, "jars")
    val engineManifestFile = File(engineDirectory, "engine-manifest.json")

    fun ensureBaseDirectories() {
        listOf(
            metadataDirectory,
            versionsDirectory,
            librariesDirectory,
            indexesDirectory,
            objectsDirectory,
            virtualLegacyDirectory,
            loggingDirectory,
            nativesDirectory,
            instancesDirectory,
            runtimesDirectory,
            launchPlansDirectory,
            engineJarsDirectory,
            File(engineDirectory, "renderers"),
            File(engineDirectory, "drivers")
        ).forEach(File::mkdirs)
    }

    fun versionDirectory(versionId: String) = File(versionsDirectory, versionId)
    fun versionJson(versionId: String) = File(versionDirectory(versionId), "$versionId.json")
    fun clientJar(versionId: String) = File(versionDirectory(versionId), "$versionId.jar")
    fun assetIndex(indexId: String) = File(indexesDirectory, "$indexId.json")
    fun assetObject(hash: String) = File(File(objectsDirectory, hash.take(2)), hash)
    fun library(relativePath: String) = File(librariesDirectory, relativePath)
    fun loggingConfig(fileName: String) = File(loggingDirectory, fileName)
    fun instanceGameDirectory(directoryName: String) = File(instancesDirectory, directoryName)
    fun launchPlan(instanceId: String) = File(launchPlansDirectory, "$instanceId.json")
    fun nativesFor(instanceId: String) = File(nativesDirectory, instanceId)
    fun runtimeHome(javaMajor: Int, architecture: String) =
        File(runtimesDirectory, "java-$javaMajor-$architecture")
    fun engineNativeDirectory(architecture: String) = File(engineDirectory, "natives/$architecture")
    fun engineRendererRootDirectory(architecture: String) = File(engineDirectory, "renderers/$architecture")
    fun engineRendererDirectory(architecture: String) = engineRendererRootDirectory(architecture)
    fun engineRendererDirectory(architecture: String, rendererId: String) =
        File(engineRendererRootDirectory(architecture), rendererId)
    fun engineDriverRootDirectory(architecture: String) = File(engineDirectory, "drivers/$architecture")
    fun engineDriverDirectory(architecture: String, driverId: String) =
        File(engineDriverRootDirectory(architecture), driverId)
}
