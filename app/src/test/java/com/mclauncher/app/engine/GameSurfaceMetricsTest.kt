package com.mclauncher.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class GameSurfaceMetricsTest {
    @Test
    fun `redmi pad pro input maps into configured game buffer`() {
        val metrics = GameSurfaceMetrics(
            viewWidth = 2560,
            viewHeight = 1600,
            bufferWidth = 896,
            bufferHeight = 504
        )

        // Coordinates copied from the Alpha 16 device trace supplied for this bug.
        assertEquals(0.3234375f, metrics.normalizedX(828f), 0.000001f)
        assertEquals(0.8249375f, metrics.normalizedY(1319.9f), 0.000001f)
        assertEquals(289.8f, metrics.bufferX(828f), 0.001f)
        assertEquals(415.7685f, metrics.bufferY(1319.9f), 0.001f)
    }

    @Test
    fun `relative motion scales from view pixels into buffer pixels`() {
        val metrics = GameSurfaceMetrics(
            viewWidth = 2560,
            viewHeight = 1600,
            bufferWidth = 896,
            bufferHeight = 504
        )

        assertEquals(35f, metrics.bufferDeltaX(100f), 0.0001f)
        assertEquals(31.5f, metrics.bufferDeltaY(100f), 0.0001f)
    }

    @Test
    fun `matching view and buffer resolutions keep motion unchanged`() {
        val metrics = GameSurfaceMetrics(
            viewWidth = 1920,
            viewHeight = 1080,
            bufferWidth = 1920,
            bufferHeight = 1080
        )

        assertEquals(73f, metrics.bufferDeltaX(73f))
        assertEquals(-41f, metrics.bufferDeltaY(-41f))
    }
}
