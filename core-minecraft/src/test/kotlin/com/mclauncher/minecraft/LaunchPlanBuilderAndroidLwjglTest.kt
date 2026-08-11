package com.mclauncher.minecraft

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.MinecraftGraphicsApi
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.OfflineAccountFactory
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LaunchPlanBuilderAndroidLwjglTest {
    @Test
    fun mapsDesktopLwjglAndExtractsArm64NativeClassifier() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-launch-plan-test").toFile()
        try {
            val layout = MinecraftLayout(root).also(MinecraftLayout::ensureBaseDirectories)
            val versionId = "1.20.1-test"
            layout.versionDirectory(versionId).mkdirs()
            layout.clientJar(versionId).writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            layout.versionJson(versionId).writeText(
                """
                {
                  "id": "$versionId",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "javaVersion": { "majorVersion": 17 },
                  "assetIndex": { "id": "1.20" },
                  "arguments": { "jvm": [], "game": [] },
                  "libraries": [
                    {
                      "name": "org.lwjgl:lwjgl:3.3.1",
                      "downloads": {
                        "artifact": {
                          "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar"
                        }
                      }
                    }
                  ]
                }
                """.trimIndent()
            )

            val replacementPath = "org/lwjgl/lwjgl/3.3.6/lwjgl-3.3.6.jar"
            val classifierPath = "org/lwjgl/lwjgl/3.3.6/lwjgl-natives-linux-arm64-3.3.6.jar"
            createJar(File(layout.engineJarsDirectory, replacementPath), "META-INF/replacement.txt", "android")
            createJar(File(layout.engineJarsDirectory, classifierPath), "linux/arm64/liblwjgl.so", "ELF-test")
            File(layout.engineJarsDirectory, "substitutions.json").writeText(
                """
                {
                  "libraries": {
                    "org.lwjgl:lwjgl:3.3.6": {
                      "name": "org.lwjgl:lwjgl:3.3.6",
                      "downloads": {
                        "artifact": { "path": "$replacementPath" },
                        "classifiers": {
                          "natives-linux-arm64": { "path": "$classifierPath" }
                        }
                      }
                    }
                  },
                  "artifactMapping": {
                    "org.lwjgl:lwjgl:3.3.1": "org.lwjgl:lwjgl:3.3.6"
                  }
                }
                """.trimIndent()
            )

            val instance = MinecraftInstance(
                id = "test-instance",
                name = "Test",
                versionId = versionId,
                gameDirectoryName = "test",
                javaVersion = JavaVersion.JAVA_17,
                createdAtEpochMs = 1L,
                installed = true
            )
            val account = OfflineAccountFactory.create("PlayerOne", nowEpochMs = 1L)
            val plan = LaunchPlanBuilder(layout).build(
                instance = instance,
                account = account,
                settings = LauncherSettings(selectedJava = JavaVersion.JAVA_17),
                architecture = "arm64-v8a"
            )

            assertEquals(
                listOf(File(layout.engineJarsDirectory, replacementPath).absolutePath, layout.clientJar(versionId).absolutePath),
                plan.classpath
            )
            assertTrue(File(plan.nativesDirectory, "liblwjgl.so").isFile)
            assertFalse(plan.classpath.any { it.contains("lwjgl-3.3.1.jar") })
            assertTrue(File(plan.workingDirectory, "options.txt").readText().contains("maxFps:60"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mapsFabricSnapshot6InheritanceToExactAndroidLwjglSdl342Artifact() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-lwjgl-sdl-test").toFile()
        try {
            val layout = MinecraftLayout(root).also(MinecraftLayout::ensureBaseDirectories)
            val baseVersionId = "26.3-snapshot-6"
            val versionId = "fabric-loader-0.19.3-$baseVersionId"
            layout.versionDirectory(baseVersionId).mkdirs()
            layout.clientJar(baseVersionId).writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            layout.versionJson(baseVersionId).writeText(
                """
                {
                  "id": "$baseVersionId",
                  "type": "snapshot",
                  "mainClass": "net.minecraft.client.main.Main",
                  "javaVersion": { "majorVersion": 25 },
                  "assetIndex": { "id": "32" },
                  "arguments": { "jvm": [], "game": [] },
                  "libraries": [
                    {
                      "name": "org.lwjgl:lwjgl-sdl:3.4.2",
                      "downloads": {
                        "artifact": { "path": "org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar" }
                      }
                    },
                    {
                      "name": "org.lwjgl:lwjgl:3.4.2",
                      "downloads": {
                        "artifact": { "path": "org/lwjgl/lwjgl/3.4.2/lwjgl-3.4.2.jar" }
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
            val fabricPath = "net/fabricmc/fabric-loader/0.19.3/fabric-loader-0.19.3.jar"
            layout.versionDirectory(versionId).mkdirs()
            layout.versionJson(versionId).writeText(
                """
                {
                  "id": "$versionId",
                  "inheritsFrom": "$baseVersionId",
                  "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
                  "arguments": { "jvm": [], "game": [] },
                  "libraries": [
                    {
                      "name": "net.fabricmc:fabric-loader:0.19.3",
                      "downloads": {
                        "artifact": { "path": "$fabricPath" }
                      }
                    }
                  ]
                }
                """.trimIndent()
            )
            val sdlPath = "org/lwjgl/lwjgl-sdl/3.4.2/lwjgl-sdl-3.4.2.jar"
            val corePath = "org/lwjgl/lwjgl/3.4.2/lwjgl-3.4.2.jar"
            val nativesPath = "org/lwjgl/lwjgl/3.4.2/lwjgl-natives-linux-arm64-3.4.2.jar"
            createJar(File(layout.engineJarsDirectory, sdlPath), "META-INF/sdl.txt", "android-sdl")
            createJar(File(layout.engineJarsDirectory, corePath), "META-INF/core.txt", "android-core")
            createJar(File(layout.engineJarsDirectory, nativesPath), "linux/arm64/liblwjgl.so", "ELF-test")
            createJar(layout.library(fabricPath), "META-INF/fabric.txt", "fabric")
            File(layout.engineJarsDirectory, "substitutions.json").writeText(
                """
                {
                  "libraries": {
                    "org.lwjgl:lwjgl-sdl:3.4.2": {
                      "name": "org.lwjgl:lwjgl-sdl:3.4.2",
                      "downloads": {
                        "artifact": { "path": "$sdlPath" }
                      }
                    },
                    "org.lwjgl:lwjgl:3.4.2": {
                      "name": "org.lwjgl:lwjgl:3.4.2",
                      "downloads": {
                        "artifact": { "path": "$corePath" },
                        "classifiers": {
                          "natives-linux-arm64": { "path": "$nativesPath" }
                        }
                      }
                    }
                  },
                  "artifactMapping": {}
                }
                """.trimIndent()
            )

            val instance = MinecraftInstance(
                id = "snapshot-sdl-test",
                name = "Snapshot SDL test",
                versionId = versionId,
                gameDirectoryName = "snapshot-sdl-test",
                javaVersion = JavaVersion.JAVA_25,
                createdAtEpochMs = 1L,
                installed = true
            )
            val plan = LaunchPlanBuilder(layout).build(
                instance = instance,
                account = OfflineAccountFactory.create("PlayerOne", nowEpochMs = 1L),
                settings = LauncherSettings(selectedJava = JavaVersion.JAVA_25),
                architecture = "arm64-v8a"
            )

            assertEquals(
                listOf(
                    File(layout.engineJarsDirectory, sdlPath).absolutePath,
                    File(layout.engineJarsDirectory, corePath).absolutePath,
                    layout.library(fabricPath).absolutePath,
                    layout.clientJar(baseVersionId).absolutePath
                ),
                plan.classpath
            )
            assertTrue(File(plan.nativesDirectory, "liblwjgl.so").isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun writesMinecraft262GraphicsApiPreferenceToInstanceOptions() = runBlocking {
        val root = Files.createTempDirectory("mclauncher-graphics-api-test").toFile()
        try {
            val layout = MinecraftLayout(root).also(MinecraftLayout::ensureBaseDirectories)
            val versionId = "26.2"
            layout.versionDirectory(versionId).mkdirs()
            layout.clientJar(versionId).writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
            layout.versionJson(versionId).writeText(
                """
                {
                  "id": "$versionId",
                  "type": "release",
                  "mainClass": "net.minecraft.client.main.Main",
                  "javaVersion": { "majorVersion": 25 },
                  "assetIndex": { "id": "32" },
                  "arguments": { "jvm": [], "game": [] },
                  "libraries": []
                }
                """.trimIndent()
            )
            File(layout.engineJarsDirectory, "substitutions.json").writeText(
                """{"libraries": {}, "artifactMapping": {}}"""
            )

            val instance = MinecraftInstance(
                id = "minecraft-26-2",
                name = "Minecraft 26.2",
                versionId = versionId,
                gameDirectoryName = "minecraft-26-2",
                javaVersion = JavaVersion.JAVA_25,
                createdAtEpochMs = 1L,
                installed = true
            )
            val plan = LaunchPlanBuilder(layout).build(
                instance = instance,
                account = OfflineAccountFactory.create("PlayerOne", nowEpochMs = 1L),
                settings = LauncherSettings(
                    selectedJava = JavaVersion.JAVA_25,
                    minecraftGraphicsApi = MinecraftGraphicsApi.OPENGL
                ),
                architecture = "arm64-v8a"
            )

            assertEquals(MinecraftGraphicsApi.OPENGL, plan.minecraftGraphicsApi)
            assertTrue(
                File(plan.workingDirectory, "options.txt")
                    .readText()
                    .contains("preferredGraphicsBackend:\"opengl\"")
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createJar(file: File, entryName: String, content: String) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}
