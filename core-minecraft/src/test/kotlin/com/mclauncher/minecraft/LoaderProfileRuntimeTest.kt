package com.mclauncher.minecraft

import com.mclauncher.model.ModLoader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoaderProfileRuntimeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun acceptsOnlyTheExactFabricArtifact() {
        val exact = profile("net.fabricmc:fabric-loader:0.15.11")
        val wrong = profile("net.fabricmc:fabric-loader:0.16.14")

        assertTrue(profileDeclaresExactLoader(exact, ModLoader.FABRIC, "1.20.1", "0.15.11"))
        assertFalse(profileDeclaresExactLoader(wrong, ModLoader.FABRIC, "1.20.1", "0.15.11"))
    }

    @Test
    fun acceptsExactForgeArtifactWithClassifier() {
        val exact = profile("net.minecraftforge:forge:1.20.1-47.2.0:universal")
        val wrong = profile("net.minecraftforge:forge:1.20.1-47.3.0:universal")

        assertTrue(profileDeclaresExactLoader(exact, ModLoader.FORGE, "1.20.1", "47.2.0"))
        assertFalse(profileDeclaresExactLoader(wrong, ModLoader.FORGE, "1.20.1", "47.2.0"))
    }

    private fun profile(coordinate: String) = json.parseToJsonElement(
        """{"libraries":[{"name":"$coordinate"}]}"""
    ).jsonObject
}
