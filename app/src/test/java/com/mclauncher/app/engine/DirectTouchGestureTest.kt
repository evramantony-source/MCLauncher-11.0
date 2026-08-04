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

    private fun down(
        gesture: DirectTouchGesture,
        pointerId: Int,
        point: DirectTouchPoint,
        eventTimeMillis: Long = 1_000L
    ) = gesture.down(
        pointerId = pointerId,
        point = point,
        eventTimeMillis = eventTimeMillis,
        dragThresholdPixels = 12f,
        dragActivationDelayMillis = 400L
    )

    @Test
    fun `tap presses immediately and releases at original coordinate`() {
        val gesture = DirectTouchGesture()
        val start = point(250f, 100f)

        val down = down(gesture, pointerId = 7, point = start)
        val up = gesture.up(pointerId = 7, point = point(252f, 103f))

        val press = assertIs<DirectTouchCommand.Button>(down.single())
        assertTrue(press.pressed)
        assertEquals(start, press.point)
        val release = assertIs<DirectTouchCommand.Button>(up.single())
        assertFalse(release.pressed)
        assertEquals(start, release.point)
        assertFalse(gesture.isActive)
    }

    @Test
    fun `quick coordinate jump remains a tap at finger down`() {
        val gesture = DirectTouchGesture()
        val start = point(205.8f, 303.75f)
        down(gesture, pointerId = 3, point = start, eventTimeMillis = 2_000L)

        val move = gesture.move(
            pointerId = 3,
            point = point(104.2f, 75.7f),
            eventTimeMillis = 2_090L
        )
        val up = gesture.up(pointerId = 3, point = point(104.2f, 75.7f))

        assertTrue(move.isEmpty())
        val release = assertIs<DirectTouchCommand.Button>(up.single())
        assertFalse(release.pressed)
        assertEquals(start, release.point)
    }

    @Test
    fun `deliberate hold plus movement preserves inventory drag`() {
        val gesture = DirectTouchGesture()
        val start = point(200f, 100f)
        val current = point(260f, 170f)
        down(gesture, pointerId = 3, point = start, eventTimeMillis = 5_000L)

        val earlyMove = gesture.move(pointerId = 3, point = current, eventTimeMillis = 5_150L)
        val heldMove = gesture.move(pointerId = 3, point = current, eventTimeMillis = 5_450L)
        val up = gesture.up(pointerId = 3, point = point(300f, 200f))

        assertTrue(earlyMove.isEmpty())
        assertIs<DirectTouchCommand.Move>(heldMove.single())
        assertEquals(2, up.size)
        assertIs<DirectTouchCommand.Move>(up[0])
        val release = assertIs<DirectTouchCommand.Button>(up[1])
        assertFalse(release.pressed)
        assertEquals(0.3f, release.point.normalizedX)
        assertEquals(0.4f, release.point.normalizedY)
    }

    @Test
    fun `holding still does not begin a drag`() {
        val gesture = DirectTouchGesture()
        val start = point(200f, 100f)
        down(gesture, pointerId = 1, point = start, eventTimeMillis = 8_000L)

        val move = gesture.move(
            pointerId = 1,
            point = point(205f, 104f),
            eventTimeMillis = 8_900L
        )
        val up = gesture.up(pointerId = 1, point = point(205f, 104f))

        assertTrue(move.isEmpty())
        assertEquals(start, assertIs<DirectTouchCommand.Button>(up.single()).point)
    }

    @Test
    fun `cancel releases an active drag at its latest point`() {
        val gesture = DirectTouchGesture()
        down(gesture, pointerId = 1, point = point(100f, 100f), eventTimeMillis = 10_000L)
        gesture.move(pointerId = 1, point = point(150f, 150f), eventTimeMillis = 10_500L)

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
        down(gesture, pointerId = 9, point = point(400f, 200f))

        assertTrue(
            gesture.move(pointerId = 10, point = point(900f, 400f), eventTimeMillis = 2_000L).isEmpty()
        )
        assertTrue(gesture.up(pointerId = 10, point = point(900f, 400f)).isEmpty())
        assertTrue(gesture.isActive)
        assertEquals(9, gesture.activePointerId)
    }

    @Test
    fun `a new primary touch releases the old press before pressing again`() {
        val gesture = DirectTouchGesture()
        val first = point(100f, 100f)
        down(gesture, pointerId = 2, point = first)

        val nextDown = down(
            gesture,
            pointerId = 4,
            point = point(700f, 300f),
            eventTimeMillis = 2_000L
        )

        val release = assertIs<DirectTouchCommand.Button>(nextDown[0])
        assertFalse(release.pressed)
        assertEquals(first, release.point)
        val press = assertIs<DirectTouchCommand.Button>(nextDown[1])
        assertTrue(press.pressed)
        assertEquals(4, gesture.activePointerId)
    }
}
