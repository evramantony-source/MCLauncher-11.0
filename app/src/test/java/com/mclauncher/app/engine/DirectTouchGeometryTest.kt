package com.mclauncher.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DirectTouchGeometryTest {
    @Test
    fun mapsSurfaceLocalCenterWithoutWindowOffsets() {
        val point = DirectTouchGeometry.map(
            localX = 768f,
            localY = 462f,
            surfaceWidth = 1536,
            surfaceHeight = 924
        )

        assertEquals(768f, point.localX)
        assertEquals(462f, point.localY)
        assertEquals(0.5f, point.normalizedX)
        assertEquals(0.5f, point.normalizedY)
        assertTrue(point.insideSurface)
    }

    @Test
    fun mapsSurfaceCornersExactly() {
        val topLeft = DirectTouchGeometry.map(0f, 0f, 1280, 720)
        val bottomRight = DirectTouchGeometry.map(1280f, 720f, 1280, 720)

        assertEquals(0f, topLeft.normalizedX)
        assertEquals(0f, topLeft.normalizedY)
        assertEquals(1f, bottomRight.normalizedX)
        assertEquals(1f, bottomRight.normalizedY)
        assertTrue(topLeft.insideSurface)
        assertTrue(bottomRight.insideSurface)
    }

    @Test
    fun clampsDragCoordinatesButMarksThemOutside() {
        val point = DirectTouchGeometry.map(-20f, 864f, 1536, 900)

        assertEquals(0f, point.localX)
        assertEquals(864f, point.localY)
        assertEquals(0f, point.normalizedX)
        assertEquals(0.96f, point.normalizedY)
        assertFalse(point.insideSurface)
    }
}
