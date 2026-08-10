package com.mclauncher.app.creation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.mclauncher.model.ModLoader
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class GeneratedLocalProject(
    val directory: File,
    val id: String,
    val displayName: String,
    val mechanic: String,
    val compiledFeature: Boolean
)

class LocalProjectGenerator(private val labRoot: File) {
    fun createModProject(
        prompt: String,
        target: LocalBuildTarget,
        attachments: List<LocalAttachment>
    ): GeneratedLocalProject {
        require(target.loader != ModLoader.VANILLA) { "Choose a Fabric, Quilt, Forge or NeoForge instance" }
        require(target.loaderVersion.isNotBlank()) { "The target instance has no loader version" }
        val request = interpret(prompt, target)
        val stamp = System.currentTimeMillis().toString()
        val project = labRoot.resolve("projects/${request.modId}-$stamp").apply { mkdirs() }
        project.resolve("references").mkdirs()
        attachments.forEach { attachment ->
            File(attachment.localPath).takeIf(File::isFile)?.copyTo(
                project.resolve("references/${safeFileName(attachment.displayName)}"),
                overwrite = true
            )
        }
        project.resolve("MCL-SPECIFICATION.txt").writeText(
            buildString {
                appendLine("MCLauncher local project")
                appendLine("Target: ${target.loader.displayName} ${target.loaderVersion}, Minecraft ${target.minecraftVersion}")
                appendLine("Requested: $prompt")
                appendLine("Interpreted capability: ${request.mechanic}")
                if (!request.compiledFeature) {
                    appendLine()
                    appendLine("This prompt is preserved as a project specification. The local capability engine generated a safe starter mod because it does not yet have a verified implementation for this mechanic.")
                }
                if (attachments.isNotEmpty()) {
                    appendLine()
                    appendLine("Attachment inspection:")
                    attachments.forEach { appendLine("- ${it.displayName}: ${it.analysis}") }
                }
            }
        )
        writeCommonBuildFiles(project, request, target)
        writeLoaderFiles(project, request, target)
        if (request.compiledFeature) writeGrapplingResources(project, request, attachments)
        return GeneratedLocalProject(
            directory = project,
            id = request.modId,
            displayName = request.displayName,
            mechanic = request.mechanic,
            compiledFeature = request.compiledFeature
        )
    }

    fun createShaderPack(
        prompt: String,
        minecraftVersion: String,
        attachments: List<LocalAttachment>
    ): Pair<File, File> {
        require(prompt.isNotBlank()) { "Describe the shader pack" }
        val stamp = System.currentTimeMillis().toString()
        val safe = safeId(prompt).take(32).ifBlank { "mcl_shader" }
        val project = labRoot.resolve("projects/$safe-$stamp").apply { mkdirs() }
        val shaders = project.resolve("shaders").apply { mkdirs() }
        val lower = prompt.lowercase(Locale.ROOT)
        val effect = when {
            "black and white" in lower || "grayscale" in lower || "monochrome" in lower -> ShaderEffect.GRAYSCALE
            "warm" in lower || "cinematic" in lower || "sunset" in lower -> ShaderEffect.WARM
            "vibrant" in lower || "saturated" in lower || "colorful" in lower -> ShaderEffect.VIBRANT
            "dark" in lower || "horror" in lower -> ShaderEffect.DARK
            else -> ShaderEffect.CLEAN
        }
        project.resolve("pack.mcmeta").writeText(
            """{"pack":{"pack_format":${packFormat(minecraftVersion)},"description":"${jsonEscape("MCLauncher local shader ‚Äî ${effect.label}")}"}}"""
        )
        project.resolve("MCL-SPECIFICATION.txt").writeText(
            buildString {
                appendLine("Requested: $prompt")
                appendLine("Generated effect: ${effect.label}")
                appendLine("Target Minecraft: $minecraftVersion")
                attachments.forEach { appendLine("Attachment ${it.displayName}: ${it.analysis}") }
            }
        )
        shaders.resolve("final.vsh").writeText(FINAL_VERTEX_SHADER)
        shaders.resolve("final.fsh").writeText(finalFragmentShader(effect))
        shaders.resolve("shaders.properties").writeText("# Generated locally by MCLauncher\noldLighting=false\n")
        val outputDirectory = labRoot.resolve("outputs").apply { mkdirs() }
        val output = uniqueFile(outputDirectory, "$safe-$stamp.zip")
        zipDirectory(project, output)
        return project to output
    }

    private fun interpret(prompt: String, target: LocalBuildTarget): ModRequest {
        require(prompt.isNotBlank()) { "Describe the mod you want" }
        val lower = prompt.lowercase(Locale.ROOT)
        val isGrapplingHook = ("grappling" in lower || "grapple" in lower) && "hook" in lower
        val supportedGrapple = isGrapplingHook && target.minecraftVersion == "1.20.1"
        val modId = if (isGrapplingHook) "mcl_grappling_hook" else safeId(prompt).take(42).ifBlank { "mcl_generated_mod" }
        val name = if (isGrapplingHook) "MCLauncher Grappling Hook" else displayName(prompt)
        return ModRequest(
            modId = modId,
            displayName = name,
            mechanic = when {
                supportedGrapple -> "working grappling-hook item"
                isGrapplingHook -> "grappling hook specification (starter project; verified implementation currently targets 1.20.1)"
                else -> "safe loader starter plus preserved specification"
            },
            compiledFeature = supportedGrapple
        )
    }

    private fun writeCommonBuildFiles(project: File, request: ModRequest, target: LocalBuildTarget) {
        project.resolve("settings.gradle").writeText(settingsGradle(request, target))
        project.resolve("build.gradle").writeText(buildGradle(request, target))
        project.resolve("gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx1024m -Dfile.encoding=UTF-8
            org.gradle.daemon=false
            org.gradle.parallel=false
            org.gradle.configuration-cache=false
            """.trimIndent() + "\n"
        )
        project.resolve("LICENSE").writeText("Generated by the device owner with MCLauncher Creation Lab.\n")
    }

    private fun settingsGradle(request: ModRequest, target: LocalBuildTarget): String {
        val repositories = when (target.loader) {
            ModLoader.FABRIC -> "maven { name = 'Fabric'; url = 'https://maven.fabricmc.net/' }"
            ModLoader.QUILT -> "maven { name = 'Quilt'; url = 'https://maven.quiltmc.org/repository/release/' }"
            ModLoader.FORGE -> "maven { name = 'Forge'; url = 'https://maven.minecraftforge.net/' }"
            ModLoader.NEOFORGE -> "maven { name = 'NeoForge'; url = 'https://maven.neoforged.net/releases' }"
            ModLoader.VANILLA -> error("Vanilla is not a mod loader")
        }
        return """
            pluginManagement {
                repositories {
                    $repositories
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            rootProject.name = '${request.modId}'
        """.trimIndent() + "\n"
    }

    private fun buildGradle(request: ModRequest, target: LocalBuildTarget): String = when (target.loader) {
        ModLoader.FABRIC -> fabricBuild(request, target)
        ModLoader.QUILT -> quiltBuild(request, target)
        ModLoader.FORGE -> forgeBuild(request, target)
        ModLoader.NEOFORGE -> neoForgeBuild(request, target)
        ModLoader.VANILLA -> error("Vanilla is not a mod loader")
    }

    private fun fabricBuild(request: ModRequest, target: LocalBuildTarget): String {
        val calendarVersion = target.minecraftVersion.substringBefore('.').toIntOrNull()?.let { it >= 26 } == true
        val loomId = if (calendarVersion) "net.fabricmc.fabric-loom" else "fabric-loom"
        val loomVersion = when {
            calendarVersion -> "1.17-SNAPSHOT"
            target.minecraftVersion.startsWith("1.21") -> "1.17-SNAPSHOT"
            else -> "1.10-SNAPSHOT"
        }
        val mappings = if (calendarVersion) "" else "mappings loom.officialMojangMappings()"
        return """
            plugins {
                id '$loomId' version '$loomVersion'
                id 'java'
            }
            group = 'com.mclauncher.generated'
            version = '1.0.0'
            base { archivesName = '${request.modId}' }
            repositories { mavenCentral() }
            dependencies {
                minecraft 'com.mojang:minecraft:${target.minecraftVersion}'
                $mappings
                implementation 'net.fabricmc:fabric-loader:${target.loaderVersion}'
            }
            tasks.withType(JavaCompile).configureEach {
                options.encoding = 'UTF-8'
                options.release = ${target.javaVersion.major}
            }
            java {
                sourceCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
                targetCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
            }
        """.trimIndent() + "\n"
    }

    private fun quiltBuild(request: ModRequest, target: LocalBuildTarget): String = """
        plugins {
            id 'org.quiltmc.loom' version '1.7.4'
            id 'java'
        }
        group = 'com.mclauncher.generated'
        version = '1.0.0'
        base { archivesName = '${request.modId}' }
        repositories {
            maven { url = 'https://maven.quiltmc.org/repository/release/' }
            mavenCentral()
        }
        dependencies {
            minecraft 'com.mojang:minecraft:${target.minecraftVersion}'
            mappings loom.officialMojangMappings()
            modImplementation 'org.quiltmc:quilt-loader:${target.loaderVersion}'
            ${if (request.compiledFeature) "modImplementation 'org.quiltmc.quilted-fabric-api:quilted-fabric-api:7.7.0+0.92.2-1.20.1'" else ""}
        }
        tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
            options.release = ${target.javaVersion.major}
        }
        java {
            sourceCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
            targetCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
        }
    """.trimIndent() + "\n"

    private fun forgeBuild(request: ModRequest, target: LocalBuildTarget): String {
        val forgeVersion = if (target.loaderVersion.startsWith("${target.minecraftVersion}-")) {
            target.loaderVersion
        } else {
            "${target.minecraftVersion}-${target.loaderVersion}"
        }
        return """
        buildscript {
            repositories {
                maven { url = 'https://maven.minecraftforge.net/' }
                mavenCentral()
                gradlePluginPortal()
            }
            dependencies { classpath 'net.minecraftforge.gradle:ForgeGradle:6.0.+' }
        }
        apply plugin: 'net.minecraftforge.gradle'
        apply plugin: 'java'
        group = 'com.mclauncher.generated'
        version = '1.0.0'
        base { archivesName = '${request.modId}' }
        minecraft { mappings channel: 'official', version: '${target.minecraftVersion}' }
        dependencies { minecraft 'net.minecraftforge:forge:$forgeVersion' }
        tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
            options.release = ${target.javaVersion.major}
        }
        java {
            sourceCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
            targetCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
        }
        jar { finalizedBy 'reobfJar' }
    """.trimIndent() + "\n"
    }

    private fun neoForgeBuild(request: ModRequest, target: LocalBuildTarget): String = """
        plugins {
            id 'java-library'
            id 'net.neoforged.moddev' version '2.0.141'
        }
        group = 'com.mclauncher.generated'
        version = '1.0.0'
        base { archivesName = '${request.modId}' }
        repositories { mavenCentral() }
        neoForge { version = '${target.loaderVersion}' }
        tasks.withType(JavaCompile).configureEach {
            options.encoding = 'UTF-8'
            options.release = ${target.javaVersion.major}
        }
        java {
            sourceCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
            targetCompatibility = JavaVersion.VERSION_${target.javaVersion.major}
        }
    """.trimIndent() + "\n"

    private fun writeLoaderFiles(project: File, request: ModRequest, target: LocalBuildTarget) {
        val packageName = "com.mclauncher.generated.${request.modId}"
        val javaDirectory = project.resolve("src/main/java/${packageName.replace('.', '/')}").apply { mkdirs() }
        val resources = project.resolve("src/main/resources").apply { mkdirs() }
        val bootstrap = when (target.loader) {
            ModLoader.FABRIC -> fabricBootstrap(packageName, request)
            ModLoader.QUILT -> quiltBootstrap(packageName, request)
            ModLoader.FORGE -> forgeBootstrap(packageName, request)
            ModLoader.NEOFORGE -> neoForgeBootstrap(packageName, request)
            ModLoader.VANILLA -> error("Vanilla is not a mod loader")
        }
        javaDirectory.resolve("GeneratedMod.java").writeText(bootstrap)
        if (request.compiledFeature) javaDirectory.resolve("GrapplingHookItem.java").writeText(grapplingHookItem(packageName))
        when (target.loader) {
            ModLoader.FABRIC -> resources.resolve("fabric.mod.json").writeText(fabricMetadata(packageName, request, target))
            ModLoader.QUILT -> resources.resolve("quilt.mod.json").writeText(quiltMetadata(packageName, request, target))
            ModLoader.FORGE -> resources.resolve("META-INF/mods.toml").apply { parentFile?.mkdirs() }
                .writeText(forgeMetadata(request, target, neo = false))
            ModLoader.NEOFORGE -> resources.resolve("META-INF/neoforge.mods.toml").apply { parentFile?.mkdirs() }
                .writeText(forgeMetadata(request, target, neo = true))
            ModLoader.VANILLA -> Unit
        }
    }

    private fun fabricBootstrap(packageName: String, request: ModRequest): String = if (request.compiledFeature) """
        package $packageName;

        import net.fabricmc.api.ModInitializer;
        import net.minecraft.core.Registry;
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.item.Item;

        public final class GeneratedMod implements ModInitializer {
            public static final String MOD_ID = "${request.modId}";
            public static final Item GRAPPLING_HOOK = new GrapplingHookItem(new Item.Properties().stacksTo(1).durability(384));

            @Override public void onInitialize() {
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MOD_ID, "grappling_hook"), GRAPPLING_HOOK);
            }
        }
    """.trimIndent() + "\n" else """
        package $packageName;
        import net.fabricmc.api.ModInitializer;
        public final class GeneratedMod implements ModInitializer {
            public static final String MOD_ID = "${request.modId}";
            @Override public void onInitialize() { }
        }
    """.trimIndent() + "\n"

    private fun quiltBootstrap(packageName: String, request: ModRequest): String = if (request.compiledFeature) """
        package $packageName;

        import net.minecraft.core.Registry;
        import net.minecraft.core.registries.BuiltInRegistries;
        import net.minecraft.resources.ResourceLocation;
        import net.minecraft.world.item.Item;
        import org.quiltmc.loader.api.ModContainer;
        import org.quiltmc.qsl.base.api.entrypoint.ModInitializer;

        public final class GeneratedMod implements ModInitializer {
            public static final String MOD_ID = "${request.modId}";
            public static final Item GRAPPLING_HOOK = new GrapplingHookItem(new Item.Properties().stacksTo(1).durability(384));
            @Override public void onInitialize(ModContainer mod) {
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MOD_ID, "grappling_hook"ﬂnz‚⁄$z{-ÆÈ‹j◊ùosable
private fun AiWorkshop(
    state: CreationLabUiState,
    instances: List<MinecraftInstance>,
    onUpdateOutput: (LocalProjectOutput) -> Unit,
    onSelectTarget: (MinecraftInstance) -> Unit,
    onUpdateInstallIntoInstance: (Boolean) -> Unit,
    onAttach: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onGenerate: (String) -> Unit
) {
    var prompt by remember { mutableStateOf("") }
    val moddedInstances = remember(instances) { instances.filter { it.loader != ModLoader.VANILLA } }
    val selectedTarget = instances.firstOrNull { it.id == state.aiTargetInstanceId }
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("MCL local Creation Engine", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "No API key, account credit, subscription or GitHub builder. Projects, attachments and compilation stay on this tablet. The first JAR build downloads a verified free Gradle toolchain and loader libraries.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LocalProjectOutput.entries.forEach { choice ->
                FilterChip(selected = state.aiOutput == choice, onClick = { onUpdateOutput(choice) }, label = { Text(choice.label) })
            }
        }
        Text("Target instance", style = MaterialTheme.typography.titleSmall)
        if (moddedInstances.isEmpty()) {
            Text(
                "Install a Fabric, Quilt, Forge or NeoForge instance first. Shader ZIPs can still be created without one.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                moddedInstances.forEach { instance ->
                    FilterChip(
                        selected = state.aiTargetInstanceId == instance.id,
                        onClick = { onSelectTarget(instance) },
                        label = {
                            Text("${instance.name} ¬∑ ${instance.loader.displayName} ${instance.loaderVersion.orEmpty()}")
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
            label = { Text("Describe the project") },
            placeholder = { Text("Example: Add a grappling hook with configurable range and a crafting recipe‚Ä¶") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onAttach, enabled = !state.aiBusy && state.aiAttachments.size < 8) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null)
                Text("Attach files, images or JARs", modifier = Modifier.padding(start = 6.dp))
            }
            FilterChip(
                selected = state.aiInstallIntoInstance,
                onClick = { onUpdateInstallIntoInstance(!state.aiInstallIntoInstance) },
                enabled = selectedTarget != null,
                label = { Text("Also add to selected instance") }
            )
        }
        state.aiAttachments.forEach { attachment ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${attachment.displayName} ¬∑ ${attachment.kind.label} ¬∑ ${formatAttachmentSize(attachment.sizeBytes)}")
                    Text(
                        attachment.analysis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { onRemoveAttachment(attachment.id) }, enabled = !state.aiBusy) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Remove attachment")
                }
            }
        }
        Text(
            "Verified capability in this build: working grappling-hook mods for Minecraft 1.20.1, safe starter JARs for all four loaders, and local color-effect shader packs. Other requests are preserved in the project specification instead of pretending unfinished behavior works. JAR metadata and common text crash signatures are inspected locally; arbitrary binary porting still requires source code and a future capability module.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.aiBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(state.aiProgress ?: "Working‚Ä¶", style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = { onGenerate(prompt) },
            enabled = prompt.isNotBlank() && !state.aiBusy &&
                (state.aiOutput == LocalProjectOutput.SHADER_ZIP || selectedTarget?.loader != null)
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
            Text(
                if (state.aiOutput == LocalProjectOutput.MOD_JAR) "Generate and build locally" else "Generate shader ZIP locally",
                modifier = Modifier.padding(start = 7.dp)
            )
        }
        if (state.aiLastOutputPath != null) {
            Text(
                "Latest output is visible in Android Files ‚Üí MCLauncher ‚Üí MCL Creation Lab ‚Üí outputs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
    else -> "$bytes B"
}

private fun safeExportName(value: String): String = value.trim()
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .take(64)
    .ifBlank { "mcl-resource-pack" }

private fun colorHex(color: Int): String = "%08X".format(Locale.ROOT, color)

private fun parseColorHex(value: String): Int? = runCatching {
    when (value.length) {
        6 -> (0xFF000000L or value.toLong(16)).toInt()
        8 -> value.toLong(16).toInt()
        else -> null
    }
}.getOrNull()

private val PRESET_COLORS = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFFF3B30.toInt(),
    0xFFFFCC00.toInt(),
    0xFF34C759.toInt(),
    0xFF007AFF.toInt(),
    0xFFAF52DE.toInt(),
    0x00000000
)
