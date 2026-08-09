package com.mclauncher.app.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.detectDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mclauncher.app.ui.CreationLabSection
import com.mclauncher.app.ui.CreationLabUiState
import com.mclauncher.app.ui.LabTexture
import com.mclauncher.app.ui.PixelDocument
import com.mclauncher.app.ui.PixelTool
import com.mclauncher.app.ui.components.LauncherCard
import com.mclauncher.app.ui.components.PageHeader
import com.mclauncher.model.MinecraftInstance
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun CreationLabScreen(
    state: CreationLabUiState,
    instances: List<MinecraftInstance>,
    onSelectSection: (CreationLabSection) -> Unit,
    onLoadResourceCatalog: (MinecraftInstance) -> Unit,
    onSelectTexture: (LabTexture) -> Unit,
    onUpdatePackName: (String) -> Unit,
    onSelectTool: (PixelTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onBeginStroke: () -> Unit,
    onEditPixel: (Int, Int, Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearArtwork: () -> Unit,
    onNewArtwork: (CreationLabSection) -> Unit,
    onImportArtwork: (android.net.Uri, CreationLabSection) -> Unit,
    onExportResourcePack: (android.net.Uri) -> Unit,
    onExportArtwork: (android.net.Uri, CreationLabSection) -> Unit,
    snackbarHost: @Composable () -> Unit
) {
    val installedInstances = remember(instances) { instances.filter { it.installed } }
    var textureSearch by remember(state.resourceVersion) { mutableStateOf("") }
    val resourcePackExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(onExportResourcePack) }
    val skinExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> uri?.let { onExportArtwork(it, CreationLabSection.SKIN) } }
    val capeExporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> uri?.let { onExportArtwork(it, CreationLabSection.CAPE) } }
    val skinImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImportArtwork(it, CreationLabSection.SKIN) }
    }
    val capeImporter = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onImportArtwork(it, CreationLabSection.CAPE) }
    }

    LaunchedEffect(installedInstances, state.resourceInstanceId) {
        if (state.resourceInstanceId == null && installedInstances.isNotEmpty()) {
            onLoadResourceCatalog(installedInstances.first())
        }
    }

    Scaffold(snackbarHost = snackbarHost) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                PageHeader(
                    title = "MCL Creation Lab",
                    subtitle = "Pixel-perfect resource packs, skins and capes"
                )
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CreationLabSection.entries.forEach { section ->
                        FilterChip(
                            selected = state.section == section,
                            onClick = { onSelectSection(section) },
                            label = { Text(section.label) },
                            leadingIcon = if (section == CreationLabSection.AI_WORKSHOP) {
                                { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp)) }
                            } else null
                        )
                    }
                }
            }
            if (state.busy) item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }

            when (state.section) {
                CreationLabSection.RESOURCE_PACK -> {
                    item {
                        ResourcePackSetup(
                            state = state,
                            instances = installedInstances,
                            textureSearch = textureSearch,
                            onTextureSearchChange = { textureSearch = it },
                            onLoadResourceCatalog = onLoadResourceCatalog,
                            onSelectTexture = onSelectTexture,
                            onUpdatePackName = onUpdatePackName
                        )
                    }
                    item {
                        state.document?.let { document ->
                            PixelEditor(
                                title = state.selectedTexturePath?.removePrefix("assets/minecraft/textures/item/") ?: "Item texture",
                                document = document,
                                state = state,
                                onSelectTool = onSelectTool,
                                onSelectColor = onSelectColor,
                                onBeginStroke = onBeginStroke,
                                onEditPixel = onEditPixel,
                                onUndo = onUndo,
                                onRedo = onRedo,
                                onClear = onClearArtwork
                            )
                        } ?: LauncherCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Choose an item texture", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "The Lab reads every PNG under the installed client's item texture catalog. Choose one above to edit every pixel.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    item {
                        LauncherCard(modifier = Modifier.fillMaxWidth()) {
                            Text("Create resource pack", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${state.editedTextureCount} edited texture${if (state.editedTextureCount == 1) "" else "s"} • Minecraft ${state.resourceVersion ?: "not selected"} • pack ${state.resourcePackFormat ?: "unknown"}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    resourcePackExporter.launch("${safeExportName(state.resourcePackName)}.zip")
                                },
                                enabled = state.editedTextureCount > 0 && !state.busy
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null)
                                Text("Create validated ZIP", modifier = Modifier.padding(start = 7.dp))
                            }
                        }
                    }
                }

                CreationLabSection.SKIN -> item {
                    ArtworkCreator(
                        section = CreationLabSection.SKIN,
                        title = "Java skin creator",
                        subtitle = "64×64 PNG with full base and outer-layer pixel access. Classic and slim arms use the same PNG canvas.",
                        state = state,
                        fileName = "mcl-skin.png",
                        onSelectTool = onSelectTool,
                        onSelectColor = onSelectColor,
                        onBeginStroke = onBeginStroke,
                        onEditPixel = onEditPixel,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onClear = onClearArtwork,
                        onNew = { onNewArtwork(CreationLabSection.SKIN) },
                        onImport = { skinImporter.launch("image/png") },
                        onExport = { skinExporter.launch("mcl-skin.png") }
                    )
                }

                CreationLabSection.CAPE -> item {
                    ArtworkCreator(
                        section = CreationLabSection.CAPE,
                        title = "Cape creator",
                        subtitle = "64×32 cape PNG for compatible cape mods, servers and services. Mojang account capes can only be granted by Mojang.",
                        state = state,
                        fileName = "mcl-cape.png",
                        onSelectTool = onSelectTool,
                        onSelectColor = onSelectColor,
                        onBeginStroke = onBeginStroke,
                        onEditPixel = onEditPixel,
                        onUndo = onUndo,
                        onRedo = onRedo,
                        onClear = onClearArtwork,
                        onNew = { onNewArtwork(CreationLabSection.CAPE) },
                        onImport = { capeImporter.launch("image/png") },
                        onExport = { capeExporter.launch("mcl-cape.png") }
                    )
                }

                CreationLabSection.AI_WORKSHOP -> item { AiWorkshopBoundary() }
            }
        }
    }
}

@Composable
private fun ResourcePackSetup(
    state: CreationLabUiState,
    instances: List<MinecraftInstance>,
    textureSearch: String,
    onTextureSearchChange: (String) -> Unit,
    onLoadResourceCatalog: (MinecraftInstance) -> Unit,
    onSelectTexture: (LabTexture) -> Unit,
    onUpdatePackName: (String) -> Unit
) {
    val visibleTextures = remember(state.textures, textureSearch) {
        val needle = textureSearch.trim()
        if (needle.isBlank()) state.textures
        else state.textures.filter { it.path.contains(needle, ignoreCase = true) || it.displayName.contains(needle, ignoreCase = true) }
    }
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Text("Vanilla item texture catalog", style = MaterialTheme.typography.titleMedium)
        if (instances.isEmpty()) {
            Text("Install a Minecraft version first so the Lab can read its complete vanilla item catalog.")
            return@LauncherCard
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            instances.forEach { instance ->
                FilterChip(
                    selected = state.resourceInstanceId == instance.id,
                    onClick = { onLoadResourceCatalog(instance) },
                    label = { Text(instance.name) }
                )
            }
        }
        OutlinedTextField(
            value = state.resourcePackName,
            onValueChange = onUpdatePackName,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Resource pack name") }
        )
        OutlinedTextField(
            value = textureSearch,
            onValueChange = onTextureSearchChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Find an item texture (${state.textures.size} total)") }
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(visibleTextures, key = { it.path }) { texture ->
                val selected = state.selectedTexturePath == texture.path
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(enabled = !state.busy) { onSelectTexture(texture) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(texture.displayName.replaceFirstChar { it.titlecase(Locale.ROOT) }, modifier = Modifier.weight(1f))
                }
            }
        }
        if (visibleTextures.isEmpty() && !state.busy) {
            Text("No item textures match that search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ArtworkCreator(
    section: CreationLabSection,
    title: String,
    subtitle: String,
    state: CreationLabUiState,
    fileName: String,
    onSelectTool: (PixelTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onBeginStroke: () -> Unit,
    onEditPixel: (Int, Int, Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LauncherCard(modifier = Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onNew, enabled = !state.busy) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Text("Blank", modifier = Modifier.padding(start = 6.dp))
                }
                OutlinedButton(onClick = onImport, enabled = !state.busy) {
                    Icon(Icons.Rounded.Upload, contentDescription = null)
                    Text("Import PNG", modifier = Modifier.padding(start = 6.dp))
                }
                Button(onClick = onExport, enabled = !state.busy) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text("Create $fileName", modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        state.document?.let { document ->
            PixelEditor(
                title = section.label,
                document = document,
                state = state,
                onSelectTool = onSelectTool,
                onSelectColor = onSelectColor,
                onBeginStroke = onBeginStroke,
                onEditPixel = onEditPixel,
                onUndo = onUndo,
                onRedo = onRedo,
                onClear = onClear
            )
        }
    }
}

@Composable
private fun PixelEditor(
    title: String,
    document: PixelDocument,
    state: CreationLabUiState,
    onSelectTool: (PixelTool) -> Unit,
    onSelectColor: (Int) -> Unit,
    onBeginStroke: () -> Unit,
    onEditPixel: (Int, Int, Int) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClear: () -> Unit
) {
    var frame by remember(title, document.width, document.height) { mutableIntStateOf(0) }
    var hex by remember(state.brushColor) { mutableStateOf(colorHex(state.brushColor)) }
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${document.width}×${document.height} pixels${if (document.frameCount > 1) " • ${document.frameCount} animation frames" else ""}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = onUndo, enabled = state.canUndo) {
                Icon(Icons.Rounded.Undo, contentDescription = null)
                Text("Undo")
            }
            TextButton(onClick = onRedo, enabled = state.canRedo) {
                Icon(Icons.Rounded.Redo, contentDescription = null)
                Text("Redo")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PixelTool.entries.forEach { tool ->
                FilterChip(
                    selected = state.tool == tool,
                    onClick = { onSelectTool(tool) },
                    label = { Text(tool.label) }
                )
            }
            OutlinedButton(onClick = onClear) {
                Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                Text("Clear", modifier = Modifier.padding(start = 5.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(state.brushColor), RoundedCornerShape(8.dp))
            )
            OutlinedTextField(
                value = hex,
                onValueChange = { value ->
                    hex = value.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }.take(8).uppercase(Locale.ROOT)
                    parseColorHex(hex)?.let(onSelectColor)
                },
                modifier = Modifier.heightIn(max = 64.dp),
                singleLine = true,
                prefix = { Text("#") },
                label = { Text("ARGB or RGB") }
            )
            PRESET_COLORS.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(color), RoundedCornerShape(7.dp))
                        .clickable { onSelectColor(color) }
                )
            }
        }
        if (document.frameCount > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { frame = (frame - 1).coerceAtLeast(0) }, enabled = frame > 0) { Text("Previous") }
                Text("Frame ${frame + 1} / ${document.frameCount}")
                OutlinedButton(
                    onClick = { frame = (frame + 1).coerceAtMost(document.frameCount - 1) },
                    enabled = frame + 1 < document.frameCount
                ) { Text("Next") }
            }
        }
        PixelCanvas(
            document = document,
            frame = frame,
            onBeginStroke = onBeginStroke,
            onPixel = { x, y -> onEditPixel(x, y, frame) }
        )
        Text(
            "Tap or drag to edit. Transparent pixels use the checkerboard. Animated item sheets are split into frames without changing the exported PNG.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PixelCanvas(
    document: PixelDocument,
    frame: Int,
    onBeginStroke: () -> Unit,
    onPixel: (Int, Int) -> Unit
) {
    val image = remember(document.pixels, document.width, document.height) {
        Bitmap.createBitmap(document.pixels, document.width, document.height, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    val safeFrame = frame.coerceIn(0, document.frameCount - 1)
    val hit: (Offset, IntSize) -> Pair<Int, Int>? = { position, size ->
        val scale = min(size.width.toFloat() / document.width, size.height.toFloat() / document.frameHeight)
        val drawWidth = document.width * scale
        val drawHeight = document.frameHeight * scale
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        if (position.x < left || position.y < top || position.x >= left + drawWidth || position.y >= top + drawHeight) {
            null
        } else {
            val x = ((position.x - left) / scale).toInt().coerceIn(0, document.width - 1)
            val localY = ((position.y - top) / scale).toInt().coerceIn(0, document.frameHeight - 1)
            x to (safeFrame * document.frameHeight + localY).coerceAtMost(document.height - 1)
        }
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .pointerInput(document.width, document.height, safeFrame) {
                detectTapGestures { position ->
                    hit(position, size)?.let { (x, y) ->
                        onBeginStroke()
                        onPixel(x, y)
                    }
                }
            }
            .pointerInput(document.width, document.height, safeFrame) {
                detectDragGestures(
                    onDragStart = { position ->
                        onBeginStroke()
                        hit(position, size)?.let { (x, y) -> onPixel(x, y) }
                    }
                ) { change, _ ->
                    change.consume()
                    hit(change.position, size)?.let { (x, y) -> onPixel(x, y) }
                }
            }
    ) {
        val scale = min(size.width / document.width, size.height / document.frameHeight)
        val drawWidth = document.width * scale
        val drawHeight = document.frameHeight * scale
        val left = (size.width - drawWidth) / 2f
        val top = (size.height - drawHeight) / 2f
        for (y in 0 until document.frameHeight) {
            for (x in 0 until document.width) {
                drawRect(
                    color = if ((x + y) % 2 == 0) Color(0xFFCBD0D6) else Color(0xFF9098A1),
                    topLeft = Offset(left + x * scale, top + y * scale),
                    size = Size(scale + 0.5f, scale + 0.5f)
                )
            }
        }
        drawImage(
            image = image,
            srcOffset = IntOffset(0, safeFrame * document.frameHeight),
            srcSize = IntSize(document.width, document.frameHeight),
            dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
            dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
            filterQuality = FilterQuality.None
        )
        if (scale >= 7f) {
            for (x in 0..document.width) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.24f),
                    start = Offset(left + x * scale, top),
                    end = Offset(left + x * scale, top + drawHeight),
                    strokeWidth = 1f
                )
            }
            for (y in 0..document.frameHeight) {
                drawLine(
                    color = Color.Black.copy(alpha = 0.24f),
                    start = Offset(left, top + y * scale),
                    end = Offset(left + drawWidth, top + y * scale),
                    strokeWidth = 1f
                )
            }
        }
    }
}

@Composable
private fun AiWorkshopBoundary() {
    var prompt by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("Fabric mod source ZIP") }
    LauncherCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("AI project workshop", style = MaterialTheme.typography.titleLarge)
        }
        Text(
            "This is the safe project boundary for prompt-to-mod and prompt-to-shader generation. Generated code will be exported for review; MCLauncher will never silently execute AI-written code.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Fabric mod source ZIP", "Shader pack ZIP").forEach { choice ->
                FilterChip(selected = output == choice, onClick = { output = choice }, label = { Text(choice) })
            }
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
            label = { Text("Describe the project") },
            placeholder = { Text("Example: Add a grappling hook with configurable range and a crafting recipe…") }
        )
        Text(
            "AI generation needs a user-owned API key. Mod JAR compilation additionally needs a pinned Gradle/Loom toolchain or a trusted remote builder; the first safe output is a complete source-project ZIP. Shader packs can be exported directly as ZIPs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {}, enabled = false) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null)
            Text("Connect AI provider to generate", modifier = Modifier.padding(start = 7.dp))
        }
    }
}

private fun safeExportName(value: String): String = value.trim()
    .replace(Regex("[^A-Za-z0-9._-]+"), "-")
    .trim('-', '.', '_')
    .take(64)
    .ifBlank { "mcl-resource-pack" }

private fun colorHex(color: Int): String = "%08X".format(Locale.ROOT, color)

private fun parseColorHex(value: String): Int? = runCatching {
    when (value.length) {
        6 -> (0xFF000000L or value.toLong(16)).toInt()
        8 -> value.toLong(16).toInt()
        else -> null
    }
}.getOrNull()

private val PRESET_COLORS = listOf(
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFFF3B30.toInt(),
    0xFFFFCC00.toInt(),
    0xFF34C759.toInt(),
    0xFF007AFF.toInt(),
    0xFFAF52DE.toInt(),
    0x00000000
)
