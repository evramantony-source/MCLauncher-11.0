package com.mclauncher.minecraft

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.ModLoader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ModpackRuntimeSpecTest {
    @Test
    fun readsExactModrinthFabricRuntime() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-mrpack-runtime").toFile()
        try {
            val archive = File(root, "pack.mrpack")
            zipText(
                archive,
                "modrinth.index.json",
                """
                {
                  "formatVersion": 1,
                  "game": "minecraft",
                  "versionId": "v40",
                  "name": "Example Pack",
                  "files": [],
                  "dependencies": {
                    "minecraft": "1.20.1",
                    "fabric-loader": "0.15.11"
                  }
                }
                """.trimIndent()
            )

            val spec = ModrinthPackInstaller(MinecraftLayout(root)).inspect(archive)
            assertEquals("1.20.1", spec.minecraftVersion)
            assertEquals(ModLoader.FABRIC, spec.loader)
            assertEquals("0.15.11", spec.loaderVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun readsExactCurseForgePrimaryLoader() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-cfpack-runtime").toFile()
        try {
            val archive = File(root, "pack.zip")
            zipText(
                archive,
                "manifest.json",
                """
                {
                  "minecraft": {
                    "version": "1.20.1",
                    "modLoaders": [
                      { "id": "forge-47.2.0", "primary": true }
                    ]
                  },
                  "name": "Example Pack",
                  "files": []
                }
                """.trimIndent()
            )

            val spec = CurseForgePackInstaller(MinecraftLayout(root)).inspect(archive)
            assertEquals("1.20.1", spec.minecraftVersion)
            assertEquals(ModLoader.FORGE, spec.loader)
            assertEquals("47.2.0", spec.loaderVersion)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsModrinthLoaderWithoutAnExactVersion() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-mrpack-no-loader-version").toFile()
        try {
            val archive = File(root, "pack.mrpack")
            zipText(
                archive,
                "modrinth.index.json",
                """
                {
                  "versionId": "broken-1",
                  "name": "Broken Pack",
                  "files": [],
                  "dependencies": {
                    "minecraft": "1.20.1",
                    "fabric-loader": ""
                  }
                }
                """.trimIndent()
            )

            assertFailsWith<IllegalStateException> {
                ModrinthPackInstaller(MinecraftLayout(root)).inspect(archive)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsModrinthLoaderVersionRangesInsteadOfPickingLatest() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-mrpack-loader-range").toFile()
        try {
            val archive = File(root, "pack.mrpack")
            zipText(
                archive,
                "modrinth.index.json",
                """
                {
                  "versionId": "ranged-1",
                  "name": "Ranged Pack",
                  "files": [],
                  "dependencies": {
                    "minecraft": "1.20.1",
                    "fabric-loader": ">=0.15.11"
                  }
                }
                """.trimIndent()
            )

            assertFailsWith<IllegalArgumentException> {
                ModrinthPackInstaller(MinecraftLayout(root)).inspect(archive)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun resolvesFabricProfileBaseGameVersion() {
        val instance = MinecraftInstance(
            id = "fabric-pack",
            name = "Fabric Pack",
            versionId = "fabric-loader-0.19.3-26.2",
            gameDirectoryName = "fabric-pack",
            javaVersion = JavaVersion.JAVA_25,
            loader = ModLoader.FABRIC,
            loaderVersion = "0.19.3",
            createdAtEpochMs = 1L,
            installed = true
        )

        assertEquals("26.2", baseGameVersion(instance))
    }

    private fun zipText(archive: File, path: String, text: String) {
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(path))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
}
