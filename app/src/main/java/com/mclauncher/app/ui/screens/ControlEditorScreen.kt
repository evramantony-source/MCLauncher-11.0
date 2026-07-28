package com.mclauncher.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.model.ControlElement
import com.mclauncher.model.ControllerBinding
import com.mclauncher.model.DefaultControls
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.TouchLookMode
import kotlin.math.roundToInt

private data class ControlPreset(
    val label: String,
    val keyCode: Int? = null,
    val mouseButton: Int? = null
)

private val controlPresets = listOf(
    ControlPreset("Jump", keyCode = 32),
    ControlPreset("Sneak", keyCode = 340),
    ControlPreset("Sprint", keyCode = 341),
    ControlPreset("Inventory", keyCode = 69),
    ControlPreset("Chat", keyCode = 84),
    ControlPreset("Drop", keyCode = 81),
    ControlPreset("Swap hands", keyCode = 70),
    ControlPreset("Escape", keyCode = 256),
    ControlPreset("Player list", keyCode = 258),
    ControlPreset("Debug F3", keyCode = 292),
    ControlPreset("Perspective F5", keyCode = 294),
    ControlPreset("Attack", mouseButton = 0),
    ControlPreset("Use", mouseButton = 1),
    ControlPreset("Pick block", mouseButton = 2)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlEditorScreen(
    settings: LauncherSettings,
    onBack: () -> Unit,
    onUpdateSettings: ((LauncherSettings) -> LauncherSettings) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    var selectedId by remember(settings.controlLayout) { mutableStateOf(settings.controlLayout.firstOrNull()?.id) }
    val selected = settings.controlLayout.firstOrNull { it.id == selectedId }

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text("Touch controls") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    IconButton(onClick = {
                        onUpdateSettings { it.copy(controlLayout = DefaultControls.layout(), controllerBindings = DefaultControls.controllerBindings()) }
                    }) { Icon(Icons.Rounded.Restore, contentDescription = "Reset controls") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Layout preview", style = MaterialTheme.typography.titleLarge)
                    Text("Drag a control, then fine-tune its size and opacity below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ControlPreview(
                        controls = settings.controlLayout,
                        selectedId = selectedId,
                        onSelected = { selectedId = it },
                        onMoved = { id, dx, dy ->
                            onUpdateSettings { current ->
                                current.copy(controlLayout = current.controlLayout.map { control ->
                                    if (control.id == id) control.copy(
                                        x = (control.x + dx).coerceIn(0f, 0.92f),
                                        y = (control.y + dy).coerceIn(0f, 0.91f)
                                    ) else control
                                })
                            }
                        }
                    )
                    Button(
                        onClick = {
                            val control = ControlElement(
                                id = "custom-${System.currentTimeMillis()}",
                                label = "Jump",
                                keyCode = 32,
                                x = 0.44f,
                                y = 0.44f
                            )
                            onUpdateSettings { current ->
                                current.copy(controlLayout = current.controlLayout + control)
                            }
                            selectedId = control.id
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add control button")
                    }
                }
            }

            selected?.let { control ->
                item {
                    LauncherCard(modifier = Modifier.fillMaxWidth()) {
                        Text("${control.label} properties", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(
                            value = control.label,
                            onValueChange = { value ->
                                updateControl(settings, control.id, onUpdateSettings) {
                                    it.copy(label = value.take(18))
                                }
                            },
                            label = { Text("Button label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text("Action", style = MaterialTheme.typography.titleSmall)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            controlPresets.forEach { preset ->
                                FilterChip(
                                    selected = control.keyCode == preset.keyCode &&
                                        control.mouseButton == preset.mouseButton,
                                    onClick = {
                                        updateControl(settings, control.id, onUpdateSettings) {
                                            it.copy(
                                                label = preset.label.take(18),
                                                keyCode = preset.keyCode,
                                                mouseButton = preset.mouseButton
                                            )
                                        }
                                    },
                                    label = { Text(preset.label) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedTextField(
                                value = control.keyCode?.toString().orEmpty(),
                                onValueChange = { text ->
                                    updateControl(settings, control.id, onUpdateSettings) {
                                        it.copy(keyCode = text.toIntOrNull(), mouseButton = null)
                                    }
                                },
                                label = { Text("GLFW key") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = control.mouseButton?.toString().orEmpty(),
                                onValueChange = { text ->
                                    updateControl(settings, control.id, onUpdateSettings) {
                                        it.copy(mouseButton = text.toIntOrNull(), keyCode = null)
                                    }
                                },
                                label = { Text("Mouse button") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        SettingSlider("Width", control.width, 0.06f..0.30f) { value -> updateControl(settings, control.id, onUpdateSettings) { it.copy(width = value) } }
                        SettingSlider("Height", control.height, 0.05f..0.22f) { value -> updateControl(settings, control.id, onUpdateSettings) { it.copy(height = value) } }
                        SettingSlider("Opacity", control.opacity, 0.15f..1f) { value -> updateControl(settings, control.id, onUpdateSettings) { it.copy(opacity = value) } }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Visible")
                            Switch(
                                checked = control.visible,
                                onCheckedChange = { checked -> updateControl(settings, control.id, onUpdateSettings) { it.copy(visible = checked) } }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Toggle instead of hold")
                            Switch(
                                checked = control.toggle,
                                onCheckedChange = { checked -> updateControl(settings, control.id, onUpdateSettings) { it.copy(toggle = checked) } }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val duplicate = control.copy(
                                        id = "custom-${System.currentTimeMillis()}",
                                        label = (control.label + " 2").take(18),
                                        x = (control.x + 0.04f).coerceAtMost(0.88f),
                                        y = (control.y + 0.04f).coerceAtMost(0.88f)
                                    )
                                    onUpdateSettings { current ->
                                        current.copy(controlLayout = current.controlLayout + duplicate)
                                    }
                                    selectedId = duplicate.id
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Duplicate") }
                            OutlinedButton(
                                onClick = {
                                    onUpdateSettings { current ->
                                        current.copy(controlLayout = current.controlLayout.filterNot { it.id == control.id })
                                    }
                                    selectedId = settings.controlLayout.firstOrNull { it.id != control.id }?.id
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("Delete") }
                        }
                    }
                }
            }

            item {
                LauncherCard(modifier = Modifier.fillMaxWidth()) {
                    Text("Global control settings", style = MaterialTheme.typography.titleLarge)
                    SettingSlider("Scale", settings.controlScale, 0.6f..1.6f) { value -> onUpdateSettings { it.copy(controlScale = value) } }
                    SettingSlider("Opacity", settings.controlOpacity, 0.2f..1f) { value -> onUpdateSettings { it.copy(controlOpacity = value) } }
                    SettingSlider("Look sensitivity", settings.lookSensitivity, 0.25f..3f) { value -> onUpdateSettings { it.copy(lookSensitivity = value) } }
                    Text("Touch look mode", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TouchLookMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.touchLookMode == mode,
                                onClick = {
                                    onUpdateSettings {
                                        it.copy(
                                            touchLookMode = mode,
                                            lookJoystickEnabled = mode == TouchLookMode.JOYSTICK
                                        )
                                    }
                                },
                                label = { Text(mode.displayName) }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Gyroscope look")
                            Text("Adds motion-sensor camera input when supported", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = settings.gyroEnabled, onCheckedChange = { checked -> onUpdateSettings { it.copy(gyroEnabled = checked) } })
                    }
                }
            }

            item {
                Text("Controller mapping", style = MaterialTheme.typography.titleLarge)
            }
            items(settings.controllerBindings, key = { it.androidKeyCode }) { binding ->
                ControllerBindingEditor(
                    binding = binding,
                    onChange = { updated ->
                        onUpdateSettings { current ->
                            current.copy(controllerBindings = current.controllerBindings.map {
                                if (it.androidKeyCode == binding.androidKeyCode) updated else it
                            })
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ControlPreview(
    controls: List<ControlElement>,
    selectedId: String?,
    onSelected: (String) -> Unit,
    onMoved: (String, Float, Float) -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(300.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { 300.dp.toPx() }
        controls.filter { it.visible }.forEach { control ->
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset { IntOffset((control.x * widthPx).roundToInt(), (control.y * heightPx).roundToInt()) }
                    .size(maxWidth * control.width, 300.dp * control.height)
                    .alpha(control.opacity)
                    .background(
                        if (selectedId == control.id) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        if (selectedId == control.id) 2.dp else 1.dp,
                        if (selectedId == control.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        RoundedCornerShape(14.dp)
                    )
                    .pointerInput(control.id) {
                        detectDragGestures(
                            onDragStart = { onSelected(control.id) },
                            onDrag = { change, amount ->
                                change.consume()
                                onMoved(control.id, amount.x / widthPx, amount.y / heightPx)
                            }
                        )
                    }
            ) { Text(control.label, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValueChange: (Float) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${(value * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value, onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun ControllerBindingEditor(binding: ControllerBinding, onChange: (ControllerBinding) -> Unit) {
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Text(binding.label, style = MaterialTheme.typography.titleMedium)
        Text("Android key ${binding.androidKeyCode}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = binding.glfwKeyCode?.toString().orEmpty(),
                onValueChange = { text -> onChange(binding.copy(glfwKeyCode = text.toIntOrNull(), mouseButton = null)) },
                label = { Text("GLFW key") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = binding.mouseButton?.toString().orEmpty(),
                onValueChange = { text -> onChange(binding.copy(mouseButton = text.toIntOrNull(), glfwKeyCode = null)) },
                label = { Text("Mouse button") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

private fun updateControl(
    settings: LauncherSettings,
    id: String,
    onUpdate: ((LauncherSettings) -> LauncherSettings) -> Unit,
    transform: (ControlElement) -> ControlElement
) {
    onUpdate { current -> current.copy(controlLayout = current.controlLayout.map { if (it.id == id) transform(it) else it }) }
}
