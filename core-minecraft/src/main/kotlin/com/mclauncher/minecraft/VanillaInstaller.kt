package com.mclauncher.minecraft

import com.mclauncher.model.InstallProgress
import com.mclauncher.model.InstallStage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class VanillaInstaller(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val ruleEvaluator: RuleEvaluator = RuleEvaluator()
) {
    suspend fun install(
        version: MojangVersionSummary,
        onProgress: (InstallProgress) -> Unit = {}
    ): File {
        layout.ensureBaseDirectories()
        val versionJsonFile = layout.versionJson(version.id)

        onProgress(InstallProgress(InstallStage.VERSION_METADATA, currentFile = versionJsonFile.name, message = "Downloading version metadata"))
        downloader.download(version.url, versionJsonFile, expectedSha1 = version.sha1)
        val versionDocument = withContext(Dispatchers.IO) {
            json.parseToJsonElement(versionJsonFile.readText()).jsonObject
        }

        installClient(version.id, versionDocument, onProgress)
        installLibraries(versionDocument, onProgress)
        installLoggingConfig(versionDocument, onProgress)
        val assetIndex = installAssetIndex(versionDocument, onProgress)
        installAssets(assetIndex, onProgress)

        onProgress(
            InstallProgress(
                stage = InstallStage.FINISHED,
                completedFiles = 1,
                totalFiles = 1,
                message = "Minecraft ${version.id} is installed"
            )
        )
        return versionJsonFile
    }

    private suspend fun installClient(
        versionId: String,
        document: JsonObject,
        onProgress: (InstallProgress) -> Unit
    ) {
        val client = document["downloads"]?.jsonObject?.get("client")?.jsonObject
            ?: error("Version $versionId has no client download")
        val target = layout.clientJar(versionId)
        onProgress(InstallProgress(InstallStage.CLIENT, currentFile = target.name, message = "Downloading Minecraft client"))
        downloader.download(
            url = client.requiredString("url"),
            destination = target,
            expectedSha1 = client.optionalString("sha1"),
            expectedSize = client.optionalLong("size")
        )
    }

    private suspend fun installLibraries(
        document: JsonObject,
        onProgress: (InstallProgress) -> Unit
    ) {
        val downloads = buildList {
            val libraries = document["libraries"] as? JsonArray ?: JsonArray(emptyList())
            for (element in libraries) {
                val library = element.jsonObject
                if (!ruleEvaluator.allows(library["rules"] as? JsonArray)) continue
                val artifact = library["downloads"]?.jsonObject?.get("artifact") as? JsonObject ?: continue
                add(
                    DownloadRequest(
                        url = artifact.requiredString("url"),
                        destination = layout.library(artifact.requiredString("path")),
                        sha1 = artifact.optionalString("sha1"),
                        size = artifact.optionalLong("size")
                    )
                )
            }
        }.distinctBy { it.destination.absolutePath }

        parallelDownload(
            stage = InstallStage.LIBRARIES,
            requests = downloads,
            message = "Downloading Java libraries",
            onProgress = onProgress
        )
    }

    private suspend fun installLoggingConfig(
        document: JsonObject,
        onProgress: (InstallProgress) -> Unit
    ) {
        val loggingClient = document["logging"]?.jsonObject?.get("client") as? JsonObject ?: return
        val file = loggingClient["file"] as? JsonObject ?: return
        val id = file.requiredString("id")
        val target = layout.loggingConfig(id)
        onProgress(InstallProgress(InstallStage.LOGGING_CONFIG, currentFile = id, message = "Downloading logging configuration"))
        downloader.download(
            url = file.requiredString("url"),
            destination = target,
            expectedSha1 = file.optionalString("sha1"),
            expectedSize = file.optionalLong("size")
        )
    }

    private suspend fun installAssetIndex(
        document: JsonObject,
        onProgress: (InstallProgress) -> Unit
    ): JsonObject {
        val assetIndex = document["assetIndex"]?.jsonObject ?: error("Version has no asset index")
        val id = assetIndex.requiredString("id")
        val target = layout.assetIndex(id)
        onProgress(InstallProgress(InstallStage.ASSET_INDEX, currentFile = target.name, message = "Downloading asset index"))
        downloader.download(
            url = assetIndex.requiredString("url"),
            destination = target,
            expectedSha1 = assetIndex.optionalString("sha1"),
            expectedSize = assetIndex.optionalLong("size")
        )
        return withContext(Dispatchers.IO) { json.parseToJsonElement(target.readText()).jsonObject }
    }

    private suspend fun installAssets(
        assetIndex: JsonObject,
        onProgress: (InstallProgress) -> Unit
    ) {
        val objects = assetIndex["objects"]?.jsonObject ?: JsonObject(emptyMap())
        val entries = objects.mapNotNull { (logicalPath, element) ->
            val objectInfo = element as? JsonObject ?: return@mapNotNull null
            val hash = objectInfo.requiredString("hash")
            AssetEntry(
                logicalPath = logicalPath,
                hash = hash,
                size = objectInfo.optionalLong("size")
            )
        }
        val downloads = entries.map { entry ->
            DownloadRequest(
                url = "https://resources.download.minecraft.net/${entry.hash.take(2)}/${entry.hash}",
                destination = layout.assetObject(entry.hash),
                sha1 = entry.hash,
                size = entry.size
            )
        }.distinctBy { it.sha1 }

        parallelDownload(
            stage = InstallStage.ASSETS,
            requests = downloads,
            message = "Downloading game assets",
            onProgress = onProgress
        )

        val virtualAssets = assetIndex["virtual"]?.jsonPrimitive?.booleanOrNull == true
        if (virtualAssets) {
            withContext(Dispatchers.IO) {
                entries.forEach { entry ->
                    val source = layout.assetObject(entry.hash)
                    val destination = File(layout.virtualLegacyDirectory, entry.logicalPath)
                    if (!destination.isFile || destination.length() != source.length()) {
                        destination.parentFile?.mkdirs()
                        source.copyTo(destination, overwrite = true)
                    }
                }
            }
        }
    }

    private suspend fun parallelDownload(
        stage: InstallStage,
        requests: List<DownloadRequest>,
        message: String,
        onProgress: (InstallProgress) -> Unit
    ) = coroutineScope {
        if (requests.isEmpty()) {
            onProgress(InstallProgress(stage, completedFiles = 0, totalFiles = 0, message = message))
            return@coroutineScope
        }
        val semaphore = Semaphore(6)
        val completed = AtomicInteger(0)
        onProgress(InstallProgress(stage, completedFiles = 0, totalFiles = requests.size, message = message))
        requests.map { request ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    downloader.download(
                        url = request.url,
                        destination = request.destination,
                        expectedSha1 = request.sha1,
                        expectedSize = request.size
                    )
                    val done = completed.incrementAndGet()
                    onProgress(
                        InstallProgress(
                            stage = stage,
                            completedFiles = done,
                            totalFiles = requests.size,
                            currentFile = request.destination.name,
                            message = message
                        )
                    )
                }
            }
        }.awaitAll()
    }

    private data class AssetEntry(
        val logicalPath: String,
        val hash: String,
        val size: Long?
    )

    private data class DownloadRequest(
        val url: String,
        val destination: File,
        val sha1: String?,
        val size: Long?
    )
}

internal fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.content ?: error("Missing required key: $key")

internal fun JsonObject.optionalString(key: String): String? =
    this[key]?.jsonPrimitive?.content

internal fun JsonObject.optionalLong(key: String): Long? =
    this[key]?.jsonPrimitive?.content?.toLongOrNull()
