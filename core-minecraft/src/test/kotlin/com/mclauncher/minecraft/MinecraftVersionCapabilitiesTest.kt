package com.mclauncher.minecraft

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MinecraftVersionCapabilitiesTest {
    @Test
    fun graphicsApiStartsWithMinecraft262() {
        assertFalse(MinecraftVersionCapabilities.supportsGraphicsApi("1.21.11"))
        assertFalse(MinecraftVersionCapabilities.supportsGraphicsApi("26.1.2"))
        assertTrue(MinecraftVersionCapabilities.supportsGraphicsApi("26.2"))
        assertTrue(MinecraftVersionCapabilities.supportsGraphicsApi("26.2-snapshot-8"))
        assertTrue(
            MinecraftVersionCapabilities.supportsGraphicsApi(
                "fabric-loader-0.19.3-26.2"
            )
        )
        assertTrue(MinecraftVersionCapabilities.supportsGraphicsApi("27.1"))
    }
    @Test
    fun snapshotSdlCompatibilityStartsAt263Snapshot6() {
        assertFalse(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "26.3-snapshot-5"
            )
        )
        assertTrue(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "26.3-snapshot-6"
            )
        )
        assertTrue(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "fabric-loader-0.19.3-26.3-snapshot-7"
            )
        )
        assertTrue(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "26.3"
            )
        )
        assertTrue(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "27.1"
            )
        )
        assertFalse(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "26.2"
            )
        )
        assertFalse(
            MinecraftVersionCapabilities.supportsSnapshotSdlGraphicsCompatibility(
                "1.21.11"
            )
        )
    }
}

