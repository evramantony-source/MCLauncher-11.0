package com.mclauncher.minecraft

import com.mclauncher.model.AccountType
import com.mclauncher.model.AuthSession
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.MinecraftGraphicsApi
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.OfflineAccount
import com.mclauncher.model.PerformancePreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

class LaunchPlanBuilder(
    private val layout: MinecraftLayout,
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = true },
    private val ruleEvaluator: RuleEvaluator = RuleEvaluator(),
    private val versionResolver: VersionResolver = VersionResolver(layout)
) {
    suspend fun build(
        instance: MinecraftInstance,
        account: OfflineAccount,
        settings: LauncherSettings,
        authSession: AuthSession? = null,
        architecture: String = "arm64-v8a"
    ): LaunchPlan = withContext(Dispatchers.IO) {
        val versionFile = layout.versionJson(instance.versionId)
        require(versionFile.isFile) { "Version ${instance.versionId} is not installed" }
        val effectiveSettings = settings.copy(
            width = (settings.width * settings.resolutionScale).toInt().coerceAtLeast(320),
            height = (settings.height * settings.resolutionScale).toInt().coerceAtLeast(180)
        )
        val launchRules = ruleEvaluator.withArchitecture(minecraftArchitecture(architecture))
        val baseVersionId = versionResolver.baseVersionId(instance.versionId)
        require(layout.clientJar(baseVersionId).isFile) { "Minecraft client JAR is missing for $baseVersionId" }
        val document = versionResolver.resolve(instance.versionId)
        val supportsGraphicsApi = MinecraftVersionCapabilities.supportsGraphicsApi(baseVersionId)
        // Keep the selected OpenGL translator prepared even when Minecraft prefers
        // Vulkan. GLFW selects the Vulkan surface dynamically, while the prepared
        // translator remains available if Minecraft falls back to OpenGL.
        val launchRenderer = effectiveSettings.renderer

        val requiredJava = document["javaVersion"]?.jsonObject
            ?.get("majorVersion")?.jsonPrimitive?.content?.toIntOrNull() ?: 8
        require(instance.javaVersion.major >= requiredJava) {
            "Minecraft ${instance.versionId} requires Java $requiredJava or newer"
        }

        val gameDirectory = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
        applyMinecraftOptions(
            gameDirectory = gameDirectory,
            settings = effectiveSettings,
            supportsGraphicsApi = supportsGraphicsApi
        )
        val nativesDirectory = layout.nativesFor(instance.id).apply {
            deleteRecursively()
            mkdirs()
        }
        prepareAndroidNatives(document, architecture, nativesDirectory, launchRules)
        val classpath = buildClasspath(baseVersionId, document, launchRules)
        val runtimeHome = layout.runtimeHome(instance.javaVersion.major, architecture)
        val javaBinary = File(runtimeHome, "bin/java")
        val placeholders = placeholders(
            instance = instance,
            account = account,
            settings = effectiveSettings,
            document = document,
            gameDirectory = gameDirectory,
            nativesDirectory = nativesDirectory,
            classpath = classpath,
            authSession = authSession
        )

        val jvmArguments = buildJvmArguments(document, effectiveSettings, placeholders, launchRules)
        val gameArguments = buildGameArguments(document, placeholders, launchRules)
        val mainClass = document.requiredString("mainClass")

        LaunchPlan(
            instanceId = instance.id,
            versionId = instance.versionId,
            runtime = RuntimeSelection(
                javaVersion = instance.javaVersion,
                javaHome = runtimeHome.absolutePath,
                javaBinary = javaBinary.absolutePath,
                architecture = architecture
            ),
            workingDirectory = gameDirectory.absolutePath,
            nativesDirectory = nativesDirectory.absolutePath,
            classpath = classpath.map(File::getAbsolutePath),
            jvmArguments = jvmArguments,
            mainClass = mainClass,
            gameArguments = gameArguments,
            environment = buildEnvironment(
                settings = effectiveSettings,
                runtimeHome = runtimeHome,
                nativesDirectory = nativesDirectory,
                launchRenderer = launchRenderer,
                supportsGraphicsApi = supportsGraphicsApi
            ),
            renderer = launchRenderer,
            graphicsDriver = effectiveSettings.graphicsDriver,
            minecraftGraphicsApi = if (supportsGraphicsApi) {
                effectiveSettings.minecraftGraphicsApi
            } else {
                MinecraftGraphicsApi.DEFAULT
            },
            windowWidth = effectiveSettings.width,
            windowHeight = effectiveSettings.height
        )
    }

    suspend fun save(plan: LaunchPlan): File = withContext(Dispatchers.IO) {
        val target = layout.launchPlan(plan.instanceId)
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(LaunchPlan.serializer(), plan))
        target
    }

    /**
     * Extracts the exact Android LWJGL classifier natives required by this resolved
     * Minecraft version. Keeping classifiers versioned avoids a global liblwjgl.so
     * from one Minecraft release being reused by an incompatible LWJGL release.
     */
    private fun prepareAndroidNatives(
        document: JsonObject,
        architecture: String,
        destination: File,
        rules: RuleEvaluator
    ) {
        val substitutionsFile = File(layout.engineJarsDirectory, "substitutions.json")
        require(substitutionsFile.isFile) { "Bundled patched-library substitutions are missing" }
        val substitutionDocument = json.parseToJsonElement(substitutionsFile.readText()).jsonObject
        val substitutions = substitutionDocument["libraries"]?.jsonObject ?: JsonObject(emptyMap())
        val artifactMapping = substitutionDocument["artifactMapping"]?.jsonObject ?: JsonObject(emptyMap())
        val libraries = document["libraries"] as? JsonArray ?: JsonArray(emptyList())
        var lwjgl3Coordinates = 0
        var extractedLibraries = 0

        for (element in libraries) {
            val library = element.jsonObject
            if (!rules.allows(library["rules"] as? JsonArray)) continue
            val coordinate = library.optionalString("name").orEmpty()
            if (!coordinate.startsWith("org.lwjgl:")) continue
            val replacementCoordinate = artifactMapping[coordinate]?.jsonPrimitive?.content ?: coordinate
            val replacement = substitutions[replacementCoordinate]?.jsonObject
                ?: error("No Android LWJGL substitution is defined for $coordinate")
            if (replacement["skip"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) continue
            lwjgl3Coordinates++
            val classifiers = replacement["downloads"]?.jsonObject
                ?.get("classifiers") as? JsonObject ?: continue
            val descriptor = classifiers.entries.firstOrNull { (classifier, _) ->
                classifierMatchesArchitecture(classifier, architecture)
            }?.value?.jsonObject ?: continue
            val path = descriptor.optionalString("path") ?: continue
            val classifierJar = File(layout.engineJarsDirectory, path)
            require(classifierJar.isFile) {
                "Missing bundled Android native classifier for $coordinate ($architecture)"
            }
            ZipFile(classifierJar).use { archive ->
                val entries = archive.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory || !entry.name.endsWith(".so", ignoreCase = true)) continue
                    val fileName = File(entry.name).name
                    require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\')) {
                        "Unsafe native entry in ${classifierJar.name}: ${entry.name}"
                    }
                    val target = File(destination, fileName)
                    archive.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.setReadable(true, true)
                    target.setExecutable(true, true)
                    extractedLibraries++
                }
            }
        }
        if (lwjgl3Coordinates > 0) {
            require(extractedLibraries > 0) {
                "No Android LWJGL natives were available for $architecture and this Minecraft version"
            }
            require(File(destination, "liblwjgl.so").isFile) {
                "The Android LWJGL core native library was not extracted for $architecture"
            }
        }
    }

    private fun classifierMatchesArchitecture(classifier: String, architecture: String): Boolean {
        val value = classifier.lowercase().replace('_', '-')
        return when (architecture) {
            "arm64-v8a" -> value.contains("arm64") || value.contains("aarch64")
            "armeabi-v7a" -> value.contains("arm32") || value.contains("aarch32") || value.contains("armeabi")
            "x86_64" -> value.contains("x86-64") || value.contains("amd64") ||
                (value.endsWith("natives-linux") && !value.contains("arm") && !value.contains("x86"))
            else -> false
        }
    }

    private fun buildClasspath(versionId: String, document: JsonObject, rules: RuleEvaluator): List<File> {
        val files = mutableListOf<File>()
        val substitutionsFile = File(layout.engineJarsDirectory, "substitutions.json")
        require(substitutionsFile.isFile) { "Bundled patched-library substitutions are missing" }
        val substitutionDocument = json.parseToJsonElement(substitutionsFile.readText()).jsonObject
        val substitutions = substitutionDocument["libraries"]?.jsonObject ?: JsonObject(emptyMap())
        val artifactMapping = substitutionDocument["artifactMapping"]?.jsonObject ?: JsonObject(emptyMap())

        // Android launcher support JARs are selected by NativeEngineCoordinator after
        // the Java version is known. Adding both Cacio generations here would create
        // duplicate AWT toolkit classes.
        val libraries = document["libraries"] as? JsonArray ?: JsonArray(emptyList())
        for (element in libraries) {
            val library = element.jsonObject
            if (!rules.allows(library["rules"] as? JsonArray)) continue
            val coordinate = library.optionalString("name").orEmpty()
            val artifact = library["downloads"]?.jsonObject?.get("artifact") as? JsonObject
            val path = artifact?.optionalString("path")
                ?: coordinate.takeIf(String::isNotBlank)?.let(MavenCoordinates::path)
                ?: continue

            if (coordinate.startsWith("org.lwjgl:") || coordinate.startsWith("org.lwjgl.lwjgl:")) {
                val replacementCoordinate = artifactMapping[coordinate]?.jsonPrimitive?.content ?: coordinate
                val replacement = substitutions[replacementCoordinate]?.jsonObject
                    ?: error("No Android LWJGL substitution is defined for $coordinate")
                if (replacement["skip"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true) continue
                val replacementPath = replacement["downloads"]?.jsonObject
                    ?.get("artifact")?.jsonObject?.optionalString("path")
                    ?: error("Android LWJGL substitution $replacementCoordinate has no Java artifact")
                val replacementFile = File(layout.engineJarsDirectory, replacementPath)
                require(replacementFile.isFile) {
                    "Missing bundled Android LWJGL replacement for $coordinate: $replacementPath"
                }
                files += replacementFile
            } else {
                val file = layout.library(path)
                require(file.isFile) { "Missing library: ${file.name}" }
                files += file
            }
        }
        files += layout.clientJar(versionId)
        return files.distinctBy(File::getAbsolutePath)
    }

    private fun buildJvmArguments(
        document: JsonObject,
        settings: LauncherSettings,
        placeholders: Map<String, String>,
        rules: RuleEvaluator
    ): List<String> {
        val output = mutableListOf<String>()
        output += "-Xms512M"
        output += "-Xmx${settings.memoryMb}M"
        val nativesPath = placeholders.getValue("${'$'}{natives_directory}")
        output += "-Djava.library.path=$nativesPath"
        val classpathValue = placeholders.getValue("${'$'}{classpath}")
        output += "-Djava.class.path=$classpathValue"
        output += "-Dmclauncher.name=MCLauncher"
        output += "-Dmclauncher.version=11.0.0-alpha05"
        output += "-Dmclauncher.fpsLimit=${settings.fpsLimit}"
        when (settings.performancePreset) {
            PerformancePreset.BATTERY -> {
                output += "-XX:+UseSerialGC"
            }
            PerformancePreset.BALANCED -> {
                output += "-XX:+UseG1GC"
                output += "-XX:MaxGCPauseMillis=100"
            }
            PerformancePreset.PERFORMANCE -> {
                output += "-XX:+UseG1GC"
                output += "-XX:MaxGCPauseMillis=50"
                output += "-XX:+ParallelRefProcEnabled"
            }
            PerformancePreset.CUSTOM -> Unit
        }

        val jvm = document["arguments"]?.jsonObject?.get("jvm") as? JsonArray
        if (jvm != null) {
            val resolved = resolveArgumentArray(jvm, placeholders, rules)
            var index = 0
            while (index < resolved.size) {
                val argument = resolved[index]
                if (argument == "-cp" || argument == "-classpath") {
                    index += 2
                    continue
                }
                output += argument
                index++
            }
        }

        val logging = document["logging"]?.jsonObject?.get("client") as? JsonObject
        if (logging != null) {
            val argument = logging.optionalString("argument")
            val fileId = (logging["file"] as? JsonObject)?.optionalString("id")
            if (argument != null && fileId != null) {
                val path = layout.loggingConfig(fileId).absolutePath
                output += argument.replace("${'$'}{path}", path)
            }
        }

        if (settings.customJvmArgs.isNotBlank()) {
            output += CommandLineTokenizer.tokenize(settings.customJvmArgs)
        }
        return output.map { replacePlaceholders(it, placeholders) }
    }

    private fun buildGameArguments(
        document: JsonObject,
        placeholders: Map<String, String>,
        rules: RuleEvaluator
    ): List<String> {
        val modern = document["arguments"]?.jsonObject?.get("game") as? JsonArray
        val raw = if (modern != null) {
            resolveArgumentArray(modern, placeholders, rules)
        } else {
            CommandLineTokenizer.tokenize(document.optionalString("minecraftArguments") ?: "")
        }
        return raw.map { replacePlaceholders(it, placeholders) }
    }

    private fun resolveArgumentArray(
        array: JsonArray,
        placeholders: Map<String, String>,
        rules: RuleEvaluator
    ): List<String> {
        val output = mutableListOf<String>()
        for (element in array) {
            when (element) {
                is kotlinx.serialization.json.JsonPrimitive -> output += element.content
                is JsonObject -> {
                    if (!rules.allows(element["rules"] as? JsonArray)) continue
                    val value = element["value"] ?: continue
                    output += when (value) {
                        is kotlinx.serialization.json.JsonPrimitive -> listOf(value.content)
                        is JsonArray -> value.map { it.jsonPrimitive.content }
                        else -> emptyList()
                    }
                }
                else -> Unit
            }
        }
        return output.map { replacePlaceholders(it, placeholders) }
    }

    private fun placeholders(
        instance: MinecraftInstance,
        account: OfflineAccount,
        settings: LauncherSettings,
        document: JsonObject,
        gameDirectory: File,
        nativesDirectory: File,
        classpath: List<File>,
        authSession: AuthSession?
    ): Map<String, String> {
        val assetIndexId = document["assetIndex"]?.jsonObject?.optionalString("id")
            ?: document.optionalString("assets")
            ?: instance.versionId
        val separator = File.pathSeparator
        return mapOf(
            "${'$'}{auth_player_name}" to account.username,
            "${'$'}{version_name}" to instance.versionId,
            "${'$'}{game_directory}" to gameDirectory.absolutePath,
            "${'$'}{assets_root}" to layout.assetsDirectory.absolutePath,
            "${'$'}{assets_index_name}" to assetIndexId,
            "${'$'}{auth_uuid}" to account.profileId.replace("-", ""),
            "${'$'}{auth_access_token}" to (authSession?.accessToken ?: "0"),
            "${'$'}{clientid}" to (authSession?.clientId ?: account.clientId ?: "0"),
            "${'$'}{auth_xuid}" to (authSession?.xuid ?: account.xuid ?: "0"),
            "${'$'}{user_type}" to if (account.type == AccountType.MICROSOFT) "msa" else "legacy",
            "${'$'}{version_type}" to (document.optionalString("type") ?: "release"),
            "${'$'}{natives_directory}" to nativesDirectory.absolutePath,
            "${'$'}{launcher_name}" to "MCLauncher",
            "${'$'}{launcher_version}" to "11.0",
            "${'$'}{classpath}" to classpath.joinToString(separator) { it.absolutePath },
            "${'$'}{classpath_separator}" to separator,
            "${'$'}{library_directory}" to layout.librariesDirectory.absolutePath,
            "${'$'}{resolution_width}" to settings.width.toString(),
            "${'$'}{resolution_height}" to settings.height.toString()
        )
    }

    private fun replacePlaceholders(value: String, placeholders: Map<String, String>): String {
        var result = value
        placeholders.forEach { (placeholder, replacement) -> result = result.replace(placeholder, replacement) }
        return result
    }

    private fun buildEnvironment(
        settings: LauncherSettings,
        runtimeHome: File,
        nativesDirectory: File,
        launchRenderer: Renderer,
        supportsGraphicsApi: Boolean
    ): Map<String, String> = buildMap {
        put("JAVA_HOME", runtimeHome.absolutePath)
        put("HOME", layout.root.absolutePath)
        put("TMPDIR", File(layout.root, "tmp").apply { mkdirs() }.absolutePath)
        put("LD_LIBRARY_PATH", nativesDirectory.absolutePath)
        put("MCLAUNCHER_RENDERER", launchRenderer.id)
        put("MCLAUNCHER_GRAPHICS_DRIVER", settings.graphicsDriver.id)
        put(
            "MCLAUNCHER_GRAPHICS_API",
            if (supportsGraphicsApi) settings.minecraftGraphicsApi.optionsValue else "unsupported"
        )
        put("MCLAUNCHER_FPS_LIMIT", settings.fpsLimit.toString())
    }
    private fun minecraftArchitecture(androidAbi: String): String = when (androidAbi) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> androidAbi
    }

    /** Applies launcher settings through Minecraft's own options file so the FPS limiter is real. */
    private fun applyMinecraftOptions(
        gameDirectory: File,
        settings: LauncherSettings,
        supportsGraphicsApi: Boolean
    ) {
        val options = File(gameDirectory, "options.txt")
        val values = linkedMapOf(
            "maxFps" to (if (settings.fpsLimit >= 240) "260" else settings.fpsLimit.coerceIn(20, 260).toString()),
            "enableVsync" to "false",
            "rawMouseInput" to settings.physicalMouseCapture.toString(),
            "touchscreen" to "false"
        )
        if (supportsGraphicsApi) {
            // 26.2 stores this enum as a quoted options.txt string.
            values["preferredGraphicsBackend"] = "\"${settings.minecraftGraphicsApi.optionsValue}\""
        }
        val existing = if (options.isFile) options.readLines().toMutableList() else mutableListOf()
        values.forEach { (key, value) ->
            val index = existing.indexOfFirst { it.substringBefore(':') == key }
            val line = "$key:$value"
            if (index >= 0) existing[index] = line else existing += line
        }
        options.parentFile?.mkdirs()
        options.writeText(existing.joinToString("\n", postfix = "\n"))
    }


}
