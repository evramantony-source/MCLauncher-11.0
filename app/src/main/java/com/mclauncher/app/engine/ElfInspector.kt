package com.mclauncher.app.engine

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object ElfInspector {
    fun architecture(file: File): String? {
        if (!file.isFile || file.length() < 20) return null
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            if (input.read(header) != header.size) return null
        }
        if (
            header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) return null
        val order = when (header[5].toInt()) {
            1 -> ByteOrder.LITTLE_ENDIAN
            2 -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val machine = ByteBuffer.wrap(header, 18, 2).order(order).short.toInt() and 0xFFFF
        return when (machine) {
            3 -> "x86"
            40 -> "armeabi-v7a"
            62 -> "x86_64"
            183 -> "arm64-v8a"
            else -> null
        }
    }

    fun matches(file: File, expectedArchitecture: String): Boolean {
        val detected = architecture(file) ?: return false
        return detected == expectedArchitecture ||
            (expectedArchitecture == "armeabi-v7a" && detected == "armeabi-v7a")
    }
}
