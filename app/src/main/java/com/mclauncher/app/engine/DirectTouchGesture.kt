package com.mclauncher.app.engine

import kotlin.math.hypot

internal sealed interface DirectTouchCommand {
    val point: DirectTouchPoint

    data class Move(override val point: DirectTouchPoint) : DirectTouchCommand
    data class Button(override val point: DirectTouchPoint, val pressed: Boolean) : DirectTouchCommand
    data class Tap(override val point: DirectTouchPoint) : DirectTouchCommand
}

/**
 * Converts one-finger SurfaceView input into desktop-style menu input.
 *
 * A finger-down positions the Minecraft cursor but does not press a mouse button.
 * Once Android confirms a normal tap, the click is anchored to that original
 * finger-down coordinate. A held gesture must also cross touch slop before it
 * becomes a real mouse drag, preserving inventory drag-and-drop without letting
 * noisy quick-tap coordinates click a different control.
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
        commands += DirectTouchCommand.Move(point)
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
        val commands = mutableListOf<DirectTouchCommand>()
        if (!dragging) {
            val elapsed = (eventTimeMillis - downEventTimeMillis).coerceAtLeast(0L)
            val distance = hypot(point.localX - start.localX, point.localY - start.localY)
            if (elapsed < dragActivationDelayMillis || distance < dragThresholdPixels) {
                return emptyList()
            }
            dragging = true
            commands += DirectTouchCommand.Button(start, pressed = true)
        }
        commands += DirectTouchCommand.Move(point)
        return commands
    }

    fun up(pointerId: Int, point: DirectTouchPoint): List<DirectTouchCommand> {
        if (pointerId != activePointerId) return emptyList()
        val commands = if (dragging) {
            listOf(
                DirectTouchCommand.Move(point),
                DirectTouchCommand.Button(point, pressed = false)
            )
        } else {
            downPoint?.let { listOf(DirectTouchCommand.Tap(it)) }.orEmpty()
        }
        clear()
        return commands
    }

    fun cancel(): List<DirectTouchCommand> {
        val commands = if (isActive && dragging) {
            (lastPoint ?: downPoint)?.let {
                listOf(DirectTouchCommand.Button(it, pressed = false))
            }.orEmpty()
        } else {
            emptyList()
        }
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
