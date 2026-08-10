package com.mclauncher.app.creation

import android.content.Context
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.delay
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipFile

class LocalProjectBuilder(private val context: Context) {
    private val labRoot = context.filesDir.resolve("creation-lab").apply { mkdirs() }
    private val generator = LocalProjectGenerator(labRoot)
    private val toolchain = LocalGradleToolchain(context)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun create(
        prompt: String,
        output: LocalProjectOutput,
        target: LocalBuildTarget?,
        attachments: List<LocalAttachment>,
        installIntoInstance: Boolean,
        onProgress: (String) -> Unit
    ): LocalCreationResult {
        require(prompt.isNotBlank()) { "Describe what you want to create" }
        require(attachments.size <= MAX_ATTACHMENTS) { "Attach at most $MAX_ATTACHMENTS files" }
        return when (output) {
            LocalProjectOutput.SHADER_ZIP -> createShader(
                prompt = prompt,
                target = target,
                attachments = attachments,
                installIntoInstance = installIntoInstance,
                onProgress = onProgress
            )
            LocalProjectOutput.MOD_JAR -> createMod(
                prompt = prompt,
                target = requireNotNull(target) { "Choose an installed mod-loader instance" },
                attachments = attachments,
                installIntoInstance = installIntoInstance,
                onProgress = onProgress
            )
        }
    }

    private suspend fun createShader(
        prompt: String,
        target: LocalBuildTarget?,
        attachments: List<LocalAttachment>,
        installIntoInstance: Boolean,
        onProgress: (String) -> Unit
    ): LocalCreationResult {
        onProgress("Generating the shader pack entirely on this device…")
        val minecraftVersion = target?.minecraftVersion ?: "1.20.1"
        val (project, output) = generator.createShaderPack(prompt, minecraftVersion, attachments)
        validateShader(output)
        val installed = if (installIntoInstance && target != null) {
            copyIntoInstance(output, target, "shaderpacks")
        } else null
        return LocalCreationResult(
            outputPath = output.absolutePath,
            projectPath = project.absolutePath,
            installedPath = installed?.absolutePath,
            summary = "Local shader ZIP created${if (installed != null) " and added to ${target?.instanceName}" else ""}."
        )
    }

    private suspend fun createMod(
        prompt: String,
        target: LocalBuildTarget,
        attachments: List<LocalAttachment>,
        installIntoInstance: Boolean,
        onProgress: (String) -> Unit
    ): LocalCreationResult {
        require(target.loader != ModLoader.VANILLA) { "The selected instance does not use a mod loader" }
        val lower = prompt.lowercase(Locale.ROOT)
        if (("upgrade" in lower || "update" in lower || "port" in lower) && attachments.any { it.kind == AttachmentKind.MOD_JAR }) {
            error(
                "The attached JAR was inspected, but a compiled JAR cannot be safely rewritten into an arbitrary new Minecraft version. " +
                    "Attach its source project; automatic semantic porting is not part of the verified local capability set yet."
            )
        }
        onProgress("Interpreting the request with the built-in Minecraft capability engine…")
        val project = generator.createModProject(prompt, target, attachments)
        val gradleVersion = toolchain.gradleVersion(target.javaVersion)
        val gradleHome = toolchain.ensureInstalled(gradleVersion, onProgress)
        val statusFile = labRoot.resolve("build-status/${UUID.randomUUID()}.json").apply {
            parentFile?.mkdirs()
            delete()
        }
        onProgress("Preparing ${target.loader.displayName} ${target.loaderVersion} for a local build…")
        val planFile = toolchain.createToolPlan(
            projectDirectory = project.directory,
            gradleHome = gradleHome,
            javaVersion = toolchain.runtimeVersion(target),
            statusFile = statusFile
        )
        onProgress("Opening the isolated on-device builder…")
        LocalBuildActivity.start(context, planFile, statusFile)
        val status = waitForBuild(statusFile, onProgress)
        require(status.exitCode == 0) { status.error ?: "Local build failed" }
        onProgress("Validating the generated JAR…")
        val built = findBuiltJar(project.directory)
        validateModJar(built, target.loader)
        val outputs = labRoot.resolve("outputs").apply { mkdirs() }
        val output = uniqueFile(outputs, "${project.id}-${target.loader.id}-${target.minecraftVersion}.jar")
        built.copyTo(output)
        val installed = if (installIntoInstance) copyIntoInstance(output, target, "mods") else null
        planFile.delete()
        statusFile.delete()
        return LocalCreationResult(
            outputPath = output.absolutePath,
            projectPath = project.directory.absolutePath,
            installedPath = installed?.absolutePath,
            summary = if (project.compiledFeature) {
                "${project.displayName} was generated, compiled locally and validated${if (installed != null) " in ${target.instanceName}" else ""}."
            } else {
                "A loadable ${target.loader.displayName} starter JAR was compiled locally. The requested behavior is preserved in MCL-SPECIFICATION.txt but is outside the current verified capability set."
            }
        )
    }

    private suspend fun waitForBuild(statusFile: File, onProgress: (String) -> Unit): LocalBuildStatus {
        var elapsedSeconds = 0
        while (elapsedSeconds < BUILD_TIMEOUT_SECONDS) {
            delay(POLL_INTERVAL_MS)
            elapsedSeconds += (POLL_INTERVAL_MS / 1_000L).toInt()
            if (statusFile.isFile) {
                val status = runCatching { json.decodeFromString<LocalBuildStatus>(statusFile.readText()) }.getOrNull()
                if (status?.finished == true) return status
            }
            if (elapsedSeconds > 0 && elapsedSeconds % 20 == 0) {
                onProgress("Building locally… ${elapsedSeconds / 60}m ${elapsedSeconds % 60}s")
            }
        }
        error("The local build timed out after ${BUILD_TIMEOUT_SECONDS / 60} minutes")
    }

    private fun findBuiltJar(project: File): File {
        val candidates = project.resolve("build/libs").listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("jar", true) }
            .filterNot { it.name.contains("sources", true) || it.name.contains("javadoc", true) || it.name.contains("dev", true) }
            .sortedByDescending(File::length)
        return candidates.firstOrNull() ?: error("Gradle finished but did not create a mod JAR")
    }

    private fun validateModJar(jar: File, loader: ModLoader) {
        require(jar.isFile && jar.length() > 0L) { "Generated JAR is empty" }
        val required = when (loader) {
            ModLoader.FABRIC -> "fabric.mod.json"
            ModLoader.QUILT -> "quilt.mod.json"
            ModLoader.FORGE -> "META-INF/mods.toml"
            ModLoader.NEOFORGE -> "META-INF/neoforge.mods.toml"
            ModLoader.VANILLA -> error("Vanilla is not a mod loader")
        }
        ZipFile(jar).use { zip ->
            require(zip.getEntry(required) != null) { "Generated JAR is missing $required" }
            require(zip.entries().asSequence().any { it.name.endsWith(".class") }) { "Generated JAR has no compiled classes" }
        }
    }

    private fun validateShader(zipFile: File) {
        ZipFile(zipFile).use { zip ->
            require(zip.getEntry("pack.mcmeta") != null) { "Shader ZIP is missing pack.mcmeta" }
            require(zip.getEntry("shaders/final.vsh") != null) { "Shader ZIP is missing final.vsh" }
            require(zip.getEntry("shaders/final.fsh") != null) { "Shader ZIP is missing final.fsh" }
        }
    }

    private fun copyIntoInstance(source: File, target: LocalBuildTarget, folder: String): File {
        val instancesRoot = context.filesDir.resolve("minecraft/instances").canonicalFile
        val gameDirectory = instancesRoot.resolve(target.gameDirectoryName).canonicalFile
        require(gameDirectory.path.startsWith(instancesRoot.path + File.separator)) { "Unsafe instance directory" }
        val destinationDirectory = gameDirectory.resolve(folder).apply { mkdirs() }
        val destination = destinationDirectory.resolve(source.name)
        source.copyTo(destination, overwrite = true)
        return destination
    }

    private fun uniqueFile(parent: File, name: String): File {
        var file = parent.resolve(name)
        var number = 2
        while (file.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty()
            file = parent.resolve("$base-$number${if (extension.isBlank()) "" else ".$extension"}")
            number++
        }
        return file
    }

    companion object {
        private const val MAX_ATTACHMENTS = 8
        private const val BUILD_TIMEOUT_SECONDS = 60 * 60
        private const val POLL_INTERVAL_MS = 1_000L
    }
}
