package com.mclauncher.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mclauncher.app.MCLauncherApplication
import com.mclauncher.app.creation.AiBuilderSettings
import com.mclauncher.app.creation.AiProjectBuilder
import com.mclauncher.app.creation.AiProjectOutput
import com.mclauncher.minecraft.baseGameVersion
import com.mclauncher.model.MinecraftInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

enum class CreationLabSection(val label: String) {
    RESOURCE_PACK("Resource pack"),
    SKIN("Skin"),
    CAPE("Cape"),
    AI_WORKSHOP("AI workshop")
}

enum class PixelTool(val label: String) {
    PENCIL("Pencil"),
    ERASER("Eraser"),
    FILL("Fill"),
    PICKER("Pick color")
}

data class LabTexture(
    val path: String,
    val displayName: String
)

data class PixelDocument(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    val frameHeight: Int
        get() = if (height > width && height % width == 0) width else height
    val frameCount: Int
        get() = (height / frameHeight).coerceAtLeast(1)
}

data class CreationLabUiState(
    val section: CreationLabSection = CreationLabSection.RESOURCE_PACK,
    val resourceInstanceId: String? = null,
    val resourceVersion: String? = null,
    val resourcePackFormat: String? = null,
    val resourcePackName: String = "My MCLauncher Pack",
    val textures: List<LabTexture> = emptyList(),
    val selectedTexturePath: String? = null,
    val editedTextureCount: Int = 0,
    val document: PixelDocument? = null,
    val tool: PixelTool = PixelTool.PENCIL,
    val brushColor: Int = 0xFFFFFFFF.toInt(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val busy: Boolean = false,
    val aiOutput: AiProjectOutput = AiProjectOutput.FABRIC_JAR,
    val aiSettings: AiBuilderSettings = AiBuilderSettings(),
    val aiBusy: Boolean = false,
    val aiProgress: String? = null,
    val message: String? = null
)

private data class ResourcePackFormat(val major: Int, val minor: Int = 0) {
    override fun toString(): String = "$major.$minor"
}

class CreationLabViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MCLauncherApplication
    private val layout = app.minecraftLayout
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val aiBuilder = AiProjectBuilder(application)
    private val mutableState = MutableStateFlow(CreationLabUiState(aiSettings = aiBuilder.loadSettings()))
    val state: StateFlow<CreationLabUiState> = mutableState.asStateFlow()

    private var resourceJar: File? = null
    private var resourcePackFormat: ResourcePackFormat? = null
    private val resourceEditsByVersion = mutableMapOf<String, MutableMap<String, PixelDocument>>()
    private var skinDocument = blankDocument(64, 64)
    private var capeDocument = blankDocument(64, 32)
    private val undo = ArrayDeque<IntArray>()
    private val redo = ArrayDeque<IntArray>()

    fun selectSection(section: CreationLabSection) {
        val selectedPath = mutableState.value.selectedTexturePath
        val document = when (section) {
            CreationLabSection.RESOURCE_PACK -> selectedPath?.let { currentResourceEdits()[it] }
            CreationLabSection.SKIN -> skinDocument
            CreationLabSection.CAPE -> capeDocument
            CreationLabSection.AI_WORKSHOP -> null
        }
        clearHistory()
        mutableState.update { it.copy(section = section, document = document) }
    }

    fun updateAiOutput(output: AiProjectOutput) {
        mutableState.update { it.copy(aiOutput = output) }
    }

    fun updateAiSettings(settings: AiBuilderSettings) {
        mutableState.update { it.copy(aiSettings = settings) }
    }

    fun generateAiProject(destination: Uri, prompt: String) {
        val current = mutableState.value
        if (current.aiBusy) return
        viewModelScope.launch {
            mutableState.update { it.copy(aiBusy = true, aiProgress = "Starting AI workshop…", message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    aiBuilder.generate(
                        destination = destination,
                        prompt = prompt,
                        output = current.aiOutput,
                        minecraftVersion = current.resourceVersion ?: "1.20.1",
                        settings = current.aiSettings
                    ) { progress -> mutableState.update { it.copy(aiProgress = progress) } }
                }
            }.onSuccess {
                mutableState.update {
                    it.copy(aiBusy = false, aiProgress = null, message = "${current.aiOutput.label} created successfully")
                }
            }.onFailure { error ->
                mutableState.update {
                    it.copy(aiBusy = false, aiProgress = null, message = "AI project failed: ${error.message ?: error::class.java.simpleName}")
                }
            }
        }
    }

    fun loadResourceCatalog(instance: MinecraftInstance) {
        viewModelScope.launch {
            mutableState.update {
                it.copy(
                    busy = true,
                    resourceInstanceId = instance.id,
                    resourceVersion = baseGameVersion(instance),
                    textures = emptyList(),
                    selectedTexturePath = null,
                    document = null,
                    message = null
                )
            }
            runCatching {
                withContext(Dispatchers.IO) {
                    val version = baseGameVersion(instance)
                    val jar = layout.clientJar(version)
                    require(jar.isFile) { "Minecraft $version client JAR is not installed" }
                    val discovered = ZipFile(jar).use { zip ->
                        zip.entries().asSequence()
                            .filter { !it.isDirectory }
                            .map { it.name }
                            .filter { it.startsWith(ITEM_TEXTURE_PREFIX) && it.endsWith(".png", ignoreCase = true) }
                            .distinct()
                            .sorted()
                            .map { path ->
                                val relative = path.removePrefix(ITEM_TEXTURE_PREFIX).removeSuffix(".png")
                                LabTexture(path, relative.replace('/', ' ').replace('_', ' '))
                            }
                            .toList()
                    }
                    val format = readResourcePackFormat(jar, version)
                    Triple(jar, discovered, format)
                }
            }.onSuccess { (jar, textures, format) ->
                resourceJar = jar
                resourcePackFormat = format
                clearHistory()
                mutableState.update {
                    it.copy(
                        textures = textures,
                        resourcePackFormat = format.toString(),
                        editedTextureCount = currentResourceEdits().size,
                        busy = false,
                        message = if (textures.isEmpty()) "No vanilla item textures were found in this client JAR" else null
                    )
                }
            }.onFailure { error ->
                resourceJar = null
                resourcePackFormat = null
                mutableState.update { it.copy(busy = false, message = "Could not open item textures: ${error.message}") }
            }
        }
    }

    fun selectTexture(texture: LabTexture) {
        val version = mutableState.value.resourceVersion ?: return
        val saved = resourceEditsByVersion[version]?.get(texture.path)
        if (saved != null) {
            clearHistory()
            mutableState.update { it.copy(selectedTexturePath = texture.path, document = saved) }
            return
        }
        val jar = resourceJar ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    ZipFile(jar).use { zip ->
                        val entry = zip.getEntry(texture.path) ?: error("Texture is missing from the client JAR")
                        val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                            ?: error("Minecraft texture is not a readable PNG")
                        bitmap.toDocument()
                    }
                }
            }.onSuccess { document ->
                clearHistory()
                mutableState.update {
                    it.copy(selectedTexturePath = texture.path, document = document, busy = false)
                }
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, message = "Could not read texture: ${error.message}") }
            }
        }
    }

    fun updatePackName(name: String) {
        mutableState.update { it.copy(resourcePackName = name.take(80)) }
    }

    fun selectTool(tool: PixelTool) {
        mutableState.update { it.copy(tool = tool) }
    }

    fun selectColor(color: Int) {
        mutableState.update { it.copy(brushColor = color) }
    }

    fun beginStroke() {
        val current = mutableState.value
        if (current.tool == PixelTool.PICKER) return
        val pixels = current.document?.pixels ?: return
        undo.addLast(pixels.copyOf())
        while (undo.size > MAX_HISTORY) undo.removeFirst()
        redo.clear()
        updateHistoryFlags()
    }

    fun editPixel(x: Int, y: Int, frameIndex: Int) {
        val current = mutableState.value
        val document = current.document ?: return
        if (x !in 0 until document.width || y !in 0 until document.height) return
        val index = y * document.width + x
        if (current.tool == PixelTool.PICKER) {
            mutableState.update { it.copy(brushColor = document.pixels[index]) }
            return
        }
        val pixels = document.pixels.copyOf()
        when (current.tool) {
            PixelTool.PENCIL -> pixels[index] = current.brushColor
            PixelTool.ERASER -> pixels[index] = 0x00000000
            PixelTool.FILL -> floodFill(
                pixels = pixels,
                width = document.width,
                startX = x,
                startY = y,
                minY = frameIndex.coerceIn(0, document.frameCount - 1) * document.frameHeight,
                maxYExclusive = ((frameIndex.coerceIn(0, document.frameCount - 1) + 1) * document.frameHeight)
                    .coerceAtMost(document.height),
                replacement = current.brushColor
            )
            PixelTool.PICKER -> Unit
        }
        if (pixels.contentEquals(document.pixels)) return
        setDocument(document.copy(pixels = pixels))
    }

    fun clearArtwork() {
        val document = mutableState.value.document ?: return
        beginStroke()
        setDocument(document.copy(pixels = IntArray(document.width * document.height)))
    }

    fun undo() {
        val document = mutableState.value.document ?: return
        if (undo.isEmpty()) return
        redo.addLast(document.pixels.copyOf())
        setDocument(document.copy(pixels = undo.removeLast()), updateHistory = false)
        updateHistoryFlags()
    }

    fun redo() {
        val document = mutableState.value.document ?: return
        if (redo.isEmpty()) return
        undo.addLast(document.pixels.copyOf())
        setDocument(document.copy(pixels = redo.removeLast()), updateHistory = false)
        updateHistoryFlags()
    }

    fun newArtwork(section: CreationLabSection) {
        val document = when (section) {
            CreationLabSection.SKIN -> blankDocument(64, 64).also { skinDocument = it }
            CreationLabSection.CAPE -> blankDocument(64, 32).also { capeDocument = it }
            else -> return
        }
        clearHistory()
        mutableState.update { it.copy(document = document, message = "Started a blank ${section.label.lowercase(Locale.ROOT)} canvas") }
    }

    fun importArtwork(uri: Uri, section: CreationLabSection) {
        val expected = when (section) {
            CreationLabSection.SKIN -> 64 to 64
            CreationLabSection.CAPE -> 64 to 32
            else -> return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val bitmap = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                        ?: error("Could not decode the selected PNG")
                    require(bitmap.width == expected.first && bitmap.height == expected.second) {
                        "${section.label} PNG must be ${expected.first}×${expected.second}, not ${bitmap.width}×${bitmap.height}"
                    }
                    bitmap.toDocument()
                }
            }.onSuccess { document ->
                if (section == CreationLabSection.SKIN) skinDocument = document else capeDocument = document
                clearHistory()
                mutableState.update { it.copy(document = document, busy = false, message = "${section.label} PNG imported") }
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, message = "Import failed: ${error.message}") }
            }
        }
    }

    fun exportResourcePack(destination: Uri) {
        val current = mutableState.value
        val version = current.resourceVersion
            ?: return mutableState.update { it.copy(message = "Choose an installed Minecraft version first") }
        val edits = resourceEditsByVersion[version].orEmpty().toMap()
        if (edits.isEmpty()) return mutableState.update { it.copy(message = "Edit at least one item texture before creating the pack") }
        val jar = resourceJar
            ?: return mutableState.update { it.copy(message = "The Minecraft client JAR is not available") }
        val format = resourcePackFormat
            ?: return mutableState.update { it.copy(message = "Could not determine the resource pack version") }
        val name = current.resourcePackName.trim().ifBlank { "MCLauncher Resource Pack" }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File(app.cacheDir, "creation-lab/${safeFileName(name)}-${UUID.randomUUID()}.zip")
                    temporary.parentFile?.mkdirs()
                    createResourcePack(temporary, jar, name, format, edits)
                    validateResourcePack(temporary, edits.size)
                    app.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        temporary.inputStream().use { it.copyTo(output) }
                    } ?: error("Android could not open the selected destination")
                    temporary.delete()
                }
            }.onSuccess {
                mutableState.update { it.copy(busy = false, message = "Created a validated Minecraft $version resource pack ZIP") }
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, message = "Resource pack export failed: ${error.message}") }
            }
        }
    }

    fun exportArtwork(destination: Uri, section: CreationLabSection) {
        val document = when (section) {
            CreationLabSection.SKIN -> skinDocument
            CreationLabSection.CAPE -> capeDocument
            else -> return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val temporary = File(app.cacheDir, "creation-lab/${section.name.lowercase()}-${UUID.randomUUID()}.png")
                    temporary.parentFile?.mkdirs()
                    temporary.outputStream().use { output ->
                        check(document.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG encoder failed" }
                    }
                    val verified = BitmapFactory.decodeFile(temporary.absolutePath) ?: error("Exported PNG could not be verified")
                    require(verified.width == document.width && verified.height == document.height) { "Exported PNG has the wrong dimensions" }
                    app.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                        temporary.inputStream().use { it.copyTo(output) }
                    } ?: error("Android could not open the selected destination")
                    temporary.delete()
                }
            }.onSuccess {
                mutableState.update { it.copy(busy = false, message = "Created ${document.width}×${document.height} ${section.label.lowercase()} PNG") }
            }.onFailure { error ->
                mutableState.update { it.copy(busy = false, message = "PNG export failed: ${error.message}") }
            }
        }
    }

    fun clearMessage() {
        mutableState.update { it.copy(message = null) }
    }

    private fun setDocument(document: PixelDocument, updateHistory: Boolean = true) {
        when (mutableState.value.section) {
            CreationLabSection.RESOURCE_PACK -> {
                val version = mutableState.value.resourceVersion ?: return
                val path = mutableState.value.selectedTexturePath ?: return
                resourceEditsByVersion.getOrPut(version) { mutableMapOf() }[path] = document
            }
            CreationLabSection.SKIN -> skinDocument = document
            CreationLabSection.CAPE -> capeDocument = document
            CreationLabSection.AI_WORKSHOP -> return
        }
        mutableState.update {
            it.copy(
                document = document,
                editedTextureCount = currentResourceEdits().size,
                canUndo = if (updateHistory) undo.isNotEmpty() else it.canUndo,
                canRedo = if (updateHistory) redo.isNotEmpty() else it.canRedo
            )
        }
    }

    private fun currentResourceEdits(): MutableMap<String, PixelDocument> {
        val version = mutableState.value.resourceVersion ?: return mutableMapOf()
        return resourceEditsByVersion.getOrPut(version) { mutableMapOf() }
    }

    private fun clearHistory() {
        undo.clear()
        redo.clear()
        updateHistoryFlags()
    }

    private fun updateHistoryFlags() {
        mutableState.update { it.copy(canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
    }

    private fun createResourcePack(
        destination: File,
        clientJar: File,
        name: String,
        format: ResourcePackFormat,
        edits: Map<String, PixelDocument>
    ) {
        val metadata = buildJsonObject {
            put("pack", buildJsonObject {
                put("description", "${name.take(96)} — created with MCL Creation Lab")
                if (format.major >= MODERN_PACK_METADATA_VERSION) {
                    put("min_format", packVersionArray(format))
                    put("max_format", packVersionArray(format))
                } else {
                    put("pack_format", format.major)
                    put("supported_formats", buildJsonArray {
                        add(JsonPrimitive(format.major))
                        add(JsonPrimitive(format.major))
                    })
                }
            })
        }
        ZipFile(clientJar).use { source ->
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.writeEntry("pack.mcmeta", json.encodeToString(JsonObject.serializer(), metadata).toByteArray())
                edits.toSortedMap().forEach { (path, document) ->
                    require(path.startsWith(ITEM_TEXTURE_PREFIX) && path.endsWith(".png")) { "Unsafe texture path $path" }
                    val png = ByteArrayOutputStream().use { bytes ->
                        check(document.toBitmap().compress(Bitmap.CompressFormat.PNG, 100, bytes)) { "PNG encoder failed for $path" }
                        bytes.toByteArray()
                    }
                    zip.writeEntry(path, png)
                    source.getEntry("$path.mcmeta")?.let { animation ->
                        zip.putNextEntry(ZipEntry("$path.mcmeta"))
                        source.getInputStream(animation).use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun validateResourcePack(archive: File, expectedTextures: Int) {
        ZipFile(archive).use { zip ->
            val metadata = zip.getEntry("pack.mcmeta") ?: error("pack.mcmeta is missing")
            val root = zip.getInputStream(metadata).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
            val pack = root["pack"]?.jsonObject ?: error("pack.mcmeta has no pack object")
            require(pack["description"] != null) { "pack.mcmeta description is missing" }
            require(pack["pack_format"] != null || (pack["min_format"] != null && pack["max_format"] != null)) {
                "pack.mcmeta compatibility range is missing"
            }
            val textures = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith(ITEM_TEXTURE_PREFIX) && it.name.endsWith(".png") }
                .toList()
            require(textures.size == expectedTextures) { "The ZIP lost one or more edited textures" }
            textures.forEach { entry ->
                val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
                    ?: error("${entry.name} is not a valid PNG")
                require(bitmap.width > 0 && bitmap.height > 0) { "${entry.name} has invalid dimensions" }
            }
        }
    }

    private fun readResourcePackFormat(clientJar: File, version: String): ResourcePackFormat {
        val fromJar = ZipFile(clientJar).use { zip ->
            val entry = zip.getEntry("version.json") ?: return@use null
            val root = zip.getInputStream(entry).bufferedReader().use { json.parseToJsonElement(it.readText()).jsonObject }
            root["pack_version"]?.jsonObject?.get("resource")?.let(::parsePackVersion)
        }
        if (fromJar != null) return fromJar
        return when (version) {
            "26.3-snapshot-6" -> ResourcePackFormat(94)
            "26.2" -> ResourcePackFormat(88)
            "26.1" -> ResourcePackFormat(84)
            "1.21.11" -> ResourcePackFormat(75)
            "1.21.9" -> ResourcePackFormat(69)
            "1.21.6" -> ResourcePackFormat(63)
            "1.21.5" -> ResourcePackFormat(55)
            "1.20.1" -> ResourcePackFormat(15)
            else -> error("Minecraft $version does not declare its resource pack version")
        }
    }

    private fun parsePackVersion(element: JsonElement): ResourcePackFormat? = when (element) {
        is JsonPrimitive -> {
            element.intOrNull?.let(::ResourcePackFormat) ?: element.contentOrNull?.split('.')?.let { parts ->
                val major = parts.getOrNull(0)?.toIntOrNull() ?: return@let null
                ResourcePackFormat(major, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
        }
        is JsonArray -> element.jsonArray.let { values ->
            values.firstOrNull()?.jsonPrimitive?.intOrNull?.let { major ->
                ResourcePackFormat(major, values.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 0)
            }
        }
        is JsonObject -> element.jsonObject.let { value ->
            val major = value["major"]?.jsonPrimitive?.intOrNull ?: return@let null
            ResourcePackFormat(major, value["minor"]?.jsonPrimitive?.intOrNull ?: 0)
        }
        else -> null
    }

    private fun packVersionArray(format: ResourcePackFormat) = buildJsonArray {
        add(JsonPrimitive(format.major))
        add(JsonPrimitive(format.minor))
    }

    private fun ZipOutputStream.writeEntry(path: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(path))
        write(bytes)
        closeEntry()
    }

    private fun Bitmap.toDocument(): PixelDocument {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return PixelDocument(width, height, pixels)
    }

    private fun PixelDocument.toBitmap(): Bitmap = Bitmap.createBitmap(
        pixels,
        width,
        height,
        Bitmap.Config.ARGB_8888
    )

    private fun safeFileName(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.', '_')
        .take(64)
        .ifBlank { "mcl-creation" }

    companion object {
        private const val ITEM_TEXTURE_PREFIX = "assets/minecraft/textures/item/"
        private const val MODERN_PACK_METADATA_VERSION = 65
        private const val MAX_HISTORY = 30

        private fun blankDocument(width: Int, height: Int) = PixelDocument(width, height, IntArray(width * height))

        private fun floodFill(
            pixels: IntArray,
            width: Int,
            startX: Int,
            startY: Int,
            minY: Int,
            maxYExclusive: Int,
            replacement: Int
        ) {
            val target = pixels[startY * width + startX]
            if (target == replacement) return
            val queue = ArrayDeque<Int>()
            queue.add(startY * width + startX)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                if (pixels[index] != target) continue
                val x = index % width
                val y = index / width
                if (y !in minY until maxYExclusive) continue
                pixels[index] = replacement
                if (x > 0) queue.add(index - 1)
                if (x + 1 < width) queue.add(index + 1)
                if (y > minY) queue.add(index - width)
                if (y + 1 < maxYExclusive) queue.add(index + width)
            }
        }
    }
}
