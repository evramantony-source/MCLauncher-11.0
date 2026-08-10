package com.mclauncher.minecraft

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.InstallProgress
import com.mclauncher.model.InstallStage
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.jar.JarFile

@Serializable
data class LoaderVersionChoice(
    val loader: ModLoader,
    val version: String,
    val stable: Boolean = true
)

@Serializable
data class ToolLaunchPlan(
    val id: String,
    val javaVersion: JavaVersion,
    val workingDirectory: String,
    val classpath: List<String>,
    val mainClass: String,
    val arguments: List<String>,
    val jvmArguments: List<String> = emptyList()
)

class LoaderInstaller(
    private val layout: MinecraftLayout,
    private val versionRepository: MojangVersionRepository = MojangVersionRepository(layout),
    private val vanillaInstaller: VanillaInstaller = VanillaInstaller(layout),
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
) {
    suspend fun available(loader: ModLoader, gameVersion: String): List<LoaderVersionChoice> = when (loader) {
        ModLoader.FABRIC -> parseFabricChoices(gameVersion)
        ModLoader.QUILT -> parseQuiltChoices(gameVersion)
        ModLoader.FORGE -> parseMavenMetadata(
            loader,
            "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml",
            gameVersion
        )
        ModLoader.NEOFORGE -> parseNeoForgeChoices(gameVersion)
        ModLoader.VANILLA -> emptyList()
    }

    suspend fun installProfile(
        instance: MinecraftInstance,
        loaderVersion: String,
        onProgress: (InstallProgress) -> Unit = {}
    ): MinecraftInstance {
        require(instance.loader != ModLoader.VANILLA) { "Choose a mod loader first" }
        val base = versionRepository.loadManifest().versions.firstOrNull { it.id == instance.versionId }
            ?: error("Minecraft ${instance.versionId} is not present in Mojang's manifest")
        onProgress(InstallProgress(stage = InstallStage.LOADER, message = "Installing Minecraft ${instance.versionId}"))
        vanillaInstaller.install(base, onProgress)

        return when (instance.loader) {
            ModLoader.FABRIC -> installJsonProfile(instance, loaderVersion, fabricProfileUrl(instance.versionId, loaderVersion), onProgress)
            ModLoader.QUILT -> installJsonProfile(instance, loaderVersion, quiltProfileUrl(instance.versionId, loaderVersion), onProgress)
            ModLoader.FORGE -> prepareInstaller(instance, loaderVersion, forgeInstallerUrl(instance.versionId, loaderVersion), onProgress)
            ModLoader.NEOFORGE -> prepareInstaller(instance, loaderVersion, neoForgeInstallerUrl(loaderVersion), onProgress)
            ModLoader.VANILLA -> instance
        }
    }

    private suspend fun installJsonProfile(
        instance: MinecraftInstance,
        loaderVersion: String,
        profileUrl: String,
        onProgress: (InstallProgress) -> Unit
    ): MinecraftInstance {
        onProgress(
            InstallProgress(
                stage = InstallStage.LOADER,
                totalFiles = 1,
                currentFile = "$loaderVersion.json",
                message = "Downloading ${instance.loader.displayName} $loaderVersion profile"
            )
        )
        val raw = downloader.readText(profileUrl)
        val profile = json.parseToJsonElement(raw).jsonObject
        requireProfileRuntime(
            profile = profile,
            loader = instance.loader,
            gameVersion = instance.versionId,
            loaderVersion = loaderVersion
        )
        val profileId = profile["id"]?.jsonPrimitive?.content
            ?: "${instance.versionId}-${instance.loader.id}-$loaderVersion"
        val target = layout.versionJson(profileId)
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(JsonObject.serializer(), profile))
        downloadProfileLibraries(profile, onProgress)
        return instance.copy(versionId = profileId, loaderVersion = loaderVersion, installed = true)
    }

    private suspend fun downloadProfileLibraries(profile: JsonObject, onProgress: (InstallProgress) -> Unit) {
        val libraries = profile["libraries"] as? JsonArray ?: return
        val downloads = libraries.mapNotNull { element ->
            val library = element.jsonObject
            val coordinate = library["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val artifact = library["downloads"]?.jsonObject?.get("artifact") as? JsonObject
            val path = artifact?.get("path")?.jsonPrimitive?.content ?: MavenCoordinates.path(coordinate)
            val url = artifact?.get("url")?.jsonPrimitive?.content
                ?: library["url"]?.jsonPrimitive?.content?.trimEnd('/')?.let { "$it/$path" }
                ?: error("No download URL for $coordinate")
            LoaderDownload(
                url = url,
                target = layout.library(path),
                sha1 = artifact?.get("sha1")?.jsonPrimitive?.content,
                size = artifact?.get("size")?.jsonPrimitive?.content?.toLongOrNull()
            )
        }
        val totalBytes = downloads.map { it.size }.takeIf { sizes -> sizes.all { it != null } }
            ?.sumOf { it ?: 0L }
        var downloadedBytes = 0L
        downloads.forEachIndexed { index, download ->
            var streamed = 0L
            onProgress(
                InstallProgress(
                    stage = InstallStage.LOADER,
                    completedFiles = index,
                    totalFiles = downloads.size,
                    currentFile = download.target.name,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                    message = "Downloading loader libraries"
                )
            )
            downloader.download(
                url = download.url,
                destination = download.target,
                expectedSha1 = download.sha1,
                expectedSize = download.size,
                onBytes = { delta ->
                    streamed += delta
                    downloadedBytes += delta
                    onProgress(
                        InstallProgress(
                            stage = InstallStage.LOADER,
                            completedFiles = index,
                            totalFiles = downloads.size,
                            currentFile = download.target.name,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            message = "Downloading loader libraries"
                        )
                    )
                }
            )
            if (streamed == 0L) downloadedBytes += download.size ?: download.target.length()
        }
        onProgress(
            InstallProgress(
                stage = InstallStage.LOADER,
                completedFiles = downloads.size,
                totalFiles = downloads.size,
                downloadedBytes = downloadedBytes,
                totalBytes = totalBytes ?: downloadedBytes.takeIf { it > 0L },
                message = "Loader libraries installed"
            )
        )
    }

    private suspend fun prepareInstaller(
        instance: MinecraftInstance,
        loaderVersion: String,
        url: String,
        onProgress: (InstallProgress) -> Unit
    ): MinecraftInstance {
        val installerDir = File(layout.root, "installers/${instance.loader.id}").apply { mkdirs() }
        val installerJar = File(installerDir, "${instance.loader.id}-$loaderVersion-installer.jar")
        var downloadedBytes = 0L
        onProgress(InstallProgress(stage = InstallStage.LOADER, totalFiles = 1, currentFile = installerJar.name, message = "Downloading ${instance.loader.displayName} installer"))
        downloader.download(url, installerJar, onBytes = { delta ->
            downloadedBytes += delta
            onProgress(
                InstallProgress(
                    stage = InstallStage.LOADER,
                    totalFiles = 1,
                    currentFile = installerJar.name,
                    downloadedBytes = downloadedBytes,
                    message = "Downloading ${instance.loader.displayName} installer"
                )
            )
        })
        val mainClass = withContext(Dispatchers.IO) {
            JarFile(installerJar).use { jar ->
                jar.manifest?.mainAttributes?.getValue("Main-Class")
            }
        } ?: error("Installer JAR has no Main-Class")
        val toolPlan = ToolLaunchPlan(
            id = "${instance.loader.id}-${instance.id}",
            javaVersion = instance.javaVersion,
            workingDirectory = layout.root.absolutePath,
            classpath = listOf(installerJar.absolutePath),
            mainClass = mainClass,
            arguments = listOf("--installClient", layout.root.absolutePath),
            jvmArguments = listOf("-Xmx1024M", "-Djava.awt.headless=true")
        )
        val planFile = File(layout.root, "tool-plans/${toolPlan.id}.json")
        planFile.parentFile?.mkdirs()
        planFile.writeText(json.encodeToString(ToolLaunchPlan.serializer(), toolPlan))
        onProgress(
            InstallProgress(
                stage = InstallStage.LOADER,
                completedFiles = 1,
                totalFiles = 1,
                downloadedBytes = installerJar.length(),
                totalBytes = installerJar.length(),
                message = "Installer prepared; running it next"
            )
        )
        return instance.copy(loaderVersion = loaderVersion, installed = false)
    }

    private data class LoaderDownload(
        val url: String,
        val target: File,
        val sha1: String?,
        val size: Long?
    )

    private suspend fun parseFabricChoices(gameVersion: String): List<LoaderVersionChoice> {
        val raw = downloader.readText("https://meta.fabricmc.net/v2/versions/loader/$gameVersion")
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val loader = element.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
            LoaderVersionChoice(
                loader = ModLoader.FABRIC,
                version = loader["version"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                stable = loader["stable"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
            )
        }
    }

    private suspend fun parseQuiltChoices(gameVersion: String): List<LoaderVersionChoice> {
        val raw = downloader.readText("https://meta.quiltmc.org/v3/versions/loader/$gameVersion")
        val array = json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            val loader = element.jsonObject["loader"]?.jsonObject ?: return@mapNotNull null
            LoaderVersionChoice(
                loader = ModLoader.QUILT,
                version = loader["version"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                stable = true
            )
        }
    }

    private suspend fun parseNeoForgeChoices(gameVersion: String): List<LoaderVersionChoice> {
        val mc = gameVersion.removePrefix("1.")
        return parseMavenMetadata(
            ModLoader.NEOFORGE,
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml",
            mc
        )
    }

    private suspend fun parseMavenMetadata(loader: ModLoader, url: String, prefix: String): List<LoaderVersionChoice> {
        val raw = downloader.readText(url)
        return Regex("<version>([^<]+)</version>").findAll(raw)
            .map { it.groupValues[1] }
            .filter { version -> version.startsWith(prefix) }
            .toList()
            .asReversed()
            .take(100)
            .map { publishedVersion ->
                val selectableVersion = if (loader == ModLoader.FORGE) {
                    publishedVersion.removePrefix("$prefix-")
                } else {
                    publishedVersion
                }
                LoaderVersionChoice(loader, selectableVersion, stable = !publishedVersion.contains("beta", true))
            }
    }

    private fun fabricProfileUrl(game: String, loader: String) =
        "https://meta.fabricmc.net/v2/versions/loader/$game/$loader/profile/json"

    private fun quiltProfileUrl(game: String, loader: String) =
        "https://meta.quiltmc.org/v3/versions/loader/$game/$loader/profile/json"

    private fun forgeInstallerUrl(game: String, loader: String) =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/$game-$loader/forge-$game-$loader-installer.jar"

    private fun neoForgeInstallerUrl(loader: String) =
        "https://maven.neoforged.net/releases/net/neoforged/neoforge/$loader/neoforge-$loader-installer.jar"

    fun toolPlanFile(instance: MinecraftInstance): File =
        File(layout.root, "tool-plans/${instance.loader.id}-${instance.id}.json")

    fun finalizeInstaller(instance: MinecraftInstance, loaderVersion: String): MinecraftInstance {
        val candidates = layout.versionsDirectory.listFiles().orEmpty()
            .filter(File::isDirectory)
            .mapNotNull { directory ->
                val jsonFile = File(directory, "${directory.name}.json")
                if (!jsonFile.isFile) null else directory.name to jsonFile
            }
            .filter { (id, file) ->
                val lower = id.lowercase()
                val loaderMatch = when (instance.loader) {
                    ModLoader.FORGE -> "forge" in lower
                    ModLoader.NEOFORGE -> "neoforge" in lower
                    else -> false
                }
                loaderMatch && runCatching {
                    val doc = json.parseToJsonElement(file.readText()).jsonObject
                    val targetsGame =
                        doc["inheritsFrom"]?.jsonPrimitive?.content == instance.versionId ||
                            id.startsWith(instance.versionId)
                    targetsGame && profileDeclaresExactLoader(
                        profile = doc,
                        loader = instance.loader,
                        gameVersion = instance.versionId,
                        loaderVersion = loaderVersion
                    )
                }.getOrDefault(false)
            }
            .sortedByDescending { it.second.lastModified() }
        val profileId = candidates.firstOrNull()?.first
            ?: error("${instance.loader.displayName} installer exited without creating a version profile")
        return instance.copy(versionId = profileId, loaderVersion = loaderVersion, installed = true)
    }

    fun verifyInstalledProfile(
        instance: MinecraftInstance,
        gameVersion: String,
        loaderVersion: String
    ) {
        require(instance.loaderVersion == loaderVersion) {
            "Installed ${instance.loader.displayName} version ${instance.loaderVersion ?: "is missing"}; expected $loaderVersion"
        }
        val profileFile = layout.versionJson(instance.versionId)
        require(profileFile.isFile) {
            "Installed ${instance.loader.displayName} profile ${instance.versionId} is missing"
        }
        val profile = json.parseToJsonElement(profileFile.readText()).jsonObject
        requireProfileRuntime(profile, instance.loader, gameVersion, loaderVersion)
    }
}

internal fun requireProfileRuntime(
    profile: JsonObject,
    loader: ModLoader,
    gameVersion: String,
    loaderVersion: String
) {
    require(profileDeclaresExactLoader(profile, loader, gameVersion, loaderVersion)) {
        "Downloaded ${loader.displayName} profile does not contain the exact required runtime " +
            "$loaderVersion for Minecraft $gameVersion"
    }
}

internal fun profileDeclaresExactLoader(
    profile: JsonObject,
    loader: ModLoader,
    gameVersion: String,
    loaderVersion: String
): Boolean {
    if (loader == ModLoader.VANILLA) return true
    val coordinates = (profile["libraries"] as? JsonArray).orEmpty().mapNotNull { element ->
        runCatching { element.jsonObject["name"]?.jsonPrimitive?.content }.getOrNull()
    }
    val expected = when (loader) {
        ModLoader.FABRIC -> "net.fabricmc:fabric-loader:$loaderVersion"
        ModLoader.QUILT -> "org.quiltmc:quilt-loader:$loaderVersion"
        ModLoader.FORGE -> "net.minecraftforge:forge:$gameVersion-$loaderVersion"
        ModLoader.NEOFORGE -> "net.neoforged:neoforge:$loaderVersion"
        ModLoader.VANILLA -> return true
    }
    return coordinates.any { coordinate ->
        coordinate == expected || coordinate.startsWith("$expected:")
    }
}

object MavenCoordinates {
    fun path(coordinate: String): String {
        val parts = coordinate.split(':')
        require(parts.size >= 3) { "Invalid Maven coordinate: $coordinate" }
        val group = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val classifier = parts.getOrNull(3)?.takeIf(String::isNotBlank)
        val extension = parts.getOrNull(4)?.takeIf(String::isNotBlank) ?: "jar"
        val fileName = buildString {
            append(artifact).append('-').append(version)
            if (classifier != null) append('-').append(classifier)
            append('.').append(extension)
        }
        return "$group/$artifact/$version/$fileName"
    }
}
