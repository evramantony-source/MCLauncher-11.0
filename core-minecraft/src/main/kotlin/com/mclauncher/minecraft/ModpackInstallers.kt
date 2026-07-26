package com.mclauncher.minecraft

import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile

@Serializable
data class ModrinthPackIndex(
    val formatVersion: Int = 1,
    val game: String = "minecraft",
    val versionId: String = "",
    val name: String,
    val summary: String? = null,
    val files: List<ModrinthPackEntry> = emptyList(),
    val dependencies: Map<String, String> = emptyMap()
)

@Serializable
data class ModrinthPackEntry(
    val path: String,
    val hashes: ModrinthHashes = ModrinthHashes(),
    val env: ModrinthPackEnvironment = ModrinthPackEnvironment(),
    val downloads: List<String> = emptyList(),
    val fileSize: Long = 0
)

@Serializable
data class ModrinthPackEnvironment(
    val client: String = "required",
    val server: String = "required"
)

@Serializable
data class CurseForgePackManifest(
    val minecraft: CurseForgePackMinecraft,
    val manifestType: String = "minecraftModpack",
    val manifestVersion: Int = 1,
    val name: String,
    val version: String = "",
    val author: String = "",
    val files: List<CurseForgePackFile> = emptyList(),
    val overrides: String = "overrides"
)

@Serializable
data class CurseForgePackMinecraft(
    val version: String,
    val modLoaders: List<CurseForgePackLoader> = emptyList()
)

@Serializable
data class CurseForgePackLoader(
    val id: String,
    val primary: Boolean = false
)

@Serializable
data class CurseForgePackFile(
    @SerialName("projectID") val projectId: Int,
    @SerialName("fileID") val fileId: Int,
    val required: Boolean = true
)

class ModrinthPackInstaller(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun install(
        instance: MinecraftInstance,
        archive: File,
        onProgress: (String) -> Unit = {}
    ): ModrinthPackIndex = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Modrinth pack file is missing" }
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        ZipFile(archive).use { zip ->
            val indexEntry = zip.getEntry("modrinth.index.json") ?: error("This .mrpack has no modrinth.index.json")
            val index = zip.getInputStream(indexEntry).bufferedReader().use { json.decodeFromString<ModrinthPackIndex>(it.readText()) }
            require(index.game.equals("minecraft", true)) { "Unsupported Modrinth pack game: ${index.game}" }
            validatePackCompatibility(instance, index.dependencies)

            val eligible = index.files.filterNot { it.env.client.equals("unsupported", true) }
            eligible.forEachIndexed { position, entry ->
                require(entry.downloads.isNotEmpty()) { "No download URL for ${entry.path}" }
                val destination = safeChild(gameDir, entry.path)
                onProgress("Installing pack file ${position + 1}/${eligible.size}: ${destination.name}")
                var failure: Throwable? = null
                for (url in entry.downloads) {
                    val result = runCatching {
                        downloader.download(
                            url = url,
                            destination = destination,
                            expectedSha1 = entry.hashes.sha1,
                            expectedSha512 = entry.hashes.sha512,
                            expectedSize = entry.fileSize.takeIf { it > 0 }
                        )
                    }
                    if (result.isSuccess) {
                        failure = null
                        break
                    }
                    failure = result.exceptionOrNull()
                }
                if (failure != null) throw IllegalStateException("Could not download ${entry.path}: ${failure.message}", failure)
            }

            extractDirectory(zip, "overrides/", gameDir)
            extractDirectory(zip, "client-overrides/", gameDir)
            index
        }
    }

    private fun validatePackCompatibility(instance: MinecraftInstance, dependencies: Map<String, String>) {
        val minecraft = dependencies["minecraft"]
        if (!minecraft.isNullOrBlank()) {
            val current = baseGameVersion(instance)
            require(current == minecraft) { "Pack requires Minecraft $minecraft, but ${instance.name} uses $current" }
        }
        val requiredLoader = when {
            dependencies.containsKey("fabric-loader") -> ModLoader.FABRIC
            dependencies.containsKey("quilt-loader") -> ModLoader.QUILT
            dependencies.containsKey("forge") -> ModLoader.FORGE
            dependencies.containsKey("neoforge") -> ModLoader.NEOFORGE
            else -> ModLoader.VANILLA
        }
        if (requiredLoader != ModLoader.VANILLA) {
            require(instance.loader == requiredLoader) { "Pack requires ${requiredLoader.displayName}, but the selected instance uses ${instance.loader.displayName}" }
        }
    }
}

class CurseForgePackInstaller(
    private val layout: MinecraftLayout,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun readManifest(instance: MinecraftInstance, archive: File): CurseForgePackManifest = withContext(Dispatchers.IO) {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("This CurseForge pack has no manifest.json")
            val manifest = zip.getInputStream(entry).bufferedReader().use { json.decodeFromString<CurseForgePackManifest>(it.readText()) }
            val current = baseGameVersion(instance)
            require(manifest.minecraft.version == current) {
                "Pack requires Minecraft ${manifest.minecraft.version}, but ${instance.name} uses $current"
            }
            val requiredLoader = manifest.minecraft.modLoaders.firstOrNull { it.primary } ?: manifest.minecraft.modLoaders.firstOrNull()
            if (requiredLoader != null) {
                val expected = when {
                    requiredLoader.id.startsWith("fabric-") -> ModLoader.FABRIC
                    requiredLoader.id.startsWith("quilt-") -> ModLoader.QUILT
                    requiredLoader.id.startsWith("forge-") -> ModLoader.FORGE
                    requiredLoader.id.startsWith("neoforge-") -> ModLoader.NEOFORGE
                    else -> null
                }
                if (expected != null) require(instance.loader == expected) {
                    "Pack requires ${expected.displayName}, but the selected instance uses ${instance.loader.displayName}"
                }
            }
            extractDirectory(zip, manifest.overrides.trim('/') + "/", layout.instanceGameDirectory(instance.gameDirectoryName))
            manifest
        }
    }
}

internal fun baseGameVersion(instance: MinecraftInstance): String = instance.versionId
    .substringBefore("-fabric")
    .substringBefore("-quilt")
    .substringBefore("-forge")
    .substringBefore("-neoforge")

internal fun safeChild(root: File, relative: String): File {
    require(relative.isNotBlank()) { "Empty archive path" }
    val target = File(root, relative.replace('\\', '/')).canonicalFile
    val canonicalRoot = root.canonicalFile
    require(target.path == canonicalRoot.path || target.path.startsWith(canonicalRoot.path + File.separator)) {
        "Unsafe archive path: $relative"
    }
    target.parentFile?.mkdirs()
    return target
}

internal fun extractDirectory(zip: ZipFile, prefix: String, destination: File) {
    val cleanPrefix = prefix.replace('\\', '/').trimStart('/')
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        val name = entry.name.replace('\\', '/')
        if (!name.startsWith(cleanPrefix) || name == cleanPrefix) continue
        val relative = name.removePrefix(cleanPrefix)
        if (relative.isBlank()) continue
        val target = safeChild(destination, relative)
        if (entry.isDirectory) target.mkdirs()
        else zip.getInputStream(entry).use { input -> target.outputStream().buffered().use(input::copyTo) }
    }
}
