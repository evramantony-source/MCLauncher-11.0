package com.mclauncher.app.engine

internal data class DirectTouchPoint(
    val normalizedX: Float,
    val normalizedY: Float,
    val insideSurface: Boolean
)

/** Maps Activity-window touch coordinates onto the exact SurfaceView rectangle. */
internal object DirectTouchGeometry {
    fun map(
        windowX: Float,
        windowY: Float,
        surfaceLeftInWindow: Float,
        surfaceTopInWindow: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): DirectTouchPoint {
        val width = surfaceWidth.coerceAtLeast(1).toFloat()
        val height = surfaceHeight.coerceAtLeast(1).toFloat()
        val localX = windowX - surfaceLeftInWindow
        val localY = windowY - surfaceTopInWindow
        return DirectTouchPoint(
            normalizedX = (localX / width).coerceIn(0f, 1f),
            normalizedY = (localY / height).coerceIn(0f, 1f),
            insideSurface = localX in 0f..width && localY in 0f..height
        )
    }
}
