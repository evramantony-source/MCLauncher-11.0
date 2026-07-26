package com.mclauncher.minecraft

import com.mclauncher.model.ContentType
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
    suspend fun search(
        apiKey: String,
        query: String,
        contentType: ContentType,
        gameVersion: String? = null,
        loader: ModLoader? = null,
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

    suspend fun files(apiKey: String, modId: Int, gameVersion: String): List<CurseForgeFile> {
        requireApiKey(apiKey)
        return json.decodeFromString<CurseForgeFilesResponse>(
            downloader.readText(
                "https://api.curseforge.com/v1/mods/$modId/files?gameVersion=${HttpDownloader.encode(gameVersion)}&pageSize=50",
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
        onProgress: (String) -> Unit = {}
    ): InstalledContent {
        requireApiKey(apiKey)
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        val folder = File(gameDir, contentType.folderName).apply { mkdirs() }
        val target = File(folder, file.fileName)
        onProgress("Downloading ${file.fileName}")
        downloadFile(apiKey, mod.id, file, target)

        if (contentType == ContentType.MODPACK) {
            onProgress("Reading ${mod.name} manifest")
            val manifest = CurseForgePackInstaller(layout, json).readManifest(instance, target)
            val required = manifest.files.filter { it.required }
            required.forEachIndexed { index, entry ->
                onProgress("Installing pack mod ${index + 1}/${required.size}")
                val childMod = mod(apiKey, entry.projectId)
                val childFile = file(apiKey, entry.projectId, entry.fileId)
                val childTarget = File(gameDir, "mods/${childFile.fileName}")
                downloadFile(apiKey, childMod.id, childFile, childTarget)
            }
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

        val sha1 = file.hashes.firstOrNull { it.algo == 1 }?.value
        val installed = InstalledContent(
            projectId = "curseforge:${mod.id}",
            versionId = file.id.toString(),
            contentType = contentType,
            fileName = target.name,
            title = mod.name,
            versionNumber = file.displayName,
            loader = instance.loader.id,
            gameVersion = gameVersion,
            sha1 = sha1
        )
        updateIndex(gameDir) { current -> current.filterNot { it.projectId == installed.projectId } + installed }
        return installed
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
        onProgress: (String) -> Unit
    ) {
        val required = parent.dependencies.filter { it.relationType == REQUIRED_DEPENDENCY }
        required.forEachIndexed { index, dependency ->
            if (!visited.add(dependency.modId)) return@forEachIndexed
            onProgress("Installing CurseForge dependency ${index + 1}/${required.size}")
            val dependencyMod = mod(apiKey, dependency.modId)
            val dependencyFile = files(apiKey, dependency.modId, gameVersion).firstOrNull()
                ?: error("No compatible file for required dependency ${dependencyMod.name}")
            val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
            downloadFile(apiKey, dependencyMod.id, dependencyFile, File(gameDir, "mods/${dependencyFile.fileName}"))
            installRequiredDependencies(apiKey, instance, dependencyFile, gameVersion, visited, onProgress)
        }
    }

    private suspend fun downloadFile(apiKey: String, modId: Int, file: CurseForgeFile, destination: File): File {
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
            expectedSize = file.fileLength.takeIf { it > 0 }
        )
    }

    private fun readIndex(gameDir: File): ContentIndex {
        val file = File(gameDir, ".mclauncher/content-index.json")
        return if (!file.isFile) ContentIndex() else runCatching {
            json.decodeFromString<ContentIndex>(file.readText())
        }.getOrDefault(ContentIndex())
    }

    private fun updateIndex(gameDir: File, transform: (List<InstalledContent>) -> List<InstalledContent>) {
        val file = File(gameDir, ".mclauncher/content-index.json")
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(ContentIndex(transform(readIndex(gameDir).items))))
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
