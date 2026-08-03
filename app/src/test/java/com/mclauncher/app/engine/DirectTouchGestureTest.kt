package com.mclauncher.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DirectTouchGestureTest {
    private fun point(x: Float, y: Float): DirectTouchPoint = DirectTouchGeometry.map(
        localX = x,
        localY = y,
        surfaceWidth = 1000,
        surfaceHeight = 500
    )

    @Test
    fun `tap positions on down and clicks once on up`() {
        val gesture = DirectTouchGesture()

        val down = gesture.down(pointerId = 7, point = point(250f, 100f), dragThresholdPixels = 12f)
        val up = gesture.up(pointerId = 7, point = point(252f, 103f))

        assertEquals(1, down.size)
        assertIs<DirectTouchCommand.Move>(down.single())
        assertEquals(1, up.size)
        assertIs<DirectTouchCommand.Tap>(up.single())
        assertEquals(0.252f, up.single().point.normalizedX)
        assertEquals(0.206f, up.single().point.normalizedY)
        assertFalse(gesture.isActive)
    }

    @Test
    fun `crossing touch slop presses at down point and preserves inventory drag`() {
        val gesture = DirectTouchGesture()
        val start = point(200f, 100f)
        val current = point(260f, 170f)
        gesture.down(pointerId = 3, point = start, dragThresholdPixels = 10f)

        val move = gesture.move(pointerId = 3, point = current)
        val up = gesture.up(pointerId = 3, point = point(300f, 200f))

        assertEquals(2, move.size)
        val press = assertIs<DirectTouchCommand.Button>(move[0])
        assertTrue(press.pressed)
        assertEquals(start, press.point)
        assertIs<DirectTouchCommand.Move>(move[1])
        assertEquals(2, up.size)
        assertIs<DirectTouchCommand.Move>(up[0])
        val release = assertIs<DirectTouchCommand.Button>(up[1])
        assertFalse(release.pressed)
        assertEquals(0.3f, release.point.normalizedX)
        assertEquals(0.4f, release.point.normalizedY)
    }

    @Test
    fun `cancel releases an active drag at its latest point`() {
        val gesture = DirectTouchGesture()
        gesture.down(pointerId = 1, point = point(100f, 100f), dragThresholdPixels = 4f)
        gesture.move(pointerId = 1, point = point(150f, 150f))

        val cancel = gesture.cancel()

        val release = assertIs<DirectTouchCommand.Button>(cancel.single())
        assertFalse(release.pressed)
        assertEquals(0.15f, release.point.normalizedX)
        assertEquals(0.3f, release.point.normalizedY)
        assertFalse(gesture.isActive)
    }

    @Test
    fun `secondary pointers cannot move or release the primary gesture`() {
        val gesture = DirectTouchGesture()
        gesture.down(pointerId = 9, point = point(400f, 200f), dragThresholdPixels = 8f)

        assertTrue(gesture.move(pointerId = 10, point = point(900f, 400f)).isEmpty())
        assertTrue(gesture.up(pointerId = 10, point = point(900f, 400f)).isEmpty())
        assertTrue(gesture.isActive)
        assertEquals(9, gesture.activePointerId)
    }

    @Test
    fun `a new primary touch releases an unfinished drag before moving`() {
        val gesture = DirectTouchGesture()
        gesture.down(pointerId = 2, point = point(100f, 100f), dragThresholdPixels = 5f)
        gesture.move(pointerId = 2, point = point(180f, 160f))

        val nextDown = gesture.down(
            pointerId = 4,
            point = point(700f, 300f),
            dragThresholdPixels = 5f
        )

        val release = assertIs<DirectTouchCommand.Button>(nextDown[0])
        assertFalse(release.pressed)
        assertEquals(0.18f, release.point.normalizedX)
        assertEquals(0.32f, release.point.normalizedY)
        assertIs<DirectTouchCommand.Move>(nextDown[1])
        assertEquals(4, gesture.activePointerId)
    }
}
