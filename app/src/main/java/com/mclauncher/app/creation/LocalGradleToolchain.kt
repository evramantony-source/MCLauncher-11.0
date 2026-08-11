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

/** Downloads a verified public Gradle distribution and launches Gradle inside
 * MCLauncher's bundled Android OpenJDK. A project's wrapper version is honored
 * when present; the wrapper script/JAR itself is never executed. */
class LocalGradleToolchain(private val context: Context) {
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val labRoot = context.filesDir.resolve("creation-lab")

    fun versionFor(projectDirectory: File, javaVersion: JavaVersion): String {
        val wrapper = projectDirectory.resolve("gradle/wrapper/gradle-wrapper.properties")
        val declared = wrapper.takeIf(File::isFile)?.readText()?.let { text ->
            GRADLE_DISTRIBUTION.find(text)?.groupValues?.getOrNull(1)
        }
        return declared ?: when (javaVersion) {
            JavaVersion.JAVA_8 -> GRADLE_JAVA_8
            JavaVersion.JAVA_17, JavaVersion.JAVA_21 -> GRADLE_JAVA_17_21
            JavaVersion.JAVA_25 -> GRADLE_JAVA_25
        }
    }

    fun ensureInstalled(version: String, onProgress: (String) -> Unit): File {
        require(version.matches(Regex("[0-9][0-9A-Za-z.-]{1,30}"))) { "Invalid Gradle version: $version" }
        val toolchains = labRoot.resolve("toolchains").apply { mkdirs() }
        val installation = toolchains.resolve("gradle-$version")
        val marker = installation.resolve(".mclauncher-complete")
        if (marker.isFile && installation.resolve("lib/gradle-launcher-$version.jar").isFile) return installation

        val archive = toolchains.resolve("gradle-$version-bin.zip")
        val archiveUrl = "$DISTRIBUTION_ROOT/gradle-$version-bin.zip"
        val expectedSha = downloadText("$archiveUrl.sha256").trim().lowercase()
        require(expectedSha.matches(Regex("[0-9a-f]{64}"))) { "Gradle published an invalid checksum" }
        if (!archive.isFile || sha256(archive) != expectedSha) {
            archive.delete()
            onProgress("Downloading verified Gradle $version (first build only)…")
            downloadFile(archiveUrl, archive, onProgress)
            require(sha256(archive) == expectedSha) { "Gradle download checksum did not match" }
        }

        onProgress("Installing the verified Gradle $version toolchain…")
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
        javaVersion: JavaVersion,
        statusFile: File,
        tasks: List<String>
    ): File {
        require(tasks.isNotEmpty()) { "Enter at least one Gradle task, such as build" }
        require(tasks.all { it.matches(Regex("[A-Za-z0-9_:.\\-]+")) }) {
            "Gradle tasks may contain letters, numbers, dots, colons, underscores and dashes"
        }
        val classpath = gradleHome.resolve("lib").walkTopDown()
            .filter { it.isFile && it.extension.equals("jar", ignoreCase = true) }
            .sortedBy(File::getAbsolutePath)
            .map(File::getAbsolutePath)
            .toList()
        require(classpath.isNotEmpty()) { "Local Gradle installation has no launcher libraries" }
        val initScript = writeStatusInitScript(projectDirectory, statusFile)
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
                "--init-script",
                initScript.absolutePath,
                "--project-dir",
                projectDirectory.absolutePath
            ) + tasks,
            jvmArguments = listOf(
                "-Xms128m",
                "-Xmx1536m",
                "-Dfile.encoding=UTF-8",
                "-Dorg.gradle.daemon=false",
                "-Dorg.gradle.parallel=false",
                "-Dorg.gradle.native=false",
                "-Dorg.gradle.vfs.watch=false",
                "-Dorg.gradle.workers.max=1",
                "-Dkotlin.compiler.execution.strategy=in-process",
                "-Dkotlin.daemon.enabled=false",
                "-Dgradle.user.home=${labRoot.resolve("gradle-cache").absolutePath}"
            ),
            logPath = File(statusFile.parentFile, "${statusFile.nameWithoutExtension}.log").absolutePath
        )
        val launchPlans = context.filesDir.resolve("minecraft/launch-plans").apply { mkdirs() }
        return launchPlans.resolve("${plan.id}.json").also { file ->
            file.writeText(json.encodeToString(plan))
        }
    }

    private fun writeStatusInitScript(projectDirectory: File, statusFile: File): File {
        val script = projectDirectory.resolve(".mclauncher/build-status.init.gradle").apply {
            parentFile?.mkdirs()
        }
        val statusPath = groovyString(statusFile.absolutePath)
        val logPath = groovyString(File(statusFile.parentFile, "${statusFile.nameWithoutExtension}.log").absolutePath)
        script.writeText(
            """
            import groovy.json.JsonOutput
            import java.io.PrintWriter
            import java.io.StringWriter

            gradle.buildFinished { result ->
                def statusFile = new File('$statusPath')
                def logFile = new File('$logPath')
                statusFile.parentFile.mkdirs()
                def failure = result.failure
                if (failure != null) {
                    def writer = new StringWriter()
                    failure.printStackTrace(new PrintWriter(writer))
                    logFile.appendText('\n--- Gradle failure ---\n' + writer.toString())
                }
                def payload = [
                    finished: true,
                    exitCode: failure == null ? 0 : 1,
                    error: failure == null ? null : failure.toString(),
                    logPath: failure == null ? null : logFile.absolutePath
                ]
                def temporary = new File(statusFile.parentFile, statusFile.name + '.part')
                temporary.text = JsonOutput.toJson(payload)
                if (statusFile.exists()) statusFile.delete()
                temporary.renameTo(statusFile)
            }
            """.trimIndent() + "\n"
        )
        return script
    }

    private fun groovyString(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private fun downloadText(url: String): String {
        val connection = open(url)
        val code = connection.responseCode
        require(code in 200..299) { "Could not download Gradle checksum (HTTP $code)" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun downloadFile(url: String, destination: File, onProgress: (String) -> Unit) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part").apply { delete() }
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
                            onProgress("Downloading Gradle $percent%")
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
        setRequestProperty("User-Agent", "MCLauncher-Code-Workspace")
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
                if (entry.isDirectory) target.mkdirs() else {
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
        private const val GRADLE_JAVA_8 = "6.9.4"
        private const val GRADLE_JAVA_17_21 = "8.12.1"
        private const val GRADLE_JAVA_25 = "9.2.1"
        private const val MAX_TOOLCHAIN_ENTRIES = 12_000
        private val GRADLE_DISTRIBUTION = Regex("gradle-([0-9][0-9A-Za-z.-]{1,30})-(?:bin|all)\\.zip")
    }
}
