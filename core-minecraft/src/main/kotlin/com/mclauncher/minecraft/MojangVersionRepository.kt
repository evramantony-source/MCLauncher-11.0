package com.mclauncher.minecraft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MojangVersionManifest(
    val latest: LatestVersions,
    val versions: List<MojangVersionSummary>
)

@Serializable
data class LatestVersions(
    val release: String,
    val snapshot: String
)

@Serializable
data class MojangVersionSummary(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String? = null,
    val complianceLevel: Int? = null
)

class MojangVersionRepository(
    private val layout: MinecraftLayout,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    companion object {
        const val VERSION_MANIFEST_URL =
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    }

    suspend fun loadManifest(forceRefresh: Boolean = false): MojangVersionManifest {
        layout.ensureBaseDirectories()
        val cache = File(layout.metadataDirectory, "version_manifest_v2.json")
        val freshEnough = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 6 * 60 * 60 * 1000L
        val text = when {
            !forceRefresh && freshEnough -> withContext(Dispatchers.IO) { cache.readText() }
            else -> runCatching { downloader.readText(VERSION_MANIFEST_URL) }
                .onSuccess { downloaded ->
                    withContext(Dispatchers.IO) {
                        cache.parentFile?.mkdirs()
                        cache.writeText(downloaded)
                    }
                }
                .getOrElse { error ->
                    if (cache.isFile) withContext(Dispatchers.IO) { cache.readText() } else throw error
                }
        }
        return json.decodeFromString(MojangVersionManifest.serializer(), text)
    }
}
