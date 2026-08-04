package com.mclauncher.app.engine

import kotlin.math.hypot

internal sealed interface DirectTouchCommand {
    val point: DirectTouchPoint

    data class Move(override val point: DirectTouchPoint) : DirectTouchCommand
    data class Button(override val point: DirectTouchPoint, val pressed: Boolean) : DirectTouchCommand
}

/**
 * Converts one-finger SurfaceView input into desktop-style menu input.
 *
 * Press and release are anchored to the finger-down coordinate so a quick tap
 * cannot become a click on a different Minecraft control if Android reports a
 * noisy final coordinate. Pointer movement is forwarded only after a deliberate
 * hold plus touch slop, which keeps inventory drag-and-drop available without
 * turning ordinary taps into accidental drags.
 */
internal class DirectTouchGesture {
    var activePointerId: Int = INVALID_POINTER_ID
        private set

    private var downPoint: DirectTouchPoint? = null
    private var lastPoint: DirectTouchPoint? = null
    private var downEventTimeMillis = 0L
    private var dragThresholdPixels = 0f
    private var dragActivationDelayMillis = 0L
    private var dragging = false

    val isActive: Boolean
        get() = activePointerId != INVALID_POINTER_ID

    fun down(
        pointerId: Int,
        point: DirectTouchPoint,
        eventTimeMillis: Long,
        dragThresholdPixels: Float,
        dragActivationDelayMillis: Long
    ): List<DirectTouchCommand> {
        val commands = cancel().toMutableList()
        activePointerId = pointerId
        downPoint = point
        lastPoint = point
        downEventTimeMillis = eventTimeMillis
        this.dragThresholdPixels = dragThresholdPixels.coerceAtLeast(0f)
        this.dragActivationDelayMillis = dragActivationDelayMillis.coerceAtLeast(0L)
        dragging = false
        commands += DirectTouchCommand.Button(point, pressed = true)
        return commands
    }

    fun move(
        pointerId: Int,
        point: DirectTouchPoint,
        eventTimeMillis: Long
    ): List<DirectTouchCommand> {
        if (pointerId != activePointerId) return emptyList()
        val start = downPoint ?: return emptyList()
        lastPoint = point
        if (!dragging) {
            val elapsed = (eventTimeMillis - downEventTimeMillis).coerceAtLeast(0L)
            val distance = hypot(point.localX - start.localX, point.localY - start.localY)
            if (elapsed < dragActivationDelayMillis || distance < dragThresholdPixels) {
                return emptyList()
            }
            dragging = true
        }
        return listOf(DirectTouchCommand.Move(point))
    }

    fun up(pointerId: Int, point: DirectTouchPoint): List<DirectTouchCommand> {
        if (pointerId != activePointerId) return emptyList()
        val commands = if (dragging) {
            listOf(
                DirectTouchCommand.Move(point),
                DirectTouchCommand.Button(point, pressed = false)
            )
        } else {
            downPoint?.let { listOf(DirectTouchCommand.Button(it, pressed = false)) }.orEmpty()
        }
        clear()
        return commands
    }

    fun cancel(): List<DirectTouchCommand> {
        val releasePoint = if (dragging) lastPoint else downPoint
        val commands = if (isActive) {
            releasePoint?.let { listOf(DirectTouchCommand.Button(it, pressed = false)) }.orEmpty()
        } else emptyList()
        clear()
        return commands
    }

    private fun clear() {
        activePointerId = INVALID_POINTER_ID
        downPoint = null
        lastPoint = null
        downEventTimeMillis = 0L
        dragThresholdPixels = 0f
        dragActivationDelayMillis = 0L
        dragging = false
    }

    companion object {
        const val INVALID_POINTER_ID = -1
    }
}
