package com.mclauncher.app.engine

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.mclauncher.minecraft.Hashing
import com.mclauncher.minecraft.HttpDownloader
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.Renderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class ComponentPackageType { RUNTIME, ENGINE, RENDERER, DRIVER }

@Serializable
data class ComponentPackage(
    val id: String,
    val name: String,
    val version: String,
    val type: ComponentPackageType,
    val architecture: String,
    val url: String,
    val sha256: String? = null,
    val size: Long? = null,
    val javaVersion: JavaVersion? = null,
    val renderer: Renderer? = null,
    val driver: GraphicsDriver? = null,
    val license: String? = null,
    val sourceProject: String? = null
)

@Serializable
data class ComponentCatalog(
    val schemaVersion: Int = 1,
    val updatedAt: String? = null,
    val packages: List<ComponentPackage> = emptyList()
)

class PackageCatalogManager(
    private val context: Context,
    private val downloader: HttpDownloader = HttpDownloader(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun load(url: String): ComponentCatalog {
        require(url.startsWith("https://")) { "Component catalog must use HTTPS" }
        val catalog = json.decodeFromString<ComponentCatalog>(downloader.readText(url))
        require(catalog.schemaVersion == 1) { "Unsupported component catalog schema ${catalog.schemaVersion}" }
        return catalog
    }

    suspend fun download(item: ComponentPackage): Uri = withContext(Dispatchers.IO) {
        require(item.url.startsWith("https://")) { "Component packages must use HTTPS" }
        val folder = File(context.cacheDir, "component-downloads").apply { mkdirs() }
        val extension = item.url.substringBefore('?').substringAfterLast('.', "zip").take(8)
        val target = File(folder, "${item.id}-${item.version}.$extension")
        downloader.download(item.url, target, expectedSize = item.size)
        if (!item.sha256.isNullOrBlank()) {
            require(Hashing.sha256(target).equals(item.sha256, ignoreCase = true)) {
                target.delete()
                "Component SHA-256 verification failed"
            }
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", target)
    }
}
