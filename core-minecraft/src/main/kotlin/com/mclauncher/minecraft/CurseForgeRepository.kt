package com.mclauncher.minecraft

import com.mclauncher.model.ContentType
import com.mclauncher.model.ContentSource
import com.mclauncher.model.InstallProgress
import com.mclauncher.model.InstallStage
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

class CurseForgeRepository(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
) {
    private val contentIndex = ContentIndexStore(layout, json)

    suspend fun search(
        apiKey: String,
        query: String,
        contentType: ContentType,
        gameVersion: String? = null,
        loader: ModLoader? = null,
        index: Int = 0,
        pageSize: Int = 30
    ): CurseForgeSearchResponse {
        requireApiKey(apiKey)
        val classId = when (contentType) {
            ContentType.MOD -> 6
            ContentType.MODPACK -> 4471
            ContentType.RESOURCE_PACK -> 12
            ContentType.SHADER -> 6552
        }
        val modLoaderType = when (loader) {
            ModLoader.FORGE -> 1
            ModLoader.FABRIC -> 4
            ModLoader.QUILT -> 5
            ModLoader.NEOFORGE -> 6
            else -> null
        }
        val url = buildString {
            append("https://api.curseforge.com/v1/mods/search?gameId=432")
            append("&classId=").append(classId)
            append("&index=").append(index.coerceAtLeast(0))
            append("&pageSize=").append(pageSize.coerceIn(1, 50))
            append("&sortField=2&sortOrder=desc")
            if (query.isNotBlank()) append("&searchFilter=").append(HttpDownloader.encode(query))
            if (!gameVersion.isNullOrBlank()) append("&gameVersion=").append(HttpDownloader.encode(gameVersion))
            if (modLoaderType != null) append("&modLoaderType=").append(modLoaderType)
        }
        return json.decodeFromString(downloader.readText(url, headers(apiKey)))
    }

    suspend fun mod(apiKey: String, modId: Int): CurseForgeMod {
        requireApiKey(apiKey)
        return json.decodeFromString<CurseForgeModResponse>(
            downloader.readText("https://api.curseforge.com/v1/mods/$modId", headers(apiKey))
        ).data
    }

    suspend fun file(apiKey: String, modId: Int, fileId: Int): CurseForgeFile {
        requireApiKey(apiKey)
        return json.decodeFromString<CurseForgeFileResponse>(
            downloader.readText("https://api.curseforge.com/v1/mods/$modId/files/$fileId", headers(apiKey))
        ).data
    }

    suspend fun files(apiKey: String, modId: Int, gameVersion: String? = null): List<CurseForgeFile> {
        requireApiKey(apiKey)
        val versionQuery = gameVersion?.takeIf(String::isNotBlank)
            ?.let { "&gameVersion=${HttpDownloader.encode(it)}" }
            .orEmpty()
        return json.decodeFromString<CurseForgeFilesResponse>(
            downloader.readText(
                "https://api.curseforge.com/v1/mods/$modId/files?pageSize=50$versionQuery",
                headers(apiKey)
            )
        ).data
    }

    suspend fun install(
        apiKey: String,
        instance: MinecraftInstance,
        mod: CurseForgeMod,
        file: CurseForgeFile,
        contentType: ContentType,
        gameVersion: String,
        installDependencies: Boolean = true,
        onProgress: (InstallProgress) -> Unit = {}
    ): InstalledContent {
        requireApiKey(apiKey)
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        val folder = File(gameDir, contentType.folderName).apply { mkdirs() }
        val target = File(folder, file.fileName)
        downloadArchive(apiKey, mod, file, target, onProgress)

        if (contentType == ContentType.MODPACK) {
            installPack(apiKey, instance, target, onProgress)
        } else if (installDependencies) {
            installRequiredDependencies(
                apiKey = apiKey,
                instance = instance,
                parent = file,
                gameVersion = gameVersion,
                visited = mutableSetOf(mod.id),
                onProgress = onProgress
            )
        }

        val installed = installedRecord(instance, mod, file, contentType, gameVersion)
        updateIndex(gameDir) { current -> current.filterNot { it.projectId == installed.projectId } + installed }
        return installed
    }

    /** Installs a standards-compliant CurseForge profile ZIP selected from local storage. */
    suspend fun installPack(
        apiKey: String,
        instance: MinecraftInstance,
        archive: File,
        onProgress: (InstallProgress) -> Unit = {}
    ): CurseForgePackManifest = withContext(Dispatchers.IO) {
        onProgress(InstallProgress(stage = InstallStage.PACK_FILES, message = "Reading CurseForge manifest"))
        val manifest = CurseForgePackInstaller(layout, json).readManifest(instance, archive)
        val required = manifest.files.filter { it.required }
        if (required.isNotEmpty()) requireApiKey(apiKey)
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
        required.forEachIndexed { index, entry ->
            onProgress(
                InstallProgress(
                    stage = InstallStage.PACK_FILES,
                    completedFiles = index,
                    totalFiles = required.size,
                    message = "Installing pack file ${index + 1}/${required.size}"
                )
            )
            val childMod = mod(apiKey, entry.projectId)
            val childFile = file(apiKey, entry.projectId, entry.fileId)
            val type = contentType(childMod)
            val childTarget = File(gameDir, "${type.folderName}/${childFile.fileName}")
            downloadFile(apiKey, childMod.id, childFile, childTarget)
            val installed = installedRecord(
                instance = instance,
                mod = childMod,
                file = childFile,
                contentType = type,
                gameVersion = manifest.minecraft.version
            )
            updateIndex(gameDir) { current ->
                current.filterNot { it.projectId == installed.projectId } + installed
            }
        }
        onProgress(
            InstallProgress(
                stage = InstallStage.PACK_FILES,
                completedFiles = required.size,
                totalFiles = required.size,
                message = "${manifest.name} installed"
            )
        )
        manifest
    }

    suspend fun updateAvailable(
        apiKey: String,
        instance: MinecraftInstance,
        item: InstalledContent
    ): CurseForgeFile? {
        val modId = item.projectId.removePrefix("curseforge:").toIntOrNull() ?: return null
        return files(apiKey, modId, item.gameVersion).firstOrNull { it.id.toString() != item.versionId }
    }

    private suspend fun installRequiredDependencies(
        apiKey: String,
        instance: MinecraftInstance,
        parent: CurseForgeFile,
        gameVersion: String,
        visited: MutableSet<Int>,
        onProgress: (InstallProgress) -> Unit
    ) {
        val required = parent.dependencies.filter { it.relationType == REQUIRED_DEPENDENCY }
        required.forEachIndexed { index, dependency ->
            if (!visited.add(dependency.modId)) return@forEachIndexed
            onProgress(
                InstallProgress(
                    stage = InstallStage.CONTENT_DOWNLOAD,
                    completedFiles = index,
                    totalFiles = required.size,
                    message = "Installing CurseForge dependency ${index + 1}/${required.size}"
                )
            )
            val dependencyMod = mod(apiKey, dependency.modId)
            val dependencyFile = files(apiKey, dependency.modId, gameVersion).firstOrNull()
                ?: error("No compatible file for required dependency ${dependencyMod.name}")
            val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
            val type = contentType(dependencyMod)
            downloadFile(
                apiKey,
                dependencyMod.id,
                dependencyFile,
                File(gameDir, "${type.folderName}/${dependencyFile.fileName}")
            )
            val installed = installedRecord(instance, dependencyMod, dependencyFile, type, gameVersion)
            updateIndex(gameDir) { current ->
                current.filterNot { it.projectId == installed.projectId } + installed
            }
            installRequiredDependencies(apiKey, instance, dependencyFile, gameVersion, visited, onProgress)
        }
    }

    suspend fun downloadArchive(
        apiKey: String,
        mod: CurseForgeMod,
        file: CurseForgeFile,
        destination: File,
        onProgress: (InstallProgress) -> Unit = {}
    ): File {
        var downloadedBytes = 0L
        val total = file.fileLength.takeIf { it > 0L }
        onProgress(
            InstallProgress(
                stage = InstallStage.CONTENT_DOWNLOAD,
                totalFiles = 1,
                currentFile = file.fileName,
                totalBytes = total,
                message = "Downloading ${mod.name}"
            )
        )
        val result = downloadFile(apiKey, mod.id, file, destination) { delta ->
            downloadedBytes += delta
            onProgress(
                InstallProgress(
                    stage = InstallStage.CONTENT_DOWNLOAD,
                    totalFiles = 1,
                    currentFile = file.fileName,
                    downloadedBytes = downloadedBytes,
                    totalBytes = total,
                    message = "Downloading ${mod.name}"
                )
            )
        }
        if (downloadedBytes == 0L) downloadedBytes = result.length()
        onProgress(
            InstallProgress(
                stage = InstallStage.CONTENT_DOWNLOAD,
                completedFiles = 1,
                totalFiles = 1,
                currentFile = file.fileName,
                downloadedBytes = downloadedBytes,
                totalBytes = total ?: downloadedBytes,
                message = "Downloaded ${mod.name}"
            )
        )
        return result
    }

    private suspend fun downloadFile(
        apiKey: String,
        modId: Int,
        file: CurseForgeFile,
        destination: File,
        onBytes: ((Long) -> Unit)? = null
    ): File {
        val downloadUrl = file.downloadUrl ?: json.decodeFromString<CurseForgeDownloadUrlResponse>(
            downloader.readText(
                "https://api.curseforge.com/v1/mods/$modId/files/${file.id}/download-url",
                headers(apiKey)
            )
        ).data
        require(downloadUrl.isNotBlank()) {
            "CurseForge did not provide a distributable URL for ${file.fileName}. Download it manually and import it into the instance."
        }
        val sha1 = file.hashes.firstOrNull { it.algo == 1 }?.value
        return downloader.download(
            downloadUrl,
            destination,
            expectedSha1 = sha1,
            expectedSize = file.fileLength.takeIf { it > 0 },
            onBytes = onBytes
        )
    }

    private fun updateIndex(gameDir: File, transform: (List<InstalledContent>) -> List<InstalledContent>) {
        contentIndex.update(gameDir, transform)
    }

    private fun installedRecord(
        instance: MinecraftInstance,
        mod: CurseForgeMod,
        file: CurseForgeFile,
        contentType: ContentType,
        gameVersion: String
    ) = InstalledContent(
        projectId = "curseforge:${mod.id}",
        versionId = file.id.toString(),
        contentType = contentType,
        fileName = file.fileName,
        title = mod.name,
        iconUrl = mod.logo?.thumbnailUrl ?: mod.logo?.url,
        versionNumber = file.displayName,
        loader = instance.loader.id,
        gameVersion = gameVersion,
        sha1 = file.hashes.firstOrNull { it.algo == 1 }?.value,
        fileSize = file.fileLength,
        source = ContentSource.CURSEFORGE
    )

    private fun contentType(mod: CurseForgeMod): ContentType = when (mod.classId) {
        12 -> ContentType.RESOURCE_PACK
        6552 -> ContentType.SHADER
        4471 -> ContentType.MODPACK
        else -> ContentType.MOD
    }

    private fun requireApiKey(apiKey: String) {
        require(apiKey.isNotBlank()) { "Enter your CurseForge API key in Settings" }
    }

    private fun headers(apiKey: String) = mapOf("x-api-key" to apiKey)

    companion object {
        private const val REQUIRED_DEPENDENCY = 3
    }
}

@Serializable
private data class CurseForgeFilesResponse(val data: List<CurseForgeFile> = emptyList())

@Serializable
private data class CurseForgeModResponse(val data: CurseForgeMod)

@Serializable
private data class CurseForgeFileResponse(val data: CurseForgeFile)

@Serializable
private data class CurseForgeDownloadUrlResponse(val data: String = "")
