package com.mclauncher.minecraft

import com.mclauncher.model.ContentSource
import com.mclauncher.model.ContentType
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ModpackExporterTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun exportsVerifiedModrinthReferencesAndSafeOverrides() {
        withLayout("modrinth-export") { layout, instance ->
            val game = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
            val remote = File(game, "mods/remote.jar").apply {
                parentFile.mkdirs()
                writeText("provider-owned")
            }
            File(game, "mods/local.jar").writeText("local")
            File(game, "config/example.toml").apply { parentFile.mkdirs(); writeText("enabled=true") }
            File(game, "logs/latest.log").apply { parentFile.mkdirs(); writeText("private log") }
            File(game, "saves/My World/level.dat").apply { parentFile.mkdirs(); writeText("private world") }
            val managed = InstalledContent(
                projectId = "AABBCCDD",
                versionId = "VVVV1111",
                contentType = ContentType.MOD,
                fileName = remote.name,
                title = "Remote Mod",
                versionNumber = "1.0.0",
                loader = "fabric",
                gameVersion = "1.20.1",
                sha1 = Hashing.sha1(remote),
                sha512 = Hashing.sha512(remote),
                downloadUrls = listOf(
                    "https://cdn.modrinth.com/data/AABBCCDD/versions/VVVV1111/remote.jar"
                ),
                fileSize = remote.length(),
                clientEnvironment = "required",
                serverEnvironment = "optional",
                source = ContentSource.MODRINTH
            )
            val archive = File(layout.root, "example.mrpack")

            val result = ModpackExporter(layout).export(
                instance = instance,
                managedContent = listOf(managed),
                destination = archive,
                format = ModpackExportFormat.MODRINTH,
                metadata = ModpackExportMetadata("Example Pack", "2.0.0", "Player")
            )

            assertEquals(1, result.referencedFiles)
            ZipFile(archive).use { zip ->
                val index = zip.getInputStream(zip.getEntry("modrinth.index.json"))
                    .bufferedReader().use { json.decodeFromString<ModrinthPackIndex>(it.readText()) }
                assertEquals("1.20.1", index.dependencies["minecraft"])
                assertEquals("0.15.11", index.dependencies["fabric-loader"])
                assertEquals(listOf("mods/remote.jar"), index.files.map { it.path })
                assertEquals(40, index.files.single().hashes.sha1?.length)
                assertEquals(128, index.files.single().hashes.sha512?.length)
                assertNotNull(zip.getEntry("overrides/mods/local.jar"))
                assertNotNull(zip.getEntry("overrides/config/example.toml"))
                assertEquals(null, zip.getEntry("overrides/mods/remote.jar"))
                assertEquals(null, zip.getEntry("overrides/logs/latest.log"))
                assertEquals(null, zip.getEntry("overrides/saves/My World/level.dat"))
            }
        }
    }

    @Test
    fun embedsAChangedModrinthFileInsteadOfPublishingABrokenReference() {
        withLayout("modrinth-modified") { layout, instance ->
            val remote = File(
                layout.instanceGameDirectory(instance.gameDirectoryName),
                "mods/changed.jar"
            ).apply { parentFile.mkdirs(); writeText("changed locally") }
            val managed = InstalledContent(
                projectId = "AABBCCDD",
                versionId = "VVVV1111",
                contentType = ContentType.MOD,
                fileName = remote.name,
                title = "Changed Mod",
                versionNumber = "1.0.0",
                gameVersion = "1.20.1",
                sha1 = "0".repeat(40),
                sha512 = "0".repeat(128),
                downloadUrls = listOf(
                    "https://cdn.modrinth.com/data/AABBCCDD/versions/VVVV1111/changed.jar"
                ),
                source = ContentSource.MODRINTH
            )
            val archive = File(layout.root, "changed.mrpack")

            val result = ModpackExporter(layout).export(
                instance,
                listOf(managed),
                archive,
                ModpackExportFormat.MODRINTH,
                ModpackExportMetadata("Changed Pack")
            )

            assertEquals(0, result.referencedFiles)
            assertTrue(result.warnings.isNotEmpty())
            ZipFile(archive).use { zip ->
                val index = zip.getInputStream(zip.getEntry("modrinth.index.json"))
                    .bufferedReader().use { json.decodeFromString<ModrinthPackIndex>(it.readText()) }
                assertTrue(index.files.isEmpty())
                assertNotNull(zip.getEntry("overrides/mods/changed.jar"))
            }
        }
    }

    @Test
    fun exportsCurseForgeIdsAtRootAndKeepsCrossProviderFilesInOverrides() {
        withLayout("curseforge-export") { layout, instance ->
            val game = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
            val curseFile = File(game, "mods/curse.jar").apply { parentFile.mkdirs(); writeText("curse") }
            val modrinthFile = File(game, "mods/modrinth.jar").apply { writeText("modrinth") }
            val curseItem = InstalledContent(
                projectId = "curseforge:1234",
                versionId = "5678",
                contentType = ContentType.MOD,
                fileName = curseFile.name,
                title = "Curse Mod",
                versionNumber = "1.0",
                gameVersion = "1.20.1",
                sha1 = Hashing.sha1(curseFile),
                source = ContentSource.CURSEFORGE
            )
            val modrinthItem = InstalledContent(
                projectId = "AABBCCDD",
                versionId = "VVVV1111",
                contentType = ContentType.MOD,
                fileName = modrinthFile.name,
                title = "Modrinth Mod",
                versionNumber = "1.0",
                gameVersion = "1.20.1",
                sha1 = Hashing.sha1(modrinthFile),
                source = ContentSource.MODRINTH
            )
            val archive = File(layout.root, "curseforge.zip")

            val result = ModpackExporter(layout).export(
                instance,
                listOf(curseItem, modrinthItem),
                archive,
                ModpackExportFormat.CURSEFORGE,
                ModpackExportMetadata("Example Pack", "3.0.0", "Player")
            )

            assertEquals(1, result.referencedFiles)
            ZipFile(archive).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("manifest.json"))
                    .bufferedReader().use { json.decodeFromString<CurseForgePackManifest>(it.readText()) }
                assertEquals("minecraftModpack", manifest.manifestType)
                assertEquals("overrides", manifest.overrides)
                assertEquals("fabric-0.15.11", manifest.minecraft.modLoaders.single().id)
                assertEquals(1234, manifest.files.single().projectId)
                assertEquals(5678, manifest.files.single().fileId)
                assertNotNull(zip.getEntry("overrides/"))
                assertNotNull(zip.getEntry("overrides/mods/modrinth.jar"))
                assertEquals(null, zip.getEntry("overrides/mods/curse.jar"))
            }
        }
    }

    @Test
    fun exportedOverrideOnlyMrpackCanBeImportedAgain() {
        withLayout("modrinth-round-trip") { layout, instance ->
            val sourceGame = layout.instanceGameDirectory(instance.gameDirectoryName).apply { mkdirs() }
            File(sourceGame, "config/round-trip.toml").apply { parentFile.mkdirs(); writeText("works=true") }
            val archive = File(layout.root, "round-trip.mrpack")
            ModpackExporter(layout).export(
                instance,
                emptyList(),
                archive,
                ModpackExportFormat.MODRINTH,
                ModpackExportMetadata("Round Trip")
            )
            val imported = instance.copy(id = "imported", gameDirectoryName = "imported")

            val index = runBlocking { ModrinthPackInstaller(layout).install(imported, archive) }

            assertEquals("Round Trip", index.name)
            assertTrue(File(layout.instanceGameDirectory("imported"), "config/round-trip.toml").isFile)
        }
    }

    private fun withLayout(
        prefix: String,
        block: (MinecraftLayout, MinecraftInstance) -> Unit
    ) {
        val root = Files.createTempDirectory("mclauncher-$prefix").toFile()
        try {
            val layout = MinecraftLayout(root).also(MinecraftLayout::ensureBaseDirectories)
            block(layout, instance())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun instance() = MinecraftInstance(
        id = "instance",
        name = "Example Instance",
        versionId = "1.20.1",
        gameDirectoryName = "instance",
        javaVersion = JavaVersion.JAVA_17,
        loader = ModLoader.FABRIC,
        loaderVersion = "0.15.11",
        createdAtEpochMs = 1L,
        installed = true
    )
}
