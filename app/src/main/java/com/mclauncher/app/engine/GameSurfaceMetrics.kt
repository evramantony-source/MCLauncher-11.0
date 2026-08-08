package com.mclauncher.app.engine

/**
 * Keeps Android input coordinates separate from the lower-resolution game buffer.
 *
 * A SurfaceView can fill the device while its SurfaceHolder renders at a fixed
 * resolution. Pointer positions are normalized in the View coordinate space,
 * while relative motion is scaled into the Surface buffer before entering GLFW.
 */
internal data class GameSurfaceMetrics(
    val viewWidth: Int = 1,
    val viewHeight: Int = 1,
    val bufferWidth: Int = 1,
    val bufferHeight: Int = 1
) {
    fun withViewSize(width: Int, height: Int): GameSurfaceMetrics = copy(
        viewWidth = width.coerceAtLeast(1),
        viewHeight = height.coerceAtLeast(1)
    )

    fun withBufferSize(width: Int, height: Int): GameSurfaceMetrics = copy(
        bufferWidth = width.coerceAtLeast(1),
        bufferHeight = height.coerceAtLeast(1)
    )

    fun normalizedX(viewX: Float): Float = viewX / viewWidth.toFloat()

    fun normalizedY(viewY: Float): Float = viewY / viewHeight.toFloat()

    fun bufferX(viewX: Float): Float = normalizedX(viewX) * bufferWidth

    fun bufferY(viewY: Float): Float = normalizedY(viewY) * bufferHeight

    fun bufferDeltaX(viewDeltaX: Float): Float = viewDeltaX * bufferWidth / viewWidth

    fun bufferDeltaY(viewDeltaY: Float): Float = viewDeltaY * bufferHeight / viewHeight
}
