package com.mclauncher.app.engine

import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.MinecraftGraphicsApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NativeEngineCompatibilityPolicyTest {
    @Test
    fun autoTurnipIsLimitedToSnapshotVulkanOnQualcomm() {
        assertTrue(
            shouldAutoSelectBundledTurnip(
                snapshotSdlCompatibility = true,
                requestedDriver = GraphicsDriver.AUTO,
                graphicsApi = MinecraftGraphicsApi.VULKAN,
                bundledTurnipAvailable = true,
                qualcommDevice = true
            )
        )
        assertFalse(
            shouldAutoSelectBundledTurnip(
                snapshotSdlCompatibility = true,
                requestedDriver = GraphicsDriver.AUTO,
                graphicsApi = MinecraftGraphicsApi.OPENGL,
                bundledTurnipAvailable = true,
                qualcommDevice = true
            )
        )
        assertFalse(
            shouldAutoSelectBundledTurnip(
                snapshotSdlCompatibility = true,
                requestedDriver = GraphicsDriver.SYSTEM,
                graphicsApi = MinecraftGraphicsApi.VULKAN,
                bundledTurnipAvailable = true,
                qualcommDevice = true
            )
        )
        assertFalse(
            shouldAutoSelectBundledTurnip(
                snapshotSdlCompatibility = false,
                requestedDriver = GraphicsDriver.AUTO,
                graphicsApi = MinecraftGraphicsApi.VULKAN,
                bundledTurnipAvailable = true,
                qualcommDevice = true
            )
        )
    }

    @Test
    fun linkerHookIsNeverPreloadedIntoTheAppNamespace() {
        assertTrue(shouldDeferNativePreload("liblinkerhook.so"))
        assertTrue(shouldDeferNativePreload("LIBLINKERHOOK.SO"))
        assertFalse(shouldDeferNativePreload("libmojoexec.so"))
        assertFalse(shouldDeferNativePreload("libvulkan_freedreno.so"))
    }
}
