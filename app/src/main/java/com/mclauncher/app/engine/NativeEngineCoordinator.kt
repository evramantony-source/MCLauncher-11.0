package com.mclauncher.app.engine

import android.content.Context
import android.view.Surface
import com.mclauncher.minecraft.LaunchPlan
import com.mclauncher.minecraft.MinecraftLayout
import com.mclauncher.minecraft.ToolLaunchPlan
import com.mclauncher.model.GraphicsDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class NativeEngineCoordinator(
    private val context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun launch(
        planFile: File,
        surface: Surface,
        sessionLog: File? = null
    ): Int = withContext(Dispatchers.IO) {
        require(NativeLaunchBridge.isAvailable) { NativeLaunchBridge.unavailableReason }
        require(planFile.isFile) { "Launch plan does not exist: ${planFile.absolutePath}" }

        val plan = json.decodeFromString(LaunchPlan.serializer(), planFile.readText())
        val javaHome = File(plan.runtime.javaHome)
        val jvm = findFile(javaHome, "libjvm.so")
            ?: error("Java ${plan.runtime.javaVersion.major} runtime is incomplete: libjvm.so is missing")
        require(jvm.isFile) { "Java runtime is incomplete" }

        val root = planFile.parentFile?.parentFile ?: error("Cannot resolve launcher data directory")
        val layout = MinecraftLayout(root)
        val engineNatives = layout.engineNativeDirectory(plan.runtime.architecture)
        listOf("libpojavexec.so", "libpojavexec_awt.so", "libglfw.so").forEach { required ->
            require(File(engineNatives, required).isFile) {
                "Bundled Android launch engine is incomplete for ${plan.runtime.architecture}: $required is missing"
            }
        }
        val awtCompatibilityLibrary = prepareRuntimeAwtCompatibility(javaHome, engineNatives)
        sessionLog?.appendText(
            "Prepared AWT compatibility library ${awtCompatibilityLibrary.absolutePath}\n"
        )
        val cacheDirectory = File(plan.workingDirectory, ".mclauncher-cache").apply { mkdirs() }
        val graphics = GraphicsRegistry(layout, plan.runtime.architecture, json)
            .resolve(plan.renderer, plan.graphicsDriver, cacheDirectory)
        sessionLog?.appendText(
            "Resolved graphics renderer=${graphics.renderer.id} " +
                "(requested=${plan.renderer.id}), driver=${graphics.driver.id} " +
                "(requested=${plan.graphicsDriver.id}), Minecraft API=" +
                plan.minecraftGraphicsApi.optionsValue + "\n"
        )

        val environment = LinkedHashMap(plan.environment)
        val appNativeDirectory = context.applicationInfo.nativeLibraryDir
        val runtimeLibraryDirectories = listOfNotNull(
            findFile(javaHome, "libjvm.so")?.parentFile,
            findFile(javaHome, "libjava.so")?.parentFile,
            findFile(javaHome, "libjli.so")?.parentFile
        )
        // Patched engine and graphics libraries must precede Mojang's desktop natives.
        val nativeSearchDirectories = buildList {
            add(engineNatives)
            addAll(graphics.searchDirectories)
            add(File(appNativeDirectory))
            addAll(runtimeLibraryDirectories)
            add(File(plan.nativesDirectory))
        }.filter(File::isDirectory).distinctBy(File::getAbsolutePath)

        val nativePath = nativeSearchDirectories.joinToString(File.pathSeparator) { it.absolutePath }
        environment["JAVA_HOME"] = javaHome.absolutePath
        environment["HOME"] = plan.workingDirectory
        environment["TMPDIR"] = cacheDirectory.absolutePath
        environment["PATH"] = listOf(File(javaHome, "bin").absolutePath, "/system/bin", "/system/xbin")
            .joinToString(File.pathSeparator)
        environment["LD_LIBRARY_PATH"] = nativePath
        environment["POJAV_NATIVEDIR"] = if (engineNatives.isDirectory) engineNatives.absolutePath else appNativeDirectory
        // Renderer selection is an internal MCLauncher concern. Sodium treats the
        // legacy POJAV_RENDERER variable as a hard Android-launcher block even after
        // the renderer has initialized successfully, so never expose it to Minecraft.
        environment["MCLAUNCHER_RENDERER_TOKEN"] = graphics.pojavRenderer
        environment["AWTSTUB_WIDTH"] = plan.windowWidth.toString()
        environment["AWTSTUB_HEIGHT"] = plan.windowHeight.toString()
        environment["MESA_GLSL_CACHE_DIR"] = cacheDirectory.absolutePath
        environment["MESA_SHADER_CACHE_DIR"] = cacheDirectory.absolutePath
        environment["MCLAUNCHER_ENGINE_NATIVES"] = engineNatives.absolutePath
        environment["MCLAUNCHER_RENDERER"] = graphics.renderer.id
        environment["MCLAUNCHER_GRAPHICS_DRIVER"] = graphics.driver.id
        sessionLog?.let { environment["MCLAUNCHER_SESSION_LOG"] = it.absolutePath }
        environment.putIfAbsent("FORCE_VSYNC", "false")
        environment.putIfAbsent("LIBGL_NOERROR", "1")
        environment.putIfAbsent("LIBGL_NOINTOVLHACK", "1")
        environment.putIfAbsent("allow_higher_compat_version", "true")
        environment.putIfAbsent("force_glsl_extensions_warn", "true")
        environment.putIfAbsent("allow_glsl_extension_directive_midshader", "true")
        environment.putAll(graphics.environment)
        environment.remove("POJAV_RENDERER")
        environment.remove("POJAV_LAUNCHER")
        environment["MG_DIR_PATH"]?.let { path ->
            require(File(path).apply { mkdirs() }.isDirectory) {
                "Could not prepare MobileGlues data directory: $path"
            }
        }
        configureDriverPaths(graphics, environment)

        val jvmArguments = stripClasspathPair(plan.jvmArguments)
        val allSupportJars = File(layout.engineJarsDirectory, "support").walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy(File::getAbsolutePath)
            .toList()
        val cacio8Jars = allSupportJars.filter { file ->
            file.invariantSeparatorsPath.contains("/caciocavallo/", ignoreCase = true)
        }
        val cacio17Jars = allSupportJars.filter { file ->
            file.invariantSeparatorsPath.contains("/caciocavallo17/", ignoreCase = true)
        }
        val selectedCacioJars = if (plan.runtime.javaVersion.major == 8) cacio8Jars else cacio17Jars
        val supportJars = (allSupportJars - cacio8Jars.toSet() - cacio17Jars.toSet() + selectedCacioJars)
            .distinctBy(File::getAbsolutePath)
        val launchClasspath = (supportJars.map(File::getAbsolutePath) + plan.classpath)
            .distinct()
            .joinToString(File.pathSeparator)
        putSystemProperty(jvmArguments, "java.class.path", launchClasspath)

        // Caciocavallo is packaged in separate Java 8 and Java 17+ forms. Mixing both
        // variants in one VM causes duplicate toolkit classes and early startup failure.
        if (selectedCacioJars.isNotEmpty()) {
            if (plan.runtime.javaVersion.major == 8) {
                jvmArguments.removeAll { it.startsWith("-Xbootclasspath/") }
                jvmArguments += "-Xbootclasspath/p:${selectedCacioJars.joinToString(File.pathSeparator) { it.absolutePath }}"
            } else {
                val agent = selectedCacioJars.first()
                if (jvmArguments.none { it == "-javaagent:${agent.absolutePath}" }) {
                    jvmArguments += "-javaagent:${agent.absolutePath}"
                }
                addJavaDesktopModuleAccess(jvmArguments)
            }
        }
        supportJars.firstOrNull { it.name.equals("MioLibPatcher.jar", ignoreCase = true) }
            ?.let { agent ->
                if (jvmArguments.none { it.startsWith("-javaagent:${agent.absolutePath}") }) {
                    jvmArguments += "-javaagent:${agent.absolutePath}"
                }
            }
        putSystemProperty(jvmArguments, "java.home", javaHome.absolutePath)
        putSystemProperty(jvmArguments, "user.home", plan.workingDirectory)
        putSystemProperty(jvmArguments, "user.dir", plan.workingDirectory)
        val tempDirectory = File(plan.workingDirectory, ".mclauncher-tmp").apply { mkdirs() }
        require(tempDirectory.isDirectory) {
            "Could not prepare Java temporary directory: ${tempDirectory.absolutePath}"
        }
        val lwjglExtractDirectory = File(plan.workingDirectory, ".lwjgl").apply { mkdirs() }
        require(lwjglExtractDirectory.isDirectory) {
            "Could not prepare LWJGL native directory: ${lwjglExtractDirectory.absolutePath}"
        }
        putSystemProperty(jvmArguments, "java.io.tmpdir", tempDirectory.absolutePath)
        putSystemProperty(jvmArguments, "jna.tmpdir", tempDirectory.absolutePath)
        selectJnaNativeDirectory(plan.classpath, engineNatives)?.let { jnaDirectory ->
            putSystemProperty(jvmArguments, "jna.boot.library.path", jnaDirectory.absolutePath)
            sessionLog?.appendText(
                "Prepared JNA native compatibility directory ${jnaDirectory.absolutePath}\n"
            )
        }
        putSystemProperty(jvmArguments, "org.lwjgl.system.SharedLibraryExtractPath", lwjglExtractDirectory.absolutePath)
        putSystemProperty(jvmArguments, "org.lwjgl.system.allocator", "system")
        putSystemProperty(jvmArguments, "mclauncher.renderer", graphics.renderer.id)
        putSystemProperty(jvmArguments, "mclauncher.graphicsDriver", graphics.driver.id)
        putSystemProperty(jvmArguments, "mclauncher.graphicsApi", plan.minecraftGraphicsApi.optionsValue)
        if (selectedCacioJars.isNotEmpty()) {
            putSystemProperty(jvmArguments, "java.awt.headless", "false")
            putSystemProperty(jvmArguments, "cacio.managed.screensize", "${plan.windowWidth}x${plan.windowHeight}")
            putSystemProperty(jvmArguments, "cacio.font.fontmanager", "sun.awt.X11FontManager")
            putSystemProperty(jvmArguments, "cacio.font.fontscaler", "sun.font.FreetypeFontScaler")
            putSystemProperty(jvmArguments, "swing.defaultlaf", "javax.swing.plaf.metal.MetalLookAndFeel")
            val cacioPackage = if (plan.runtime.javaVersion.major == 8) "net.java.openjdk.cacio.ctc" else "com.github.caciocavallosilano.cacio.ctc"
            putSystemProperty(jvmArguments, "awt.toolkit", "$cacioPackage.CTCToolkit")
            putSystemProperty(jvmArguments, "java.awt.graphicsenv", "$cacioPackage.CTCGraphicsEnvironment")
        }
        findFile(engineNatives, "libopenal.so")?.let { putSystemProperty(jvmArguments, "org.lwjgl.openal.libname", it.absolutePath) }
        (findFile(engineNatives, "libfreetype.so") ?: findFile(javaHome, "libfreetype.so"))
            ?.let { putSystemProperty(jvmArguments, "org.lwjgl.freetype.libname", it.absolutePath) }
        resolveLwjglOpenGlLibrary(graphics)?.let { rendererLibrary ->
            putSystemProperty(jvmArguments, "org.lwjgl.opengl.libname", rendererLibrary.absolutePath)
            sessionLog?.appendText(
                "Prepared LWJGL OpenGL library ${rendererLibrary.absolutePath}\n"
            )
        }
        putSystemProperty(jvmArguments, "org.lwjgl.vulkan.libname", "libvulkan.so")
        putSystemProperty(jvmArguments, "org.lwjgl.spvc.libname", "spirv-cross-c-shared")
        putSystemProperty(jvmArguments, "glfwstub.windowWidth", plan.windowWidth.toString())
        putSystemProperty(jvmArguments, "glfwstub.windowHeight", plan.windowHeight.toString())
        jvmArguments.removeAll { it.startsWith("-XX:ActiveProcessorCount=") }
        jvmArguments += "-XX:ActiveProcessorCount=${Runtime.getRuntime().availableProcessors()}"
        putSystemProperty(jvmArguments, "java.library.path", nativePath)

        val preload = buildPreloadList(
            javaHome = javaHome,
            engineNatives = engineNatives,
            graphics = graphics
        )

        NativeLaunchBridge.nativeStart(
            context = context,
            javaHome = javaHome.absolutePath,
            workingDirectory = plan.workingDirectory,
            jvmArguments = jvmArguments.toTypedArray(),
            mainClass = plan.mainClass,
            gameArguments = plan.gameArguments.toTypedArray(),
            environmentKeys = environment.keys.toTypedArray(),
            environmentValues = environment.values.toTypedArray(),
            preloadLibraries = preload.map(File::getAbsolutePath).toTypedArray(),
            surface = surface
        )
    }

    suspend fun runTool(toolPlanFile: File, architecture: String): Int = withContext(Dispatchers.IO) {
        require(NativeLaunchBridge.isAvailable) { NativeLaunchBridge.unavailableReason }
        require(toolPlanFile.isFile) { "Tool launch plan does not exist: ${toolPlanFile.absolutePath}" }
        val tool = json.decodeFromString(ToolLaunchPlan.serializer(), toolPlanFile.readText())
        val root = toolPlanFile.parentFile?.parentFile ?: error("Cannot resolve launcher data directory")
        val layout = MinecraftLayout(root)
        val javaHome = layout.runtimeHome(tool.javaVersion.major, architecture)
        require(findFile(javaHome, "libjvm.so")?.isFile == true) {
            "Java ${tool.javaVersion.major} runtime is not installed for $architecture"
        }
        val engineNatives = layout.engineNativeDirectory(architecture)
        prepareRuntimeAwtCompatibility(javaHome, engineNatives)
        val runtimeLibraries = listOfNotNull(
            findFile(javaHome, "libjvm.so")?.parentFile,
            findFile(javaHome, "libjava.so")?.parentFile,
            findFile(javaHome, "libjli.so")?.parentFile
        )
        val nativeDirectories = buildList {
            add(engineNatives)
            add(File(context.applicationInfo.nativeLibraryDir))
            addAll(runtimeLibraries)
        }.filter(File::isDirectory).distinctBy(File::getAbsolutePath)
        val nativePath = nativeDirectories.joinToString(File.pathSeparator) { it.absolutePath }
        val environment = linkedMapOf(
            "JAVA_HOME" to javaHome.absolutePath,
            "HOME" to tool.workingDirectory,
            "TMPDIR" to File(tool.workingDirectory, ".mclauncher-tool-tmp").apply { mkdirs() }.absolutePath,
            "PATH" to listOf(File(javaHome, "bin").absolutePath, "/system/bin").joinToString(File.pathSeparator),
            "LD_LIBRARY_PATH" to nativePath,
            "POJAV_NATIVEDIR" to if (engineNatives.isDirectory) engineNatives.absolutePath else context.applicationInfo.nativeLibraryDir
        )
        val jvmArguments = tool.jvmArguments.toMutableList()
        putSystemProperty(jvmArguments, "java.class.path", tool.classpath.joinToString(File.pathSeparator))
        putSystemProperty(jvmArguments, "java.home", javaHome.absolutePath)
        putSystemProperty(jvmArguments, "user.home", tool.workingDirectory)
        putSystemProperty(jvmArguments, "user.dir", tool.workingDirectory)
        putSystemProperty(jvmArguments, "java.library.path", nativePath)
        val preload = mutableListOf<File>()
        listOf("libjli.so", "libjvm.so", "libverify.so", "libjava.so", "libzip.so", "libnet.so", "libnio.so")
            .mapNotNullTo(preload) { findFile(javaHome, it) }
        if (engineNatives.isDirectory) {
            engineNatives.walkTopDown()
                .filter { it.isFile && it.extension.equals("so", true) }
                .filterNot { it.name.startsWith("liblwjgl", ignoreCase = true) }
                .filterNot { it.name.equals("libjnidispatch.so", ignoreCase = true) }
                .filterNot { isUnsupportedProcessHookLibrary(it.name) }
                .filterNot { isRendererOrDriverLibrary(it.name) }
                .sortedWith(compareBy<File> { preloadPriority(it.name) }.thenBy(File::getName))
                .forEach(preload::add)
        }
        NativeLaunchBridge.nativeStart(
            context = context,
            javaHome = javaHome.absolutePath,
            workingDirectory = tool.workingDirectory,
            jvmArguments = jvmArguments.toTypedArray(),
            mainClass = tool.mainClass,
            gameArguments = tool.arguments.toTypedArray(),
            environmentKeys = environment.keys.toTypedArray(),
            environmentValues = environment.values.toTypedArray(),
            preloadLibraries = preload.distinctBy(File::getAbsolutePath).map(File::getAbsolutePath).toTypedArray(),
            surface = null
        )
    }

    private fun configureDriverPaths(
        graphics: ResolvedGraphicsStack,
        environment: MutableMap<String, String>
    ) {
        val files = graphics.searchDirectories.asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().asSequence() }
            .filter(File::isFile)
            .toList()

        when (graphics.driver) {
            GraphicsDriver.ANGLE -> {
                files.firstOrNull { it.name.contains("egl", true) && it.extension == "so" }
                    ?.let { environment["POJAVEXEC_EGL"] = it.absolutePath }
                files.firstOrNull { it.name.contains("glesv2", true) && it.extension == "so" }
                    ?.let { environment["POJAVEXEC_GLES2"] = it.absolutePath }
            }
            GraphicsDriver.TURNIP,
            GraphicsDriver.PANVK,
            GraphicsDriver.SWIFTSHADER -> {
                files.firstOrNull { it.extension.equals("json", true) && it.name.contains("icd", true) }
                    ?.let {
                        environment["VK_ICD_FILENAMES"] = it.absolutePath
                        environment["VK_DRIVER_FILES"] = it.absolutePath
                    }
            }
            else -> Unit
        }
    }

    private fun buildPreloadList(
        javaHome: File,
        engineNatives: File,
        graphics: ResolvedGraphicsStack
    ): List<File> {
        val output = mutableListOf<File>()
        val runtimeLibraries = listOf("libjli.so", "libjvm.so", "libverify.so", "libjava.so", "libzip.so", "libnet.so", "libnio.so")
            .mapNotNull { findFile(javaHome, it) }

        val engineLibraries = sequenceOf(engineNatives)
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension.equals("so", true) }
            // LWJGL invokes JNI_OnLoad itself when Java loads these libraries. Early dlopen
            // would make the Android VM own them before the Minecraft VM exists.
            .filterNot { it.name.startsWith("liblwjgl", ignoreCase = true) }
            .filterNot { it.name.equals("libjnidispatch.so", ignoreCase = true) }
            .filterNot { isUnsupportedProcessHookLibrary(it.name) }
            // These tiny engine stubs have the same SONAME as the real OpenJDK libraries.
            // Loading them first makes libfontmanager resolve against the wrong library.
            .filterNot { isConflictingAwtStubLibrary(it.name) }
            .filterNot { isRendererOrDriverLibrary(it.name) }
            .toList()
        val graphicsLibraries = graphics.searchDirectories.asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension.equals("so", true) }
            .filter { file -> graphics.preloadTokens.any { token -> file.name.contains(token, ignoreCase = true) } }
            .toList()
        (engineLibraries + graphicsLibraries)
            .sortedWith(compareBy<File> { preloadPriority(it.name) }.thenBy(File::getName))
            .forEach(output::add)
        output += runtimeLibraries
        return output.distinctBy(File::getAbsolutePath)
    }

    /**
     * Upstream LWJGL assumes desktop Linux and falls back to libGLX.so.0 when
     * org.lwjgl.opengl.libname is absent. Android renderer packs expose their
     * desktop-OpenGL compatibility entry point under a different filename, so
     * pass the same renderer selected by the Mojo renderspec bridge explicitly.
     */
    private fun resolveLwjglOpenGlLibrary(graphics: ResolvedGraphicsStack): File? {
        val tokens = when (graphics.renderer) {
            com.mclauncher.model.Renderer.VULKAN -> return null
            com.mclauncher.model.Renderer.MOBILE_GLUES -> listOf("libmobileglues.so")
            com.mclauncher.model.Renderer.OPEN_LTW -> listOf("libltw")
            com.mclauncher.model.Renderer.NG_GL4ES ->
                listOf("ng_gl4es", "ng-gl4es", "nggl4es")
            com.mclauncher.model.Renderer.GL4ES -> listOf("libgl4es")
            com.mclauncher.model.Renderer.ANGLE -> listOf("libegl_angle", "libegl")
            com.mclauncher.model.Renderer.ZINK ->
                listOf("libegl_mesa", "libosmesa", "mesa")
            com.mclauncher.model.Renderer.KRYPTON -> listOf("krypton")
            com.mclauncher.model.Renderer.VIRGL -> listOf("virgl", "virpipe")
            com.mclauncher.model.Renderer.CUSTOM ->
                listOf(graphics.pojavRenderer.lowercase())
            com.mclauncher.model.Renderer.AUTO ->
                error("Automatic renderer was not resolved")
        }
        val libraries = graphics.searchDirectories.asSequence()
            .filter(File::isDirectory)
            .flatMap { it.walkTopDown().asSequence() }
            .filter { it.isFile && it.extension.equals("so", ignoreCase = true) }
            .toList()
        return tokens.firstNotNullOfOrNull { token ->
            libraries.firstOrNull { it.name.contains(token, ignoreCase = true) }
        } ?: error(
            "No Android OpenGL library matched ${graphics.renderer.displayName} " +
                "(MCLAUNCHER_RENDERER_TOKEN=${graphics.pojavRenderer})"
        )
    }


    private fun isRendererOrDriverLibrary(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(
            "gl4es", "ltw", "mobileglues", "krypton", "angle", "mesa", "zink",
            "osmesa", "gallium", "virgl", "virpipe", "turnip", "freedreno",
            "panvk", "panfrost", "swiftshader", "vulkan_freedreno", "vulkan_panfrost"
        ).any(lower::contains)
    }

    /**
     * JNA 5.14+ uses native ABI 7.0 while older Minecraft libraries commonly
     * use ABI 6.1. Android cannot use the desktop native embedded in jna.jar,
     * so the bundled engine carries one verified Android build for each ABI.
     */
    private fun selectJnaNativeDirectory(classpath: List<String>, engineNatives: File): File? {
        val directoryName = jnaNativeDirectoryName(classpath) ?: return null
        return File(engineNatives, directoryName)
            .takeIf { File(it, "libjnidispatch.so").isFile }
    }

    /**
     * The pinned engine's CMake build does not include its exit-hook module.
     * ByteHook and ShadowHook arrive as unrelated APK dependencies and can abort
     * when initialized manually from MCLauncher's extracted-library namespace.
     */
    private fun isUnsupportedProcessHookLibrary(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("bytehook") ||
            lower.contains("shadowhook") ||
            lower.contains("exithook")
    }

    private fun isConflictingAwtStubLibrary(name: String): Boolean =
        name.equals("libawt_headless.so", ignoreCase = true) ||
            name.equals("libawt_xawt.so", ignoreCase = true)

    /**
     * OpenJDK's AWT bootstrap loads this compatibility library from the runtime
     * directory by absolute path. Keep the real runtime libawt_headless.so in
     * place and overlay only MojoLauncher's tiny libawt_xawt.so stub, matching
     * the pinned engine's MultiRTUtils.postPrepare behavior.
     */
    private fun prepareRuntimeAwtCompatibility(javaHome: File, engineNatives: File): File {
        val source = File(engineNatives, "libawt_xawt.so")
        require(source.isFile) {
            "Bundled Android launch engine is incomplete: libawt_xawt.so is missing"
        }
        val runtimeLibraryDirectory = findFile(javaHome, "libawt.so")?.parentFile
            ?: error("Java runtime is incomplete: libawt.so is missing")
        val target = File(runtimeLibraryDirectory, source.name)
        if (
            target.isFile &&
            target.length() == source.length() &&
            target.readBytes().contentEquals(source.readBytes())
        ) {
            return target
        }

        val temporary = File(
            runtimeLibraryDirectory,
            ".${source.name}.${System.nanoTime()}.tmp"
        )
        try {
            source.copyTo(temporary, overwrite = true)
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporary.delete()
        }
        require(target.isFile && target.length() == source.length()) {
            "Could not prepare ${target.absolutePath}"
        }
        return target
    }

    private fun preloadPriority(name: String): Int = when {
        name.contains("c++_shared", ignoreCase = true) -> 0
        name.contains("tinywrapper", ignoreCase = true) -> 2
        name.contains("linkerhook", ignoreCase = true) -> 3
        name.contains("awt_headless", ignoreCase = true) -> 3
        name.contains("awt_xawt", ignoreCase = true) -> 4
        name.contains("pojav", ignoreCase = true) || name.contains("exit_hook", ignoreCase = true) -> 4
        name.contains("freetype", ignoreCase = true) -> 5
        name.contains("openal", ignoreCase = true) -> 5
        name.contains("vulkan", ignoreCase = true) -> 5
        name.contains("angle", ignoreCase = true) -> 6
        name.contains("mesa", ignoreCase = true) || name.contains("zink", ignoreCase = true) -> 6
        name.contains("mobileglues", ignoreCase = true) || name.contains("ltw", ignoreCase = true) -> 7
        name.contains("gl4es", ignoreCase = true) || name.contains("virgl", ignoreCase = true) -> 7
        name.contains("glfw", ignoreCase = true) -> 8
        name.contains("lwjgl", ignoreCase = true) -> 9
        else -> 10
    }


    private fun addJavaDesktopModuleAccess(arguments: MutableList<String>) {
        val required = listOf(
            "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
            "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
            "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
            "--add-opens=java.base/java.util=ALL-UNNAMED",
            "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
            "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
            "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED"
        )
        required.filterNot(arguments::contains).forEach(arguments::add)
    }

    private fun stripClasspathPair(arguments: List<String>): MutableList<String> {
        val output = mutableListOf<String>()
        var index = 0
        while (index < arguments.size) {
            val argument = arguments[index]
            if (argument == "-cp" || argument == "-classpath" || argument == "--class-path") {
                index += 2
                continue
            }
            output += argument
            index++
        }
        return output
    }

    private fun putSystemProperty(arguments: MutableList<String>, key: String, value: String) {
        val prefix = "-D$key="
        // Version metadata can contain the same property more than once. The JVM uses
        // the last value, so replacing only the first entry silently leaves stale values.
        arguments.removeAll { it.startsWith(prefix) }
        arguments += prefix + value
    }

    private fun findFile(root: File, name: String): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == name }
}

internal fun jnaNativeDirectoryName(classpath: List<String>): String? {
    val version = classpath.asSequence()
        .map { File(it).name }
        .mapNotNull { filename ->
            Regex(
                """^jna-(\d+)\.(\d+)(?:\.\d+)?(?:[-.].*)?\.jar$""",
                RegexOption.IGNORE_CASE
            ).matchEntire(filename)
        }
        .map { match ->
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        }
        .firstOrNull()
        ?: return null
    if (version.first != 5) return null
    return if (version.second >= 14) "jna-7" else "jna-6"
}
