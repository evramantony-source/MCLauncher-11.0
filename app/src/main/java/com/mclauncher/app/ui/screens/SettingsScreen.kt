package com.mclauncher.app.ui.screens

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mclauncher.app.engine.ComponentPackage
import com.mclauncher.app.engine.ComponentPackageType
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.app.ui.components.PageHeader
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.LauncherSettings
import com.mclauncher.model.LauncherThemeMode
import com.mclauncher.model.PerformancePreset
import com.mclauncher.model.Renderer
import com.mclauncher.model.TouchLookMode
import kotlin.math.roundToInt

private val archiveMimeTypes = arrayOf(
    "application/zip",
    "application/octet-stream",
    "application/vnd.android.package-archive",
    "application/x-xz",
    "application/gzip"
)

@Composable
fun SettingsScreen(
    state: LauncherUiState,
    onUpdateSettings: ((LauncherSettings) -> LauncherSettings) -> Unit,
    onImportRuntime: (Uri, JavaVersion) -> Unit,
    onRepairBundledEngine: () -> Unit,
    onImportGraphicsPack: (Uri) -> Unit,
    onRefreshEngine: () -> Unit,
    onLoadCatalog: () -> Unit,
    onInstallComponent: (ComponentPackage) -> Unit,
    onOpenControls: () -> Unit,
    onOpenLogs: () -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    val settings = state.snapshot.settings
    val runtimePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { onImportRuntime(it, settings.selectedJava) }
    }
    val graphicsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(onImportGraphicsPack)
    }

    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                PageHeader(
                    title = "Settings",
                    subtitle = "Engine, Java, graphics, accounts, performance, controls and diagnostics"
                )
            }

            item {
                SettingGroup("Appearance") {
                    Text("Theme")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LauncherThemeMode.entries.forEach { mode ->
                            FilterChip(
                                selected = settings.themeMode == mode,
                                onClick = { onUpdateSettings { it.copy(themeMode = mode) } },
                                label = { Text(mode.displayName) }
                            )
                        }
                    }
                }
            }

            item {
                SettingGroup("Minecraft engine") {
                    val environment = state.engineEnvironment
                    if (environment == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text("Checking engine components")
                        }
                    } else {
                        StatusLine("Native JVM bridge", environment.nativeBridgeAvailable, environment.nativeBridgeDetail)
                        StatusLine(
                            "Android LWJGL engine (${environment.enginePack.architecture})",
                            environment.enginePack.installed,
                            environment.enginePack.detail
                        )
                        environment.runtimes.forEach { runtime ->
                            StatusLine("Java ${runtime.version.major}", runtime.installed, runtime.detail)
                        }
                    }
                    state.engineOperation?.let {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator()
                            Text(it)
                        }
                    }
                    Button(
                        onClick = onRepairBundledEngine,
                        enabled = state.engineOperation == null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Repair bundled engine") }
                    Text(
                        "Java, patched LWJGL/GLFW, audio and renderer components are packaged in the MCLauncher APK. Repair simply restores those local assets; it never downloads another launcher.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Developer overrides (optional)", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { runtimePicker.launch(archiveMimeTypes) },
                            enabled = state.engineOperation == null,
                            modifier = Modifier.weight(1f)
                        ) { Text("Import Java ${settings.selectedJava.major}") }
                        OutlinedButton(
                            onClick = { graphicsPicker.launch(archiveMimeTypes) },
                            enabled = state.engineOperation == null,
                            modifier = Modifier.weight(1f)
                        ) { Text("Import graphics pack") }
                        OutlinedButton(
                            onClick = onRefreshEngine,
                            enabled = state.engineOperation == null,
                            modifier = Modifier.weight(1f)
                        ) { Text("Recheck") }
                    }
                    Text(
                        "Java and graphics overrides are only for development or testing. The native launch engine cannot be replaced from the normal UI.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingGroup("Optional component updates") {
                    OutlinedTextField(
                        value = settings.runtimeCatalogUrl,
                        onValueChange = { value -> onUpdateSettings { it.copy(runtimeCatalogUrl = value.trim()) } },
                        label = { Text("HTTPS runtime catalog URL") },
                        supportingText = { Text("Optional fallback for runtime-only catalogues; bundled Java remains the default") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = settings.componentCatalogUrl,
                        onValueChange = { value -> onUpdateSettings { it.copy(componentCatalogUrl = value.trim()) } },
                        label = { Text("HTTPS component catalog URL") },
                        supportingText = { Text("Catalog entries must include architecture, checksum and license metadata") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = onLoadCatalog, enabled = !state.componentLoading) {
                        if (state.componentLoading) CircularProgressIndicator() else Text("Load catalog")
                    }
                    state.componentCatalog?.let { catalog ->
                        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
                        val compatiblePackages = catalog.packages.filter { item ->
                            item.type != ComponentPackageType.ENGINE &&
                                (item.architecture == abi || item.architecture == "universal")
                        }
                        Text(
                            "${compatiblePackages.size} compatible runtime/graphics packages · device ABI $abi",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        compatiblePackages
                            .take(20)
                            .forEach { item ->
                                val installed = when (item.type) {
                                    ComponentPackageType.RUNTIME -> state.engineEnvironment?.runtimes?.any {
                                        it.version == item.javaVersion && it.installed
                                    } == true
                                    ComponentPackageType.RENDERER -> state.engineEnvironment?.renderers?.any {
                                        it.renderer == item.renderer && it.installed
                                    } == true
                                    ComponentPackageType.DRIVER -> state.engineEnvironment?.drivers?.any {
                                        it.driver == item.driver && it.installed
                                    } == true
                                    ComponentPackageType.ENGINE -> state.engineEnvironment?.enginePack?.installed == true
                                }
                                ComponentRow(item, state.engineOperation == null, installed) { onInstallComponent(item) }
                            }
                    }
                }
            }

            item {
                SettingGroup("Microsoft account") {
                    OutlinedTextField(
                        value = settings.microsoftClientId,
                        onValueChange = { value -> onUpdateSettings { it.copy(microsoftClientId = value.trim()) } },
                        label = { Text("Microsoft OAuth client ID") },
                        supportingText = { Text("Required for device-code sign-in. Offline accounts need no client ID.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SettingGroup("Java runtime") {
                    JavaVersion.entries.forEach { version ->
                        FilterChip(
                            selected = settings.selectedJava == version,
                            onClick = { onUpdateSettings { it.copy(selectedJava = version) } },
                            label = { Text("Java ${version.major}") }
                        )
                    }
                    Text(
                        "Minecraft normally uses Java 8, 17 or 21 depending on version. Java 25 remains available for compatible builds and experiments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingGroup("Renderer backend") {
                    val availableRenderers = Renderer.entries.filter { renderer ->
                        val status = state.engineEnvironment?.renderers?.firstOrNull { it.renderer == renderer }
                        val downloadable = state.componentCatalog?.packages?.any {
                            it.type == ComponentPackageType.RENDERER && it.renderer == renderer
                        } == true
                        renderer == Renderer.AUTO || status?.installed == true || downloadable
                    }
                    availableRenderers.forEach { renderer ->
                        val status = state.engineEnvironment?.renderers?.firstOrNull { it.renderer == renderer }
                        val installer = state.componentCatalog?.packages?.firstOrNull {
                            it.type == ComponentPackageType.RENDERER &&
                                it.renderer == renderer &&
                                (it.architecture == "universal" || it.architecture == Build.SUPPORTED_ABIS.firstOrNull())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilterChip(
                                selected = settings.renderer == renderer,
                                onClick = { onUpdateSettings { it.copy(renderer = renderer) } },
                                enabled = renderer == Renderer.AUTO || status?.installed == true,
                                label = { Text(renderer.displayName) }
                            )
                            if (status?.installed == true) {
                                Text("Ready", color = MaterialTheme.colorScheme.primary)
                            } else if (renderer != Renderer.AUTO && installer != null) {
                                Button(
                                    onClick = { onInstallComponent(installer) },
                                    enabled = state.engineOperation == null
                                ) {
                                    Text("Install")
                                }
                            }
                        }
                        Text(renderer.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "MCLauncher shows backends that are already ready or have a verified one-tap package for this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                SettingGroup("Graphics driver") {
                    val availableDrivers = GraphicsDriver.entries.filter { driver ->
                        val status = state.engineEnvironment?.drivers?.firstOrNull { it.driver == driver }
                        val downloadable = state.componentCatalog?.packages?.any {
                            it.type == ComponentPackageType.DRIVER && it.driver == driver
                        } == true
                        driver in listOf(GraphicsDriver.AUTO, GraphicsDriver.SYSTEM) ||
                            status?.installed == true || downloadable
                    }
                    availableDrivers.forEach { driver ->
                        val status = state.engineEnvironment?.drivers?.firstOrNull { it.driver == driver }
                        val installer = state.componentCatalog?.packages?.firstOrNull {
                            it.type == ComponentPackageType.DRIVER &&
                                it.driver == driver &&
                                (it.architecture == "universal" || it.architecture == Build.SUPPORTED_ABIS.firstOrNull())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val ready = driver in listOf(GraphicsDriver.AUTO, GraphicsDriver.SYSTEM) || status?.installed == true
                            FilterChip(
                                selected = settings.graphicsDriver == driver,
                                onClick = { onUpdateSettings { it.copy(graphicsDriver = driver) } },
                                enabled = ready,
                                label = { Text(driver.displayName) }
                            )
                            if (ready) {
                                Text("Ready", color = MaterialTheme.colorScheme.primary)
                            } else if (installer != null) {
                                Button(
                                    onClick = { onInstallComponent(installer) },
                                    enabled = state.engineOperation == null
                                ) {
                                    Text("Install")
                                }
                            }
                        }
                        Text(driver.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item {
                SettingGroup("Performance") {
                    Text("Preset")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PerformancePreset.entries.forEach { preset ->
                            FilterChip(
                                selected = settings.performancePreset == preset,
                                onClick = { onUpdateSettings { it.copy(performancePreset = preset) } },
                                label = { Text(preset.displayName) }
                            )
                        }
                    }
                    LabeledSlider(
                        label = "Memory",
                        valueText = "${settings.memoryMb} MB",
                        value = settings.memoryMb.toFloat(),
                        range = 512f..8192f,
                        steps = 29
                    ) { value -> onUpdateSettings { it.copy(memoryMb = (value / 256f).roundToInt() * 256) } }
                    LabeledSlider(
                        label = "FPS limit",
                        valueText = if (settings.fpsLimit >= 240) "Unlimited" else settings.fpsLimit.toString(),
                        value = settings.fpsLimit.toFloat(),
                        range = 20f..240f,
                        steps = 21
                    ) { value -> onUpdateSettings { it.copy(fpsLimit = (value / 10f).roundToInt() * 10) } }
                    LabeledSlider(
                        label = "Render resolution",
                        valueText = "${(settings.resolutionScale * 100).roundToInt()}%",
                        value = settings.resolutionScale,
                        range = 0.5f..1f,
                        steps = 4
                    ) { value -> onUpdateSettings { it.copy(resolutionScale = value) } }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        NumberField("Width", settings.width, Modifier.weight(1f)) { value -> onUpdateSettings { it.copy(width = value.coerceIn(640, 3840)) } }
                        NumberField("Height", settings.height, Modifier.weight(1f)) { value -> onUpdateSettings { it.copy(height = value.coerceIn(360, 2160)) } }
                    }
                }
            }

            item {
                SettingGroup("Content") {
                    SwitchRow("Show snapshots", settings.showSnapshots) { checked -> onUpdateSettings { it.copy(showSnapshots = checked) } }
                    SwitchRow("Install required dependencies", settings.autoInstallDependencies) { checked -> onUpdateSettings { it.copy(autoInstallDependencies = checked) } }
                    SwitchRow("Check managed content for updates", settings.autoUpdateContent) { checked -> onUpdateSettings { it.copy(autoUpdateContent = checked) } }
                    OutlinedTextField(
                        value = settings.curseForgeApiKey,
                        onValueChange = { value -> onUpdateSettings { it.copy(curseForgeApiKey = value.trim()) } },
                        label = { Text("CurseForge API key") },
                        supportingText = {
                            Text(
                                if (state.curseForgeAvailable) "CurseForge browsing is connected"
                                else "Required by CurseForge's official API; Modrinth works without a key"
                            )
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                SettingGroup("Controls and diagnostics") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onOpenControls, modifier = Modifier.weight(1f)) { Text("Edit touch controls") }
                        OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) { Text("Logs and crashes") }
                    }
                    SwitchRow("Movement joystick", settings.movementJoystickEnabled) { checked -> onUpdateSettings { it.copy(movementJoystickEnabled = checked) } }
                    Text("Touch look mode", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(
                        "Swipe anywhere uses the right side of the screen like a normal mobile game. In menus, tap or drag anywhere to position the virtual mouse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SwitchRow("Show virtual mouse in Minecraft menus", settings.virtualMouseEnabled) { checked ->
                        onUpdateSettings { it.copy(virtualMouseEnabled = checked) }
                    }
                    SwitchRow("Capture physical mouse", settings.physicalMouseCapture) { checked -> onUpdateSettings { it.copy(physicalMouseCapture = checked) } }
                    SwitchRow("Hide touch controls after keyboard, mouse or gamepad input", settings.hideTouchControlsWithExternalInput) { checked ->
                        onUpdateSettings { it.copy(hideTouchControlsWithExternalInput = checked) }
                    }
                    SwitchRow("Gyroscope look", settings.gyroEnabled) { checked -> onUpdateSettings { it.copy(gyroEnabled = checked) } }
                    LabeledSlider(
                        "Joystick size",
                        "${(settings.joystickSize * 100).roundToInt()}%",
                        settings.joystickSize,
                        0.14f..0.34f,
                        9
                    ) { value -> onUpdateSettings { it.copy(joystickSize = value) } }
                    LabeledSlider(
                        "Joystick dead zone",
                        "${(settings.joystickDeadZone * 100).roundToInt()}%",
                        settings.joystickDeadZone,
                        0.05f..0.40f,
                        6
                    ) { value -> onUpdateSettings { it.copy(joystickDeadZone = value) } }
                    LabeledSlider(
                        "Gamepad dead zone",
                        "${(settings.gamepadDeadZone * 100).roundToInt()}%",
                        settings.gamepadDeadZone,
                        0.05f..0.40f,
                        6
                    ) { value -> onUpdateSettings { it.copy(gamepadDeadZone = value) } }
                    LabeledSlider(
                        "Look sensitivity",
                        "${(settings.lookSensitivity * 100).roundToInt()}%",
                        settings.lookSensitivity,
                        0.25f..2.5f,
                        8
                    ) { value -> onUpdateSettings { it.copy(lookSensitivity = value) } }
                }
            }

            item {
                SettingGroup("Advanced launch") {
                    SwitchRow("Keep launcher open", settings.keepLauncherOpen) { checked -> onUpdateSettings { it.copy(keepLauncherOpen = checked) } }
                    SwitchRow("Sustained performance mode", settings.sustainedPerformanceMode) { checked ->
                        onUpdateSettings { it.copy(sustainedPerformanceMode = checked) }
                    }
                    OutlinedTextField(
                        value = settings.customJvmArgs,
                        onValueChange = { value -> onUpdateSettings { it.copy(customJvmArgs = value) } },
                        label = { Text("Additional JVM arguments") },
                        supportingText = { Text("Arguments are tokenized safely; do not repeat -Xmx or classpath flags") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun ComponentRow(item: ComponentPackage, enabled: Boolean, installed: Boolean, onInstall: () -> Unit) {
    LauncherCard {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${item.type.name.lowercase()} · ${item.version} · ${item.architecture}" +
                        (item.license?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (installed) {
                Text("Installed", color = MaterialTheme.colorScheme.primary)
            } else {
                Button(onClick = onInstall, enabled = enabled) { Text("Install") }
            }
        }
    }
}

@Composable
private fun SettingGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    LauncherCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun StatusLine(title: String, ready: Boolean, detail: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(if (ready) "●" else "○", color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(valueText, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range), onValueChange = onValueChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier = Modifier, onValue: (Int) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter(Char::isDigit).toIntOrNull()?.let(onValue) },
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}
