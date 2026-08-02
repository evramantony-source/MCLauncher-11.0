package com.mclauncher.app.ui.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HotbarTouchLayoutTest {
    @Test
    fun `matches Minecraft automatic GUI scale on tablet and launcher resolutions`() {
        assertEquals(4, HotbarTouchLayout.guiScale(1536, 960))
        assertEquals(728, HotbarTouchLayout.widthPixels(1536, 960))
        assertEquals(88, HotbarTouchLayout.heightPixels(1536, 960))

        assertEquals(3, HotbarTouchLayout.guiScale(1280, 720))
        assertEquals(546, HotbarTouchLayout.widthPixels(1280, 720))
    }

    @Test
    fun `respects an explicit Minecraft GUI scale`() {
        assertEquals(2, HotbarTouchLayout.guiScale(1536, 960, requestedScale = 2))
    }

    @Test
    fun `maps the full hotbar from slot one through slot nine`() {
        assertEquals(0, HotbarTouchLayout.slotAt(0f, 900f))
        assertEquals(0, HotbarTouchLayout.slotAt(99f, 900f))
        assertEquals(1, HotbarTouchLayout.slotAt(100f, 900f))
        assertEquals(4, HotbarTouchLayout.slotAt(450f, 900f))
        assertEquals(8, HotbarTouchLayout.slotAt(899f, 900f))
        assertEquals(8, HotbarTouchLayout.slotAt(900f, 900f))
    }

    @Test
    fun `rejects touches outside the hotbar`() {
        assertNull(HotbarTouchLayout.slotAt(-1f, 900f))
        assertNull(HotbarTouchLayout.slotAt(901f, 900f))
        assertNull(HotbarTouchLayout.slotAt(0f, 0f))
    }
}
