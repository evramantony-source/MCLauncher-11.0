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
 * A normal tap moves first and clicks only after Android confirms the tap. A
 * drag presses at the original finger-down position once touch slop is crossed,
 * then follows the finger until release. This mirrors the pinned launch engine's
 * proven GUI behavior and keeps inventory drag-and-drop available.
 */
internal class DirectTouchGesture {
    var activePointerId: Int = INVALID_POINTER_ID
        private set

    private var downPoint: DirectTouchPoint? = null
    private var lastPoint: DirectTouchPoint? = null
    private var dragThresholdPixels = 0f
    private var dragging = false

    val isActive: Boolean
        get() = activePointerId != INVALID_POINTER_ID

    fun down(
        pointerId: Int,
        point: DirectTouchPoint,
        dragThresholdPixels: Float
    ): List<DirectTouchCommand> {
        val commands = cancel().toMutableList()
        activePointerId = pointerId
        downPoint = point
        lastPoint = point
        this.dragThresholdPixels = dragThresholdPixels.coerceAtLeast(0f)
        dragging = false
        commands += DirectTouchCommand.Move(point)
        return commands
    }

    fun move(pointerId: Int, point: DirectTouchPoint): List<DirectTouchCommand> {
        if (pointerId != activePointerId) return emptyList()
        val start = downPoint ?: return emptyList()
        val commands = mutableListOf<DirectTouchCommand>()
        if (!dragging && hypot(point.localX - start.localX, point.localY - start.localY) >= dragThresholdPixels) {
            dragging = true
            commands += DirectTouchCommand.Button(start, pressed = true)
        }
        lastPoint = point
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
            listOf(DirectTouchCommand.Tap(point))
        }
        clear()
        return commands
    }

    fun cancel(): List<DirectTouchCommand> {
        val commands = if (isActive && dragging) {
            lastPoint?.let { listOf(DirectTouchCommand.Button(it, pressed = false)) }.orEmpty()
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
        dragThresholdPixels = 0f
        dragging = false
    }

    companion object {
        const val INVALID_POINTER_ID = -1
    }
}
