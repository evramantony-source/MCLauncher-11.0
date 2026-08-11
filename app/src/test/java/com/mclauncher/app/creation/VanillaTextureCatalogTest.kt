package com.mclauncher.app.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VanillaTextureCatalogTest {
    @Test
    fun acceptsEveryVanillaTextureCategoryUsedByItemRendering() {
        listOf(
            "assets/minecraft/textures/item/diamond_sword.png",
            "assets/minecraft/textures/block/stone.png",
            "assets/minecraft/textures/entity/chest/normal.png",
            "assets/minecraft/textures/entity/equipment/humanoid/diamond.png",
            "assets/minecraft/textures/gui/sprites/container/slot.png"
        ).forEach { path ->
            assertTrue(isEditableVanillaTexturePath(path), path)
        }
    }

    @Test
    fun rejectsNonTexturesAndUnsafeArchivePaths() {
        listOf(
            "assets/minecraft/models/item/diamond_sword.json",
            "assets/example/textures/item/fake.png",
            "assets/minecraft/textures/../lang/en_us.png",
            "assets/minecraft/textures/item\\fake.png",
            "/assets/minecraft/textures/item/fake.png"
        ).forEach { path ->
            assertFalse(isEditableVanillaTexturePath(path), path)
        }
    }

    @Test
    fun preservesTheCategoryInCatalogLabels() {
        val path = "assets/minecraft/textures/entity/chest/christmas.png"
        assertEquals("entity/chest/christmas", vanillaTextureRelativePath(path))
        assertEquals("entity › chest › christmas", vanillaTextureDisplayName(path))
    }
}
