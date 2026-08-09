package com.mclauncher.minecraft

import com.mclauncher.model.ContentSource
import com.mclauncher.model.ContentType
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedOutputStream
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class ModpackExportFormat(
    val extension: String,
    val mimeType: String,
    val displayName: String
) {
    MODRINTH("mrpack", "application/x-modrinth-modpack+zip", "Modrinth"),
    CURSEFORGE("zip", "application/zip", "CurseForge")
}

data class ModpackExportMetadata(
    val name: String,
    val version: String = "1.0.0",
    val author: String = "MCLauncher player",
    val summary: String? = null
)

data class ModpackExportResult(
    val archive: File,
    val format: ModpackExportFormat,
    val referencedFiles: Int,
    val overrideFiles: Int,
    val warnings: List<String>
)

/** Creates format-valid, self-checked Modrinth and CurseForge client packs. */
class ModpackExporter(
    private val layout: MinecraftLayout,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
) {
    fun export(
        instance: MinecraftInstance,
        managedContent: List<InstalledContent>,
        destination: File,
        format: ModpackExportFormat,
        metadata: ModpackExportMetadata,
        onProgress: (completed: Int, total: Int, currentFile: String) -> Unit = { _, _, _ -> }
    ): ModpackExportResult {
        require(instance.installed) { "Finish installing ${instance.name} before exporting it" }
        require(metadata.name.isNotBlank()) { "Pack name cannot be blank" }
        require(metadata.version.isNotBlank()) { "Pack version cannot be blank" }
        val gameDirectory = layout.instanceGameDirectory(instance.gameDirectoryName)
        require(gameDirectory.isDirectory) { "Instance game directory is missing" }

        val warnings = mutableListOf<String>()
        val managedFiles = managedContent
            .asSequence()
            .filter { it.enabled && it.contentType != ContentType.MODPACK }
            .mapNotNull { item ->
                val path = "${item.contentType.folderName}/${item.fileName}".normalizedPackPath()
                val file = safeExistingFile(gameDirectory, path)
                if (file == null) {
                    warnings += "Skipped missing managed file ${item.fileName}"
                    null
                } else {
                    ManagedFile(path, file, item)
                }
            }
            .toList()

        val modrinthEntries = mutableListOf<ModrinthPackEntry>()
        val curseForgeEntries = mutableListOf<CurseForgePackFile>()
        val referencedPaths = mutableSetOf<String>()
        managedFiles.forEach { managed ->
            when (format) {
                ModpackExportFormat.MODRINTH -> {
                    val entry = modrinthReference(managed)
                    if (entry != null) {
                        modrinthEntries += entry
                        referencedPaths += managed.path
                    } else if (managed.item.resolvedSource() == ContentSource.MODRINTH) {
                        warnings += "Embedded ${managed.item.title}: its provider metadata or local hash no longer matches"
                    }
                }
                ModpackExportFormat.CURSEFORGE -> {
                    val entry = curseForgeReference(managed)
                    if (entry != null) {
                        curseForgeEntries += entry
                        referencedPaths += managed.path
                    } else if (managed.item.resolvedSource() == ContentSource.CURSEFORGE) {
                        warnings += "Embedded ${managed.item.title}: its CurseForge identity or local hash no longer matches"
                    }
                }
            }
        }

        val overrideFiles = collectOverrides(gameDirectory, referencedPaths)
        val dependencies = dependencies(instance)
        val metadataBytes = when (format) {
            ModpackExportFormat.MODRINTH -> json.encodeToString(
                ModrinthPackIndex(
                    formatVersion = 1,
                    game = "minecraft",
                    versionId = metadata.version.trim(),
                    name = metadata.name.trim(),
                    summary = metadata.summary?.trim()?.takeIf(String::isNotBlank),
                    files = modrinthEntries.sortedBy(ModrinthPackEntry::path),
                    dependencies = dependencies
                )
            ).toByteArray(Charsets.UTF_8)
            ModpackExportFormat.CURSEFORGE -> json.encodeToString(
                CurseForgePackManifest(
                    minecraft = CurseForgePackMinecraft(
                        version = dependencies.getValue("minecraft"),
                        modLoaders = curseForgeLoader(instance)
                    ),
                    name = metadata.name.trim(),
                    version = metadata.version.trim(),
                    author = metadata.author.trim(),
                    files = curseForgeEntries
                        .distinctBy { it.projectId to it.fileId }
                        .sortedWith(compareBy(CurseForgePackFile::projectId, CurseForgePackFile::fileId)),
                    overrides = "overrides"
                )
            ).toByteArray(Charsets.UTF_8)
        }

        destination.parentFile?.mkdirs()
        val temporary = File(
            destination.parentFile ?: gameDirectory,
            ".${destination.name}.${UUID.randomUUID()}.tmp"
        )
        val total = overrideFiles.size + 1
        try {
            ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { zip ->
                val metadataPath = if (format == ModpackExportFormat.MODRINTH) {
                    "modrinth.index.json"
                } else {
                    "manifest.json"
                }
                writeBytes(zip, metadataPath, metadataBytes)
                onProgress(1, total, metadataPath)
                writeDirectory(zip, "overrides/")
                overrideFiles.forEachIndexed { index, override ->
                    writeFile(zip, "overrides/${override.path}", override.file)
                    onProgress(index + 2, total, override.path)
                }
            }
            validate(temporary, format)
            if (destination.exists() && !destination.delete()) {
                error("Could not replace ${destination.name}")
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } finally {
            temporary.delete()
        }

        return ModpackExportResult(
            archive = destination,
            format = format,
            referencedFiles = referencedPaths.size,
            overrideFiles = overrideFiles.size,
            warnings = warnings.distinct()
        )
    }

    fun validate(archive: File, format: ModpackExportFormat) {
        require(archive.isFile && archive.length() > 0L) { "Exported pack is empty" }
        ZipFile(archive).use { zip ->
            val names = mutableSetOf<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                require(names.add(entry.name)) { "Duplicate ZIP entry ${entry.name}" }
                validateArchivePath(entry.name)
            }
            require(zip.getEntry("overrides/") != null) { "Export is missing its overrides directory" }
            when (format) {
                ModpackExportFormat.MODRINTH -> {
                    val entry = zip.getEntry("modrinth.index.json")
                        ?: error("Export is missing modrinth.index.json")
                    val index = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use {
                        json.decodeFromString<ModrinthPackIndex>(it.readText())
                    }
                    require(index.formatVersion == 1 && index.game == "minecraft") {
                        "Invalid Modrinth pack header"
                    }
                    require(index.name.isNotBlank() && index.versionId.isNotBlank()) {
                        "Invalid Modrinth pack identity"
                    }
                    require(index.dependencies["minecraft"].orEmpty().isNotBlank()) {
                        "Modrinth pack has no Minecraft dependency"
                    }
                    index.files.forEach { file ->
                        validateArchivePath(file.path)
                        require(file.hashes.sha1?.matches(SHA1) == true) { "Invalid SHA-1 for ${file.path}" }
                        require(file.hashes.sha512?.matches(SHA512) == true) { "Invalid SHA-512 for ${file.path}" }
                        require(file.downloads.isNotEmpty() && file.downloads.all(::isAllowedModrinthUrl)) {
                            "Invalid Modrinth download URL for ${file.path}"
                        }
                        require(file.fileSize >= 0L) { "Invalid file size for ${file.path}" }
                    }
                }
                ModpackExportFormat.CURSEFORGE -> {
                    val entry = zip.getEntry("manifest.json")
                        ?: error("Export is missing manifest.json")
                    val manifest = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use {
                        json.decodeFromString<CurseForgePackManifest>(it.readText())
                    }
                    require(manifest.manifestType == "minecraftModpack" && manifest.manifestVersion == 1) {
                        "Invalid CurseForge manifest header"
                    }
                    require(manifest.name.isNotBlank() && manifest.version.isNotBlank()) {
                        "Invalid CurseForge pack identity"
                    }
                    require(manifest.minecraft.version.isNotBlank() && manifest.overrides == "overrides") {
                        "Invalid CurseForge Minecraft or overrides declaration"
                    }
                    require(manifest.files.all { it.projectId > 0 && it.fileId > 0 }) {
                        "Invalid CurseForge project or file ID"
                    }
                }
            }
        }
    }

    private fun modrinthReference(managed: ManagedFile): ModrinthPackEntry? {
        if (managed.item.resolvedSource() != ContentSource.MODRINTH) return null
        val urls = managed.item.downloadUrls.filter(::isAllowedModrinthUrl).distinct()
        if (urls.isEmpty()) return null
        val localSha1 = Hashing.sha1(managed.file)
        val expectedSha1 = managed.item.sha1?.lowercase()?.takeIf { it.matches(SHA1) } ?: return null
        if (localSha1 != expectedSha1) return null
        val localSha512 = Hashing.sha512(managed.file)
        val expectedSha512 = managed.item.sha512?.lowercase()?.takeIf { it.matches(SHA512) }
        if (expectedSha512 != null && localSha512 != expectedSha512) return null
        return ModrinthPackEntry(
            path = managed.path,
            hashes = ModrinthHashes(sha1 = localSha1, sha512 = localSha512),
            env = ModrinthPackEnvironment(
                client = managed.item.clientEnvironment.packEnvironment("required"),
                server = managed.item.serverEnvironment.packEnvironment("required")
            ),
            downloads = urls,
            fileSize = managed.file.length()
        )
    }

    private fun curseForgeReference(managed: ManagedFile): CurseForgePackFile? {
        if (managed.item.resolvedSource() != ContentSource.CURSEFORGE) return null
        val projectId = managed.item.projectId.removePrefix("curseforge:").toIntOrNull()
            ?.takeIf { it > 0 } ?: return null
        val fileId = managed.item.versionId.toIntOrNull()?.takeIf { it > 0 } ?: return null
        val expectedSha1 = managed.item.sha1?.lowercase()?.takeIf { it.matches(SHA1) }
        if (expectedSha1 != null && Hashing.sha1(managed.file) != expectedSha1) return null
        return CurseForgePackFile(projectId = projectId, fileId = fileId, required = true)
    }

    private fun collectOverrides(gameDirectory: File, referencedPaths: Set<String>): List<OverrideFile> {
        val canonicalRoot = gameDirectory.canonicalFile
        return gameDirectory.walkTopDown()
            .onEnter { directory ->
                if (directory == gameDirectory) return@onEnter true
                val relative = directory.relativeTo(gameDirectory).invariantSeparatorsPath
                relative.substringBefore('/') !in EXCLUDED_DIRECTORIES &&
                    !Files.isSymbolicLink(directory.toPath())
            }
            .filter(File::isFile)
            .mapNotNull { file ->
                if (Files.isSymbolicLink(file.toPath())) return@mapNotNull null
                val canonical = file.canonicalFile
                if (!canonical.path.startsWith(canonicalRoot.path + File.separator)) return@mapNotNull null
                val path = file.relativeTo(gameDirectory).invariantSeparatorsPath.normalizedPackPath()
                when {
                    path in referencedPaths -> null
                    path.substringBefore('/') in EXCLUDED_DIRECTORIES -> null
                    path in EXCLUDED_FILES -> null
                    path.endsWith(".disabled", ignoreCase = true) -> null
                    else -> OverrideFile(path, file)
                }
            }
            .sortedBy(OverrideFile::path)
            .toList()
    }

    private fun dependencies(instance: MinecraftInstance): Map<String, String> = linkedMapOf<String, String>().apply {
        put("minecraft", baseGameVersion(instance))
        val loaderKey = when (instance.loader) {
            ModLoader.VANILLA -> null
            ModLoader.FABRIC -> "fabric-loader"
            ModLoader.QUILT -> "quilt-loader"
            ModLoader.FORGE -> "forge"
            ModLoader.NEOFORGE -> "neoforge"
        }
        if (loaderKey != null) {
            val version = instance.loaderVersion?.trim()?.takeIf(String::isNotBlank)
                ?: error("${instance.name} has no exact ${instance.loader.displayName} version")
            put(loaderKey, version)
        }
    }

    private fun curseForgeLoader(instance: MinecraftInstance): List<CurseForgePackLoader> =
        if (instance.loader == ModLoader.VANILLA) {
            emptyList()
        } else {
            val version = instance.loaderVersion?.trim()?.takeIf(String::isNotBlank)
                ?: error("${instance.name} has no exact ${instance.loader.displayName} version")
            listOf(CurseForgePackLoader("${instance.loader.id}-$version", primary = true))
        }

    private fun safeExistingFile(root: File, relative: String): File? {
        validateArchivePath(relative)
        val canonicalRoot = root.canonicalFile
        val target = File(root, relative).canonicalFile
        if (!target.path.startsWith(canonicalRoot.path + File.separator)) return null
        return target.takeIf(File::isFile)
    }

    private fun validateArchivePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\')) {
            "Unsafe pack path: $path"
        }
        require('\\' !in path && !DRIVE_PATH.containsMatchIn(path)) { "Unsafe pack path: $path" }
        require(path.split('/').none { it == ".." || it.isBlank() && path != "overrides/" }) {
            "Unsafe pack path: $path"
        }
    }

    private fun writeDirectory(zip: ZipOutputStream, path: String) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.closeEntry()
    }

    private fun writeBytes(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun writeFile(zip: ZipOutputStream, path: String, source: File) {
        validateArchivePath(path)
        val entry = ZipEntry(path).apply { time = 0L }
        zip.putNextEntry(entry)
        source.inputStream().buffered().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun String.normalizedPackPath(): String = replace('\\', '/').trimStart('/').also {
        validateArchivePath(it)
    }

    private fun String?.packEnvironment(fallback: String): String =
        this?.lowercase()?.takeIf { it in PACK_ENVIRONMENTS } ?: fallback

    private fun InstalledContent.resolvedSource(): ContentSource? =
        source ?: if (projectId.startsWith("curseforge:")) ContentSource.CURSEFORGE else ContentSource.MODRINTH

    private data class ManagedFile(val path: String, val file: File, val item: InstalledContent)
    private data class OverrideFile(val path: String, val file: File)

    companion object {
        private val SHA1 = Regex("[0-9a-f]{40}")
        private val SHA512 = Regex("[0-9a-f]{128}")
        private val DRIVE_PATH = Regex("^[A-Za-z]:[/\\\\]")
        private val PACK_ENVIRONMENTS = setOf("required", "optional", "unsupported")
        private val MODRINTH_DOWNLOAD_HOSTS = setOf(
            "cdn.modrinth.com",
            "github.com",
            "raw.githubusercontent.com",
            "gitlab.com"
        )
        private val EXCLUDED_DIRECTORIES = setOf(
            ".mclauncher",
            "backups",
            "crash-reports",
            "logs",
            "modpacks",
            "saves",
            "screenshots",
            "server-resource-packs"
        )
        private val EXCLUDED_FILES = setOf(
            "launcher_accounts.json",
            "launcher_profiles.json",
            "servers.dat",
            "servers.dat_old",
            "usercache.json",
            "usernamecache.json"
        )

        private fun isAllowedModrinthUrl(url: String): Boolean {
            val uri = runCatching { URI(url) }.getOrNull() ?: return false
            return uri.scheme.equals("https", true) &&
                uri.host?.lowercase() in MODRINTH_DOWNLOAD_HOSTS &&
                !url.contains(' ')
        }
    }
}
