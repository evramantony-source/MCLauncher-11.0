package com.mclauncher.app.engine

import android.content.Context
import android.net.Uri
import android.os.Build
import com.mclauncher.minecraft.MinecraftLayout
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.Renderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class EnginePackManager(
    private val context: Context,
    private val layout: MinecraftLayout,
    private val json: Json = Json { prettyPrint = true; ignoreUnknownKeys = true }
) {
    private val bundledEngineManager = BundledEngineManager(context, layout)

    val architecture: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    suspend fun inspect(): EngineEnvironmentState = withContext(Dispatchers.IO) {
        val bundledInstallError = runCatching { bundledEngineManager.installIfNeeded() }.exceptionOrNull()
        val runtimes = JavaVersion.entries.map(::inspectRuntime)
        val jars = layout.engineJarsDirectory.walkTopDown().count { it.isFile && it.extension.equals("jar", true) }
        val natives = layout.engineNativeDirectory(architecture).walkFiles("so").count()
        val renderers = layout.engineRendererRootDirectory(architecture).walkFiles("so").count()
        val drivers = layout.engineDriverRootDirectory(architecture).walkFiles("so").count()
        val registry = GraphicsRegistry(layout, architecture, json)
        val nativeRoot = layout.engineNativeDirectory(architecture)
        val exactEnginePresent = listOf("libpojavexec.so", "libpojavexec_awt.so", "libglfw.so")
            .all { File(nativeRoot, it).isFile }
        val completeRuntimeSet = runtimes.all(RuntimeStatus::installed)
        val completePayload = bundledInstallError == null && jars > 0 && exactEnginePresent && renderers > 0 && completeRuntimeSet
        EngineEnvironmentState(
            nativeBridgeAvailable = NativeLaunchBridge.isAvailable,
            nativeBridgeDetail = if (NativeLaunchBridge.isAvailable) {
                "Built-in JNI invocation engine is available"
            } else {
                NativeLaunchBridge.unavailableReason
            },
            runtimes = runtimes,
            enginePack = EnginePackStatus(
                installed = completePayload,
                architecture = architecture,
                jarCount = jars,
                nativeCount = natives,
                rendererCount = renderers + drivers,
                detail = when {
                    bundledInstallError != null -> "Bundled engine error: ${bundledInstallError.message}"
                    jars == 0 && natives == 0 -> "Bundled Android engine is missing from this APK"
                    jars == 0 -> "Bundled native libraries were found, but patched LWJGL JARs are missing"
                    !exactEnginePresent -> "Bundled libpojavexec/libpojavexec_awt/libglfw set is incomplete"
                    renderers == 0 -> "No bundled renderer is installed"
                    !completeRuntimeSet -> "One or more bundled Java runtimes are incomplete"
                    else -> "$jars patched JARs, $natives engine libraries, and ${renderers + drivers} graphics libraries installed from the APK"
                }
            ),
            renderers = registry.rendererStatuses(),
            drivers = registry.driverStatuses()
        )
    }

    suspend fun installBundledEngine(
        force: Boolean = false,
        onProgress: (String) -> Unit = {}
    ): EngineEnvironmentState {
        bundledEngineManager.installIfNeeded(force, onProgress)
        return inspect()
    }

    /**
     * Legacy manual import path retained only for developer compatibility tests.
     * Normal users receive the complete engine from the MCLauncher APK. Alpha 01
     * does not expose this path in the normal Settings screen.
     */
    suspend fun importLauncherBundle(
        archive: File,
        sourceName: String,
        onProgress: (String) -> Unit = {}
    ): LauncherBundleImportResult = withContext(Dispatchers.IO) {
        require(archive.isFile) { "Engine bundle does not exist: ${archive.absolutePath}" }
        val staging = File(layout.root, "staging/developer-engine-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            onProgress("Opening engine bundle")
            ArchiveExtractor.extract(archive, staging)
            unpackNestedArchives(staging, maxDepth = 4)

            val allFiles = staging.walkTopDown().filter(File::isFile).toList()
            val jarCandidates = allFiles.filter { file ->
                file.extension.equals("jar", ignoreCase = true) &&
                    listOf("lwjgl", "cacio", "jna", "glfw", "mio", "patcher").any { token ->
                        file.name.contains(token, ignoreCase = true) || file.path.contains(token, ignoreCase = true)
                    }
            }
            val nativeCandidates = allFiles.filter { file ->
                file.extension.equals("so", ignoreCase = true) &&
                    isCompatibleAbiPath(file) && ElfInspector.matches(file, architecture)
            }
            require(jarCandidates.any { it.name.contains("lwjgl", ignoreCase = true) }) {
                "The developer engine archive does not expose patched LWJGL JARs in a supported layout."
            }
            require(nativeCandidates.any { file ->
                listOf("pojav", "glfw", "lwjgl", "awt").any { token ->
                    file.name.contains(token, ignoreCase = true) || file.path.contains(token, ignoreCase = true)
                }
            }) { "No compatible Android GLFW/LWJGL bridge was found for $architecture" }

            onProgress("Installing patched LWJGL and native launch libraries")
            val jarsTarget = layout.engineJarsDirectory.apply { deleteRecursively(); mkdirs() }
            val nativesTarget = layout.engineNativeDirectory(architecture).apply { deleteRecursively(); mkdirs() }
            val rendererLegacy = layout.engineRendererDirectory(architecture, "developer-bundle").apply {
                deleteRecursively(); mkdirs()
            }
            val driverLegacy = layout.engineDriverDirectory(architecture, "developer-bundle").apply {
                deleteRecursively(); mkdirs()
            }

            jarCandidates.distinctBy { it.name to it.length() }.forEach { source ->
                source.copyTo(uniqueTarget(jarsTarget, source.name), overwrite = true)
            }
            nativeCandidates.distinctBy { it.name to it.length() }.forEach { source ->
                val targetDirectory = when (classifyGraphicsFile(source)) {
                    GraphicsFileType.RENDERER -> rendererLegacy
                    GraphicsFileType.DRIVER -> driverLegacy
                    GraphicsFileType.ENGINE -> nativesTarget
                }
                val target = source.copyTo(uniqueTarget(targetDirectory, source.name), overwrite = true)
                makeNativeCodeReadOnly(target)
            }

            layout.engineManifestFile.writeText(
                json.encodeToString(
                    InstalledEngineManifest(
                        architecture = architecture,
                        importedAtEpochMs = System.currentTimeMillis(),
                        sourceName = sourceName,
                        jars = jarsTarget.listFiles().orEmpty().map(File::getName).sorted(),
                        natives = nativesTarget.listFiles().orEmpty().map(File::getName).sorted(),
                        graphics = (rendererLegacy.listFiles().orEmpty().toList() + driverLegacy.listFiles().orEmpty().toList())
                            .map(File::getName).sorted()
                    )
                )
            )

            val runtimeCandidates = allFiles.filter { it.name == "libjvm.so" && ElfInspector.matches(it, architecture) }
                .mapNotNull { jvm ->
                    runCatching {
                        val home = findJavaHome(jvm)
                        val major = declaredJavaMajor(home) ?: inferJavaMajorFromPath(jvm)
                        major?.let { it to home }
                    }.getOrNull()
                }
                .distinctBy { (major, home) -> major to home.absolutePath }

            val installed = mutableListOf<JavaVersion>()
            JavaVersion.entries.forEach { version ->
                val candidate = runtimeCandidates.firstOrNull { it.first == version.major }?.second
                if (candidate != null) {
                    onProgress("Installing Java ${version.major} for $architecture")
                    val target = layout.runtimeHome(version.major, architecture)
                    replaceDirectory(candidate, target)
                    validateRuntime(target, version)
                    File(target, ".mclauncher-runtime.json").writeText(
                        json.encodeToString(
                            InstalledRuntimeManifest(
                                javaMajor = version.major,
                                architecture = architecture,
                                importedAtEpochMs = System.currentTimeMillis(),
                                sourceName = sourceName
                            )
                        )
                    )
                    installed += version
                }
            }

            val missing = JavaVersion.entries.filterNot(installed::contains)
            require(installed.isNotEmpty()) {
                "No mobile OpenJDK runtime could be extracted from the developer engine archive for $architecture"
            }
            val environment = inspect()
            require(environment.enginePack.installed) { environment.enginePack.detail }
            LauncherBundleImportResult(installed, missing, environment)
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun importRuntime(uri: Uri, version: JavaVersion): RuntimeStatus = withContext(Dispatchers.IO) {
        val archive = copyUriToCache(uri, "runtime-${version.major}")
        val staging = File(layout.root, "staging/runtime-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ArchiveExtractor.extract(archive, staging)
            unpackNestedArchives(staging, maxDepth = 2)
            val jvm = staging.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
                ?: error("This archive does not contain an Android Java runtime (libjvm.so was not found)")
            val javaHome = findJavaHome(jvm)
            val target = layout.runtimeHome(version.major, architecture)
            replaceDirectory(javaHome, target)
            validateRuntime(target, version)
            File(target, ".mclauncher-runtime.json").writeText(
                json.encodeToString(
                    InstalledRuntimeManifest(
                        javaMajor = version.major,
                        architecture = architecture,
                        importedAtEpochMs = System.currentTimeMillis(),
                        sourceName = displayName(uri)
                    )
                )
            )
            inspectRuntime(version)
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /**
     * Imports the patched LWJGL/window bridge. Any graphics libraries found in
     * a full launcher APK are preserved in a legacy graphics folder and become
     * selectable through the registry instead of being mixed with JVM natives.
     */
    suspend fun importEnginePack(uri: Uri): EnginePackStatus = withContext(Dispatchers.IO) {
        val archive = copyUriToCache(uri, "engine-pack")
        val staging = File(layout.root, "staging/engine-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ArchiveExtractor.extract(archive, staging)
            unpackNestedArchives(staging, maxDepth = 2)

            val allFiles = staging.walkTopDown().filter(File::isFile).toList()
            val jarCandidates = allFiles.filter { file ->
                file.extension.equals("jar", ignoreCase = true) &&
                    listOf("lwjgl", "cacio", "jna", "glfw").any { token ->
                        file.name.contains(token, ignoreCase = true) || file.path.contains(token, ignoreCase = true)
                    }
            }
            val nativeCandidates = allFiles.filter { file ->
                file.extension.equals("so", ignoreCase = true) && isCompatibleAbiPath(file) && ElfInspector.matches(file, architecture)
            }
            require(jarCandidates.any { it.name.contains("lwjgl", ignoreCase = true) }) {
                "No patched Android LWJGL JARs were found. Select a compatible engine pack or APK."
            }
            require(nativeCandidates.isNotEmpty()) { "No Android native libraries for $architecture were found." }
            require(nativeCandidates.any { file ->
                listOf("glfw", "lwjgl", "pojav", "awt", "cacio").any { token ->
                    file.name.contains(token, ignoreCase = true) || file.path.contains(token, ignoreCase = true)
                }
            }) {
                "Native libraries were found, but none look like an Android LWJGL/window bridge."
            }

            val jarsTarget = layout.engineJarsDirectory.apply { deleteRecursively(); mkdirs() }
            val nativesTarget = layout.engineNativeDirectory(architecture).apply { deleteRecursively(); mkdirs() }
            val rendererLegacy = layout.engineRendererDirectory(architecture, "legacy-import").apply { mkdirs() }
            val driverLegacy = layout.engineDriverDirectory(architecture, "legacy-import").apply { mkdirs() }

            jarCandidates.distinctBy(File::getName).forEach { source ->
                source.copyTo(File(jarsTarget, source.name), overwrite = true)
            }
            nativeCandidates.distinctBy { it.name to it.length() }.forEach { source ->
                val type = classifyGraphicsFile(source)
                val targetDirectory = when (type) {
                    GraphicsFileType.RENDERER -> rendererLegacy
                    GraphicsFileType.DRIVER -> driverLegacy
                    GraphicsFileType.ENGINE -> nativesTarget
                }
                val target = source.copyTo(uniqueTarget(targetDirectory, source.name), overwrite = true)
                makeNativeCodeReadOnly(target)
            }

            layout.engineManifestFile.writeText(
                json.encodeToString(
                    InstalledEngineManifest(
                        architecture = architecture,
                        importedAtEpochMs = System.currentTimeMillis(),
                        sourceName = displayName(uri),
                        jars = jarsTarget.listFiles().orEmpty().map(File::getName).sorted(),
                        natives = nativesTarget.listFiles().orEmpty().map(File::getName).sorted(),
                        graphics = (rendererLegacy.listFiles().orEmpty().toList() + driverLegacy.listFiles().orEmpty().toList())
                            .map(File::getName).sorted()
                    )
                )
            )
            inspect().enginePack
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    /** Imports one renderer or GLES/Vulkan driver plugin archive. */
    suspend fun importGraphicsPack(uri: Uri): InstalledGraphicsPackManifest = withContext(Dispatchers.IO) {
        val archive = copyUriToCache(uri, "graphics-pack")
        val staging = File(layout.root, "staging/graphics-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ArchiveExtractor.extract(archive, staging)
            unpackNestedArchives(staging, maxDepth = 2)
            val files = staging.walkTopDown().filter(File::isFile).toList()
            val nativeFiles = files.filter { file ->
                file.extension.equals("so", ignoreCase = true) && isCompatibleAbiPath(file) && ElfInspector.matches(file, architecture)
            }
            require(nativeFiles.isNotEmpty()) { "This archive has no $architecture Android native libraries" }

            val manifestFile = files.firstOrNull { it.name.equals("mclauncher-graphics.json", true) }
            val suppliedManifest = manifestFile?.let {
                runCatching { json.decodeFromString(GraphicsPackManifest.serializer(), it.readText()) }
                    .getOrElse { error("Invalid mclauncher-graphics.json: ${it.message}") }
            }
            val manifest = suppliedManifest ?: inferGraphicsIdentity(nativeFiles).let { inferred ->
                GraphicsPackManifest(
                    id = inferred.id,
                    name = inferred.name,
                    kind = inferred.kind,
                    renderer = inferred.renderer,
                    driver = inferred.driver,
                    pojavRenderer = inferred.pojavRenderer
                )
            }
            require(manifest.schemaVersion == 1) { "Unsupported graphics-pack schema ${manifest.schemaVersion}" }
            require(manifest.architecture == null || manifest.architecture == architecture) {
                "This pack targets ${manifest.architecture}; this device needs $architecture"
            }
            require((manifest.kind == GraphicsPackKind.RENDERER && manifest.renderer != null) ||
                (manifest.kind == GraphicsPackKind.DRIVER && manifest.driver != null)) {
                "Graphics manifest must specify a renderer or driver"
            }
            if (manifest.renderer == Renderer.CUSTOM) {
                require(!manifest.pojavRenderer.isNullOrBlank()) {
                    "Custom renderer plugins must specify pojavRenderer"
                }
            }

            val safeId = manifest.id.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-')
            require(safeId.isNotBlank()) { "Graphics pack id is invalid" }
            val target = when (manifest.kind) {
                GraphicsPackKind.RENDERER -> layout.engineRendererDirectory(architecture, safeId)
                GraphicsPackKind.DRIVER -> layout.engineDriverDirectory(architecture, safeId)
            }.apply { deleteRecursively(); mkdirs() }

            val copied = mutableListOf<String>()
            nativeFiles.distinctBy { it.name to it.length() }.forEach { source ->
                val output = source.copyTo(uniqueTarget(target, source.name), overwrite = true)
                makeNativeCodeReadOnly(output)
                copied += output.name
            }
            files.filter { file ->
                file.extension.lowercase() in setOf("json", "conf", "cfg", "txt") &&
                    !file.name.equals("mclauncher-graphics.json", true)
            }.take(32).forEach { source ->
                val output = source.copyTo(uniqueTarget(target, source.name), overwrite = true)
                copied += output.name
            }

            val installed = InstalledGraphicsPackManifest(
                id = safeId,
                name = manifest.name,
                version = manifest.version,
                kind = manifest.kind,
                architecture = architecture,
                renderer = manifest.renderer,
                driver = manifest.driver,
                pojavRenderer = manifest.pojavRenderer,
                preload = manifest.preload,
                environment = manifest.environment,
                files = copied.sorted(),
                sourceName = displayName(uri),
                sourceProject = manifest.sourceProject,
                license = manifest.license,
                importedAtEpochMs = System.currentTimeMillis()
            )
            File(target, "mclauncher-graphics.json").writeText(json.encodeToString(installed))
            installed
        } finally {
            archive.delete()
            staging.deleteRecursively()
        }
    }

    private fun inferGraphicsIdentity(files: List<File>): GraphicsIdentity {
        val searchable = files.joinToString(" ") { (it.name + " " + it.invariantSeparatorsPath).lowercase() }
        return when {
            listOf("mobileglues", "libmg", "mgbridge").any(searchable::contains) ->
                GraphicsIdentity("mobileglues", "MobileGlues", GraphicsPackKind.RENDERER, Renderer.MOBILE_GLUES, null, "opengles2")
            "krypton" in searchable ->
                GraphicsIdentity("krypton", "Krypton Wrapper", GraphicsPackKind.RENDERER, Renderer.KRYPTON, null, "opengles2")
            listOf("openltw", "tinywrapper", "ltw").any(searchable::contains) ->
                GraphicsIdentity("openltw", "OpenLTW / LTW", GraphicsPackKind.RENDERER, Renderer.OPEN_LTW, null, "opengles2")
            listOf("ng-gl4es", "ng_gl4es", "nggl4es").any(searchable::contains) ->
                GraphicsIdentity("ng-gl4es", "NG-GL4ES", GraphicsPackKind.RENDERER, Renderer.NG_GL4ES, null, "opengles2")
            "gl4es" in searchable ->
                GraphicsIdentity("gl4es", "GL4ES", GraphicsPackKind.RENDERER, Renderer.GL4ES, null, "opengles2")
            "virgl" in searchable || "virpipe" in searchable ->
                GraphicsIdentity("virgl", "VirGL", GraphicsPackKind.RENDERER, Renderer.VIRGL, null, "opengles3_virgl")
            "zink" in searchable || ("mesa" in searchable && "vulkan" in searchable) ->
                GraphicsIdentity("zink", "Zink", GraphicsPackKind.RENDERER, Renderer.ZINK, null, "opengles2")
            "turnip" in searchable || "freedreno" in searchable ->
                GraphicsIdentity("turnip", "Turnip", GraphicsPackKind.DRIVER, null, GraphicsDriver.TURNIP, null)
            "panvk" in searchable || "panfrost" in searchable ->
                GraphicsIdentity("panvk", "PanVK", GraphicsPackKind.DRIVER, null, GraphicsDriver.PANVK, null)
            "swiftshader" in searchable ->
                GraphicsIdentity("swiftshader", "SwiftShader", GraphicsPackKind.DRIVER, null, GraphicsDriver.SWIFTSHADER, null)
            "angle" in searchable ->
                GraphicsIdentity("angle-renderer", "ANGLE renderer", GraphicsPackKind.RENDERER, Renderer.ANGLE, null, "opengles3_desktopgl_angle_vulkan")
            "vulkan" in searchable ->
                GraphicsIdentity("vulkan", "Native Vulkan", GraphicsPackKind.RENDERER, Renderer.VULKAN, null, "vulkan")
            else -> error("Could not identify this graphics pack. Add a mclauncher-graphics.json manifest.")
        }
    }

    private fun inspectRuntime(version: JavaVersion): RuntimeStatus {
        val home = layout.runtimeHome(version.major, architecture)
        val jvm = home.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
        val javaLibrary = home.walkTopDown().firstOrNull { it.isFile && it.name == "libjava.so" }
        val installed = jvm != null && javaLibrary != null && ElfInspector.matches(jvm, architecture)
        return RuntimeStatus(
            version = version,
            architecture = architecture,
            installed = installed,
            javaHome = home.absolutePath,
            detail = if (installed) "Ready: ${jvm!!.parentFile?.absolutePath}" else "Not installed for $architecture"
        )
    }

    private fun validateRuntime(home: File, version: JavaVersion) {
        val jvm = home.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
            ?: error("Imported Java ${version.major} runtime is missing libjvm.so")
        require(ElfInspector.matches(jvm, architecture)) {
            "The runtime architecture (${ElfInspector.architecture(jvm) ?: "unknown"}) does not match $architecture"
        }
        validateJavaVersion(home, version)
        require(home.walkTopDown().any { it.isFile && it.name == "libjava.so" }) {
            "Imported Java ${version.major} runtime is missing libjava.so"
        }
        home.walkTopDown().filter { it.isFile && (it.name == "java" || it.extension == "so") }
            .forEach(::makeNativeCodeReadOnly)
    }

    private fun declaredJavaMajor(home: File): Int? {
        val release = home.walkTopDown().maxDepth(4).firstOrNull { it.isFile && it.name == "release" } ?: return null
        val declared = release.useLines { lines ->
            lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')?.trim()?.trim('"')
        } ?: return null
        val components = declared.substringBefore('-').split('.')
        return if (components.firstOrNull() == "1") components.getOrNull(1)?.toIntOrNull()
        else components.firstOrNull()?.toIntOrNull()
    }

    private fun inferJavaMajorFromPath(file: File): Int? {
        val normalized = file.invariantSeparatorsPath.lowercase()
        val match = Regex("(?:jre|jdk|java|runtime)[-_]?(8|17|21|25)(?:/|[-_])").find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun validateJavaVersion(home: File, version: JavaVersion) {
        val release = home.walkTopDown().maxDepth(3).firstOrNull { it.isFile && it.name == "release" } ?: return
        val declared = release.useLines { lines ->
            lines.firstOrNull { it.startsWith("JAVA_VERSION=") }
                ?.substringAfter('=')?.trim()?.trim('"')
        } ?: return
        val components = declared.substringBefore('-').split('.')
        val major = if (components.firstOrNull() == "1") components.getOrNull(1)?.toIntOrNull() else components.firstOrNull()?.toIntOrNull()
        require(major == null || major == version.major) {
            "Selected Java ${version.major}, but the imported runtime reports Java $declared"
        }
    }

    private fun makeNativeCodeReadOnly(file: File) {
        file.setReadable(true, false)
        file.setExecutable(true, false)
        file.setWritable(false, false)
    }

    private fun findJavaHome(jvm: File): File {
        var current: File? = jvm.parentFile
        repeat(5) {
            val candidate = current ?: return@repeat
            if (candidate.walkTopDown().maxDepth(4).any { it.isFile && it.name == "libjava.so" }) {
                val release = File(candidate, "release")
                val bin = File(candidate, "bin")
                if (release.isFile || bin.isDirectory || candidate.name.startsWith("jre", true) || candidate.name.startsWith("java", true)) return candidate
            }
            current = candidate.parentFile
        }
        return jvm.parentFile?.parentFile?.parentFile ?: error("Cannot determine Java home")
    }

    private fun replaceDirectory(source: File, target: File) {
        val backup = File(target.parentFile, target.name + ".backup")
        backup.deleteRecursively()
        if (target.exists()) target.renameTo(backup)
        try {
            target.mkdirs()
            source.copyRecursively(target, overwrite = true)
            backup.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (backup.exists()) backup.renameTo(target)
            throw error
        }
    }

    private fun copyUriToCache(uri: Uri, prefix: String): File {
        val target = File(context.cacheDir, "$prefix-${UUID.randomUUID()}.archive")
        context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(target).use(input::copyTo) }
            ?: error("Could not open selected file")
        return target
    }

    private fun displayName(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: "selected archive"

    private fun isCompatibleAbiPath(file: File): Boolean {
        val path = file.invariantSeparatorsPath.lowercase()
        val aliases = when (architecture) {
            "arm64-v8a" -> listOf("arm64-v8a", "aarch64", "arm64")
            "armeabi-v7a" -> listOf("armeabi-v7a", "arm32", "armhf", "aarch32")
            "x86_64" -> listOf("x86_64", "amd64")
            else -> listOf(architecture.lowercase())
        }
        val mentionsKnownAbi = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86", "aarch64", "arm64", "arm32").any(path::contains)
        return !mentionsKnownAbi || aliases.any(path::contains)
    }

    private fun unpackNestedArchives(root: File, maxDepth: Int) {
        val extracted = mutableSetOf<String>()
        repeat(maxDepth) { depth ->
            val archives = root.walkTopDown().filter { file ->
                file.isFile && (file.name.endsWith(".zip", true) || file.name.endsWith(".apk", true) ||
                    file.name.endsWith(".tar.xz", true) || file.name.endsWith(".txz", true) ||
                    file.name.endsWith(".tar.gz", true) || file.name.endsWith(".tgz", true))
            }.filter { archive ->
                runCatching { extracted.add(archive.canonicalPath) }.getOrDefault(false)
            }.toList()
            if (archives.isEmpty()) return
            archives.forEachIndexed { index, nested ->
                val safeName = nested.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = File(nested.parentFile, ".nested-$depth-$index-$safeName")
                runCatching { ArchiveExtractor.extract(nested, destination) }
            }
        }
    }

    private fun classifyGraphicsFile(file: File): GraphicsFileType {
        val value = (file.name + " " + file.invariantSeparatorsPath).lowercase()
        if (listOf("angle", "turnip", "freedreno", "panvk", "panfrost", "swiftshader").any(value::contains)) return GraphicsFileType.DRIVER
        if (listOf("mobileglues", "openltw", "tinywrapper", "gl4es", "virgl", "virpipe", "zink", "mesa", "vulkan", "egl", "gles").any(value::contains)) return GraphicsFileType.RENDERER
        return GraphicsFileType.ENGINE
    }

    private fun uniqueTarget(directory: File, fileName: String): File {
        val first = File(directory, fileName)
        if (!first.exists()) return first
        val stem = fileName.substringBeforeLast('.', fileName)
        val extension = fileName.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = File(directory, if (extension.isBlank()) "$stem-$index" else "$stem-$index.$extension")
            if (!candidate.exists()) return candidate
            index++
        }
    }

    private enum class GraphicsFileType { ENGINE, RENDERER, DRIVER }

    private data class GraphicsIdentity(
        val id: String,
        val name: String,
        val kind: GraphicsPackKind,
        val renderer: Renderer?,
        val driver: GraphicsDriver?,
        val pojavRenderer: String?
    )

    @Serializable
    private data class InstalledRuntimeManifest(
        val javaMajor: Int,
        val architecture: String,
        val importedAtEpochMs: Long,
        val sourceName: String
    )

    @Serializable
    private data class InstalledEngineManifest(
        val architecture: String,
        val importedAtEpochMs: Long,
        val sourceName: String,
        val jars: List<String>,
        val natives: List<String>,
        val graphics: List<String>
    )
}

private fun File.walkFiles(extension: String): List<File> =
    if (!isDirectory) emptyList() else walkTopDown().filter { it.isFile && it.extension.equals(extension, true) }.toList()
