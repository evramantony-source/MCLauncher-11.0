package com.mclauncher.app.creation

import android.content.Context
import com.mclauncher.minecraft.ToolLaunchPlan
import com.mclauncher.model.JavaVersion
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

class LocalGradleToolchain(private val context: Context) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val labRoot = context.filesDir.resolve("creation-lab")

    fun gradleVersion(javaVersion: JavaVersion): String =
        if (javaVersion.major >= 25) GRADLE_JAVA_25 else GRADLE_JAVA_17_21

    fun runtimeVersion(target: LocalBuildTarget): JavaVersion = when {
        target.javaVersion.major >= 25 -> JavaVersion.JAVA_25
        else -> JavaVersion.JAVA_21
    }

    fun ensureInstalled(version: String, onProgress: (String) -> Unit): File {
        val toolchains = labRoot.resolve("toolchains").apply { mkdirs() }
        val installation = toolchains.resolve("gradle-$version")
        val marker = installation.resolve(".mclauncher-complete")
        if (marker.isFile && installation.resolve("lib/gradle-launcher-$version.jar").isFile) return installation

        val archive = toolchains.resolve("gradle-$version-bin.zip")
        val expectedSha = downloadText("$DISTRIBUTION_ROOT/gradle-$version-bin.zip.sha256").trim().lowercase()
        require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Gradle published an invalid checksum" }
        if (!archive.isFile || sha256(archive) != expectedSha) {
            archive.delete()
            onProgress("Downloading the free local Gradle $version toolchain (first build only)…")
            downloadFile("$DISTRIBUTION_ROOT/gradle-$version-bin.zip", archive, onProgress)
            require(sha256(archive) == expectedSha) { "Gradle download checksum did not match" }
        }

        onProgress("Installing the verified local build toolchain…")
        val staging = toolchains.resolve(".gradle-$version-${UUID.randomUUID()}").apply {
            deleteRecursively()
            mkdirs()
        }
        unzipSafely(archive, staging)
        val extracted = staging.resolve("gradle-$version")
        require(extracted.resolve("lib/gradle-launcher-$version.jar").isFile) {
            "Gradle archive is missing its launcher"
        }
        installation.deleteRecursively()
        check(extracted.renameTo(installation)) { "Could not install the local Gradle toolchain" }
        staging.deleteRecursively()
        marker.writeText("sha256=$expectedSha\n")
        return installation
    }

    fun createToolPlan(
        projectDirectory: File,
        gradleHome: File,
        javaVersion: JavaVersion
    ): File {
        val classpath = gradleHome.resolve("lib").walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy(File::getAbsolutePath)
            .map(File::getAbsolutePath)
            .toList()
        require(classpath.isNotEmpty()) { "Local Gradle installation has no launcher libraries" }
        val plan = ToolLaunchPlan(
            id = "creation-lab-${UUID.randomUUID()}",
            javaVersion = javaVersion,
            workingDirectory = projectDirectory.absolutePath,
            classpath = classpath,
            mainClass = "org.gradle.launcher.GradleMain",
            arguments = listOf(
                "--no-daemon",
                "--console=plain",
                "--stacktrace",
                "--max-workers=1",
                "--project-dir",
                projectDirectory.absolutePath,
                "clean",
                "build"
            ),
            jvmArguments = listOf(
                "-Xms128m",
                "-Xmx1536m",
                "-Dfile.encoding=UTF-8",
                "-Dorg.gradle.daemon=false",
                "-Dorg.gradle.parallel=false",
                "-Dgradle.user.home=${labRoot.resolve("gradle-cache").absolutePath}"
            )
        )
        val launchPlans = context.filesDir.resolve("minecraft/launch-plans").apply { mkdirs() }
        return launchPlans.resolve("${plan.id}.json").also { file ->
            file.writeText(json.encodeToString(plan))
        }
    }

    private fun downloadText(url: String): String {
        val connection = open(url)
        val code = connection.responseCode
        require(code in 200..299) { "Could not download Gradle checksum (HTTP $code)" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun downloadFile(url: String, destination: File, onProgress: (String) -> Unit) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        temporary.delete()
        val connection = open(url)
        val code = connection.responseCode
        require(code in 200..299) { "Could not download local Gradle (HTTP $code)" }
        val total = connection.contentLengthLong
        connection.inputStream.buffered().use { input ->
            temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = 0L
                var lastPercent = -1
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    if (total > 0L) {
                        val percent = ((downloaded * 100L) / total).toInt()
                        if (percent >= lastPercent + 5) {
                            lastPercent = percent
                            onProgress("Downloading local Gradle… $percent%")
                        }
                    }
                }
            }
        }
        connection.disconnect()
        check(temporary.renameTo(destination)) { "Could not store the Gradle download" }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("User-Agent", "MCLauncher-Local-Builder")
    }

    private fun unzipSafely(archive: File, destination: File) {
        val canonicalDestination = destination.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            var entries = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_TOOLCHAIN_ENTRIES) { "Gradle archive has too many entries" }
                val target = destination.resolve(entry.name).canonicalFile
                require(target.path.startsWith(canonicalDestination.path + File.separator)) {
                    "Unsafe path in Gradle archive"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().buffered().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val DISTRIBUTION_ROOT = "https://downloads.gradle.org/distributions"
        private const val GRADLE_JAVA_17_21 = "8.12.1"
        private const val GRADLE_JAVA_25 = "9.2.1"
        private const val MAX_TOOLCHAIN_ENTRIES = 10_000
    }
}
