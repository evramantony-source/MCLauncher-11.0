package com.mclauncher.app.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import com.mclauncher.app.R
import java.io.File
import java.io.FileNotFoundException

/** Exposes only instance game folders and Creation Lab outputs through Android's
 * Storage Access Framework. This makes MCLauncher appear beside Drive and other
 * launchers without granting broad device-storage permissions. */
class MCLauncherDocumentsProvider : DocumentsProvider() {
    override fun onCreate(): Boolean {
        context?.filesDir?.resolve(CREATION_DIRECTORY)?.mkdirs()
        context?.filesDir?.resolve(INSTANCES_DIRECTORY)?.mkdirs()
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_ROOT_PROJECTION)
        cursor.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_ID)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
            add(DocumentsContract.Root.COLUMN_TITLE, "MCLauncher")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Instances and Creation Lab projects")
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
            add(
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                    DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD or
                    DocumentsContract.Root.FLAG_SUPPORTS_SEARCH
            )
            add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*")
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION).also { cursor ->
            includeDocument(cursor, documentId)
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION)
        if (parentDocumentId == ROOT_DOCUMENT_ID) {
            includeDocument(cursor, INSTANCES_DOCUMENT_ID)
            includeDocument(cursor, CREATION_DOCUMENT_ID)
            return cursor
        }
        val parent = fileForDocumentId(parentDocumentId)
        requireDirectory(parent)
        parent.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .forEach { includeDocument(cursor, documentIdForFile(parentDocumentId, it)) }
        return cursor
    }

    override fun querySearchDocuments(
        rootId: String,
        query: String,
        projection: Array<out String>?
    ): Cursor {
        val cursor = MatrixCursor(projection?.toList()?.toTypedArray() ?: DEFAULT_DOCUMENT_PROJECTION)
        val needle = query.trim().lowercase()
        listOf(INSTANCES_DOCUMENT_ID, CREATION_DOCUMENT_ID).forEach { rootDocument ->
            val root = fileForDocumentId(rootDocument)
            root.walkTopDown().drop(1).take(250)
                .filter { needle.isBlank() || it.name.lowercase().contains(needle) }
                .forEach { file -> includeDocument(cursor, documentIdForDescendant(rootDocument, root, file)) }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val file = fileForDocumentId(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file: $documentId")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = fileForDocumentId(parentDocumentId)
        requireDirectory(parent)
        val safeName = safeDisplayName(displayName)
        val target = uniqueFile(parent, safeName)
        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
            check(target.mkdir()) { "Could not create folder $safeName" }
        } else {
            check(target.createNewFile()) { "Could not create file $safeName" }
        }
        return documentIdForFile(parentDocumentId, target)
    }

    override fun deleteDocument(documentId: String) {
        require(documentId !in PROTECTED_DOCUMENT_IDS) { "MCLauncher root folders cannot be deleted" }
        val file = fileForDocumentId(documentId)
        check(if (file.isDirectory) file.deleteRecursively() else file.delete()) { "Could not delete ${file.name}" }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        require(documentId !in PROTECTED_DOCUMENT_IDS) { "MCLauncher root folders cannot be renamed" }
        val source = fileForDocumentId(documentId)
        val parentId = documentId.substringBeforeLast('/', ROOT_DOCUMENT_ID)
        val target = uniqueFile(source.parentFile ?: error("Document has no parent"), safeDisplayName(displayName))
        check(source.renameTo(target)) { "Could not rename ${source.name}" }
        return documentIdForFile(parentId, target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        if (parentDocumentId == ROOT_DOCUMENT_ID) return documentId != ROOT_DOCUMENT_ID && runCatching {
            fileForDocumentId(documentId)
        }.isSuccess
        val parent = runCatching { fileForDocumentId(parentDocumentId).canonicalFile }.getOrNull() ?: return false
        val child = runCatching { fileForDocumentId(documentId).canonicalFile }.getOrNull() ?: return false
        return child.path.startsWith(parent.path + File.separator)
    }

    private fun includeDocument(cursor: MatrixCursor, documentId: String) {
        if (documentId == ROOT_DOCUMENT_ID) {
            cursor.newRow().apply {
                add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, ROOT_DOCUMENT_ID)
                add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, "MCLauncher")
                add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
                add(DocumentsContract.Document.COLUMN_ICON, R.mipmap.ic_launcher)
                add(DocumentsContract.Document.COLUMN_FLAGS, 0)
            }
            return
        }
        val file = fileForDocumentId(documentId)
        val protected = documentId in PROTECTED_DOCUMENT_IDS
        var flags = if (file.isDirectory) DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else
            DocumentsContract.Document.FLAG_SUPPORTS_WRITE
        if (!protected) flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_DELETE or DocumentsContract.Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
            add(
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                when (documentId) {
                    INSTANCES_DOCUMENT_ID -> "Minecraft instances"
                    CREATION_DOCUMENT_ID -> "MCL Creation Lab"
                    else -> file.name
                }
            )
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType(file))
            add(DocumentsContract.Document.COLUMN_FLAGS, flags)
            add(DocumentsContract.Document.COLUMN_SIZE, if (file.isFile) file.length() else null)
            add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified().takeIf { it > 0L })
        }
    }

    private fun fileForDocumentId(documentId: String): File {
        val files = context?.filesDir ?: throw FileNotFoundException("Provider is not attached")
        val (base, relative) = when {
            documentId == INSTANCES_DOCUMENT_ID -> files.resolve(INSTANCES_DIRECTORY) to ""
            documentId.startsWith("$INSTANCES_DOCUMENT_ID/") -> files.resolve(INSTANCES_DIRECTORY) to documentId.removePrefix("$INSTANCES_DOCUMENT_ID/")
            documentId == CREATION_DOCUMENT_ID -> files.resolve(CREATION_DIRECTORY) to ""
            documentId.startsWith("$CREATION_DOCUMENT_ID/") -> files.resolve(CREATION_DIRECTORY) to documentId.removePrefix("$CREATION_DOCUMENT_ID/")
            else -> throw FileNotFoundException("Unknown MCLauncher document: $documentId")
        }
        val target = if (relative.isBlank()) base else base.resolve(relative)
        val canonicalBase = base.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget != canonicalBase && !canonicalTarget.path.startsWith(canonicalBase.path + File.separator)) {
            throw FileNotFoundException("Unsafe MCLauncher document path")
        }
        return canonicalTarget
    }

    private fun documentIdForFile(parentDocumentId: String, file: File): String =
        "$parentDocumentId/${file.name}"

    private fun documentIdForDescendant(rootDocumentId: String, root: File, file: File): String =
        "$rootDocumentId/${file.relativeTo(root).invariantSeparatorsPath}"

    private fun requireDirectory(file: File) {
        if (!file.isDirectory) throw FileNotFoundException("Not a directory: ${file.name}")
    }

    private fun mimeType(file: File): String {
        if (file.isDirectory) return DocumentsContract.Document.MIME_TYPE_DIR
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: when (file.extension.lowercase()) {
                "jar" -> "application/java-archive"
                "log" -> "text/plain"
                else -> "application/octet-stream"
            }
    }

    private fun safeDisplayName(value: String): String {
        val safe = value.trim().replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").take(128)
        require(safe.isNotBlank() && safe != "." && safe != "..") { "Invalid document name" }
        return safe
    }

    private fun uniqueFile(parent: File, name: String): File {
        var candidate = parent.resolve(name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").takeIf { it != name }.orEmpty()
        var counter = 2
        while (candidate.exists()) {
            candidate = parent.resolve("$base ($counter)${if (extension.isBlank()) "" else ".$extension"}")
            counter++
        }
        return candidate
    }

    companion object {
        private const val ROOT_ID = "mclauncher"
        private const val ROOT_DOCUMENT_ID = "root"
        private const val INSTANCES_DOCUMENT_ID = "instances"
        private const val CREATION_DOCUMENT_ID = "creation-lab"
        private const val INSTANCES_DIRECTORY = "minecraft/instances"
        private const val CREATION_DIRECTORY = "creation-lab"
        private val PROTECTED_DOCUMENT_IDS = setOf(INSTANCES_DOCUMENT_ID, CREATION_DOCUMENT_ID)
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_SUMMARY,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES
        )
        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_ICON
        )
    }
}
