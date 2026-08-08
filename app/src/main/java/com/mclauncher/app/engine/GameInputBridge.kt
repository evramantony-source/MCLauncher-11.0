package com.mclauncher.app.engine

import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.mclauncher.model.ControllerBinding
import git.artdeell.dnbootstrap.glfw.GLFW
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.abs

data class GamePointerState(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val grabbed: Boolean = false
)

/**
 * Unified GLFW input boundary for touch controls, physical keyboards, mice and controllers.
 * All stateful axes are translated to ordinary GLFW key/mouse events so the native engine
 * never depends on an unfinished gamepad queue.
 */
object GameInputBridge {
    @Volatile private var controllerBindings: Map<Int, ControllerBinding> = emptyMap()
    @Volatile private var lookSensitivity: Float = 1f
    @Volatile private var gamepadDeadZone: Float = 0.18f
    @Volatile private var surfaceWidth: Int = 1
    @Volatile private var surfaceHeight: Int = 1
    @Volatile private var directTouchCaptureEnabled: Boolean = false

    private const val MOUSE_CLICK_HOLD_MILLIS = 33L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _pointerState = MutableStateFlow(GamePointerState())
    val pointerState: StateFlow<GamePointerState> = _pointerState.asStateFlow()

    private val pressedKeys = mutableSetOf<Int>()
    private val pressedMouseButtons = mutableSetOf<Int>()
    private val pendingClickReleases = mutableMapOf<Int, Runnable>()
    private var lastMouseX: Float? = null
    private var lastMouseY: Float? = null
    private var lastTriggerLeft = false
    private var lastTriggerRight = false
    private val directTouchGesture = DirectTouchGesture()
    private val directTouchClickLock = Any()
    private var pendingDirectTouchRelease: PendingDirectTouchRelease? = null

    fun configure(bindings: List<ControllerBinding>, sensitivity: Float, controllerDeadZone: Float = 0.18f) {
        controllerBindings = bindings.associateBy(ControllerBinding::androidKeyCode)
        lookSensitivity = sensitivity.coerceIn(0.1f, 4f)
        gamepadDeadZone = controllerDeadZone.coerceIn(0.05f, 0.60f)
    }

    fun setSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
    }

    fun setDirectTouchCaptureEnabled(enabled: Boolean) {
        directTouchCaptureEnabled = enabled
        if (!enabled) resetDirectTouch(releaseButton = true)
    }

    fun isDirectTouchCaptureEnabled(): Boolean = directTouchCaptureEnabled

    fun syncPointerState(x: Double, y: Double, grabbed: Boolean) {
        _pointerState.value = GamePointerState(
            x = x.toFloat().coerceIn(0f, 1f),
            y = y.toFloat().coerceIn(0f, 1f),
            grabbed = grabbed
        )
    }

    const val ACTION_RELEASE = 0
    const val ACTION_PRESS = 1
    const val ACTION_REPEAT = 2

    const val MOUSE_LEFT = 0
    const val MOUSE_RIGHT = 1
    const val MOUSE_MIDDLE = 2
    const val MOUSE_BACK = 3
    const val MOUSE_FORWARD = 4

    fun key(key: Int, pressed: Boolean, modifiers: Int = 0, repeat: Boolean = false) {
        if (!NativeLaunchBridge.isAvailable) return
        synchronized(pressedKeys) {
            if (pressed) pressedKeys += key else pressedKeys -= key
        }
        NativeLaunchBridge.nativeSendKey(
            key,
            when {
                repeat -> ACTION_REPEAT
                pressed -> ACTION_PRESS
                else -> ACTION_RELEASE
            },
            modifiers
        )
    }

    /** Changes a held key only when its desired state differs. */
    fun setKeyState(key: Int, pressed: Boolean, modifiers: Int = 0) {
        val changed = synchronized(pressedKeys) {
            if (pressed) pressedKeys.add(key) else pressedKeys.remove(key)
        }
        if (changed && NativeLaunchBridge.isAvailable) {
            NativeLaunchBridge.nativeSendKey(key, if (pressed) ACTION_PRESS else ACTION_RELEASE, modifiers)
        }
    }

    fun character(codePoint: Int) {
        if (NativeLaunchBridge.isAvailable && Character.isValidCodePoint(codePoint)) {
            NativeLaunchBridge.nativeSendChar(codePoint)
        }
    }

    fun mouseButton(button: Int, pressed: Boolean, modifiers: Int = 0) {
        if (!NativeLaunchBridge.isAvailable) return
        synchronized(pressedMouseButtons) {
            if (pressed) pressedMouseButtons += button else pressedMouseButtons -= button
        }
        NativeLaunchBridge.nativeSendMouseButton(
            button,
            if (pressed) ACTION_PRESS else ACTION_RELEASE,
            modifiers
        )
    }

    fun setMouseButtonState(button: Int, pressed: Boolean, modifiers: Int = 0) {
        val changed = synchronized(pressedMouseButtons) {
            if (pressed) pressedMouseButtons.add(button) else pressedMouseButtons.remove(button)
        }
        if (changed && NativeLaunchBridge.isAvailable) {
            NativeLaunchBridge.nativeSendMouseButton(button, if (pressed) ACTION_PRESS else ACTION_RELEASE, modifiers)
        }
    }

    /**
     * Sends a complete click with one rendered-frame of hold time.
     *
     * Mojo/GLFW drains pointer movement before queued button input. Keeping the
     * button pressed briefly lets Minecraft observe both states on separate
     * event polls instead of collapsing an instantaneous press and release.
     */
    fun clickMouseButton(button: Int, modifiers: Int = 0) {
        if (!NativeLaunchBridge.isAvailable) return

        val previousRelease = synchronized(pendingClickReleases) {
            pendingClickReleases.remove(button)
        }
        if (previousRelease != null) {
            mainHandler.removeCallbacks(previousRelease)
            mouseButton(button, false, modifiers)
        }

        mouseButton(button, true, modifiers)
        lateinit var release: Runnable
        release = Runnable {
            val ownsRelease = synchronized(pendingClickReleases) {
                if (pendingClickReleases[button] === release) {
                    pendingClickReleases.remove(button)
                    true
                } else {
                    false
                }
            }
            if (ownsRelease) mouseButton(button, false, modifiers)
        }
        synchronized(pendingClickReleases) {
            pendingClickReleases[button] = release
        }
        mainHandler.postDelayed(release, MOUSE_CLICK_HOLD_MILLIS)
    }

    fun cursorDelta(dx: Float, dy: Float, applySensitivity: Boolean = true) {
        if (NativeLaunchBridge.isAvailable && (dx != 0f || dy != 0f)) {
            val multiplier = if (applySensitivity) lookSensitivity else 1f
            val scaledX = dx * multiplier
            val scaledY = dy * multiplier
            val current = _pointerState.value
            if (!current.grabbed) {
                val next = current.copy(
                    x = (current.x + scaledX / surfaceWidth).coerceIn(0f, 1f),
                    y = (current.y + scaledY / surfaceHeight).coerceIn(0f, 1f)
                )
                _pointerState.value = next
                GLFW.cursorX = next.x.toDouble()
                GLFW.cursorY = next.y.toDouble()
            }
            NativeLaunchBridge.nativeSendCursorDelta(scaledX, scaledY)
        }
    }

    fun cursorPosition(x: Float, y: Float) {
        cursorPositionNormalized(
            x = x / surfaceWidth,
            y = y / surfaceHeight
        )
    }

    /**
     * Sends an absolute menu pointer without depending on SurfaceView pixels.
     * Compose and Android views can have different coordinate spaces under
     * immersive-mode insets, so touchscreen overlays should call this directly.
     */
    fun cursorPositionNormalized(x: Float, y: Float) {
        if (!NativeLaunchBridge.isAvailable) return
        val normalizedX = x.coerceIn(0f, 1f)
        val normalizedY = y.coerceIn(0f, 1f)
        updateAbsolutePointer(normalizedX, normalizedY)
        NativeLaunchBridge.nativeSendCursorPosition(normalizedX.toDouble(), normalizedY.toDouble())
    }

    private fun directTouchButtonAtNormalized(x: Float, y: Float, pressed: Boolean) {
        if (!NativeLaunchBridge.isAvailable) return
        val normalizedX = x.coerceIn(0f, 1f)
        val normalizedY = y.coerceIn(0f, 1f)
        updateAbsolutePointer(normalizedX, normalizedY)
        synchronized(pressedMouseButtons) {
            if (pressed) pressedMouseButtons += MOUSE_LEFT else pressedMouseButtons -= MOUSE_LEFT
        }
        NativeLaunchBridge.nativeSendTouchButton(
            x = normalizedX.toDouble(),
            y = normalizedY.toDouble(),
            button = MOUSE_LEFT,
            action = if (pressed) ACTION_PRESS else ACTION_RELEASE,
            modifiers = 0
        )
    }

    /**
     * Sends a tap as one short coordinate-bound desktop click after Android has
     * confirmed the gesture. Both edges carry the exact same cursor position,
     * matching the maintained Pojav/Amethyst GUI input contract.
     */
    private fun tapDirectTouchAt(point: DirectTouchPoint) {
        cancelPendingDirectTouchRelease(releaseButton = true)
        directTouchButtonAtNormalized(point.normalizedX, point.normalizedY, pressed = true)

        lateinit var release: Runnable
        release = Runnable {
            val ownsRelease = synchronized(directTouchClickLock) {
                if (pendingDirectTouchRelease?.runnable === release) {
                    pendingDirectTouchRelease = null
                    true
                } else {
                    false
                }
            }
            if (ownsRelease) {
                directTouchButtonAtNormalized(
                    point.normalizedX,
                    point.normalizedY,
                    pressed = false
                )
            }
        }
        synchronized(directTouchClickLock) {
            pendingDirectTouchRelease = PendingDirectTouchRelease(point, release)
        }
        mainHandler.postDelayed(release, MOUSE_CLICK_HOLD_MILLIS)
    }

    private fun cancelPendingDirectTouchRelease(releaseButton: Boolean) {
        val pending = synchronized(directTouchClickLock) {
            pendingDirectTouchRelease.also { pendingDirectTouchRelease = null }
        } ?: return
        mainHandler.removeCallbacks(pending.runnable)
        if (releaseButton) {
            directTouchButtonAtNormalized(
                pending.point.normalizedX,
                pending.point.normalizedY,
                pressed = false
            )
        }
    }

    private fun updateAbsolutePointer(normalizedX: Float, normalizedY: Float) {
        _pointerState.update { it.copy(x = normalizedX, y = normalizedY) }
        GLFW.cursorX = normalizedX.toDouble()
        GLFW.cursorY = normalizedY.toDouble()
    }

    fun scroll(dx: Float, dy: Float) {
        if (NativeLaunchBridge.isAvailable && (dx != 0f || dy != 0f)) {
            NativeLaunchBridge.nativeSendScroll(dx, dy)
        }
    }

    fun movementAxes(x: Float, y: Float, deadZone: Float = gamepadDeadZone) {
        val dz = deadZone.coerceIn(0.05f, 0.60f)
        setKeyState(65, x < -dz) // A
        setKeyState(68, x > dz)  // D
        setKeyState(87, y < -dz) // W
        setKeyState(83, y > dz)  // S
    }

    fun releaseMovement() = movementAxes(0f, 0f)

    fun handleAndroidKey(event: KeyEvent): Boolean {
        val mouseEvent = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        if (mouseEvent && event.keyCode == KeyEvent.KEYCODE_BACK) {
            val pressed = when (event.action) {
                KeyEvent.ACTION_DOWN -> true
                KeyEvent.ACTION_UP -> false
                else -> return true
            }
            mouseButton(MOUSE_RIGHT, pressed)
            return true
        }

        val controllerEvent = event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
            event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (controllerEvent && !pointerState.value.grabbed) {
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> mouseButton(MOUSE_LEFT, pressed)
                KeyEvent.KEYCODE_BUTTON_B -> key(256, pressed)
                KeyEvent.KEYCODE_BUTTON_START -> key(256, pressed)
                else -> Unit
            }
            if (event.keyCode in setOf(
                    KeyEvent.KEYCODE_BUTTON_A,
                    KeyEvent.KEYCODE_BUTTON_B,
                    KeyEvent.KEYCODE_BUTTON_START
                )
            ) return true
        }
        if (controllerEvent) controllerBindings[event.keyCode]?.let { binding ->
            val pressed = event.action == KeyEvent.ACTION_DOWN
            if (event.action !in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
            binding.glfwKeyCode?.let { key(it, pressed, repeat = event.repeatCount > 0) }
            binding.mouseButton?.let { mouseButton(it, pressed) }
            return true
        }

        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN) return true
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) return false
        if (event.action == KeyEvent.ACTION_MULTIPLE) return true
        if (event.repeatCount != 0) return true
        if (event.action == KeyEvent.ACTION_UP && event.flags and KeyEvent.FLAG_CANCELED != 0) return true
        val glfw = AndroidGlfwKeyMapper.map(event.keyCode) ?: -1
        val modifiers = AndroidGlfwKeyMapper.modifiers(event)
        val pressed = when (event.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        synchronized(pressedKeys) {
            if (glfw >= 0) {
                if (pressed) pressedKeys += glfw else pressedKeys -= glfw
            }
        }
        val unicode = if (pressed) event.getUnicodeChar(event.metaState) else 0
        if (NativeLaunchBridge.isAvailable) {
            // Follow the pinned GLFW engine's physical-keyboard path exactly. Sending
            // a separately translated GLFW event and Unicode event can race its queue.
            NativeLaunchBridge.nativeSendRawKey(event.keyCode, glfw, if (pressed) ACTION_PRESS else ACTION_RELEASE, modifiers, unicode)
        }
        return true
    }

    /** Handles pointer buttons, wheel, relative mouse motion and game-controller axes. */
    fun handleGenericMotion(event: MotionEvent): Boolean {
        val mouseSource = event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

        if (mouseSource) {
            when (event.actionMasked) {
                MotionEvent.ACTION_SCROLL -> {
                    scroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    )
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> {
                    val pressed = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                    mapMouseButton(event.actionButton)?.let { mouseButton(it, pressed) }
                    return true
                }
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_MOVE -> {
                    val relativeX = event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                    val relativeY = event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    if (relativeX != 0f || relativeY != 0f) {
                        cursorDelta(relativeX, relativeY)
                    } else if (!pointerState.value.grabbed) {
                        cursorPosition(event.x, event.y)
                    } else {
                        val previousX = lastMouseX
                        val previousY = lastMouseY
                        if (previousX != null && previousY != null) {
                            cursorDelta(event.x - previousX, event.y - previousY)
                        }
                        lastMouseX = event.x
                        lastMouseY = event.y
                    }
                    syncMouseButtonState(event.buttonState)
                    return true
                }
            }
        }

        if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            val leftX = filteredAxis(event, MotionEvent.AXIS_X)
            val leftY = filteredAxis(event, MotionEvent.AXIS_Y)

            val rightX = bestAxis(event, MotionEvent.AXIS_Z, MotionEvent.AXIS_RX)
            val rightY = bestAxis(event, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RY)
            if (pointerState.value.grabbed) {
                movementAxes(leftX, leftY)
                if (abs(rightX) > gamepadDeadZone || abs(rightY) > gamepadDeadZone) {
                    cursorDelta(rightX * 16f, rightY * 16f)
                }
            } else {
                releaseMovement()
                val cursorX = if (abs(rightX) > gamepadDeadZone) rightX else leftX
                val cursorY = if (abs(rightY) > gamepadDeadZone) rightY else leftY
                if (abs(cursorX) > gamepadDeadZone || abs(cursorY) > gamepadDeadZone) {
                    cursorDelta(cursorX * 18f, cursorY * 18f, applySensitivity = false)
                }
            }

            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            setKeyState(263, hatX < -0.5f)
            setKeyState(262, hatX > 0.5f)
            setKeyState(265, hatY < -0.5f)
            setKeyState(264, hatY > 0.5f)

            val leftTrigger = maxOf(
                event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_BRAKE)
            ) > 0.55f
            val rightTrigger = maxOf(
                event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                event.getAxisValue(MotionEvent.AXIS_GAS)
            ) > 0.55f
            if (leftTrigger != lastTriggerLeft) {
                mouseButton(MOUSE_RIGHT, leftTrigger)
                lastTriggerLeft = leftTrigger
            }
            if (rightTrigger != lastTriggerRight) {
                mouseButton(MOUSE_LEFT, rightTrigger)
                lastTriggerRight = rightTrigger
            }
            return true
        }
        return false
    }

    /** Handles mouse ACTION_DOWN/ACTION_UP events delivered through dispatchTouchEvent. */
    fun handlePointerButtonEvent(event: MotionEvent): Boolean {
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        val pressed = event.actionMasked == MotionEvent.ACTION_DOWN
        if (event.actionMasked !in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) return false
        val button = mapMouseButton(event.actionButton.takeIf { it != 0 } ?: event.buttonState) ?: MOUSE_LEFT
        mouseButton(button, pressed)
        return true
    }

    /**
     * Handles direct touchscreen menu and inventory input on the SurfaceView itself.
     *
     * MotionEvent coordinates are already local to the SurfaceView. Normal taps
     * remain anchored to ACTION_DOWN. A held gesture must also cross touch slop
     * before pointer movement is forwarded for inventory drag-and-drop.
     */
    fun handleDirectTouch(
        event: MotionEvent,
        width: Int,
        height: Int,
        dragThresholdPixels: Float,
        dragActivationDelayMillis: Long
    ): Boolean {
        if (!directTouchCaptureEnabled || width <= 0 || height <= 0) return false

        fun pointerPosition(pointerIndex: Int): DirectTouchPoint = DirectTouchGeometry.map(
            localX = event.getX(pointerIndex),
            localY = event.getY(pointerIndex),
            surfaceWidth = width,
            surfaceHeight = height
        )

        fun dispatch(commands: List<DirectTouchCommand>) {
            commands.forEach { command ->
                when (command) {
                    is DirectTouchCommand.Move -> cursorPositionNormalized(
                        command.point.normalizedX,
                        command.point.normalizedY
                    )
                    is DirectTouchCommand.Button -> {
                        directTouchButtonAtNormalized(
                            command.point.normalizedX,
                            command.point.normalizedY,
                            command.pressed
                        )
                    }
                    is DirectTouchCommand.Tap -> tapDirectTouchAt(command.point)
                }
            }
        }

        if (event.pointerCount > 0 && NativeLaunchBridge.isAvailable) {
            val activeIndex = event.findPointerIndex(directTouchGesture.activePointerId)
            val traceIndex = activeIndex.takeIf { it >= 0 }
                ?: event.actionIndex.coerceIn(0, event.pointerCount - 1)
            runCatching {
                NativeLaunchBridge.nativeTraceDirectTouchEvent(
                    action = event.actionMasked,
                    pointerId = event.getPointerId(traceIndex),
                    localX = event.getX(traceIndex),
                    localY = event.getY(traceIndex),
                    rawX = event.rawX,
                    rawY = event.rawY,
                    width = width,
                    height = height,
                    pointerCount = event.pointerCount,
                    elapsedMillis = (event.eventTime - event.downTime).coerceAtLeast(0L)
                )
            }
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val point = pointerPosition(event.actionIndex)
                if (!point.insideSurface) {
                    false
                } else {
                    // Finish the previous frame-delayed tap before moving the
                    // cursor for a new finger gesture.
                    cancelPendingDirectTouchRelease(releaseButton = true)
                    dispatch(
                        directTouchGesture.down(
                            pointerId = event.getPointerId(event.actionIndex),
                            point = point,
                            eventTimeMillis = event.eventTime,
                            dragThresholdPixels = dragThresholdPixels,
                            dragActivationDelayMillis = dragActivationDelayMillis
                        )
                    )
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(directTouchGesture.activePointerId)
                if (pointerIndex < 0) {
                    false
                } else {
                    dispatch(
                        directTouchGesture.move(
                            directTouchGesture.activePointerId,
                            pointerPosition(pointerIndex),
                            event.eventTime
                        )
                    )
                    true
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerId = directTouchGesture.activePointerId
                val pointerIndex = event.findPointerIndex(pointerId)
                    .takeIf { it >= 0 } ?: event.actionIndex
                val active = directTouchGesture.isActive
                if (active) {
                    dispatch(directTouchGesture.up(pointerId, pointerPosition(pointerIndex)))
                }
                active
            }
            MotionEvent.ACTION_CANCEL -> {
                val active = directTouchGesture.isActive
                resetDirectTouch(releaseButton = true)
                active
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                directTouchGesture.isActive
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val active = directTouchGesture.isActive
                if (
                    active &&
                    event.getPointerId(event.actionIndex) == directTouchGesture.activePointerId
                ) {
                    dispatch(
                        directTouchGesture.up(
                            directTouchGesture.activePointerId,
                            pointerPosition(event.actionIndex)
                        )
                    )
                }
                active
            }
            else -> directTouchGesture.isActive
        }
    }

    fun resetPointerPosition() {
        lastMouseX = null
        lastMouseY = null
    }

    fun releaseAll() {
        val pendingReleases = synchronized(pendingClickReleases) {
            pendingClickReleases.values.toList().also { pendingClickReleases.clear() }
        }
        pendingReleases.forEach(mainHandler::removeCallbacks)
        resetDirectTouch(releaseButton = true)
        synchronized(pressedKeys) { pressedKeys.toList() }.forEach { setKeyState(it, false) }
        synchronized(pressedMouseButtons) { pressedMouseButtons.toList() }.forEach { setMouseButtonState(it, false) }
        lastTriggerLeft = false
        lastTriggerRight = false
        resetPointerPosition()
    }

    private fun resetDirectTouch(releaseButton: Boolean) {
        val commands = directTouchGesture.cancel()
        if (releaseButton) {
            commands.filterIsInstance<DirectTouchCommand.Button>().forEach { command ->
                directTouchButtonAtNormalized(
                    command.point.normalizedX,
                    command.point.normalizedY,
                    command.pressed
                )
            }
        }
        cancelPendingDirectTouchRelease(releaseButton)
    }

    private data class PendingDirectTouchRelease(
        val point: DirectTouchPoint,
        val runnable: Runnable
    )

    private fun filteredAxis(event: MotionEvent, axis: Int): Float {
        val value = event.getAxisValue(axis)
        return if (abs(value) < gamepadDeadZone) 0f else value.coerceIn(-1f, 1f)
    }

    private fun bestAxis(event: MotionEvent, primary: Int, fallback: Int): Float {
        val first = filteredAxis(event, primary)
        return if (first != 0f) first else filteredAxis(event, fallback)
    }

    private fun syncMouseButtonState(state: Int) {
        setMouseButtonState(MOUSE_LEFT, state and MotionEvent.BUTTON_PRIMARY != 0)
        setMouseButtonState(MOUSE_RIGHT, state and MotionEvent.BUTTON_SECONDARY != 0)
        setMouseButtonState(MOUSE_MIDDLE, state and MotionEvent.BUTTON_TERTIARY != 0)
        setMouseButtonState(MOUSE_BACK, state and MotionEvent.BUTTON_BACK != 0)
        setMouseButtonState(MOUSE_FORWARD, state and MotionEvent.BUTTON_FORWARD != 0)
    }

    private fun mapMouseButton(button: Int): Int? = when (button) {
        MotionEvent.BUTTON_PRIMARY -> MOUSE_LEFT
        MotionEvent.BUTTON_SECONDARY -> MOUSE_RIGHT
        MotionEvent.BUTTON_TERTIARY -> MOUSE_MIDDLE
        MotionEvent.BUTTON_BACK -> MOUSE_BACK
        MotionEvent.BUTTON_FORWARD -> MOUSE_FORWARD
        else -> null
    }
}

object AndroidGlfwKeyMapper {
    private val keyMap = buildMap {
        put(KeyEvent.KEYCODE_SPACE, 32)
        put(KeyEvent.KEYCODE_APOSTROPHE, 39)
        put(KeyEvent.KEYCODE_COMMA, 44)
        put(KeyEvent.KEYCODE_MINUS, 45)
        put(KeyEvent.KEYCODE_PERIOD, 46)
        put(KeyEvent.KEYCODE_SLASH, 47)
        (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).forEachIndexed { index, code -> put(code, 48 + index) }
        put(KeyEvent.KEYCODE_SEMICOLON, 59)
        put(KeyEvent.KEYCODE_EQUALS, 61)
        (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z).forEachIndexed { index, code -> put(code, 65 + index) }
        put(KeyEvent.KEYCODE_LEFT_BRACKET, 91)
        put(KeyEvent.KEYCODE_BACKSLASH, 92)
        put(KeyEvent.KEYCODE_RIGHT_BRACKET, 93)
        put(KeyEvent.KEYCODE_GRAVE, 96)
        put(KeyEvent.KEYCODE_ESCAPE, 256)
        put(KeyEvent.KEYCODE_ENTER, 257)
        put(KeyEvent.KEYCODE_NUMPAD_ENTER, 257)
        put(KeyEvent.KEYCODE_TAB, 258)
        put(KeyEvent.KEYCODE_DEL, 259) // Android DEL is backspace
        put(KeyEvent.KEYCODE_INSERT, 260)
        put(KeyEvent.KEYCODE_FORWARD_DEL, 261)
        put(KeyEvent.KEYCODE_DPAD_RIGHT, 262)
        put(KeyEvent.KEYCODE_DPAD_LEFT, 263)
        put(KeyEvent.KEYCODE_DPAD_DOWN, 264)
        put(KeyEvent.KEYCODE_DPAD_UP, 265)
        put(KeyEvent.KEYCODE_PAGE_UP, 266)
        put(KeyEvent.KEYCODE_PAGE_DOWN, 267)
        put(KeyEvent.KEYCODE_MOVE_HOME, 268)
        put(KeyEvent.KEYCODE_MOVE_END, 269)
        put(KeyEvent.KEYCODE_CAPS_LOCK, 280)
        put(KeyEvent.KEYCODE_SCROLL_LOCK, 281)
        put(KeyEvent.KEYCODE_NUM_LOCK, 282)
        put(KeyEvent.KEYCODE_SYSRQ, 283)
        put(KeyEvent.KEYCODE_BREAK, 284)
        (KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12).forEachIndexed { index, code -> put(code, 290 + index) }
        put(KeyEvent.KEYCODE_NUMPAD_0, 320)
        put(KeyEvent.KEYCODE_NUMPAD_1, 321)
        put(KeyEvent.KEYCODE_NUMPAD_2, 322)
        put(KeyEvent.KEYCODE_NUMPAD_3, 323)
        put(KeyEvent.KEYCODE_NUMPAD_4, 324)
        put(KeyEvent.KEYCODE_NUMPAD_5, 325)
        put(KeyEvent.KEYCODE_NUMPAD_6, 326)
        put(KeyEvent.KEYCODE_NUMPAD_7, 327)
        put(KeyEvent.KEYCODE_NUMPAD_8, 328)
        put(KeyEvent.KEYCODE_NUMPAD_9, 329)
        put(KeyEvent.KEYCODE_NUMPAD_DOT, 330)
        put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 331)
        put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 332)
        put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 333)
        put(KeyEvent.KEYCODE_NUMPAD_ADD, 334)
        put(KeyEvent.KEYCODE_NUMPAD_EQUALS, 336)
        put(KeyEvent.KEYCODE_SHIFT_LEFT, 340)
        put(KeyEvent.KEYCODE_CTRL_LEFT, 341)
        put(KeyEvent.KEYCODE_ALT_LEFT, 342)
        put(KeyEvent.KEYCODE_META_LEFT, 343)
        put(KeyEvent.KEYCODE_SHIFT_RIGHT, 344)
        put(KeyEvent.KEYCODE_CTRL_RIGHT, 345)
        put(KeyEvent.KEYCODE_ALT_RIGHT, 346)
        put(KeyEvent.KEYCODE_META_RIGHT, 347)
        put(KeyEvent.KEYCODE_MENU, 348)
    }

    fun map(androidKeyCode: Int): Int? = keyMap[androidKeyCode]

    fun modifiers(event: KeyEvent): Int {
        var result = 0
        if (event.isShiftPressed) result = result or 0x0001
        if (event.isCtrlPressed) result = result or 0x0002
        if (event.isAltPressed) result = result or 0x0004
        if (event.isMetaPressed) result = result or 0x0008
        if (event.isCapsLockOn) result = result or 0x0010
        if (event.isNumLockOn) result = result or 0x0020
        return result
    }
}
