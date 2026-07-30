package com.mclauncher.app.engine

import com.mclauncher.minecraft.MinecraftLayout
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.Renderer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class GraphicsRegistryTest {
    @Test
    fun nativeVulkanUsesAndroidLoaderWithoutRendererPack() {
        val root = Files.createTempDirectory("mclauncher-vulkan-test").toFile()
        try {
            val resolved = GraphicsRegistry(
                MinecraftLayout(root),
                "arm64-v8a"
            ).resolve(
                requestedRenderer = Renderer.VULKAN,
                requestedDriver = GraphicsDriver.SYSTEM,
                cacheDirectory = File(root, "cache").apply { mkdirs() }
            )

            assertEquals(Renderer.VULKAN, resolved.renderer)
            assertEquals(GraphicsDriver.SYSTEM, resolved.driver)
            assertEquals("vulkan", resolved.pojavRenderer)
            assertEquals(emptyList(), resolved.searchDirectories)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun knownOpenLtwRendererIgnoresStaleAngleToken() {
        val root = Files.createTempDirectory("mclauncher-graphics-test").toFile()
        try {
            val layout = MinecraftLayout(root)
            val pack = layout.engineRendererDirectory("arm64-v8a", Renderer.OPEN_LTW.id)
                .apply { mkdirs() }
            File(pack, "libltw.so").writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
            File(pack, "mclauncher-graphics.json").writeText(
                """
                {
                  "schemaVersion": 1,
                  "id": "openltw",
                  "name": "OpenLTW / LTW",
                  "version": "test",
                  "kind": "renderer",
                  "architecture": "arm64-v8a",
                  "renderer": "OPEN_LTW",
                  "pojavRenderer": "opengles3_desktopgl_angle_vulkan",
                  "files": ["libltw.so"],
                  "sourceName": "test",
                  "importedAtEpochMs": 0
                }
                """.trimIndent()
            )

            val resolved = GraphicsRegistry(layout, "arm64-v8a").resolve(
                requestedRenderer = Renderer.OPEN_LTW,
                requestedDriver = GraphicsDriver.SYSTEM,
                cacheDirectory = File(root, "cache").apply { mkdirs() }
            )

            assertEquals(Renderer.OPEN_LTW, resolved.renderer)
            assertEquals("opengles3_ltw", resolved.pojavRenderer)
        } finally {
            root.deleteRecursively()
        }
    }
}
