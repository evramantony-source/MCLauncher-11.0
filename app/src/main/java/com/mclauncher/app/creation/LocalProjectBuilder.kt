package com.mclauncher.app.creation

import android.content.Context
import com.mclauncher.model.ModLoader
import java.io.File
import java.util.zip.ZipFile

class LocalProjectBuilder(private val context: Context) {
    private val labRoot = context.filesDir.resolve("creation-lab").apply { mkdirs() }
    private val generator = LocalProjectGenerator(labRoot)

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
        onProgress("Interpreting the request with the built-in Minecraft capability engine…")
        val project = generator.createModProject(prompt, target, attachments)
        val outputs = labRoot.resolve("outputs").apply { mkdirs() }
        val output = uniqueFile(outputs, "${project.id}-${target.loader.id}-${target.minecraftVersion}.jar")
        onProgress("Assembling the ${target.loader.displayName} JAR directly on this device…")
        generator.assembleModJar(project.directory, output)
        onProgress("Validating loader metadata and generated resources…")
        validateModJar(output, target.loader, project.verifiedFeature)
        val installed = if (installIntoInstance) {
            copyIntoInstance(output, target, "mods", "${project.id}.jar")
        } else null
        return LocalCreationResult(
            outputPath = output.absolutePath,
            projectPath = project.directory.absolutePath,
            installedPath = installed?.absolutePath,
            summary = "${project.displayName} was generated as a working data-driven mod, assembled locally and validated${if (installed != null) " in ${target.instanceName}" else ""}."
        )
    }

    private fun validateModJar(jar: File, loader: ModLoader, verifiedMechanic: Boolean) {
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
            require(zip.getEntry("META-INF/MCL-SPECIFICATION.txt") != null) {
                "Generated JAR is missing its project specification"
            }
            if (verifiedMechanic) {
                require(zip.getEntry("data/minecraft/tags/functions/tick.json") != null) {
                    "Generated grappling-hook JAR is missing its tick function tag"
                }
                require(zip.getEntry("data/mcl_grappling_hook/functions/tick.mcfunction") != null) {
                    "Generated grappling-hook JAR is missing its mechanic"
                }
                require(zip.getEntry("assets/mcl_grappling_hook/textures/item/grappling_hook.png") != null) {
                    "Generated grappling-hook JAR is missing its texture"
                }
            }
        }
    }

    private fun validateShader(zipFile: File) {
        ZipFile(zipFile).use { zip ->
            require(zip.getEntry("pack.mcmeta") != null) { "Shader ZIP is missing pack.mcmeta" }
            require(zip.getEntry("shaders/final.vsh") != null) { "Shader ZIP is missing final.vsh" }
            require(zip.getEntry("shaders/final.fsh") != null) { "Shader ZIP is missing final.fsh" }
        }
    }

    private fun copyIntoInstance(
        source: File,
        target: LocalBuildTarget,
        folder: String,
        destinationName: String = source.name
    ): File {
        val instancesRoot = context.filesDir.resolve("minecraft/instances").canonicalFile
        val gameDirectory = instancesRoot.resolve(target.gameDirectoryName).canonicalFile
        require(gameDirectory.path.startsWith(instancesRoot.path + File.separator)) { "Unsafe instance directory" }
        val destinationDirectory = gameDirectory.resolve(folder).apply { mkdirs() }
        val destination = destinationDirectory.resolve(destinationName)
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
    }
}
