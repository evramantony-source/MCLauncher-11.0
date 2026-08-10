package com.mclauncher.app.creation

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

class AttachmentInspector(private val context: Context) {
    private val attachmentDirectory = context.filesDir.resolve("creation-lab/attachments")

    fun import(uri: Uri): LocalAttachment {
        attachmentDirectory.mkdirs()
        val metadata = context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) null else {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                name to size
            }
        }
        val displayName = safeName(metadata?.first ?: uri.lastPathSegment ?: "attachment")
        val declaredSize = metadata?.second ?: -1L
        require(declaredSize <= MAX_ATTACHMENT_BYTES || declaredSize < 0L) {
            "$displayName is larger than the 256 MB attachment limit"
        }
        val destination = uniqueFile(attachmentDirectory, "${UUID.randomUUID()}-$displayName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_ATTACHMENT_BYTES) { "$displayName is larger than the 256 MB attachment limit" }
                    output.write(buffer, 0, read)
                }
            }
        } ?: error("Android could not open $displayName")
        val kind = kindOf(destination, context.contentResolver.getType(uri))
        return LocalAttachment(
            id = destination.name.substringBefore('-'),
            displayName = displayName,
            kind = kind,
            sizeBytes = destination.length(),
            localPath = destination.absolutePath,
            analysis = inspect(destination, kind)
        )
    }

    fun delete(attachment: LocalAttachment) {
        val file = File(attachment.localPath)
        val root = attachmentDirectory.canonicalFile
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (target.parentFile == root) target.delete()
    }

    private fun inspect(file: File, kind: AttachmentKind): String = when (kind) {
        AttachmentKind.IMAGE -> inspectImage(file)
        AttachmentKind.MOD_JAR -> inspectJar(file)
        AttachmentKind.SOURCE_ARCHIVE -> inspectSourceArchive(file)
        AttachmentKind.LOG -> inspectText(file)
        AttachmentKind.OTHER -> "Attached as a reference file (${formatBytes(file.length())})."
    }

    private fun inspectImage(file: File): String {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            "Readable ${options.outWidth}×${options.outHeight} image. It can be used as an item texture reference."
        } else {
            "Android could not decode this image."
        }
    }

    private fun inspectJar(file: File): String = runCatching {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toSet()
            val loader = when {
                "fabric.mod.json" in entries -> "Fabric"
                "quilt.mod.json" in entries -> "Quilt"
                "META-INF/neoforge.mods.toml" in entries -> "NeoForge"
                "META-INF/mods.toml" in entries -> "Forge"
                else -> "unknown loader"
            }
            val classes = entries.count { it.endsWith(".class", ignoreCase = true) }
            val assets = entries.count { it.startsWith("assets/") && !it.endsWith('/') }
            "$loader JAR; $classes compiled classes and $assets asset files found. Binary metadata can be inspected, but a safe version port needs source code too."
        }
    }.getOrElse { "Unreadable JAR/ZIP: ${it.message ?: "invalid archive"}" }

    private fun inspectSourceArchive(file: File): String = runCatching {
        ZipFile(file).use { zip ->
            val names = zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }.toList()
            val sources = names.count { it.endsWith(".java") || it.endsWith(".kt") }
            val buildFiles = names.count {
                it.endsWith("build.gradle") || it.endsWith("build.gradle.kts") || it.endsWith("pom.xml")
            }
            "Source archive with $sources source files and $buildFiles recognized build files."
        }
    }.getOrElse { "Unreadable source archive: ${it.message ?: "invalid archive"}" }

    private fun inspectText(file: File): String {
        val text = file.inputStream().buffered().use { input ->
            val bytes = ByteArray(MAX_TEXT_INSPECTION_BYTES)
            val count = input.read(bytes).coerceAtLeast(0)
            bytes.copyOf(count).toString(Charsets.UTF_8)
        }
        val signals = buildList {
            if ("OutOfMemoryError" in text) add("Java ran out of memory")
            if ("MixinApplyError" in text || "MixinTransformerError" in text) add("a Mixin failed to apply")
            if ("NoSuchMethodError" in text) add("a mod called a method missing from this version")
            if ("NoClassDefFoundError" in text || "ClassNotFoundException" in text) add("a required class or dependency is missing")
            if ("UnsupportedClassVersionError" in text) add("the mod and Java versions do not match")
            if ("ModResolutionException" in text || "Incompatible mod set" in text) add("the installed mod set is incompatible")
        }
        return if (signals.isEmpty()) {
            "Text/log attached; no common crash signature was recognized in the first 128 KB."
        } else {
            "Detected: ${signals.joinToString("; ")}."
        }
    }

    private fun kindOf(file: File, mimeType: String?): AttachmentKind {
        val extension = file.extension.lowercase()
        return when {
            mimeType?.startsWith("image/") == true || extension in IMAGE_EXTENSIONS -> AttachmentKind.IMAGE
            extension == "jar" -> AttachmentKind.MOD_JAR
            extension in setOf("zip", "7z", "tar", "gz", "tgz") -> AttachmentKind.SOURCE_ARCHIVE
            mimeType?.startsWith("text/") == true || extension in TEXT_EXTENSIONS -> AttachmentKind.LOG
            else -> AttachmentKind.OTHER
        }
    }

    private fun safeName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .take(120)
        .ifBlank { "attachment" }

    private fun uniqueFile(parent: File, name: String): File {
        var candidate = parent.resolve(name)
        var number = 2
        while (candidate.exists()) {
            val base = name.substringBeforeLast('.', name)
            val extension = name.substringAfterLast('.', "").takeIf { name.contains('.') }.orEmpty()
            candidate = parent.resolve("$base ($number)${if (extension.isBlank()) "" else ".$extension"}")
            number++
        }
        return candidate
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes bytes"
    }

    companion object {
        private const val MAX_ATTACHMENT_BYTES = 256L * 1024L * 1024L
        private const val MAX_TEXT_INSPECTION_BYTES = 128 * 1024
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val TEXT_EXTENSIONS = setOf("txt", "log", "json", "toml", "xml", "properties", "md", "java", "kt")
    }
}
