package com.mclauncher.app.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mclauncher.app.engine.GameInputBridge
import com.mclauncher.app.engine.GamePointerState
import com.mclauncher.model.ControlElement
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.TouchLookMode
import kotlin.math.hypot
import kotlin.math.roundToInt

@Composable
fun GameTouchOverlay(
    visible: Boolean,
    settings: LauncherSettings,
    pointerState: GamePointerState,
    modifier: Modifier = Modifier,
    onMenu: () -> Unit,
    onToggleVisibility: () -> Unit
) {
    if (!visible) return
    val toggles = remember { mutableStateMapOf<String, Boolean>() }
    val directTouchActive = settings.virtualMouseEnabled && !pointerState.grabbed

    DisposableEffect(Unit) {
        onDispose {
            GameInputBridge.releaseMovement()
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val shortSide = if (maxWidth < maxHeight) maxWidth else maxHeight
        val joystickDiameter = shortSide * settings.joystickSize.coerceIn(0.14f, 0.34f)
        val density = LocalDensity.current

        when {
            pointerState.grabbed &&
                !directTouchActive &&
                settings.touchLookMode == TouchLookMode.JOYSTICK -> {
                LookJoystick(
                    sensitivity = settings.lookSensitivity,
                    deadZone = settings.joystickDeadZone,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 28.dp, bottom = 28.dp)
                        .size(joystickDiameter)
                )
            }
            pointerState.grabbed && !directTouchActive -> {
                LookPad(
                    sensitivity = settings.lookSensitivity,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .fillMaxWidth(0.62f)
                )
            }
        }

        if (pointerState.grabbed && !directTouchActive && settings.movementJoystickEnabled) {
            MovementJoystick(
                deadZone = settings.joystickDeadZone,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 28.dp, bottom = 28.dp)
                    .size(joystickDiameter)
            )
        }

        if (pointerState.grabbed && !directTouchActive) {
            settings.controlLayout.filter(ControlElement::visible).forEach { control ->
                val width = maxWidth * (control.width * settings.controlScale).coerceIn(0.04f, 0.35f)
                val height = maxHeight * (control.height * settings.controlScale).coerceIn(0.04f, 0.30f)
                DynamicControl(
                    control = control,
                    toggled = toggles[control.id] == true,
                    globalOpacity = settings.controlOpacity,
                    modifier = Modifier
                        .offset(
                            x = maxWidth * control.x.coerceIn(0f, 0.94f),
                            y = maxHeight * control.y.coerceIn(0f, 0.92f)
                        )
                        .size(width, height),
                    onToggle = { active -> toggles[control.id] = active }
                )
            }
        }

        if (pointerState.grabbed && settings.virtualMouseEnabled) {
            val hotbarWidthPx = HotbarTouchLayout.widthPixels(
                constraints.maxWidth,
                constraints.maxHeight
            )
            val hotbarHeightPx = maxOf(
                HotbarTouchLayout.heightPixels(constraints.maxWidth, constraints.maxHeight).toFloat(),
                with(density) { 48.dp.toPx() }
            )
            HotbarTouchTarget(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .size(
                        width = with(density) { hotbarWidthPx.toDp() },
                        height = with(density) { hotbarHeightPx.toDp() }
                    )
            )
        }

        Row(
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KeyboardButton()
            OverlayButton("Esc") { tapKey(256) }
            OverlayButton("Menu", onMenu)
            OverlayButton("Hide", onToggleVisibility)
        }
    }
}

@Composable
private fun MovementJoystick(deadZone: Float, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    fun update(position: Offset, width: Float, height: Float) {
        val center = Offset(width / 2f, height / 2f)
        val delta = position - center
        val radius = minOf(width, height) * 0.34f
        val distance = hypot(delta.x, delta.y)
        val limited = if (distance > radius && distance > 0f) delta * (radius / distance) else delta
        knob = limited
        val x = (limited.x / radius).coerceIn(-1f, 1f)
        val y = (limited.y / radius).coerceIn(-1f, 1f)
        GameInputBridge.movementAxes(x, y, deadZone)
    }

    JoystickSurface(
        modifier = modifier.pointerInput(deadZone) {
            detectDragGestures(
                onDragStart = { start -> update(start, size.width.toFloat(), size.height.toFloat()) },
                onDragEnd = {
                    knob = Offset.Zero
                    GameInputBridge.releaseMovement()
                },
                onDragCancel = {
                    knob = Offset.Zero
                    GameInputBridge.releaseMovement()
                }
            ) { change, _ ->
                change.consume()
                update(change.position, size.width.toFloat(), size.height.toFloat())
            }
        },
        knobOffset = with(density) { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) },
        label = "Move"
    )
}

@Composable
private fun LookJoystick(sensitivity: Float, deadZone: Float, modifier: Modifier = Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    fun updateVisual(position: Offset, width: Float, height: Float) {
        val center = Offset(width / 2f, height / 2f)
        val delta = position - center
        val radius = minOf(width, height) * 0.34f
        val distance = hypot(delta.x, delta.y)
        knob = if (distance > radius && distance > 0f) delta * (radius / distance) else delta
    }

    JoystickSurface(
        modifier = modifier
            .pointerInput(sensitivity, deadZone) {
                detectDragGestures(
                    onDragStart = { updateVisual(it, size.width.toFloat(), size.height.toFloat()) },
                    onDragEnd = { knob = Offset.Zero },
                    onDragCancel = { knob = Offset.Zero }
                ) { change, amount ->
                    change.consume()
                    updateVisual(change.position, size.width.toFloat(), size.height.toFloat())
                    if (hypot(knob.x, knob.y) > minOf(size.width, size.height) * deadZone * 0.5f) {
                        GameInputBridge.cursorDelta(amount.x, amount.y)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapMouse(GameInputBridge.MOUSE_LEFT) },
                    onLongPress = { tapMouse(GameInputBridge.MOUSE_RIGHT) }
                )
            },
        knobOffset = with(density) { IntOffset(knob.x.roundToInt(), knob.y.roundToInt()) },
        label = "Look"
    )
}

@Composable
private fun JoystickSurface(modifier: Modifier, knobOffset: IntOffset, label: String) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .alpha(0.70f)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), CircleShape)
            .border(2.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.75f), CircleShape)
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            modifier = Modifier
                .offset { knobOffset }
                .size(42.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.82f), CircleShape)
                .border(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f), CircleShape)
        )
    }
}

@Composable
private fun DynamicControl(
    control: ControlElement,
    toggled: Boolean,
    globalOpacity: Float,
    modifier: Modifier,
    onToggle: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val effectiveOpacity = (control.opacity * globalOpacity).coerceIn(0.15f, 1f)

    fun send(pressed: Boolean) {
        control.keyCode?.let { GameInputBridge.key(it, pressed) }
        control.mouseButton?.let { GameInputBridge.mouseButton(it, pressed) }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .alpha(effectiveOpacity)
            .background(
                if (toggled) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surface,
                shape
            )
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .pointerInput(control.id, control.toggle, toggled) {
                if (control.toggle) {
                    detectTapGestures(onTap = {
                        val next = !toggled
                        onToggle(next)
                        send(next)
                    })
                } else {
                    detectTapGestures(onPress = {
                        send(true)
                        try {
                            tryAwaitRelease()
                        } finally {
                            send(false)
                        }
                    })
                }
            }
    ) {
        Text(control.label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun LookPad(
    sensitivity: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .pointerInput(sensitivity) {
                detectDragGestures { change, amount ->
                    change.consume()
                    GameInputBridge.cursorDelta(amount.x, amount.y)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tapMouse(GameInputBridge.MOUSE_LEFT) },
                    onLongPress = { tapMouse(GameInputBridge.MOUSE_RIGHT) }
                )
            }
    )
}

@Composable
private fun HotbarTouchTarget(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { position ->
                HotbarTouchLayout.slotAt(position.x, size.width.toFloat())?.let { slot ->
                    tapKey(49 + slot)
                }
            }
        }
    )
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(38.dp)
            .padding(horizontal = 3.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f), RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 11.dp)
            .pointerInput(label) { detectTapGestures(onTap = { onClick() }) }
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    }
}

@Composable
private fun KeyboardButton() {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var capture by remember { mutableStateOf("") }

    Box {
        OverlayButton("Keyboard") {
            focusRequester.requestFocus()
            keyboard?.show()
        }
        BasicTextField(
            value = capture,
            onValueChange = { newValue ->
                val appended = if (newValue.startsWith(capture)) newValue.substring(capture.length) else newValue
                appended.codePoints().toArray().forEach { codePoint -> GameInputBridge.character(codePoint) }
                capture = ""
            },
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            modifier = Modifier
                .size(1.dp)
                .alpha(0.01f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { GameInputBridge.handleAndroidKey(it.nativeKeyEvent) }
        )
    }
}

private fun tapKey(key: Int) {
    GameInputBridge.key(key, true)
    GameInputBridge.key(key, false)
}

private fun tapMouse(button: Int) {
    GameInputBridge.clickMouseButton(button)
}
