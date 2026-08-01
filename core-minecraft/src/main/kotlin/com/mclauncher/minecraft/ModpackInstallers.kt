package com.mclauncher.minecraft

import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import com.mclauncher.model.InstallProgress
import com.mclauncher.model.InstallStage
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

data class PackRuntimeSpec(
    val minecraftVersion: String,
    val loader: ModLoader,
    val loaderVersion: String? = null
)

class ModrinthPackInstaller(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun inspect(archive: File): PackRuntimeSpec = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Modrinth pack file is missing" }
        ZipFile(archive).use { zip ->
            val indexEntry = zip.getEntry("modrinth.index.json") ?: error("This .mrpack has no modrinth.index.json")
            val index = zip.getInputStream(indexEntry).bufferedReader().use {
                json.decodeFromString<ModrinthPackIndex>(it.readText())
            }
            runtimeSpec(index.dependencies)
        }
    }

    suspend fun install(
        instance: MinecraftInstance,
        archive: File,
        onProgress: (InstallProgress) -> Unit = {}
    ): ModrinthPackIndex = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Modrinth pack file is missing" }
        val gameDir = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        ZipFile(archive).use { zip ->
            val indexEntry = zip.getEntry("modrinth.index.json") ?: error("This .mrpack has no modrinth.index.json")
            val index = zip.getInputStream(indexEntry).bufferedReader().use { json.decodeFromString<ModrinthPackIndex>(it.readText()) }
            require(index.game.equals("minecraft", true)) { "Unsupported Modrinth pack game: ${index.game}" }
            validatePackCompatibility(instance, index.dependencies)

            val eligible = index.files.filterNot { it.env.client.equals("unsupported", true) }
            val totalBytes = eligible.map { it.fileSize }.takeIf { sizes -> sizes.all { it > 0L } }?.sum()
            var downloadedBytes = 0L
            eligible.forEachIndexed { position, entry ->
                require(entry.downloads.isNotEmpty()) { "No download URL for ${entry.path}" }
                val destination = safeChild(gameDir, entry.path)
                onProgress(
                    InstallProgress(
                        stage = InstallStage.PACK_FILES,
                        completedFiles = position,
                        totalFiles = eligible.size,
                        currentFile = destination.name,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        message = "Installing pack files"
                    )
                )
                var failure: Throwable? = null
                for (url in entry.downloads) {
                    var streamed = 0L
                    val result = runCatching {
                        downloader.download(
                            url = url,
                            destination = destination,
                            expectedSha1 = entry.hashes.sha1,
                            expectedSha512 = entry.hashes.sha512,
                            expectedSize = entry.fileSize.takeIf { it > 0 },
                            onBytes = { delta ->
                                streamed += delta
                                downloadedBytes += delta
                                onProgress(
                                    InstallProgress(
                                        stage = InstallStage.PACK_FILES,
                                        completedFiles = position,
                                        totalFiles = eligible.size,
                                        currentFile = destination.name,
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        message = "Installing pack files"
                                    )
                                )
                            }
                        )
                    }
                    if (result.isSuccess) {
                        if (streamed == 0L) downloadedBytes += entry.fileSize.takeIf { it > 0L } ?: destination.length()
                        failure = null
                        break
                    }
                    failure = result.exceptionOrNull()
                }
                if (failure != null) throw IllegalStateException("Could not download ${entry.path}: ${failure.message}", failure)
            }

            extractDirectory(zip, "overrides/", gameDir)
            extractDirectory(zip, "client-overrides/", gameDir)
            onProgress(
                InstallProgress(
                    stage = InstallStage.PACK_FILES,
                    completedFiles = eligible.size,
                    totalFiles = eligible.size,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes ?: downloadedBytes.takeIf { it > 0L },
                    message = "${index.name} installed"
                )
            )
            index
        }
    }

    private fun validatePackCompatibility(instance: MinecraftInstance, dependencies: Map<String, String>) {
        val spec = runtimeSpec(dependencies)
        val current = baseGameVersion(instance)
        require(current == spec.minecraftVersion) {
            "Pack requires Minecraft ${spec.minecraftVersion}, but ${instance.name} uses $current"
        }
        require(instance.loader == spec.loader) {
            "Pack requires ${spec.loader.displayName}, but the selected instance uses ${instance.loader.displayName}"
        }
        if (!spec.loaderVersion.isNullOrBlank()) require(instance.loaderVersion == spec.loaderVersion) {
            "Pack requires ${spec.loader.displayName} ${spec.loaderVersion}, but the instance uses ${instance.loaderVersion ?: "no loader version"}"
        }
    }

    private fun runtimeSpec(dependencies: Map<String, String>): PackRuntimeSpec {
        val minecraft = dependencies["minecraft"]?.takeIf(String::isNotBlank)
            ?: error("Modrinth pack does not declare a Minecraft version")
        val loaderEntry = listOf(
            "fabric-loader" to ModLoader.FABRIC,
            "quilt-loader" to ModLoader.QUILT,
            "forge" to ModLoader.FORGE,
            "neoforge" to ModLoader.NEOFORGE
        ).firstOrNull { dependencies.containsKey(it.first) }
        return PackRuntimeSpec(
            minecraftVersion = minecraft,
            loader = loaderEntry?.second ?: ModLoader.VANILLA,
            loaderVersion = loaderEntry?.first?.let { dependencies[it] }?.takeIf(String::isNotBlank)
        )
    }
}

class CurseForgePackInstaller(
    private val layout: MinecraftLayout,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun inspect(archive: File): PackRuntimeSpec = withContext(Dispatchers.IO) {
        require(archive.isFile) { "CurseForge pack file is missing" }
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("This CurseForge pack has no manifest.json")
            val manifest = zip.getInputStream(entry).bufferedReader().use {
                json.decodeFromString<CurseForgePackManifest>(it.readText())
            }
            runtimeSpec(manifest)
        }
    }

    suspend fun readManifest(instance: MinecraftInstance, archive: File): CurseForgePackManifest = withContext(Dispatchers.IO) {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("This CurseForge pack has no manifest.json")
            val manifest = zip.getInputStream(entry).bufferedReader().use { json.decodeFromString<CurseForgePackManifest>(it.readText()) }
            val current = baseGameVersion(instance)
            require(manifest.minecraft.version == current) {
                "Pack requires Minecraft ${manifest.minecraft.version}, but ${instance.name} uses $current"
            }
            val spec = runtimeSpec(manifest)
            require(instance.loader == spec.loader) {
                "Pack requires ${spec.loader.displayName}, but the selected instance uses ${instance.loader.displayName}"
            }
            if (!spec.loaderVersion.isNullOrBlank()) require(instance.loaderVersion == spec.loaderVersion) {
                "Pack requires ${spec.loader.displayName} ${spec.loaderVersion}, but the instance uses ${instance.loaderVersion ?: "no loader version"}"
            }
            extractDirectory(zip, manifest.overrides.trim('/') + "/", layout.instanceGameDirectory(instance.gameDirectoryName))
            manifest
        }
    }

    private fun runtimeSpec(manifest: CurseForgePackManifest): PackRuntimeSpec {
        val required = manifest.minecraft.modLoaders.firstOrNull { it.primary }
            ?: manifest.minecraft.modLoaders.firstOrNull()
        val loader = when {
            required == null -> ModLoader.VANILLA
            required.id.startsWith("fabric-") -> ModLoader.FABRIC
            required.id.startsWith("quilt-") -> ModLoader.QUILT
            required.id.startsWith("forge-") -> ModLoader.FORGE
            required.id.startsWith("neoforge-") -> ModLoader.NEOFORGE
            else -> error("Unsupported CurseForge loader ${required.id}")
        }
        return PackRuntimeSpec(
            minecraftVersion = manifest.minecraft.version,
            loader = loader,
            loaderVersion = required?.id?.substringAfter('-', missingDelimiterValue = "")?.takeIf(String::isNotBlank)
        )
    }
}

internal fun baseGameVersion(instance: MinecraftInstance): String {
    val loaderVersion = instance.loaderVersion
    if (!loaderVersion.isNullOrBlank()) {
        val prefixed = when (instance.loader) {
            ModLoader.FABRIC -> "fabric-loader-$loaderVersion-"
            ModLoader.QUILT -> "quilt-loader-$loaderVersion-"
            else -> null
        }
        if (prefixed != null && instance.versionId.startsWith(prefixed)) {
            return instance.versionId.removePrefix(prefixed)
        }
    }
    return instance.versionId
        .substringBefore("-fabric")
        .substringBefore("-quilt")
        .substringBefore("-forge")
        .substringBefore("-neoforge")
}

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
