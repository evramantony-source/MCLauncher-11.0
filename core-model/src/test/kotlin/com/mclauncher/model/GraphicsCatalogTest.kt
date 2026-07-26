package com.mclauncher.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphicsCatalogTest {
    @Test
    fun rendererIdsAreUniqueAndStable() {
        assertEquals(Renderer.entries.size, Renderer.entries.map(Renderer::id).distinct().size)
        assertTrue(Renderer.entries.any { it == Renderer.MOBILE_GLUES })
        assertTrue(Renderer.entries.any { it == Renderer.OPEN_LTW })
        assertTrue(Renderer.entries.any { it == Renderer.ZINK })
        assertTrue(Renderer.entries.any { it == Renderer.ANGLE })
        assertTrue(Renderer.entries.any { it == Renderer.KRYPTON })
        assertTrue(Renderer.entries.any { it == Renderer.CUSTOM })
    }

    @Test
    fun driversAreIndependentFromRenderers() {
        assertTrue(GraphicsDriver.entries.any { it == GraphicsDriver.ANGLE })
        assertTrue(GraphicsDriver.entries.any { it == GraphicsDriver.TURNIP })
        assertTrue(GraphicsDriver.SYSTEM.requiresVulkan.not())
    }
}
