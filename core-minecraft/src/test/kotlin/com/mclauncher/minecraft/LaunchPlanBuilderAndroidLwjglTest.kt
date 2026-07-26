package com.mclauncher.minecraft

import com.mclauncher.model.JavaVersion
import com.mclauncher.model.LauncherSettings
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

    private fun createJar(file: File, entryName: String, content: String) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
}
