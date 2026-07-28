package com.mclauncher.minecraft

import com.mclauncher.model.ContentType
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class ModrinthRepository(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
) {
    suspend fun search(
        query: String,
        contentType: ContentType,
        gameVersion: String? = null,
        loader: ModLoader? = null,
        offset: Int = 0,
        limit: Int = 30
    ): ModrinthSearchResponse {
        val projectType = when (contentType) {
            ContentType.MOD -> "mod"
            ContentType.MODPACK -> "modpack"
            ContentType.RESOURCE_PACK -> "resourcepack"
            ContentType.SHADER -> "shader"
        }
        val facets = buildList {
            add(listOf("project_type:$projectType"))
            if (!gameVersion.isNullOrBlank()) add(listOf("versions:$gameVersion"))
            if (contentType == ContentType.MOD && loader != null && loader != ModLoader.VANILLA) {
                add(listOf("categories:${loader.id}"))
            }
        }
        val url = buildString {
            append("https://api.modrinth.com/v2/search?query=")
            append(HttpDownloader.encode(query.trim()))
            append("&limit=").append(limit.coerceIn(1, 100))
            append("&offset=").append(offset.coerceAtLeast(0))
            append("&index=relevance")
            append("&facets=").append(HttpDownloader.encode(json.encodeToString(facets)))
        }
        return json.decodeFromString(downloader.readText(url))
    }

    suspend fun versions(
        projectId: String,
        gameVersion: String,
        loader: ModLoader? = null
    ): List<ModrinthVersion> {
        val query = buildString {
            append("?game_versions=")
            append(HttpDownloader.encode(json.encodeToString(listOf(gameVersion))))
            if (loader != null && loader != ModLoader.VANILLA) {
                append("&loaders=")
                append(HttpDownloader.encode(json.encodeToString(listOf(loader.id))))
            }
        }
        return json.decodeFromString(downloader.readText("https://api.modrinth.com/v2/project/$projectId/version$query"))
    }

    suspend fun version(versionId: String): ModrinthVersion =
        json.decodeFromString(downloader.readText("https://api.modrinth.com/v2/version/$versionId"))

    suspend fun install(
        instance: MinecraftInstance,
        project: ModrinthProject,
        version: ModrinthVersion,
        contentType: ContentType,
        installDependencies: Boolean = true,
        onProgress: (String) -> Unit = {}
    ): InstalledContent {
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        val targetFolder = File(gameDir, contentType.folderName).apply { mkdirs() }
        val file = version.files.firstOrNull { it.primary } ?: version.files.firstOrNull()
            ?: error("${version.name} does not contain a downloadable file")
        onProgress("Downloading ${file.filename}")
        val destination = File(targetFolder, file.filename)
        downloader.download(
            url = file.url,
            destination = destination,
            expectedSha1 = file.hashes.sha1,
            expectedSha512 = file.hashes.sha512,
            expectedSize = file.size.takeIf { it > 0 }
        )
        if (contentType == ContentType.MODPACK) {
            onProgress("Applying ${project.title} to ${instance.name}")
            ModrinthPackInstaller(layout, downloader, json).install(instance, destination, onProgress)
        }

        val installed = InstalledContent(
            projectId = project.project_id,
            versionId = version.id,
            contentType = contentType,
            fileName = destination.name,
            title = project.title,
            iconUrl = project.icon_url,
            versionNumber = version.version_number,
            loader = version.loaders.firstOrNull(),
            gameVersion = instance.versionId.substringBefore("-fabric").substringBefore("-quilt"),
            sha1 = file.hashes.sha1
        )
        updateIndex(gameDir) { current -> current.filterNot { it.projectId == project.project_id } + installed }

        if (installDependencies && contentType != ContentType.MODPACK) {
            val required = version.dependencies.filter { it.dependency_type == "required" }
            required.forEachIndexed { index, dependency ->
                onProgress("Installing dependency ${index + 1}/${required.size}")
                installDependency(instance, dependency, contentType, mutableSetOf(version.id), onProgress)
            }
        }
        return installed
    }

    suspend fun updateAvailable(instance: MinecraftInstance, item: InstalledContent): ModrinthVersion? {
        val candidates = versions(
            projectId = item.projectId,
            gameVersion = item.gameVersion,
            loader = item.loader?.let { value -> ModLoader.entries.firstOrNull { it.id == value } }
        )
        val latest = candidates.firstOrNull() ?: return null
        return latest.takeIf { it.id != item.versionId }
    }

    suspend fun listInstalled(instance: MinecraftInstance): List<InstalledContent> = withContext(Dispatchers.IO) {
        readIndex(layout.instanceGameDirectory(instance.gameDirectoryName)).items
    }

    suspend fun setEnabled(instance: MinecraftInstance, item: InstalledContent, enabled: Boolean) = withContext(Dispatchers.IO) {
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
        val folder = File(gameDir, item.contentType.folderName)
        val enabledFile = File(folder, item.fileName.removeSuffix(".disabled"))
        val disabledFile = File(folder, enabledFile.name + ".disabled")
        if (enabled) {
            if (disabledFile.isFile && !disabledFile.renameTo(enabledFile)) error("Could not enable ${item.title}")
        } else {
            if (enabledFile.isFile && !enabledFile.renameTo(disabledFile)) error("Could not disable ${item.title}")
        }
        updateIndex(gameDir) { items ->
            items.map { current -> if (current.projectId == item.projectId) current.copy(enabled = enabled) else current }
        }
    }

    suspend fun remove(instance: MinecraftInstance, item: InstalledContent) = withContext(Dispatchers.IO) {
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName)
        val folder = File(gameDir, item.contentType.folderName)
        File(folder, item.fileName).delete()
        File(folder, item.fileName + ".disabled").delete()
        updateIndex(gameDir) { items -> items.filterNot { it.projectId == item.projectId } }
    }

    private suspend fun installDependency(
        instance: MinecraftInstance,
        dependency: ModrinthDependency,
        fallbackType: ContentType,
        visited: MutableSet<String>,
        onProgress: (String) -> Unit
    ) {
        val dependencyVersion = when {
            !dependency.version_id.isNullOrBlank() -> version(dependency.version_id)
            !dependency.project_id.isNullOrBlank() -> versions(
                projectId = dependency.project_id,
                gameVersion = instance.versionId.substringBefore("-fabric").substringBefore("-quilt"),
                loader = instance.loader
            ).firstOrNull()
            else -> null
        } ?: return
        if (!visited.add(dependencyVersion.id)) return
        val projectId = dependencyVersion.project_id
        val project = project(projectId)
        val type = project.project_type.toContentType() ?: fallbackType
        install(instance, project, dependencyVersion, type, installDependencies = false, onProgress = onProgress)
        dependencyVersion.dependencies.filter { it.dependency_type == "required" }.forEach {
            installDependency(instance, it, type, visited, onProgress)
        }
    }

    suspend fun project(projectId: String): ModrinthProject {
        val raw = downloader.readText("https://api.modrinth.com/v2/project/$projectId")
        val detail = json.parseToJsonElement(raw).jsonObject
        return ModrinthProject(
            project_id = detail["id"]?.jsonPrimitive?.content ?: projectId,
            project_type = detail["project_type"]?.jsonPrimitive?.content ?: "mod",
            slug = detail["slug"]?.jsonPrimitive?.content ?: projectId,
            author = detail["team"]?.jsonPrimitive?.content ?: "",
            title = detail["title"]?.jsonPrimitive?.content ?: projectId,
            description = detail["description"]?.jsonPrimitive?.content ?: "",
            categories = detail["categories"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            downloads = detail["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            icon_url = detail["icon_url"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun String.toContentType(): ContentType? = when (this) {
        "mod" -> ContentType.MOD
        "modpack" -> ContentType.MODPACK
        "resourcepack" -> ContentType.RESOURCE_PACK
        "shader" -> ContentType.SHADER
        else -> null
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
        val current = readIndex(gameDir)
        file.writeText(json.encodeToString(ContentIndex(transform(current.items))))
    }
}
