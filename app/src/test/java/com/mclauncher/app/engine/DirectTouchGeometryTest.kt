package com.mclauncher.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectTouchGeometryTest {
    @Test
    fun mapsWindowCoordinatesAfterStatusBarInset() {
        val point = DirectTouchGeometry.map(
            windowX = 768f,
            windowY = 498f,
            surfaceLeftInWindow = 0f,
            surfaceTopInWindow = 36f,
            surfaceWidth = 1536,
            surfaceHeight = 924
        )

        assertEquals(0.5f, point.normalizedX)
        assertEquals(0.5f, point.normalizedY)
        assertTrue(point.insideSurface)
    }

    @Test
    fun mapsSurfaceCornersWithoutRawScreenOffsets() {
        val topLeft = DirectTouchGeometry.map(12f, 40f, 12f, 40f, 1280, 720)
        val bottomRight = DirectTouchGeometry.map(1292f, 760f, 12f, 40f, 1280, 720)

        assertEquals(0f, topLeft.normalizedX)
        assertEquals(0f, topLeft.normalizedY)
        assertEquals(1f, bottomRight.normalizedX)
        assertEquals(1f, bottomRight.normalizedY)
        assertTrue(topLeft.insideSurface)
        assertTrue(bottomRight.insideSurface)
    }

    @Test
    fun clampsDragCoordinatesButMarksThemOutside() {
        val point = DirectTouchGeometry.map(-20f, 900f, 0f, 36f, 1536, 900)

        assertEquals(0f, point.normalizedX)
        assertEquals(0.96f, point.normalizedY)
        assertFalse(point.insideSurface)
    }
}
