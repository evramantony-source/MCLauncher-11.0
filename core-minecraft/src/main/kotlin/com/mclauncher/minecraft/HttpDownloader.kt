package com.mclauncher.minecraft

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HttpDownloader(
    private val userAgent: String = "MCLauncher/11.0 (Android; contact: local-user)",
    private val maxAttempts: Int = 3
) {
    suspend fun download(
        url: String,
        destination: File,
        expectedSha1: String? = null,
        expectedSha512: String? = null,
        expectedSize: Long? = null,
        headers: Map<String, String> = emptyMap(),
        onBytes: ((Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        if (isValid(destination, expectedSha1, expectedSha512, expectedSize)) return@withContext destination

        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, destination.name + ".part")
        var lastError: Throwable? = null

        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            try {
                downloadAttempt(url, temporary, expectedSize, headers, onBytes)
                if (!isValid(temporary, expectedSha1, expectedSha512, expectedSize)) {
                    temporary.delete()
                    error("Downloaded file failed integrity check: ${destination.name}")
                }
                replaceFile(temporary, destination)
                return@withContext destination
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < maxAttempts) delay(700L * (attempt + 1))
            }
        }

        if (temporary.exists() && expectedSize != null && temporary.length() > expectedSize) temporary.delete()
        throw IllegalStateException(
            "Failed to download ${destination.name} after $maxAttempts attempts: ${lastError?.message}",
            lastError
        )
    }

    suspend fun readText(url: String, headers: Map<String, String> = emptyMap()): String =
        request("GET", url, headers = headers)

    suspend fun postJson(
        url: String,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): String = request(
        method = "POST",
        url = url,
        headers = mapOf("Content-Type" to "application/json") + headers,
        body = body.toByteArray(StandardCharsets.UTF_8)
    )

    suspend fun postForm(
        url: String,
        values: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): String {
        val body = values.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return request(
            method = "POST",
            url = url,
            headers = mapOf("Content-Type" to "application/x-www-form-urlencoded") + headers,
            body = body.toByteArray(StandardCharsets.UTF_8)
        )
    }

    suspend fun request(
        method: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        acceptedStatus: IntRange = 200..299
    ): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        repeat(maxAttempts.coerceAtLeast(1)) { attempt ->
            val connection = open(url, method, headers)
            try {
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Length", body.size.toString())
                    connection.outputStream.use { it.write(body) }
                }
                val status = connection.responseCode
                val stream = if (status in acceptedStatus) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in acceptedStatus) error("HTTP $status while requesting $url${if (text.isBlank()) "" else ": $text"}")
                return@withContext text
            } catch (error: Throwable) {
                lastError = error
                if (attempt + 1 < maxAttempts) delay(500L * (attempt + 1))
            } finally {
                connection.disconnect()
            }
        }
        throw IllegalStateException("Failed to request $url: ${lastError?.message}", lastError)
    }

    private fun downloadAttempt(
        url: String,
        temporary: File,
        expectedSize: Long?,
        headers: Map<String, String>,
        onBytes: ((Long) -> Unit)?
    ) {
        if (expectedSize != null && temporary.length() > expectedSize) temporary.delete()
        val existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = open(url, "GET", headers).apply {
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }

        try {
            val status = connection.responseCode
            if (status == 416) {
                if (expectedSize != null && existing == expectedSize) return
                temporary.delete()
                error("Server rejected the saved partial download")
            }
            if (status !in 200..299) {
                val message = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("HTTP $status while downloading $url${if (message.isBlank()) "" else ": $message"}")
            }

            val append = status == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!append && temporary.exists()) temporary.delete()
            FileOutputStream(temporary, append).buffered().use { output ->
                connection.inputStream.buffered().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            output.write(buffer, 0, count)
                            onBytes?.invoke(count.toLong())
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 25_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = method
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept-Encoding", "identity")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }

    private fun replaceFile(source: File, destination: File) {
        if (destination.exists() && !destination.delete()) error("Cannot replace ${destination.absolutePath}")
        if (!source.renameTo(destination)) {
            source.copyTo(destination, overwrite = true)
            source.delete()
        }
    }

    private fun isValid(file: File, sha1: String?, sha512: String?, size: Long?): Boolean {
        if (!file.isFile) return false
        if (size != null && size >= 0 && file.length() != size) return false
        if (!sha1.isNullOrBlank() && !Hashing.sha1(file).equals(sha1, ignoreCase = true)) return false
        if (!sha512.isNullOrBlank() && !Hashing.sha512(file).equals(sha512, ignoreCase = true)) return false
        return true
    }

    companion object {
        fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    }
}
