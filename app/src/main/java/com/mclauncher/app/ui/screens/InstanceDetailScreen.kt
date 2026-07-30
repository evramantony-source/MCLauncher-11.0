package com.mclauncher.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mclauncher.app.ui.LauncherUiState
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.minecraft.InstalledContent
import com.mclauncher.minecraft.MinecraftVersionCapabilities
import com.mclauncher.model.GraphicsDriver
import com.mclauncher.model.InstanceLaunchSettings
import com.mclauncher.model.JavaVersion
import com.mclauncher.model.MinecraftGraphicsApi
import com.mclauncher.model.MinecraftInstance
import com.mclauncher.model.PerformancePreset
import com.mclauncher.model.Renderer
import java.io.File

private enum class InstanceSection { OVERVIEW, SETTINGS, CONTENT, SCREENSHOTS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceDetailScreen(
    instanceId: String,
    state: LauncherUiState,
    screenshots: List<File>,
    onBack: () -> Unit,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUpdateInstance: (String, (MinecraftInstance) -> MinecraftInstance) -> Unit,
    onLoadContent: (String) -> Unit,
    onUpdateContent: (String) -> Unit,
    onToggleContent: (InstalledContent) -> Unit,
    onRemoveContent: (InstalledContent) -> Unit,
    onOpenScreenshot: (File) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    val instance = state.snapshot.instances.firstOrNull { it.id == instanceId }
    var section by remember { mutableStateOf(InstanceSection.OVERVIEW) }

    LaunchedEffect(instanceId) { onLoadContent(instanceId) }

    Scaffold(
        snackbarHost = snackbarHost,
        topBar = {
            TopAppBar(
                title = { Text(instance?.name ?: "Instance") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        if (instance == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null)
                Text("Instance not found", style = MaterialTheme.typography.titleLarge)
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InstanceSection.entries.forEach { option ->
                        FilterChip(
                            selected = section == option,
                            onClick = { section = option },
                            label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        )
                    }
                }
            }

            when (section) {
                InstanceSection.OVERVIEW -> overviewItems(instance, state, onPlay, onDelete, onUpdateInstance)
                InstanceSection.SETTINGS -> settingsItems(instance, state, onUpdateInstance)
                InstanceSection.CONTENT -> contentItems(instance.id, state.installedContent, onUpdateContent, onToggleContent, onRemoveContent)
                InstanceSection.SCREENSHOTS -> screenshotItems(screenshots, onOpenScreenshot)
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.overviewItems(
    instance: MinecraftInstance,
    state: LauncherUiState,
    onPlay: (String) -> Unit,
    onDelete: (String) -> Unit,
    onUpdateInstance: (String, (MinecraftInstance) -> MinecraftInstance) -> Unit
) {
    val effectiveSettings = instance.launchSettings.applyTo(state.snapshot.settings)
    val supportsGraphicsApi =
        MinecraftVersionCapabilities.supportsGraphicsApi(instance.versionId)

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (instance.installed) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = if (instance.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(instance.name, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        if (instance.installed) "Ready to launch" else "Installation or loader setup is incomplete",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Button(onClick = { onPlay(instance.id) }, enabled = instance.installed) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    Text("Play", modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Configuration", style = MaterialTheme.typography.titleLarge)
            DetailRow("Minecraft version", instance.versionId)
            DetailRow("Loader", instance.loader.displayName + (instance.loaderVersion?.let { " $it" } ?: ""))
            DetailRow("Java runtime", "Java ${instance.javaVersion.major}")
            DetailRow("Account", state.selectedAccount?.username ?: "No account selected")
            DetailRow("Settings", if (instance.launchSettings.enabled) "Per-instance" else "Global defaults")
            if (supportsGraphicsApi) {
                DetailRow("Minecraft graphics API", effectiveSettings.minecraftGraphicsApi.displayName)
            }
            DetailRow("Renderer", effectiveSettings.renderer.displayName)
            DetailRow("Graphics driver", effectiveSettings.graphicsDriver.displayName)
            DetailRow("Memory", "${effectiveSettings.memoryMb} MB")
            DetailRow("Resolution", "${effectiveSettings.width}×${effectiveSettings.height} · ${(effectiveSettings.resolutionScale * 100).toInt()}%")
            DetailRow("FPS limit", effectiveSettings.fpsLimit.toString())
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Favorite")
                    Text("Pin this instance near the top of the library", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = instance.favorite,
                    onCheckedChange = { checked -> onUpdateInstance(instance.id) { it.copy(favorite = checked) } }
                )
            }
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Folder, contentDescription = null)
                Text("Game directory", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Text("minecraft/instances/${instance.gameDirectoryName}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Saves, mods, resource packs, shaderpacks, options, crash reports and screenshots are isolated inside this instance.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Danger zone", style = MaterialTheme.typography.titleLarge)
            Text(
                "Removing the instance deletes its private game directory but keeps shared Mojang assets and libraries.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FilledTonalButton(onClick = { onDelete(instance.id) }) {
                Icon(Icons.Rounded.Delete, contentDescription = null)
                Text("Remove instance", modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.settingsItems(
    instance: MinecraftInstance,
    state: LauncherUiState,
    onUpdateInstance: (String, (MinecraftInstance) -> MinecraftInstance) -> Unit
) {
    val global = state.snapshot.settings
    val overrides = instance.launchSettings
    val effective = overrides.applyTo(global)
    val supportsGraphicsApi =
        MinecraftVersionCapabilities.supportsGraphicsApi(instance.versionId)

    fun updateOverrides(transform: (InstanceLaunchSettings) -> InstanceLaunchSettings) {
        onUpdateInstance(instance.id) { current ->
            current.copy(launchSettings = transform(current.launchSettings))
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Java runtime", style = MaterialTheme.typography.titleLarge)
            Text(
                "Java is stored with the instance because different Minecraft versions require different runtimes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val installedJava = state.engineEnvironment?.runtimes
                ?.filter { it.installed }
                ?.map { it.version }
                .orEmpty()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JavaVersion.entries
                    .filter { it == instance.javaVersion || it in installedJava }
                    .forEach { version ->
                        FilterChip(
                            selected = instance.javaVersion == version,
                            onClick = {
                                onUpdateInstance(instance.id) { current ->
                                    current.copy(javaVersion = version)
                                }
                            },
                            label = { Text("Java ${version.major}") }
                        )
                    }
            }
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Use global launch settings", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (overrides.enabled) {
                            "This instance has independent launch settings."
                        } else {
                            "Graphics API, renderer, driver, RAM, FPS and resolution follow Settings."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = !overrides.enabled,
                    onCheckedChange = { useGlobal ->
                        onUpdateInstance(instance.id) { current ->
                            current.copy(
                                launchSettings = if (useGlobal) {
                                    InstanceLaunchSettings()
                                } else {
                                    InstanceLaunchSettings.fromGlobal(global)
                                }
                            )
                        }
                    }
                )
            }
        }
    }

    if (!overrides.enabled) return

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Graphics", style = MaterialTheme.typography.titleLarge)
            if (supportsGraphicsApi) {
                Text("Minecraft graphics API", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MinecraftGraphicsApi.entries.forEach { api ->
                        FilterChip(
                            selected = effective.minecraftGraphicsApi == api,
                            onClick = {
                                updateOverrides { it.copy(minecraftGraphicsApi = api) }
                            },
                            label = { Text(api.displayName) }
                        )
                    }
                }
                Text(
                    effective.minecraftGraphicsApi.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    "Minecraft's Graphics API selector is available for 26.2 and newer. This version uses OpenGL.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                if (supportsGraphicsApi && effective.minecraftGraphicsApi == MinecraftGraphicsApi.VULKAN) {
                    "OpenGL fallback renderer"
                } else {
                    "OpenGL renderer"
                },
                style = MaterialTheme.typography.titleSmall
            )
            val availableRenderers = Renderer.entries.filter { renderer ->
                renderer != Renderer.VULKAN &&
                    (
                        renderer == Renderer.AUTO ||
                            renderer == effective.renderer ||
                            state.engineEnvironment?.renderers?.firstOrNull { it.renderer == renderer }?.installed == true
                        )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableRenderers.forEach { renderer ->
                    FilterChip(
                        selected = effective.renderer == renderer,
                        onClick = { updateOverrides { it.copy(renderer = renderer) } },
                        label = { Text(renderer.displayName) }
                    )
                }
            }

            Text("Graphics driver", style = MaterialTheme.typography.titleSmall)
            val availableDrivers = GraphicsDriver.entries.filter { driver ->
                driver in listOf(GraphicsDriver.AUTO, GraphicsDriver.SYSTEM) ||
                    driver == effective.graphicsDriver ||
                    state.engineEnvironment?.drivers?.firstOrNull { it.driver == driver }?.installed == true
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                availableDrivers.forEach { driver ->
                    FilterChip(
                        selected = effective.graphicsDriver == driver,
                        onClick = { updateOverrides { it.copy(graphicsDriver = driver) } },
                        label = { Text(driver.displayName) }
                    )
                }
            }
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Performance", style = MaterialTheme.typography.titleLarge)
            Text("Memory · ${effective.memoryMb} MB", style = MaterialTheme.typography.titleSmall)
            val memoryOptions = (listOf(1024, 1536, 2048, 3072, 3584, 4096, 5120) + effective.memoryMb)
                .distinct()
                .sorted()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                memoryOptions.forEach { memory ->
                    FilterChip(
                        selected = effective.memoryMb == memory,
                        onClick = { updateOverrides { it.copy(memoryMb = memory) } },
                        label = { Text("${memory} MB") }
                    )
                }
            }

            Text("Performance preset", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PerformancePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = effective.performancePreset == preset,
                        onClick = { updateOverrides { it.copy(performancePreset = preset) } },
                        label = { Text(preset.displayName) }
                    )
                }
            }

            Text("FPS limit", style = MaterialTheme.typography.titleSmall)
            val fpsOptions = (listOf(30, 60, 90, 120, 260) + effective.fpsLimit).distinct().sorted()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                fpsOptions.forEach { fps ->
                    FilterChip(
                        selected = effective.fpsLimit == fps,
                        onClick = { updateOverrides { it.copy(fpsLimit = fps) } },
                        label = { Text(if (fps >= 260) "Unlimited" else fps.toString()) }
                    )
                }
            }
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Resolution", style = MaterialTheme.typography.titleLarge)
            val resolutionOptions = (
                listOf(960 to 540, 1280 to 720, 1600 to 900, 1920 to 1080) +
                    (effective.width to effective.height)
                ).distinct()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                resolutionOptions.forEach { (width, height) ->
                    FilterChip(
                        selected = effective.width == width && effective.height == height,
                        onClick = {
                            updateOverrides {
                                it.copy(width = width, height = height)
                            }
                        },
                        label = { Text("${width}×$height") }
                    )
                }
            }

            Text("Render scale", style = MaterialTheme.typography.titleSmall)
            val scaleOptions = (listOf(0.50f, 0.67f, 0.75f, 0.85f, 1.00f) + effective.resolutionScale)
                .distinct()
                .sorted()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                scaleOptions.forEach { scale ->
                    FilterChip(
                        selected = kotlin.math.abs(effective.resolutionScale - scale) < 0.001f,
                        onClick = { updateOverrides { it.copy(resolutionScale = scale) } },
                        label = { Text("${(scale * 100).toInt()}%") }
                    )
                }
            }
            Text(
                "Effective game window: " +
                    "${(effective.width * effective.resolutionScale).toInt()}×" +
                    "${(effective.height * effective.resolutionScale).toInt()}",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text("Advanced JVM arguments", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = effective.customJvmArgs,
                onValueChange = { value -> updateOverrides { it.copy(customJvmArgs = value) } },
                label = { Text("Arguments for this instance") },
                supportingText = { Text("Leave blank unless a modpack or runtime specifically requires an argument.") },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.contentItems(
    instanceId: String,
    content: List<InstalledContent>,
    onUpdate: (String) -> Unit,
    onToggle: (InstalledContent) -> Unit,
    onRemove: (InstalledContent) -> Unit
) {
    item {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Managed content", style = MaterialTheme.typography.titleLarge)
                    Text("Check Modrinth and CurseForge for compatible updates.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onUpdate(instanceId) }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = null)
                    Text("Update", modifier = Modifier.padding(start = 5.dp))
                }
            }
        }
    }
    if (content.isEmpty()) {
        item {
            LauncherCard(modifier = Modifier.fillMaxWidth()) {
                Text("No managed content is installed yet.")
                Text("Open Discover to install compatible mods, resource packs or shaders.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    items(content, key = { it.projectId }) { item ->
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(item.title.take(1).uppercase())
                    if (!item.iconUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = item.iconUrl,
                            contentDescription = "${item.title} icon",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium)
                    Text("${item.contentType.displayName} • ${item.versionNumber}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(item.fileName, style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { onToggle(item) }) { Text(if (item.enabled) "Disable" else "Enable") }
                IconButton(onClick = { onRemove(item) }) { Icon(Icons.Rounded.Delete, contentDescription = "Remove") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.screenshotItems(
    screenshots: List<File>,
    onOpen: (File) -> Unit
) {
    if (screenshots.isEmpty()) {
        item { LauncherCard(modifier = Modifier.fillMaxWidth()) { Text("No screenshots have been created by this instance yet.") } }
    }
    items(screenshots, key = { it.absolutePath }) { file ->
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Image, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                    Text("${file.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { onOpen(file) }) { Text("Open") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}
