package com.mclauncher.app.engine

import android.content.Context
import android.os.Build
import com.mclauncher.minecraft.MinecraftLayout
import com.mclauncher.model.JavaVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Installs the launch engine already embedded inside MCLauncher's APK.
 *
 * The build workflow compiles a pinned LGPL engine source revision and vendors
 * the resulting native libraries, patched LWJGL artifacts, renderer libraries,
 * and Java runtime archives into assets/bundled_engine. No launcher APK is ever
 * downloaded or extracted on the user's Android device.
 */
class BundledEngineManager(
    private val context: Context,
    private val layout: MinecraftLayout
) {
    private val installMutex = Mutex()

    private val architecture: String
        get() = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

    suspend fun installIfNeeded(
        force: Boolean = false,
        progress: (String) -> Unit = {}
    ) = installMutex.withLock {
        withContext(Dispatchers.IO) {
            val version = readAssetText("bundled_engine/bundle-version.txt")?.trim()
                ?: error("This APK was built without the bundled engine payload")
            val marker = File(layout.engineDirectory, ".bundled-engine-$architecture.version")
            if (!force && marker.isFile && marker.readText().trim() == version && basicPayloadPresent()) {
                return@withContext
            }

            progress("Installing MCLauncher's bundled engine")
            layout.ensureBaseDirectories()
            // Replace the payload under one install lock so Play and
            // background inspection cannot observe half-copied engine directories.
            layout.engineJarsDirectory.deleteRecursively()
            layout.engineNativeDirectory(architecture).deleteRecursively()
            layout.engineRendererRootDirectory(architecture).deleteRecursively()
            layout.engineDriverRootDirectory(architecture).deleteRecursively()
            copyAssetTree("bundled_engine/common/jars", layout.engineJarsDirectory)
            copyAssetTree("bundled_engine/$architecture/natives", layout.engineNativeDirectory(architecture))
            copyAssetTree("bundled_engine/$architecture/renderers", layout.engineRendererRootDirectory(architecture))
            copyAssetTree("bundled_engine/$architecture/drivers", layout.engineDriverRootDirectory(architecture))

            JavaVersion.entries.forEach { javaVersion ->
                val runtimeAssets = listOf(
                    "bundled_engine/common/runtimes/java-${javaVersion.major}",
                    "bundled_engine/$architecture/runtimes/java-${javaVersion.major}"
                ).flatMap { root ->
                    listAssetFiles(root)
                        .filter { it.endsWith(".tar.xz", true) || it.endsWith(".tar.gz", true) || it.endsWith(".tgz", true) }
                        .sorted()
                }
                if (runtimeAssets.isEmpty()) return@forEach

                progress("Installing bundled Java ${javaVersion.major}")
                val staging = File(layout.root, "staging/bundled-java-${javaVersion.major}-$architecture").apply {
                    deleteRecursively()
                    mkdirs()
                }
                val archives = mutableListOf<File>()
                try {
                    runtimeAssets.forEachIndexed { index, assetPath ->
                        val extension = when {
                            assetPath.endsWith(".tar.xz", true) -> ".tar.xz"
                            assetPath.endsWith(".tgz", true) -> ".tgz"
                            else -> ".tar.gz"
                        }
                        val archive = File(context.cacheDir, "mclauncher-java-${javaVersion.major}-$architecture-$index$extension")
                        context.assets.open(assetPath).use { input ->
                            FileOutputStream(archive).use(input::copyTo)
                        }
                        archives += archive
                        ArchiveExtractor.extract(archive, staging)
                    }

                    val javaHome = findJavaHome(staging)
                        ?: error("Bundled Java ${javaVersion.major} is invalid: bin/java or libjvm.so is missing")
                    val target = layout.runtimeHome(javaVersion.major, architecture)
                    replaceDirectory(javaHome, target)
                    prepareRuntimePermissions(target)
                } finally {
                    archives.forEach(File::delete)
                    staging.deleteRecursively()
                }
            }

            require(basicPayloadPresent()) {
                "The APK does not contain a complete $architecture launch engine. Re-run the Build Android APK workflow."
            }
            marker.parentFile?.mkdirs()
            marker.writeText(version)
        }
    }

    fun packagedPayloadAvailable(): Boolean =
        assetExists("bundled_engine/bundle-version.txt") &&
            assetDirectoryNotEmpty("bundled_engine/common/jars") &&
            assetDirectoryNotEmpty("bundled_engine/$architecture/natives")

    private fun basicPayloadPresent(): Boolean {
        val lwjgl = layout.engineJarsDirectory.walkTopDown().any {
            it.isFile && it.extension.equals("jar", true) && it.name.contains("lwjgl", true)
        }
        val nativeRoot = layout.engineNativeDirectory(architecture)
        val engine = File(nativeRoot, "libpojavexec.so").isFile
        val awt = File(nativeRoot, "libpojavexec_awt.so").isFile
        val glfw = File(nativeRoot, "libglfw.so").isFile
        val renderer = layout.engineRendererRootDirectory(architecture).walkTopDown().any {
            it.isFile && it.extension.equals("so", true)
        }
        val runtimes = JavaVersion.entries.all { version ->
            val home = layout.runtimeHome(version.major, architecture)
            File(home, "bin/java").isFile && home.walkTopDown().any { it.isFile && it.name == "libjvm.so" }
        }
        return lwjgl && engine && awt && glfw && renderer && runtimes
    }

    private fun prepareRuntimePermissions(target: File) {
        File(target, "bin/java").setExecutable(true, true)
        target.walkTopDown().filter { it.isFile && (it.extension == "so" || it.parentFile?.name == "bin") }.forEach {
            it.setReadable(true, true)
            it.setExecutable(true, true)
        }
    }

    private fun copyAssetTree(assetPath: String, destination: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) return
        destination.mkdirs()
        children.forEach { child ->
            val childAsset = "$assetPath/$child"
            val nested = context.assets.list(childAsset).orEmpty()
            val target = File(destination, child)
            if (nested.isNotEmpty()) {
                copyAssetTree(childAsset, target)
            } else {
                target.parentFile?.mkdirs()
                context.assets.open(childAsset).use { input ->
                    FileOutputStream(target).use(input::copyTo)
                }
                if (target.name == "java" || target.extension.equals("so", true)) {
                    target.setExecutable(true, true)
                }
            }
        }
    }

    private fun listAssetFiles(path: String): List<String> {
        val children = runCatching { context.assets.list(path).orEmpty() }.getOrDefault(emptyArray())
        return children.flatMap { child ->
            val childPath = "$path/$child"
            val nested = runCatching { context.assets.list(childPath).orEmpty() }.getOrDefault(emptyArray())
            if (nested.isEmpty()) listOf(childPath) else listAssetFiles(childPath)
        }
    }

    private fun assetExists(path: String): Boolean = runCatching {
        context.assets.open(path).use { }
        true
    }.getOrDefault(false)

    private fun assetDirectoryNotEmpty(path: String): Boolean =
        runCatching { context.assets.list(path).orEmpty().isNotEmpty() }.getOrDefault(false)

    private fun readAssetText(path: String): String? = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun findJavaHome(root: File): File? {
        val javaBinary = root.walkTopDown().firstOrNull { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
        val jvm = root.walkTopDown().firstOrNull { it.isFile && it.name == "libjvm.so" }
        if (javaBinary == null || jvm == null) return null
        var cursor: File? = javaBinary.parentFile?.parentFile
        while (cursor != null && cursor != root.parentFile) {
            if (File(cursor, "bin/java").isFile && cursor.walkTopDown().any { it.isFile && it.name == "libjvm.so" }) {
                return cursor
            }
            cursor = cursor.parentFile
        }
        return root
    }

    private fun replaceDirectory(source: File, destination: File) {
        val temporary = File(destination.parentFile, destination.name + ".new")
        temporary.deleteRecursively()
        source.copyRecursively(temporary, overwrite = true)
        destination.deleteRecursively()
        if (!temporary.renameTo(destination)) {
            temporary.copyRecursively(destination, overwrite = true)
            temporary.deleteRecursively()
        }
        require(destination.isDirectory) { "Could not install ${destination.name}" }
    }
}
