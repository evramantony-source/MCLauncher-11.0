package com.mclauncher.app.creation

import android.content.Context
import android.net.Uri
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

@Serializable
private data class WorkspaceMetadata(
    val id: String,
    val name: String,
    val minecraftVersion: String,
    val loader: ModLoader,
    val loaderVersion: String
)

/** Owns editable Gradle source projects under Creation Lab. Imported projects
 * are only extracted and displayed; code runs solely after an explicit build. */
class CodeWorkspaceManager(private val context: Context) {
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private val labRoot = context.filesDir.resolve("creation-lab").apply { mkdirs() }
    private val workspaceRoot = labRoot.resolve("workspaces").apply { mkdirs() }
    private val toolchain = LocalGradleToolchain(context)

    fun listProjects(): List<CodeWorkspaceProject> = workspaceRoot.listFiles().orEmpty()
        .filter(File::isDirectory)
        .mapNotNull { directory ->
            runCatching {
                val metadata = json.decodeFromString<WorkspaceMetadata>(directory.resolve(METADATA_FILE).readText())
                CodeWorkspaceProject(
                    id = metadata.id,
                    name = metadata.name,
                    minecraftVersion = metadata.minecraftVersion,
                    loader = metadata.loader,
                    loaderVersion = metadata.loaderVersion
                )
            }.getOrNull()
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    fun createStarter(name: String, target: LocalBuildTarget): CodeWorkspaceProject {
        require(target.loader != ModLoader.VANILLA) { "Choose a Fabric, Quilt, Forge or NeoForge instance" }
        require(target.loaderVersion.isNotBlank()) { "The selected instance has no loader version" }
        val displayName = name.trim().take(64).ifBlank { "My ${target.loader.displayName} Mod" }
        val id = uniqueWorkspaceId(safeId(displayName))
        val directory = workspaceRoot.resolve(id).apply { mkdirs() }
        val metadata = WorkspaceMetadata(id, displayName, target.minecraftVersion, target.loader, target.loaderVersion)
        directory.resolve(METADATA_FILE).writeText(json.encodeToString(metadata))
        writeStarterFiles(directory, metadata, target.javaVersion.major)
        return metadata.toProject()
    }

    fun importProject(source: Uri, target: LocalBuildTarget, requestedName: String?): CodeWorkspaceProject {
        require(target.loader != ModLoader.VANILLA) { "Choose a mod-loader instance for this source project" }
        val cached = context.cacheDir.resolve("creation-lab/source-${UUID.randomUUID()}.zip").apply {
            parentFile?.mkdirs()
        }
        try {
            context.contentResolver.openInputStream(source)?.use { input ->
                cached.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= MAX_PROJECT_ARCHIVE_BYTES) { "Source ZIP is larger than 200 MB" }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: error("Android could not open the source ZIP")
        } catch (error: Throwable) {
            cached.delete()
            throw error
        }
        try {
            val defaultName = source.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')
                ?.take(64).orEmpty().ifBlank { "Imported mod source" }
            val displayName = requestedName?.trim()?.take(64).orEmpty().ifBlank { defaultName }
            val id = uniqueWorkspaceId(safeId(displayName))
            val directory = workspaceRoot.resolve(id).apply { mkdirs() }
            val metadata = WorkspaceMetadata(id, displayName, target.minecraftVersion, target.loader, target.loaderVersion)
            runCatching {
                extractProject(cached, directory)
                require(directory.resolve("build.gradle").isFile || directory.resolve("build.gradle.kts").isFile) {
                    "The ZIP is not a Gradle source project: build.gradle or build.gradle.kts is missing"
                }
                directory.resolve(METADATA_FILE).writeText(json.encodeToString(metadata))
            }.onFailure {
                directory.deleteRecursively()
                throw it
            }
            return metadata.toProject()
        } finally {
            cached.delete()
        }
    }

    fun listEditableFiles(projectId: String): List<CodeWorkspaceFile> {
        val project = projectDirectory(projectId)
        return project.walkTopDown()
            .onEnter { directory ->
                directory == project || directory.name !in HIDDEN_BUILD_DIRECTORIES
            }
            .filter(File::isFile)
            .filter { it.name != METADATA_FILE && isEditable(it) }
            .take(MAX_VISIBLE_FILES)
            .map { CodeWorkspaceFile(it.relativeTo(project).invariantSeparatorsPath, it.length()) }
            .sortedBy(CodeWorkspaceFile::path)
            .toList()
    }

    fun readTextFile(projectId: String, path: String): String {
        val file = safeProjectFile(projectId, path)
        require(file.isFile) { "Workspace file does not exist: $path" }
        require(isEditable(file)) { "This binary file cannot be edited as text" }
        require(file.length() <= MAX_EDITABLE_FILE_BYTES) { "File is too large for the touch editor" }
        return file.readText()
    }

    fun saveTextFile(projectId: String, path: String, content: String) {
        require(content.toByteArray().size <= MAX_EDITABLE_FILE_BYTES) { "File is too large for the touch editor" }
        val file = safeProjectFile(projectId, path)
        require(isEditable(file)) { "Use a source ZIP for binary files" }
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    fun createTextFile(projectId: String, path: String): CodeWorkspaceFile {
        val file = safeProjectFile(projectId, path)
        require(!file.exists()) { "That file already exists" }
        require(isEditable(file)) { "Choose a source or text filename" }
        file.parentFile?.mkdirs()
        file.writeText("")
        return CodeWorkspaceFile(file.relativeTo(projectDirectory(projectId)).invariantSeparatorsPath, 0L)
    }

    suspend fun build(
        projectId: String,
        target: LocalBuildTarget,
        taskText: String,
        installIntoInstance: Boolean,
        onProgress: (String) -> Unit
    ): CodeBuildResult {
        require(target.loader != ModLoader.VANILLA) { "Choose a mod-loader instance" }
        val project = projectDirectory(projectId)
        val metadata = json.decodeFromString<WorkspaceMetadata>(project.resolve(METADATA_FILE).readText())
        if (installIntoInstance) {
            require(metadata.loader == target.loader && metadata.minecraftVersion == target.minecraftVersion) {
                "This workspace targets ${metadata.loader.displayName} ${metadata.minecraftVersion}; choose its matching instance before copying the JAR"
            }
        }
        require(project.resolve("build.gradle").isFile || project.resolve("build.gradle.kts").isFile) {
            "This workspace has no Gradle build script"
        }
        val tasks = taskText.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val gradleVersion = toolchain.versionFor(project, target.javaVersion)
        onProgress("Preparing Gradle $gradleVersion with Java ${target.javaVersion.major}…")
        val gradleHome = toolchain.ensureInstalled(gradleVersion, onProgress)
        val statusFile = labRoot.resolve("build-status/${UUID.randomUUID()}.json").apply {
            parentFile?.mkdirs()
            delete()
        }
        val planFile = toolchain.createToolPlan(
            projectDirectory = project,
            gradleHome = gradleHome,
            javaVersion = target.javaVersion,
            statusFile = statusFile,
            tasks = tasks
        )
        onProgress("Opening the separate on-device Gradle builder…")
        LocalBuildActivity.start(context, planFile, statusFile)
        val status = waitForBuild(statusFile, onProgress)
        require(status.exitCode == 0) {
            val logHint = status.logPath?.let { " Log: Android Files → MCLauncher → MCL Creation Lab → build-status → ${File(it).name}" }.orEmpty()
            (status.error ?: "Local Gradle build failed") + logHint
        }
        onProgress("Checking the compiled mod JAR…")
        val built = findBuiltJar(project)
        validateModJar(built)
        val outputs = labRoot.resolve("outputs").apply { mkdirs() }
        val output = uniqueFile(outputs, built.name).also { built.copyTo(it) }
        val installed = if (installIntoInstance) copyIntoInstance(output, target) else null
        return CodeBuildResult(output.absolutePath, installed?.absolutePath, status.logPath)
    }

    private suspend fun waitForBuild(statusFile: File, onProgress: (String) -> Unit): LocalBuildStatus {
        var elapsedSeconds = 0
        while (elapsedSeconds < BUILD_TIMEOUT_SECONDS) {
            delay(POLL_INTERVAL_MS)
            elapsedSeconds++
            if (statusFile.isFile) {
                val status = runCatching { json.decodeFromString<LocalBuildStatus>(statusFile.readText()) }.getOrNull()
                if (status?.finished == true) return status
            }
            if (elapsedSeconds % 20 == 0) {
                onProgress("Building on this tablet… ${elapsedSeconds / 60}m ${elapsedSeconds % 60}s")
            }
        }
        error("The local build timed out after ${BUILD_TIMEOUT_SECONDS / 60} minutes")
    }

    private fun findBuiltJar(project: File): File = project.walkTopDown()
        .onEnter { directory -> directory.name !in setOf(".gradle", ".git") }
        .filter { it.isFile && it.extension.equals("jar", true) }
        .filter { "/build/libs/" in it.invariantSeparatorsPath }
        .filterNot {
            val name = it.name.lowercase(Locale.ROOT)
            "sources" in name || "javadoc" in name || "dev-shadow" in name || "dev.jar" in name
        }
        .sortedByDescending(File::lastModified)
        .firstOrNull()
        ?: error("Gradle finished but no JAR was found under build/libs")

    private fun validateModJar(jar: File) {
        require(jar.isFile && jar.length() > 0L) { "Generated JAR is empty" }
        ZipFile(jar).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            require(entries.any { it.endsWith(".class") }) { "Generated JAR has no compiled classes" }
            require(
                "fabric.mod.json" in entries ||
                    "quilt.mod.json" in entries ||
                    "META-INF/mods.toml" in entries ||
                    "META-INF/neoforge.mods.toml" in entries
            ) { "Generated JAR has no Fabric, Quilt, Forge or NeoForge metadata" }
        }
    }

    private fun copyIntoInstance(source: File, target: LocalBuildTarget): File {
        val instancesRoot = context.filesDir.resolve("minecraft/instances").canonicalFile
        val gameDirectory = instancesRoot.resolve(target.gameDirectoryName).canonicalFile
        require(gameDirectory.path.startsWith(instancesRoot.path + File.separator)) { "Unsafe instance directory" }
        val mods = gameDirectory.resolve("mods").apply { mkdirs() }
        return uniqueFile(mods, source.name).also { source.copyTo(it) }
    }

    private fun extractProject(archive: File, destination: File) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence().toList()
            require(entries.size <= MAX_PROJECT_ENTRIES) { "Source ZIP has too many files" }
            val names = entries.filterNot { it.isDirectory }.map { normalizeArchivePath(it.name) }
            val sharedRoot = names.mapNotNull { it.substringBefore('/', "").takeIf(String::isNotBlank) }
                .distinct().singleOrNull()
                ?.takeIf { root -> names.all { it.startsWith("$root/") } }
            val prefix = sharedRoot?.let { "$it/" }.orEmpty()
            val canonicalDestination = destination.canonicalFile
            var extractedBytes = 0L
            entries.forEach { entry ->
                val normalized = normalizeArchivePath(entry.name)
                val relative = normalized.removePrefix(prefix)
                if (relative.isBlank()) return@forEach
                val target = destination.resolve(relative).canonicalFile
                require(target.path.startsWith(canonicalDestination.path + File.separator)) { "Unsafe source ZIP path" }
                if (entry.isDirectory) target.mkdirs() else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                extractedBytes += count
                                require(extractedBytes <= MAX_PROJECT_BYTES) { "Source ZIP expands beyond 150 MB" }
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun writeStarterFiles(directory: File, metadata: WorkspaceMetadata, javaMajor: Int) {
        directory.resolve("settings.gradle").writeText(settingsGradle(metadata))
        directory.resolve("build.gradle").writeText(buildGradle(metadata, javaMajor))
        directory.resolve("gradle/wrapper/gradle-wrapper.properties").apply { parentFile?.mkdirs() }.writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-${recommendedGradle(metadata, javaMajor)}-bin.zip\n"
        )
        directory.resolve("gradle.properties").writeText(
            "org.gradle.daemon=false\norg.gradle.parallel=false\norg.gradle.workers.max=1\norg.gradle.configuration-cache=false\n"
        )
        val packageName = "com.mclauncher.workspace.${safeId(metadata.name)}"
        val source = directory.resolve("src/main/java/${packageName.replace('.', '/')}/WorkspaceMod.java").apply {
            parentFile?.mkdirs()
        }
        source.writeText(starterJava(packageName, metadata))
        writeMetadata(directory.resolve("src/main/resources"), packageName, metadata)
        directory.resolve("README-MCLAUNCHER.txt").writeText(
            """
            This is an editable source workspace, not AI-generated behavior.
            Target: ${metadata.loader.displayName} ${metadata.loaderVersion}, Minecraft ${metadata.minecraftVersion}
            Paste or create your source/resources, update build.gradle when your version needs a different plugin, then press Create JAR.
            Imported Gradle scripts execute only after you explicitly press the build button.
            """.trimIndent() + "\n"
        )
    }

    private fun settingsGradle(metadata: WorkspaceMetadata): String {
        val loaderRepository = when (metadata.loader) {
            ModLoader.FABRIC -> "maven { url = 'https://maven.fabricmc.net/' }"
            ModLoader.QUILT -> "maven { url = 'https://maven.quiltmc.org/repository/release/' }"
            ModLoader.FORGE -> "maven { url = 'https://maven.minecraftforge.net/' }"
            ModLoader.NEOFORGE -> "maven { url = 'https://maven.neoforged.net/releases' }"
            ModLoader.VANILLA -> error("Vanilla has no mod workspace")
        }
        return """
            pluginManagement {
                repositories {
                    $loaderRepository
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = '${safeId(metadata.name)}'
        """.trimIndent() + "\n"
    }

    private fun buildGradle(metadata: WorkspaceMetadata, javaMajor: Int): String = when (metadata.loader) {
        ModLoader.FABRIC -> {
            val calendarVersion = metadata.minecraftVersion.substringBefore('.').toIntOrNull()?.let { it >= 26 } == true
            val lastObfuscatedRelease = metadata.minecraftVersion == "1.21.11"
            val loomId = if (calendarVersion) "net.fabricmc.fabric-loom" else "fabric-loom"
            val loomVersion = when {
                calendarVersion -> "1.17-SNAPSHOT"
                lastObfuscatedRelease -> "1.14-SNAPSHOT"
                javaMajor <= 8 -> "0.12-SNAPSHOT"
                else -> "1.10-SNAPSHOT"
            }
            val loaderDependency = if (calendarVersion) "implementation" else "modImplementation"
            """
                plugins { id '$loomId' version '$loomVersion'; id 'java' }
                group = 'com.mclauncher.workspace'
                version = '1.0.0'
                base { archivesName = '${safeId(metadata.name)}' }
                repositories { mavenCentral() }
                dependencies {
                    minecraft 'com.mojang:minecraft:${metadata.minecraftVersion}'
                    ${if (calendarVersion) "" else "mappings loom.officialMojangMappings()"}
                    $loaderDependency 'net.fabricmc:fabric-loader:${metadata.loaderVersion}'
                }
                tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = $javaMajor }
                java { sourceCompatibility = JavaVersion.toVersion($javaMajor); targetCompatibility = JavaVersion.toVersion($javaMajor) }
            """.trimIndent() + "\n"
        }
        ModLoader.QUILT -> """
            plugins { id 'org.quiltmc.loom' version '1.7.4'; id 'java' }
            group = 'com.mclauncher.workspace'
            version = '1.0.0'
            base { archivesName = '${safeId(metadata.name)}' }
            repositories { maven { url = 'https://maven.quiltmc.org/repository/release/' }; mavenCentral() }
            dependencies {
                minecraft 'com.mojang:minecraft:${metadata.minecraftVersion}'
                mappings loom.officialMojangMappings()
                modImplementation 'org.quiltmc:quilt-loader:${metadata.loaderVersion}'
            }
            tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = $javaMajor }
            java { sourceCompatibility = JavaVersion.toVersion($javaMajor); targetCompatibility = JavaVersion.toVersion($javaMajor) }
        """.trimIndent() + "\n"
        ModLoader.FORGE -> {
            val forgeVersion = metadata.loaderVersion.takeIf { it.startsWith("${metadata.minecraftVersion}-") }
                ?: "${metadata.minecraftVersion}-${metadata.loaderVersion}"
            """
                buildscript {
                    repositories { maven { url = 'https://maven.minecraftforge.net/' }; mavenCentral(); gradlePluginPortal() }
                    dependencies { classpath 'net.minecraftforge.gradle:ForgeGradle:6.0.+' }
                }
                apply plugin: 'net.minecraftforge.gradle'
                apply plugin: 'java'
                group = 'com.mclauncher.workspace'
                version = '1.0.0'
                base { archivesName = '${safeId(metadata.name)}' }
                minecraft { mappings channel: 'official', version: '${metadata.minecraftVersion}' }
                dependencies { minecraft 'net.minecraftforge:forge:$forgeVersion' }
                tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = $javaMajor }
                java { sourceCompatibility = JavaVersion.toVersion($javaMajor); targetCompatibility = JavaVersion.toVersion($javaMajor) }
                jar { finalizedBy 'reobfJar' }
            """.trimIndent() + "\n"
        }
        ModLoader.NEOFORGE -> """
            plugins { id 'java-library'; id 'net.neoforged.moddev' version '2.0.141' }
            group = 'com.mclauncher.workspace'
            version = '1.0.0'
            base { archivesName = '${safeId(metadata.name)}' }
            repositories { mavenCentral() }
            neoForge { version = '${metadata.loaderVersion}' }
            tasks.withType(JavaCompile).configureEach { options.encoding = 'UTF-8'; options.release = $javaMajor }
            java { sourceCompatibility = JavaVersion.toVersion($javaMajor); targetCompatibility = JavaVersion.toVersion($javaMajor) }
        """.trimIndent() + "\n"
        ModLoader.VANILLA -> error("Vanilla has no mod workspace")
    }

    private fun recommendedGradle(metadata: WorkspaceMetadata, javaMajor: Int): String = when {
        metadata.minecraftVersion.substringBefore('.').toIntOrNull()?.let { it >= 26 } == true -> "9.5.1"
        javaMajor <= 8 -> "6.9.4"
        else -> "8.12.1"
    }

    private fun starterJava(packageName: String, metadata: WorkspaceMetadata): String = when (metadata.loader) {
        ModLoader.FABRIC -> """
            package $packageName;
            import net.fabricmc.api.ModInitializer;
            public final class WorkspaceMod implements ModInitializer {
                public static final String MOD_ID = "${safeId(metadata.name)}";
                @Override public void onInitialize() { }
            }
        """.trimIndent() + "\n"
        ModLoader.FORGE -> """
            package $packageName;
            import net.minecraftforge.fml.common.Mod;
            @Mod(WorkspaceMod.MOD_ID)
            public final class WorkspaceMod {
                public static final String MOD_ID = "${safeId(metadata.name)}";
            }
        """.trimIndent() + "\n"
        ModLoader.NEOFORGE -> """
            package $packageName;
            import net.neoforged.fml.common.Mod;
            @Mod(WorkspaceMod.MOD_ID)
            public final class WorkspaceMod {
                public static final String MOD_ID = "${safeId(metadata.name)}";
            }
        """.trimIndent() + "\n"
        ModLoader.QUILT -> """
            package $packageName;
            public final class WorkspaceMod {
                public static final String MOD_ID = "${safeId(metadata.name)}";
                private WorkspaceMod() { }
            }
        """.trimIndent() + "\n"
        ModLoader.VANILLA -> error("Vanilla has no mod workspace")
    }

    private fun writeMetadata(resources: File, packageName: String, metadata: WorkspaceMetadata) {
        resources.mkdirs()
        val modId = safeId(metadata.name)
        when (metadata.loader) {
            ModLoader.FABRIC -> resources.resolve("fabric.mod.json").writeText(
                """{"schemaVersion":1,"id":"$modId","version":"1.0.0","name":"${jsonEscape(metadata.name)}","entrypoints":{"main":["$packageName.WorkspaceMod"]},"depends":{"fabricloader":">=${metadata.loaderVersion}","minecraft":"${metadata.minecraftVersion}"}}""" + "\n"
            )
            ModLoader.QUILT -> resources.resolve("quilt.mod.json").writeText(
                """{"schema_version":1,"quilt_loader":{"group":"com.mclauncher.workspace","id":"$modId","version":"1.0.0","metadata":{"name":"${jsonEscape(metadata.name)}","license":"ARR"},"depends":[{"id":"quilt_loader","versions":">=${metadata.loaderVersion}"},{"id":"minecraft","versions":"${metadata.minecraftVersion}"}]}}""" + "\n"
            )
            ModLoader.FORGE, ModLoader.NEOFORGE -> {
                val neo = metadata.loader == ModLoader.NEOFORGE
                val path = if (neo) "META-INF/neoforge.mods.toml" else "META-INF/mods.toml"
                val dependency = if (neo) "neoforge" else "forge"
                resources.resolve(path).apply { parentFile?.mkdirs() }.writeText(
                    """
                    modLoader="javafml"
                    loaderVersion="[1,)"
                    license="All Rights Reserved"
                    [[mods]]
                    modId="$modId"
                    version="1.0.0"
                    displayName="${tomlEscape(metadata.name)}"
                    [[dependencies.$modId]]
                    modId="$dependency"
                    mandatory=true
                    versionRange="[${metadata.loaderVersion},)"
                    ordering="NONE"
                    side="BOTH"
                    [[dependencies.$modId]]
                    modId="minecraft"
                    mandatory=true
                    versionRange="[${metadata.minecraftVersion}]"
                    ordering="NONE"
                    side="BOTH"
                    """.trimIndent() + "\n"
                )
            }
            ModLoader.VANILLA -> Unit
        }
    }

    private fun WorkspaceMetadata.toProject() = CodeWorkspaceProject(id, name, minecraftVersion, loader, loaderVersion)

    private fun projectDirectory(id: String): File {
        require(id.matches(Regex("[a-z0-9_-]{2,80}"))) { "Invalid workspace ID" }
        val root = workspaceRoot.canonicalFile
        val project = workspaceRoot.resolve(id).canonicalFile
        require(project.path.startsWith(root.path + File.separator) && project.isDirectory) { "Workspace does not exist" }
        return project
    }

    private fun safeProjectFile(projectId: String, path: String): File {
        val project = projectDirectory(projectId)
        val normalized = path.trim().replace('\\', '/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ".." !in normalized.split('/')) {
            "Unsafe workspace path"
        }
        require(normalized.split('/').none { it in HIDDEN_BUILD_DIRECTORIES || it == METADATA_FILE }) {
            "Build-cache and MCLauncher metadata paths cannot be edited"
        }
        val target = project.resolve(normalized).canonicalFile
        require(target.path.startsWith(project.path + File.separator)) { "Unsafe workspace path" }
        return target
    }

    private fun isEditable(file: File): Boolean = file.name in EDITABLE_NAMES || file.extension.lowercase() in EDITABLE_EXTENSIONS

    private fun normalizeArchivePath(value: String): String {
        val normalized = value.replace('\\', '/').trimStart('/')
        require(normalized.isNotBlank() && ".." !in normalized.split('/')) { "Unsafe path in source ZIP" }
        return normalized
    }

    private fun uniqueWorkspaceId(base: String): String {
        var candidate = "${base.take(48)}-${System.currentTimeMillis()}"
        var counter = 2
        while (workspaceRoot.resolve(candidate).exists()) candidate = "${base.take(44)}-${System.currentTimeMillis()}-$counter".also { counter++ }
        return candidate
    }

    private fun safeId(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .let { when { it.length < 2 -> "mcl_workspace"; it.first() !in 'a'..'z' -> "mcl_$it"; else -> it } }
        .take(48)

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun tomlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun uniqueFile(parent: File, name: String): File {
        var candidate = parent.resolve(name)
        var counter = 2
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty()
            candidate = parent.resolve("$base-$counter${if (extension.isBlank()) "" else ".$extension"}")
            counter++
        }
        return candidate
    }

    companion object {
        private const val METADATA_FILE = ".mclauncher-workspace.json"
        private const val MAX_PROJECT_ENTRIES = 5_000
        private const val MAX_PROJECT_ARCHIVE_BYTES = 200L * 1024L * 1024L
        private const val MAX_PROJECT_BYTES = 150L * 1024L * 1024L
        private const val MAX_VISIBLE_FILES = 2_000
        private const val MAX_EDITABLE_FILE_BYTES = 1024L * 1024L
        private const val BUILD_TIMEOUT_SECONDS = 60 * 60
        private const val POLL_INTERVAL_MS = 1_000L
        private val HIDDEN_BUILD_DIRECTORIES = setOf(".gradle", ".git", "build", ".mclauncher")
        private val EDITABLE_NAMES = setOf("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "gradlew")
        private val EDITABLE_EXTENSIONS = setOf(
            "java", "kt", "kts", "groovy", "gradle", "json", "toml", "properties", "xml", "yml", "yaml",
            "txt", "md", "mcmeta", "mcfunction", "glsl", "vsh", "fsh", "vert", "frag", "cfg", "conf"
        )
    }
}
