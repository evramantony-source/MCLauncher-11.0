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
            """{"pack":{"pack_format":${packFormat(minecraftVersion)},"description":"${jsonEscape("MCLauncher local shader — ${effect.label}")}"}}"""
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
            org.gradle.daemon=false
            org.gradle.parallel=false
            org.gradle.workers.max=1
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
                Registry.register(BuiltInRegistries.ITEM, new ResourceLocation(MOD_ID, "grappling_hook"), GRAPPLING_HOOK);
            }
        }
    """.trimIndent() + "\n" else """
        package $packageName;
        public final class GeneratedMod {
            public static final String MOD_ID = "${request.modId}";
        }
    """.trimIndent() + "\n"

    private fun forgeBootstrap(packageName: String, request: ModRequest): String = if (request.compiledFeature) """
        package $packageName;

        import net.minecraft.world.item.Item;
        import net.minecraftforge.eventbus.api.IEventBus;
        import net.minecraftforge.fml.common.Mod;
        import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
        import net.minecraftforge.registries.DeferredRegister;
        import net.minecraftforge.registries.ForgeRegistries;
        import net.minecraftforge.registries.RegistryObject;

        @Mod(GeneratedMod.MOD_ID)
        public final class GeneratedMod {
            public static final String MOD_ID = "${request.modId}";
            private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
            public static final RegistryObject<Item> GRAPPLING_HOOK = ITEMS.register("grappling_hook", () -> new GrapplingHookItem(new Item.Properties().stacksTo(1).durability(384)));
            public GeneratedMod() {
                IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
                ITEMS.register(bus);
            }
        }
    """.trimIndent() + "\n" else """
        package $packageName;
        import net.minecraftforge.fml.common.Mod;
        @Mod(GeneratedMod.MOD_ID)
        public final class GeneratedMod {
            public static final String MOD_ID = "${request.modId}";
        }
    """.trimIndent() + "\n"

    private fun neoForgeBootstrap(packageName: String, request: ModRequest): String = """
        package $packageName;
        import net.neoforged.fml.common.Mod;
        @Mod(GeneratedMod.MOD_ID)
        public final class GeneratedMod {
            public static final String MOD_ID = "${request.modId}";
        }
    """.trimIndent() + "\n"

    private fun grapplingHookItem(packageName: String): String = """
        package $packageName;

        import net.minecraft.server.level.ServerPlayer;
        import net.minecraft.sounds.SoundEvents;
        import net.minecraft.sounds.SoundSource;
        import net.minecraft.world.InteractionHand;
        import net.minecraft.world.InteractionResultHolder;
        import net.minecraft.world.entity.player.Player;
        import net.minecraft.world.item.Item;
        import net.minecraft.world.item.ItemStack;
        import net.minecraft.world.level.Level;
        import net.minecraft.world.phys.HitResult;
        import net.minecraft.world.phys.Vec3;

        public final class GrapplingHookItem extends Item {
            private static final double RANGE = 32.0;
            private static final double PULL_STRENGTH = 1.35;

            public GrapplingHookItem(Properties properties) { super(properties); }

            @Override
            public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
                ItemStack stack = player.getItemInHand(hand);
                HitResult target = player.pick(RANGE, 0.0F, false);
                if (target.getType() == HitResult.Type.MISS) return InteractionResultHolder.pass(stack);
                if (!level.isClientSide) {
                    Vec3 delta = target.getLocation().subtract(player.position());
                    if (delta.lengthSqr() > 1.0) {
                        Vec3 pull = delta.normalize().scale(PULL_STRENGTH).add(0.0, 0.35, 0.0);
                        player.setDeltaMovement(pull);
                        player.hurtMarked = true;
                        player.fallDistance = 0.0F;
                        player.getCooldowns().addCooldown(this, 12);
                        if (player instanceof ServerPlayer serverPlayer) {
                            stack.hurtAndBreak(1, serverPlayer, broken -> broken.broadcastBreakEvent(hand));
                        }
                        level.playSound(null, player.blockPosition(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.PLAYERS, 0.9F, 1.15F);
                    }
                }
                return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
            }
        }
    """.trimIndent() + "\n"

    private fun fabricMetadata(packageName: String, request: ModRequest, target: LocalBuildTarget): String = """
        {
          "schemaVersion": 1,
          "id": "${request.modId}",
          "version": "1.0.0",
          "name": "${jsonEscape(request.displayName)}",
          "description": "Generated locally by MCLauncher. No API service or remote builder was used.",
          "environment": "*",
          "entrypoints": { "main": ["$packageName.GeneratedMod"] },
          "depends": { "fabricloader": ">=${target.loaderVersion}", "minecraft": "${target.minecraftVersion}" }
        }
    """.trimIndent() + "\n"

    private fun quiltMetadata(packageName: String, request: ModRequest, target: LocalBuildTarget): String = """
        {
          "schema_version": 1,
          "quilt_loader": {
            "group": "com.mclauncher.generated",
            "id": "${request.modId}",
            "version": "1.0.0",
            "metadata": {
              "name": "${jsonEscape(request.displayName)}",
              "description": "Generated locally by MCLauncher",
              "license": "ARR"
            },
            "entrypoints": { ${if (request.compiledFeature) "\"init\": \"$packageName.GeneratedMod\"" else ""} },
            "depends": [
              { "id": "quilt_loader", "versions": ">=${target.loaderVersion}" },
              { "id": "minecraft", "versions": "${target.minecraftVersion}" }
            ]
          }
        }
    """.trimIndent() + "\n"

    private fun forgeMetadata(request: ModRequest, target: LocalBuildTarget, neo: Boolean): String {
        val loaderRange = if (neo) "[1,)" else "[47,)"
        val dependency = if (neo) "neoforge" else "forge"
        val loaderVersion = if (!neo) target.loaderVersion.removePrefix("${target.minecraftVersion}-") else target.loaderVersion
        return """
            modLoader="javafml"
            loaderVersion="$loaderRange"
            license="All Rights Reserved"

            [[mods]]
            modId="${request.modId}"
            version="1.0.0"
            displayName="${tomlEscape(request.displayName)}"
            description='''Generated locally by MCLauncher. No API service or remote builder was used.'''

            [[dependencies.${request.modId}]]
            modId="$dependency"
            mandatory=true
            versionRange="[$loaderVersion,)"
            ordering="NONE"
            side="BOTH"

            [[dependencies.${request.modId}]]
            modId="minecraft"
            mandatory=true
            versionRange="[${target.minecraftVersion}]"
            ordering="NONE"
            side="BOTH"
        """.trimIndent() + "\n"
    }

    private fun writeGrapplingResources(project: File, request: ModRequest, attachments: List<LocalAttachment>) {
        val resources = project.resolve("src/main/resources")
        resources.resolve("assets/${request.modId}/lang/en_us.json").apply { parentFile?.mkdirs() }
            .writeText("{\"item.${request.modId}.grappling_hook\":\"Grappling Hook\"}\n")
        resources.resolve("assets/${request.modId}/models/item/grappling_hook.json").apply { parentFile?.mkdirs() }
            .writeText("{\"parent\":\"minecraft:item/handheld\",\"textures\":{\"layer0\":\"${request.modId}:item/grappling_hook\"}}\n")
        resources.resolve("data/${request.modId}/recipes/grappling_hook.json").apply { parentFile?.mkdirs() }
            .writeText(
                """{"type":"minecraft:crafting_shaped","pattern":[" II"," SI","S  "],"key":{"I":{"item":"minecraft:iron_ingot"},"S":{"item":"minecraft:string"}},"result":{"item":"${request.modId}:grappling_hook"}}""" + "\n"
            )
        val texture = resources.resolve("assets/${request.modId}/textures/item/grappling_hook.png").apply { parentFile?.mkdirs() }
        val image = attachments.firstOrNull { it.kind == AttachmentKind.IMAGE }
            ?.let { BitmapFactory.decodeFile(it.localPath) }
        if (image != null) {
            val scaled = Bitmap.createScaledBitmap(image, 16, 16, true)
            texture.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (scaled !== image) scaled.recycle()
            image.recycle()
        } else {
            writeDefaultHookTexture(texture)
        }
    }

    private fun writeDefaultHookTexture(file: File) {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }
        canvas.drawColor(Color.TRANSPARENT)
        paint.color = Color.rgb(205, 218, 224)
        listOf(11 to 2, 12 to 2, 10 to 3, 12 to 3, 9 to 4, 12 to 4, 8 to 5, 11 to 5, 8 to 6, 10 to 6, 8 to 7, 9 to 7)
            .forEach { (x, y) -> canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, paint) }
        paint.color = Color.rgb(130, 82, 48)
        for (i in 0..8) canvas.drawRect((7 - i / 2).toFloat(), (7 + i).toFloat(), (8 - i / 2).toFloat(), (8 + i).toFloat(), paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun finalFragmentShader(effect: ShaderEffect): String {
        val transform = when (effect) {
            ShaderEffect.GRAYSCALE -> "float l = dot(color.rgb, vec3(0.299, 0.587, 0.114)); color.rgb = vec3(l);"
            ShaderEffect.WARM -> "color.rgb = vec3(color.r * 1.08, color.g * 1.01, color.b * 0.88);"
            ShaderEffect.VIBRANT -> "float l = dot(color.rgb, vec3(0.299, 0.587, 0.114)); color.rgb = mix(vec3(l), color.rgb, 1.35);"
            ShaderEffect.DARK -> "color.rgb = pow(color.rgb * 0.78, vec3(1.08));"
            ShaderEffect.CLEAN -> "color.rgb = color.rgb;"
        }
        return """
            #version 120
            uniform sampler2D colortex0;
            varying vec2 texcoord;
            void main() {
                vec4 color = texture2D(colortex0, texcoord);
                $transform
                gl_FragColor = color;
            }
        """.trimIndent() + "\n"
    }

    private fun zipDirectory(source: File, destination: File) {
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            source.walkTopDown().filter(File::isFile).sortedBy(File::getAbsolutePath).forEach { file ->
                val relative = file.relativeTo(source).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(relative))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    private fun packFormat(version: String): Int = when {
        version.startsWith("26.") -> 75
        version.startsWith("1.21") -> 34
        version.startsWith("1.20.5") || version.startsWith("1.20.6") -> 32
        version.startsWith("1.20.3") || version.startsWith("1.20.4") -> 22
        else -> 15
    }

    private fun displayName(prompt: String): String = prompt.trim()
        .split(Regex("\\s+"))
        .take(7)
        .joinToString(" ")
        .take(64)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        .ifBlank { "MCLauncher Generated Mod" }

    private fun safeId(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
        .let { if (it.length < 2) "mcl_generated_mod" else it }

    private fun safeFileName(value: String): String = value.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(120)
    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    private fun tomlEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

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

    private data class ModRequest(
        val modId: String,
        val displayName: String,
        val mechanic: String,
        val compiledFeature: Boolean
    )

    private enum class ShaderEffect(val label: String) {
        CLEAN("clean pass-through"),
        GRAYSCALE("grayscale"),
        WARM("warm cinematic color"),
        VIBRANT("vibrant color"),
        DARK("dark atmosphere")
    }

    companion object {
        private val FINAL_VERTEX_SHADER = """
            #version 120
            varying vec2 texcoord;
            void main() {
                gl_Position = ftransform();
                texcoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
            }
        """.trimIndent() + "\n"
    }
}
