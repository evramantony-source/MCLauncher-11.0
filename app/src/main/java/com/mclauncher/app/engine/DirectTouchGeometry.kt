package com.mclauncher.app.engine

internal data class DirectTouchPoint(
    val localX: Float,
    val localY: Float,
    val normalizedX: Float,
    val normalizedY: Float,
    val insideSurface: Boolean
)

/** Maps coordinates already delivered to the SurfaceView into GLFW's normalized space. */
internal object DirectTouchGeometry {
    fun map(
        localX: Float,
        localY: Float,
        surfaceWidth: Int,
        surfaceHeight: Int
    ): DirectTouchPoint {
        val width = surfaceWidth.coerceAtLeast(1).toFloat()
        val height = surfaceHeight.coerceAtLeast(1).toFloat()
        return DirectTouchPoint(
            localX = localX.coerceIn(0f, width),
            localY = localY.coerceIn(0f, height),
            normalizedX = (localX / width).coerceIn(0f, 1f),
            normalizedY = (localY / height).coerceIn(0f, 1f),
            insideSurface = localX in 0f..width && localY in 0f..height
        )
    }
}
