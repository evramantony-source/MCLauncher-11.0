package com.mclauncher.minecraft

import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import com.mclauncher.model.ContentSource
import com.mclauncher.model.ContentType
import com.mclauncher.model.InstallProgress
import com.mclauncher.model.InstallStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
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
) {
    fun exactLoaderVersion(): String? = when (loader) {
        ModLoader.VANILLA -> null
        else -> loaderVersion?.trim()?.takeIf(String::isNotEmpty)?.let(::requireExactVersion)
            ?: error("The modpack does not declare an exact ${loader.displayName} version")
    }

    private fun requireExactVersion(declared: String): String {
        val value = declared.trim()
        require(value.isNotEmpty()) {
            "The modpack does not declare an exact ${loader.displayName} version"
        }
        require(
            value.matches(Regex("[0-9A-Za-z][0-9A-Za-z._+\\-]*"))
        ) {
            "The modpack declares a ${loader.displayName} version range ($value), not one exact version"
        }
        return value
    }
}

class ModrinthPackInstaller(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val contentIndex = ContentIndexStore(layout, json)

    suspend fun readIndex(archive: File): ModrinthPackIndex = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Modrinth pack file is missing" }
        ZipFile(archive).use { zip -> readAndValidateIndex(zip) }
    }

    suspend fun inspect(archive: File): PackRuntimeSpec = withContext(Dispatchers.IO) {
        runtimeSpec(readIndex(archive).dependencies)
    }

    suspend fun recoverInstalledContent(
        instance: MinecraftInstance,
        archive: File
    ): List<InstalledContent> = withContext(Dispatchers.IO) {
        val gameDirectory = layout.instanceGameDirectory(instance.gameDirectoryName)
        readIndex(archive).files
            .filterNot { it.env.client.equals("unsupported", true) }
            .mapNotNull { entry ->
                val destination = runCatching { safeChild(gameDirectory, entry.path) }.getOrNull()
                    ?.takeIf(File::isFile) ?: return@mapNotNull null
                installedEntry(instance, entry, destination)
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
            val index = readAndValidateIndex(zip)
            validatePackCompatibility(instance, index.dependencies)

            val eligible = index.files.filterNot { it.env.client.equals("unsupported", true) }
            val installedEntries = mutableListOf<InstalledContent>()
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
                installedEntry(instance, entry, destination)?.let(installedEntries::add)
            }

            extractDirectory(zip, "overrides/", gameDir)
            extractDirectory(zip, "client-overrides/", gameDir)
            if (installedEntries.isNotEmpty()) {
                contentIndex.update(gameDir) { current ->
                    val importedIds = installedEntries.mapTo(mutableSetOf()) { it.projectId }
                    current.filterNot { it.projectId in importedIds } + installedEntries
                }
            }
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

    private fun readAndValidateIndex(zip: ZipFile): ModrinthPackIndex {
        val indexEntry = zip.getEntry("modrinth.index.json")
            ?: error("This .mrpack has no modrinth.index.json")
        val index = zip.getInputStream(indexEntry).bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<ModrinthPackIndex>(it.readText())
        }
        require(index.formatVersion == 1) { "Unsupported Modrinth pack format ${index.formatVersion}" }
        require(index.game.equals("minecraft", true)) { "Unsupported Modrinth pack game: ${index.game}" }
        require(index.name.isNotBlank()) { "Modrinth pack name is missing" }
        require(index.versionId.isNotBlank()) { "Modrinth pack version ID is missing" }
        val paths = mutableSetOf<String>()
        index.files.forEach { entry ->
            require(paths.add(entry.path)) { "Duplicate Modrinth pack path: ${entry.path}" }
            safeChild(layout.root, entry.path)
            require(entry.downloads.isNotEmpty()) { "No download URL for ${entry.path}" }
            entry.downloads.forEach { url ->
                val uri = runCatching { URI(url) }.getOrNull()
                require(uri?.scheme.equals("https", true) && !uri?.host.isNullOrBlank()) {
                    "Invalid HTTPS download URL for ${entry.path}"
                }
            }
            require(entry.hashes.sha1?.matches(Regex("[0-9a-fA-F]{40}")) == true) {
                "${entry.path} does not declare a valid SHA-1 hash"
            }
            require(entry.hashes.sha512?.matches(Regex("[0-9a-fA-F]{128}")) == true) {
                "${entry.path} does not declare a valid SHA-512 hash"
            }
            require(entry.fileSize >= 0) { "Negative file size for ${entry.path}" }
            listOf(entry.env.client, entry.env.server).forEach { environment ->
                require(environment in setOf("required", "optional", "unsupported")) {
                    "Unsupported Modrinth environment value: $environment"
                }
            }
        }
        return index
    }

    private fun installedEntry(
        instance: MinecraftInstance,
        entry: ModrinthPackEntry,
        destination: File
    ): InstalledContent? {
        val contentType = when (entry.path.substringBefore('/')) {
            ContentType.MOD.folderName -> ContentType.MOD
            ContentType.RESOURCE_PACK.folderName -> ContentType.RESOURCE_PACK
            ContentType.SHADER.folderName -> ContentType.SHADER
            else -> null
        } ?: return null
        val origin = entry.downloads.firstNotNullOfOrNull(::modrinthOrigin) ?: return null
        return InstalledContent(
            projectId = origin.first,
            versionId = origin.second,
            contentType = contentType,
            fileName = destination.name,
            title = destination.nameWithoutExtension,
            versionNumber = origin.second,
            loader = instance.loader.id,
            gameVersion = baseGameVersion(instance),
            sha1 = entry.hashes.sha1,
            sha512 = entry.hashes.sha512,
            downloadUrls = entry.downloads,
            fileSize = destination.length(),
            clientEnvironment = entry.env.client,
            serverEnvironment = entry.env.server,
            source = ContentSource.MODRINTH
        )
    }

    private fun modrinthOrigin(url: String): Pair<String, String>? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.host.equals("cdn.modrinth.com", true)) return null
        val parts = uri.path.trim('/').split('/')
        val dataIndex = parts.indexOf("data")
        if (dataIndex < 0 || parts.getOrNull(dataIndex + 2) != "versions") return null
        val projectId = parts.getOrNull(dataIndex + 1)?.takeIf(String::isNotBlank) ?: return null
        val versionId = parts.getOrNull(dataIndex + 3)?.takeIf(String::isNotBlank) ?: return null
        return projectId to versionId
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
        val exactLoaderVersion = spec.exactLoaderVersion()
        if (exactLoaderVersion != null) require(instance.loaderVersion == exactLoaderVersion) {
            "Pack requires ${spec.loader.displayName} $exactLoaderVersion, but the instance uses ${instance.loaderVersion ?: "no loader version"}"
        }
    }

    private fun runtimeSpec(dependencies: Map<String, String>): PackRuntimeSpec {
        val minecraft = dependencies["minecraft"]?.trim()?.takeIf(String::isNotBlank)
            ?: error("Modrinth pack does not declare a Minecraft version")
        val loaderEntries = listOf(
            "fabric-loader" to ModLoader.FABRIC,
            "quilt-loader" to ModLoader.QUILT,
            "forge" to ModLoader.FORGE,
            "neoforge" to ModLoader.NEOFORGE
        ).filter { dependencies.containsKey(it.first) }
        require(loaderEntries.size <= 1) {
            "Modrinth pack declares multiple loaders: ${loaderEntries.joinToString { it.second.displayName }}"
        }
        val loaderEntry = loaderEntries.singleOrNull()
        return PackRuntimeSpec(
            minecraftVersion = minecraft,
            loader = loaderEntry?.second ?: ModLoader.VANILLA,
            loaderVersion = loaderEntry?.first?.let { dependencies[it]?.trim() }
        ).also(PackRuntimeSpec::exactLoaderVersion)
    }
}

class CurseForgePackInstaller(
    private val layout: MinecraftLayout,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun readManifest(archive: File): CurseForgePackManifest = withContext(Dispatchers.IO) {
        require(archive.isFile) { "CurseForge pack file is missing" }
        ZipFile(archive).use { zip -> readAndValidateManifest(zip) }
    }

    suspend fun inspect(archive: File): PackRuntimeSpec = withContext(Dispatchers.IO) {
        runtimeSpec(readManifest(archive))
    }

    suspend fun readManifest(instance: MinecraftInstance, archive: File): CurseForgePackManifest = withContext(Dispatchers.IO) {
        ZipFile(archive).use { zip ->
            val manifest = readAndValidateManifest(zip)
            val current = baseGameVersion(instance)
            require(manifest.minecraft.version == current) {
                "Pack requires Minecraft ${manifest.minecraft.version}, but ${instance.name} uses $current"
            }
            val spec = runtimeSpec(manifest)
            require(instance.loader == spec.loader) {
                "Pack requires ${spec.loader.displayName}, but the selected instance uses ${instance.loader.displayName}"
            }
            val exactLoaderVersion = spec.exactLoaderVersion()
            if (exactLoaderVersion != null) require(instance.loaderVersion == exactLoaderVersion) {
                "Pack requires ${spec.loader.displayName} $exactLoaderVersion, but the instance uses ${instance.loaderVersion ?: "no loader version"}"
            }
            extractDirectory(zip, manifest.overrides.trim('/') + "/", layout.instanceGameDirectory(instance.gameDirectoryName))
            manifest
        }
    }

    private fun readAndValidateManifest(zip: ZipFile): CurseForgePackManifest {
        val entry = zip.getEntry("manifest.json")
            ?: error("This CurseForge pack has no manifest.json")
        val manifest = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use {
            json.decodeFromString<CurseForgePackManifest>(it.readText())
        }
        require(manifest.manifestType == "minecraftModpack") {
            "Unsupported CurseForge manifest type ${manifest.manifestType}"
        }
        require(manifest.manifestVersion == 1) {
            "Unsupported CurseForge manifest version ${manifest.manifestVersion}"
        }
        require(manifest.name.isNotBlank()) { "CurseForge pack name is missing" }
        require(manifest.minecraft.version.isNotBlank()) { "CurseForge Minecraft version is missing" }
        require(manifest.overrides.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Unsafe CurseForge overrides path: ${manifest.overrides}"
        }
        manifest.files.forEach { file ->
            require(file.projectId > 0 && file.fileId > 0) { "Invalid CurseForge project or file ID" }
        }
        runtimeSpec(manifest)
        return manifest
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
            minecraftVersion = manifest.minecraft.version.trim(),
            loader = loader,
            loaderVersion = required?.id?.substringAfter('-', missingDelimiterValue = "")?.takeIf(String::isNotBlank)
        ).also(PackRuntimeSpec::exactLoaderVersion)
    }
}

/** Resolves the actual Minecraft version from a loader profile id. */
fun baseGameVersion(instance: MinecraftInstance): String {
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
