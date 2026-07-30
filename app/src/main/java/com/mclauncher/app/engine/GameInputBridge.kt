package com.mclauncher.app.engine

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
import kotlin.math.hypot

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
    @Volatile private var virtualMouseCaptureEnabled: Boolean = false

    private val _pointerState = MutableStateFlow(GamePointerState())
    val pointerState: StateFlow<GamePointerState> = _pointerState.asStateFlow()

    private val pressedKeys = mutableSetOf<Int>()
    private val pressedMouseButtons = mutableSetOf<Int>()
    private var lastMouseX: Float? = null
    private var lastMouseY: Float? = null
    private var lastTriggerLeft = false
    private var lastTriggerRight = false
    private var virtualTouchPointerId = MotionEvent.INVALID_POINTER_ID
    private var virtualTouchDownTime = 0L
    private var virtualTouchDownX = 0f
    private var virtualTouchDownY = 0f
    private var virtualTouchMoved = false

    fun configure(bindings: List<ControllerBinding>, sensitivity: Float, controllerDeadZone: Float = 0.18f) {
        controllerBindings = bindings.associateBy(ControllerBinding::androidKeyCode)
        lookSensitivity = sensitivity.coerceIn(0.1f, 4f)
        gamepadDeadZone = controllerDeadZone.coerceIn(0.05f, 0.60f)
    }

    fun setSurfaceSize(width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
    }

    fun setVirtualMouseCaptureEnabled(enabled: Boolean) {
        virtualMouseCaptureEnabled = enabled
        if (!enabled) resetVirtualTouch()
    }

    fun isVirtualMouseCaptureEnabled(): Boolean = virtualMouseCaptureEnabled

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
        _pointerState.update { it.copy(x = normalizedX, y = normalizedY) }
        GLFW.cursorX = normalizedX.toDouble()
        GLFW.cursorY = normalizedY.toDouble()
        NativeLaunchBridge.nativeSendCursorPosition(normalizedX.toDouble(), normalizedY.toDouble())
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
     * Handles touchscreen menu navigation before Compose can consume the event.
     *
     * Coordinates use the Activity window's coordinate space and are converted to
     * the SurfaceView's normalized space. A tap is left-click, a stationary long
     * press is right-click, and dragging only moves the cursor.
     */
    fun handleVirtualMouseTouch(
        event: MotionEvent,
        surfaceLeft: Float,
        surfaceTop: Float,
        width: Int,
        height: Int,
        touchSlop: Float
    ): Boolean {
        if (!virtualMouseCaptureEnabled) return false

        fun pointerPosition(pointerIndex: Int): Pair<Float, Float> =
            (event.getX(pointerIndex) - surfaceLeft) to
                (event.getY(pointerIndex) - surfaceTop)

        fun movePointer(localX: Float, localY: Float) {
            cursorPositionNormalized(
                x = localX / width.coerceAtLeast(1),
                y = localY / height.coerceAtLeast(1)
            )
        }

        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val (localX, localY) = pointerPosition(event.actionIndex)
                if (localX !in 0f..width.toFloat() || localY !in 0f..height.toFloat()) {
                    false
                } else {
                    virtualTouchPointerId = event.getPointerId(event.actionIndex)
                    virtualTouchDownTime = event.eventTime
                    virtualTouchDownX = localX
                    virtualTouchDownY = localY
                    virtualTouchMoved = false
                    movePointer(localX, localY)
                    true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pointerIndex = event.findPointerIndex(virtualTouchPointerId)
                if (pointerIndex < 0) {
                    false
                } else {
                    val (localX, localY) = pointerPosition(pointerIndex)
                    if (hypot(localX - virtualTouchDownX, localY - virtualTouchDownY) > touchSlop) {
                        virtualTouchMoved = true
                    }
                    movePointer(localX, localY)
                    true
                }
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(virtualTouchPointerId)
                    .takeIf { it >= 0 } ?: event.actionIndex
                val active = virtualTouchPointerId != MotionEvent.INVALID_POINTER_ID
                val (localX, localY) = pointerPosition(pointerIndex)
                if (active) movePointer(localX, localY)
                val shouldClick = active && !virtualTouchMoved
                val button = if (
                    shouldClick &&
                    event.eventTime - virtualTouchDownTime >=
                    android.view.ViewConfiguration.getLongPressTimeout()
                ) {
                    MOUSE_RIGHT
                } else {
                    MOUSE_LEFT
                }
                resetVirtualTouch()
                if (shouldClick) {
                    mouseButton(button, true)
                    mouseButton(button, false)
                }
                active
            }
            MotionEvent.ACTION_CANCEL -> {
                val active = virtualTouchPointerId != MotionEvent.INVALID_POINTER_ID
                resetVirtualTouch()
                active
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                virtualTouchPointerId != MotionEvent.INVALID_POINTER_ID
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val active = virtualTouchPointerId != MotionEvent.INVALID_POINTER_ID
                if (
                    active &&
                    event.getPointerId(event.actionIndex) == virtualTouchPointerId
                ) {
                    resetVirtualTouch()
                }
                active
            }
            else -> virtualTouchPointerId != MotionEvent.INVALID_POINTER_ID
        }
    }

    fun resetPointerPosition() {
        lastMouseX = null
        lastMouseY = null
    }

    fun releaseAll() {
        synchronized(pressedKeys) { pressedKeys.toList() }.forEach { setKeyState(it, false) }
        synchronized(pressedMouseButtons) { pressedMouseButtons.toList() }.forEach { setMouseButtonState(it, false) }
        lastTriggerLeft = false
        lastTriggerRight = false
        resetPointerPosition()
        resetVirtualTouch()
    }

    private fun resetVirtualTouch() {
        virtualTouchPointerId = MotionEvent.INVALID_POINTER_ID
        virtualTouchDownTime = 0L
        virtualTouchMoved = false
    }

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
