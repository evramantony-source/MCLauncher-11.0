package com.mclauncher.app.engine

import android.system.Os
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

internal object ArchiveExtractor {
    private const val MAX_ENTRIES = 120_000
    private const val MAX_TOTAL_BYTES = 5L * 1024L * 1024L * 1024L

    fun extract(archive: File, destination: File) {
        destination.mkdirs()
        BufferedInputStream(FileInputStream(archive)).use { input ->
            input.mark(16)
            val header = ByteArray(8)
            val count = input.read(header)
            input.reset()
            when {
                count >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() ->
                    extractZip(input, destination)
                count >= 6 && header.copyOfRange(0, 6).contentEquals(
                    byteArrayOf(
                        0xFD.toByte(), 0x37.toByte(), 0x7A.toByte(),
                        0x58.toByte(), 0x5A.toByte(), 0x00.toByte()
                    )
                ) -> XZInputStream(input).use { extractTar(it, destination) }
                count >= 2 && header[0] == 0x1F.toByte() && header[1] == 0x8B.toByte() ->
                    GzipCompressorInputStream(input).use { extractTar(it, destination) }
                else -> error("Unsupported archive format. Use ZIP, APK, TAR.XZ, or TAR.GZ.")
            }
        }
    }

    private fun extractZip(input: InputStream, destination: File) {
        ZipInputStream(input).use { zip ->
            var entries = 0
            var total = 0L
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Archive contains too many entries" }
                val target = safeTarget(destination, entry.name)
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { output ->
                        total += copyLimited(zip, output, MAX_TOTAL_BYTES - total)
                    }
                    if (target.name == "java" || target.name.endsWith(".so")) {
                        target.setExecutable(true, true)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun extractTar(input: InputStream, destination: File) {
        val pendingLinks = mutableListOf<PendingLink>()
        TarArchiveInputStream(input).use { tar ->
            var entries = 0
            var total = 0L
            while (true) {
                val entry = tar.nextEntry ?: break
                entries++
                require(entries <= MAX_ENTRIES) { "Archive contains too many entries" }
                val target = safeTarget(destination, entry.name)
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        applyMode(target, entry.mode)
                    }
                    entry.isSymbolicLink -> pendingLinks += PendingLink(target, entry.linkName, symbolic = true)
                    entry.isLink -> pendingLinks += PendingLink(target, entry.linkName, symbolic = false)
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output ->
                            total += copyLimited(tar, output, MAX_TOTAL_BYTES - total)
                        }
                        applyMode(target, entry.mode)
                    }
                }
            }
        }
        pendingLinks.forEach { createSafeLink(destination, it) }
    }

    private fun createSafeLink(destination: File, link: PendingLink) {
        require(link.linkName.isNotBlank()) { "Archive contains an empty link target" }
        require(!File(link.linkName).isAbsolute) { "Blocked absolute archive link: ${link.linkName}" }
        val resolved = if (link.symbolic) {
            File(link.target.parentFile, link.linkName).canonicalFile
        } else {
            safeTarget(destination, link.linkName)
        }
        require(isInside(destination.canonicalFile, resolved)) { "Blocked archive link escape: ${link.linkName}" }
        link.target.parentFile?.mkdirs()
        link.target.delete()
        runCatching {
            if (link.symbolic) Os.symlink(link.linkName, link.target.absolutePath)
            else Os.link(resolved.absolutePath, link.target.absolutePath)
        }.getOrElse {
            // A copied hard-link target is safer than leaving a required runtime file missing.
            if (!link.symbolic && resolved.isFile) resolved.copyTo(link.target, overwrite = true)
            else throw it
        }
    }

    private fun applyMode(file: File, mode: Int) {
        val safeMode = mode and 0x1FF
        runCatching { Os.chmod(file.absolutePath, safeMode) }
        if (file.name == "java" || file.name.endsWith(".so")) file.setExecutable(true, true)
    }

    private fun safeTarget(destination: File, entryName: String): File {
        require(entryName.isNotBlank()) { "Archive contains an empty path" }
        require(!File(entryName).isAbsolute && !entryName.startsWith('/')) {
            "Blocked absolute archive entry: $entryName"
        }
        val normalized = entryName.replace('\\', '/')
        require(!normalized.split('/').contains("..")) { "Blocked path traversal entry: $entryName" }
        val target = File(destination, normalized).canonicalFile
        val root = destination.canonicalFile
        require(isInside(root, target)) { "Blocked path traversal entry: $entryName" }
        return target
    }

    private fun isInside(root: File, target: File): Boolean =
        target.path == root.path || target.path.startsWith(root.path + File.separator)

    private fun copyLimited(input: InputStream, output: FileOutputStream, remaining: Long): Long {
        require(remaining > 0) { "Archive is larger than the safety limit" }
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= remaining) { "Archive is larger than the safety limit" }
            output.write(buffer, 0, read)
        }
        return copied
    }

    private data class PendingLink(val target: File, val linkName: String, val symbolic: Boolean)
}
