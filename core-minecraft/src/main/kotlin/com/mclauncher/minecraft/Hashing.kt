package com.mclauncher.minecraft

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

object Hashing {
    fun sha1(file: File): String = digest(file, "SHA-1")
    fun sha256(file: File): String = digest(file, "SHA-256")
    fun sha512(file: File): String = digest(file, "SHA-512")

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
