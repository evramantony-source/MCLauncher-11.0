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
    fun builtInCatalog(): ComponentCatalog = ComponentCatalog(
        updatedAt = "2026-07-28",
        packages = listOf(
            ComponentPackage(
                id = "openltw-2025-07-16",
                name = "OpenLTW / LTW",
                version = "2025.7.16",
                type = ComponentPackageType.RENDERER,
                architecture = "universal",
                url = "https://github.com/ShirosakiMio/FCLRendererPlugin/releases/download/Renderer/LTW-2025.7.16.apk",
                sha256 = "f36d7145da5188f83225aa97fc8422aa909308819e8fb2f4b97e1d253c48b1f5",
                size = 2_680_542,
                renderer = Renderer.OPEN_LTW,
                sourceProject = "https://github.com/ShirosakiMio/FCLRendererPlugin"
            ),
            ComponentPackage(
                id = "angle-renderer-arm64",
                name = "ANGLE renderer",
                version = "2025.01.22",
                type = ComponentPackageType.RENDERER,
                architecture = "arm64-v8a",
                url = "https://github.com/ShirosakiMio/FCLRendererPlugin/releases/download/Renderer/ANGLE.Renderer.apk",
                sha256 = "04e984363d3256e255c3d96aca68c264d3f800b9f4c7d093ab40eb3d4c042c2e",
                size = 4_484_899,
                renderer = Renderer.ANGLE,
                sourceProject = "https://github.com/ShirosakiMio/FCLRendererPlugin"
            ),
            ComponentPackage(
                id = "zink-mesa25-arm64",
                name = "Zink Mesa 25",
                version = "25",
                type = ComponentPackageType.RENDERER,
                architecture = "arm64-v8a",
                url = "https://github.com/ShirosakiMio/FCLRendererPlugin/releases/download/Renderer/Zink.Mesa25.apk",
                sha256 = "815f0e4a8437939bc0447ccace640bbb714b4173038f1d4d668be62dc18d1e30",
                size = 5_263_088,
                renderer = Renderer.ZINK,
                sourceProject = "https://github.com/ShirosakiMio/FCLRendererPlugin"
            )
        )
    )

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
