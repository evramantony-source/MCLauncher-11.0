package com.mclauncher.app.engine

import com.mclauncher.minecraft.MinecraftLayout
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.Renderer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Resolves a renderer plugin and its optional GLES/Vulkan driver layer.
 *
 * Built-in renderer IDs define their native renderer token; a package manifest
 * can add preload order and environment variables but cannot silently change
 * OpenLTW into ANGLE or another backend. Custom packs remain manifest-driven.
 */
class GraphicsRegistry(
    private val layout: MinecraftLayout,
    private val architecture: String,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private data class RendererPreset(
        val renderer: Renderer,
        val pojavRenderer: String,
        val tokens: List<String>,
        val preloadTokens: List<String>,
        val environment: Map<String, String>
    )

    private data class DriverPreset(
        val driver: GraphicsDriver,
        val tokens: List<String>,
        val preloadTokens: List<String>,
        val environment: Map<String, String>
    )

    private val rendererPresets = listOf(
        RendererPreset(
            renderer = Renderer.MOBILE_GLUES,
            pojavRenderer = "mobileglues",
            tokens = listOf("libmobileglues.so"),
            preloadTokens = listOf("libmobileglues.so"),
            environment = mapOf(
                "LIBGL_ES" to "3",
                "MG_DIR_PATH" to "${'$'}{cache}/mobileglues"
            )
        ),
        RendererPreset(
            renderer = Renderer.ANGLE,
            pojavRenderer = "opengles3_desktopgl_angle_vulkan",
            tokens = listOf("angle", "libegl_angle", "libglesv2_angle"),
            preloadTokens = listOf("vulkan", "angle", "egl", "glesv2"),
            environment = mapOf(
                "MCLAUNCHER_GLES_DRIVER" to "angle",
                "ANGLE_DEFAULT_PLATFORM" to "vulkan"
            )
        ),
        RendererPreset(
            renderer = Renderer.OPEN_LTW,
            pojavRenderer = "opengles3_ltw",
            tokens = listOf("openltw", "ltw", "tinywrapper"),
            preloadTokens = listOf("openltw", "ltw", "tinywrapper", "egl", "gles"),
            environment = mapOf(
                "LTW_LOG_DIR" to "${'$'}{cache}/openltw",
                "LIBGL_ES" to "3"
            )
        ),
        RendererPreset(
            renderer = Renderer.NG_GL4ES,
            pojavRenderer = "ng-gl4es",
            tokens = listOf("ng_gl4es", "ng-gl4es", "nggl4es"),
            preloadTokens = listOf("ng_gl4es", "ng-gl4es", "nggl4es", "gl4es"),
            environment = mapOf(
                "LIBGL_ES" to "3",
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NORMALIZE" to "1",
                "LIBGL_NOERROR" to "1"
            )
        ),
        RendererPreset(
            renderer = Renderer.GL4ES,
            pojavRenderer = "opengles3",
            tokens = listOf("gl4es"),
            preloadTokens = listOf("gl4es", "egl", "gles"),
            environment = mapOf(
                "LIBGL_ES" to "3",
                "LIBGL_MIPMAP" to "3",
                "LIBGL_NORMALIZE" to "1",
                "LIBGL_NOERROR" to "1"
            )
        ),
        RendererPreset(
            renderer = Renderer.ZINK,
            pojavRenderer = "vulkan_zink",
            tokens = listOf("zink", "mesa", "gallium"),
            preloadTokens = listOf("vulkan", "mesa", "zink", "gallium"),
            environment = mapOf(
                "MESA_LOADER_DRIVER_OVERRIDE" to "zink",
                "GALLIUM_DRIVER" to "zink",
                "MESA_GL_VERSION_OVERRIDE" to "4.6",
                "MESA_GLSL_VERSION_OVERRIDE" to "460"
            )
        ),
        RendererPreset(
            renderer = Renderer.VIRGL,
            pojavRenderer = "opengles3_virgl",
            tokens = listOf("virgl", "virpipe"),
            preloadTokens = listOf("virgl", "virpipe", "mesa"),
            environment = mapOf(
                "GALLIUM_DRIVER" to "virpipe",
                "VTEST_SOCKET_NAME" to "${'$'}{cache}/.virgl_test",
                "MESA_GL_VERSION_OVERRIDE" to "4.3",
                "MESA_GLSL_VERSION_OVERRIDE" to "430"
            )
        ),
        RendererPreset(
            renderer = Renderer.VULKAN,
            pojavRenderer = "vulkan",
            tokens = listOf("vulkan", "lwjgl_vulkan"),
            preloadTokens = listOf("vulkan"),
            environment = mapOf("MCLAUNCHER_GRAPHICS_API" to "vulkan")
        ),
        RendererPreset(
            renderer = Renderer.KRYPTON,
            pojavRenderer = "krypton",
            tokens = listOf("krypton"),
            preloadTokens = listOf("krypton", "egl", "gles"),
            environment = emptyMap()
        ),
        RendererPreset(
            renderer = Renderer.CUSTOM,
            pojavRenderer = "opengles2",
            tokens = emptyList(),
            preloadTokens = emptyList(),
            environment = emptyMap()
        )
    )

    private val driverPresets = listOf(
        DriverPreset(
            driver = GraphicsDriver.SYSTEM,
            tokens = emptyList(),
            preloadTokens = emptyList(),
            environment = mapOf("MCLAUNCHER_GLES_DRIVER" to "system")
        ),
        DriverPreset(
            driver = GraphicsDriver.ANGLE,
            tokens = listOf("angle", "libegl_angle", "libglesv2_angle"),
            preloadTokens = listOf("vulkan", "angle", "egl", "glesv2"),
            environment = mapOf(
                "MCLAUNCHER_GLES_DRIVER" to "angle",
                "ANGLE_DEFAULT_PLATFORM" to "vulkan"
            )
        ),
        DriverPreset(
            driver = GraphicsDriver.TURNIP,
            tokens = listOf("turnip", "freedreno", "vulkan_freedreno"),
            preloadTokens = listOf("vulkan_freedreno", "turnip", "vulkan"),
            environment = mapOf("MCLAUNCHER_VULKAN_DRIVER" to "turnip")
        ),
        DriverPreset(
            driver = GraphicsDriver.PANVK,
            tokens = listOf("panvk", "vulkan_panfrost"),
            preloadTokens = listOf("vulkan_panfrost", "panvk", "vulkan"),
            environment = mapOf("MCLAUNCHER_VULKAN_DRIVER" to "panvk")
        ),
        DriverPreset(
            driver = GraphicsDriver.SWIFTSHADER,
            tokens = listOf("swiftshader", "vk_swiftshader"),
            preloadTokens = listOf("vk_swiftshader", "swiftshader", "vulkan"),
            environment = mapOf("MCLAUNCHER_VULKAN_DRIVER" to "swiftshader")
        )
    )

    fun rendererStatuses(): List<RendererStatus> = Renderer.entries.map { renderer ->
        if (renderer == Renderer.AUTO) {
            val installed = rendererPresets.any { it.renderer != Renderer.CUSTOM && rendererLibraries(it.renderer, it).isNotEmpty() } ||
                rendererLibraries(Renderer.CUSTOM, preset(Renderer.CUSTOM)).isNotEmpty()
            RendererStatus(renderer, installed, rendererRoot().soFiles().size, if (installed) "At least one compatible renderer is installed" else "No renderer pack installed")
        } else {
            val libraries = rendererLibraries(renderer, preset(renderer))
            RendererStatus(
                renderer = renderer,
                installed = libraries.isNotEmpty(),
                libraryCount = libraries.size,
                detail = if (libraries.isNotEmpty()) "${libraries.size} native libraries detected" else "Import a ${renderer.displayName} pack"
            )
        }
    }

    fun driverStatuses(): List<DriverStatus> = GraphicsDriver.entries.map { driver ->
        when (driver) {
            GraphicsDriver.AUTO -> {
                val installed = driverPresets.filter { it.driver != GraphicsDriver.SYSTEM }
                    .any { driverLibraries(it.driver, it).isNotEmpty() }
                DriverStatus(driver, true, driverRoot().soFiles().size, if (installed) "Automatic driver packs available" else "Will use the system driver")
            }
            GraphicsDriver.SYSTEM -> DriverStatus(driver, true, 0, "Built into Android")
            else -> {
                val libraries = driverLibraries(driver, driverPreset(driver))
                DriverStatus(driver, libraries.isNotEmpty(), libraries.size, if (libraries.isNotEmpty()) "${libraries.size} native libraries detected" else "Import a ${driver.displayName} driver pack")
            }
        }
    }

    fun resolve(requestedRenderer: Renderer, requestedDriver: GraphicsDriver, cacheDirectory: File): ResolvedGraphicsStack {
        val renderer = requestedRenderer
            .takeUnless { it == Renderer.AUTO }
            ?.takeIf { rendererLibraries(it, preset(it)).isNotEmpty() }
            ?: chooseAutomaticRenderer()
        val rendererPreset = preset(renderer)
        val rendererDirectory = rendererDirectory(renderer)
            ?: error("${renderer.displayName} is selected, but its native pack is not installed for $architecture")
        val rendererLibraries = rendererLibraries(renderer, rendererPreset)
        require(rendererLibraries.isNotEmpty()) {
            "${renderer.displayName} is selected, but its native pack is not installed for $architecture"
        }
        val rendererManifest = readManifest(rendererDirectory)
        if (renderer == Renderer.CUSTOM) {
            require(!rendererManifest?.pojavRenderer.isNullOrBlank()) {
                "A custom renderer manifest must provide pojavRenderer"
            }
        }

        val driver = requestedDriver
            .takeUnless { it == GraphicsDriver.AUTO }
            ?.takeIf {
                it == GraphicsDriver.SYSTEM ||
                    driverLibraries(it, driverPreset(it)).isNotEmpty()
            }
            ?: chooseAutomaticDriver(renderer)
        val driverPreset = driverPreset(driver)
        val driverDirectory = if (driver == GraphicsDriver.SYSTEM) null else driverDirectory(driver)
        if (driver != GraphicsDriver.SYSTEM) {
            require(driverDirectory != null && driverLibraries(driver, driverPreset).isNotEmpty()) {
                "${driver.displayName} is selected, but its driver pack is not installed for $architecture"
            }
        }
        val driverManifest = driverDirectory?.let(::readManifest)

        val environment = linkedMapOf<String, String>()
        environment += rendererPreset.environment
        environment += driverPreset.environment
        rendererManifest?.environment?.let(environment::putAll)
        driverManifest?.environment?.let(environment::putAll)
        val expandedEnvironment = environment.mapValues { (_, value) -> value.replace("${'$'}{cache}", cacheDirectory.absolutePath) }

        // Known renderer IDs have a fixed native ABI. Do not let a stale or
        // misidentified third-party manifest turn OpenLTW into ANGLE (or any other
        // backend). Only CUSTOM deliberately delegates its token to the manifest.
        val pojavRenderer = if (renderer == Renderer.CUSTOM) {
            rendererManifest?.pojavRenderer
                ?: error("A custom renderer manifest must provide pojavRenderer")
        } else {
            rendererPreset.pojavRenderer
        }

        return ResolvedGraphicsStack(
            renderer = renderer,
            driver = driver,
            pojavRenderer = pojavRenderer,
            searchDirectories = listOfNotNull(rendererDirectory, driverDirectory),
            preloadTokens = (rendererPreset.preloadTokens + driverPreset.preloadTokens +
                rendererManifest.orEmptyPreload() + driverManifest.orEmptyPreload()).distinct(),
            environment = expandedEnvironment
        )
    }

    private fun chooseAutomaticRenderer(): Renderer {
        val priority = listOf(
            Renderer.MOBILE_GLUES,
            Renderer.OPEN_LTW,
            Renderer.NG_GL4ES,
            Renderer.GL4ES,
            Renderer.ANGLE,
            Renderer.ZINK,
            Renderer.KRYPTON,
            Renderer.VIRGL,
            Renderer.VULKAN,
            Renderer.CUSTOM
        )
        return priority.firstOrNull { renderer -> rendererLibraries(renderer, preset(renderer)).isNotEmpty() }
            ?: error("No compatible renderer pack is installed for $architecture")
    }

    private fun chooseAutomaticDriver(renderer: Renderer): GraphicsDriver {
        if (renderer == Renderer.ANGLE) return GraphicsDriver.SYSTEM
        val candidates = if (renderer.requiresVulkan) {
            listOf(GraphicsDriver.TURNIP, GraphicsDriver.PANVK, GraphicsDriver.SYSTEM)
        } else {
            listOf(GraphicsDriver.SYSTEM, GraphicsDriver.ANGLE)
        }
        return candidates.first { driver -> driver == GraphicsDriver.SYSTEM || driverLibraries(driver, driverPreset(driver)).isNotEmpty() }
    }

    private fun preset(renderer: Renderer): RendererPreset = rendererPresets.first { it.renderer == renderer }
    private fun driverPreset(driver: GraphicsDriver): DriverPreset = driverPresets.first { it.driver == driver }

    private fun rendererRoot(): File = layout.engineRendererRootDirectory(architecture)
    private fun driverRoot(): File = layout.engineDriverRootDirectory(architecture)

    private fun rendererDirectory(renderer: Renderer): File? {
        val root = rendererRoot()
        val exact = layout.engineRendererDirectory(architecture, renderer.id)
        if (exact.soFiles().isNotEmpty()) return exact
        packDirectory(root) { it.renderer == renderer }?.let { return it }
        if (renderer == Renderer.CUSTOM) return null
        val tokens = preset(renderer).tokens
        return root.listFiles().orEmpty().filter(File::isDirectory).firstOrNull { directory ->
            matchingLibraries(listOf(directory), tokens).isNotEmpty()
        }
    }

    private fun driverDirectory(driver: GraphicsDriver): File? {
        val root = driverRoot()
        val exact = layout.engineDriverDirectory(architecture, driver.id)
        if (exact.soFiles().isNotEmpty()) return exact
        packDirectory(root) { it.driver == driver }?.let { return it }
        val tokens = driverPreset(driver).tokens
        return root.listFiles().orEmpty().filter(File::isDirectory).firstOrNull { directory ->
            matchingLibraries(listOf(directory), tokens).isNotEmpty()
        }
    }

    private fun packDirectory(root: File, predicate: (InstalledGraphicsPackManifest) -> Boolean): File? {
        if (!root.isDirectory) return null
        return root.listFiles().orEmpty().filter(File::isDirectory).firstOrNull { directory ->
            readManifest(directory)?.let(predicate) == true
        }
    }

    private fun rendererLibraries(renderer: Renderer, preset: RendererPreset): List<File> {
        val directory = rendererDirectory(renderer) ?: return emptyList()
        val all = directory.soFiles()
        val manifestMatches = readManifest(directory)?.renderer == renderer
        return if (manifestMatches || preset.tokens.isEmpty()) all else matchingLibraries(listOf(directory), preset.tokens)
    }

    private fun driverLibraries(driver: GraphicsDriver, preset: DriverPreset): List<File> {
        val directory = driverDirectory(driver) ?: return emptyList()
        val all = directory.soFiles()
        val manifestMatches = readManifest(directory)?.driver == driver
        return if (manifestMatches) all else matchingLibraries(listOf(directory), preset.tokens)
    }

    private fun matchingLibraries(roots: List<File>, tokens: List<String>): List<File> {
        if (tokens.isEmpty()) return emptyList()
        return roots.asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
            .filter { file -> tokens.any { token -> file.name.contains(token, ignoreCase = true) } }
            .distinctBy(File::getAbsolutePath)
            .toList()
    }

    private fun readManifest(directory: File): InstalledGraphicsPackManifest? {
        val file = File(directory, "mclauncher-graphics.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString(InstalledGraphicsPackManifest.serializer(), file.readText()) }.getOrNull()
    }

    private fun InstalledGraphicsPackManifest?.orEmptyPreload(): List<String> = this?.preload.orEmpty()

    private fun File.soFiles(): List<File> = if (!isDirectory) emptyList() else walkTopDown()
        .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
        .toList()
}
