package com.mclauncher.app.creation

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class AiProjectOutput(val label: String, val extension: String, val mimeType: String) {
    FABRIC_JAR("Fabric mod JAR", "jar", "application/java-archive"),
    SHADER_ZIP("Shader pack ZIP", "zip", "application/zip")
}

data class AiBuilderSettings(
    val apiKey: String = "",
    val model: String = "gpt-5-mini",
    val githubToken: String = "",
    val repository: String = "evramantony-source/MCLauncher-11.0",
    val builderRef: String = "agent/alpha13-exact-loader-green-touch"
)

private data class GeneratedFile(val path: String, val content: String)
private data class GeneratedProject(val name: String, val files: List<GeneratedFile>)

class AiProjectBuilder(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val credentials = AiCredentialStore(context)

    fun loadSettings(): AiBuilderSettings = credentials.load()
    fun saveSettings(settings: AiBuilderSettings) = credentials.save(settings)

    suspend fun generate(
        destination: Uri,
        prompt: String,
        output: AiProjectOutput,
        minecraftVersion: String,
        settings: AiBuilderSettings,
        onProgress: (String) -> Unit
    ) {
        require(prompt.isNotBlank()) { "Describe what you want the AI to create" }
        require(settings.apiKey.isNotBlank()) { "Enter your AI API key" }
        saveSettings(settings)
        onProgress("AI is designing the project…")
        val project = generateProject(prompt, output, minecraftVersion, settings)
        if (output == AiProjectOutput.SHADER_ZIP) {
            onProgress("Validating and packaging shader pack…")
            val bytes = zip(project.files)
            require(project.files.any { it.path == "pack.mcmeta" }) { "AI output is missing pack.mcmeta" }
            context.contentResolver.openOutputStream(destination, "w")!!.use { it.write(bytes) }
            return
        }

        require(settings.githubToken.isNotBlank()) { "Enter a GitHub token so the trusted builder can compile the JAR" }
        onProgress("Sending generated source to the trusted JAR builder…")
        val requestId = UUID.randomUUID().toString().replace("-", "")
        dispatchBuild(project, requestId, settings)
        onProgress("GitHub is compiling the Fabric mod…")
        val jarBytes = waitForJar(requestId, settings, onProgress)
        context.contentResolver.openOutputStream(destination, "w")!!.use { it.write(jarBytes) }
    }

    private fun generateProject(
        prompt: String,
        output: AiProjectOutput,
        minecraftVersion: String,
        settings: AiBuilderSettings
    ): GeneratedProject {
        val target = if (output == AiProjectOutput.FABRIC_JAR) {
            """Create a complete compile-ready Fabric mod for Minecraft $minecraftVersion. Include settings.gradle, build.gradle, gradle.properties, fabric.mod.json, Java sources, resources, recipes and assets required by the request. Use Fabric Loom 1.10-SNAPSHOT, Fabric Loader 0.19.3 and Java 21. Do not include a Gradle wrapper, binaries, base64 data, network calls, or code outside the project."""
        } else {
            """Create a complete Minecraft shader pack ZIP project for Minecraft $minecraftVersion. Include pack.mcmeta and all required shader files. Do not include binaries, base64 data, network calls, or files outside the pack."""
        }
        val body = buildJsonObject {
            put("model", settings.model.ifBlank { "gpt-5-mini" })
            put("response_format", buildJsonObject { put("type", "json_object") })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", "$target Return only one JSON object: {\"name\":\"safe-project-name\",\"files\":[{\"path\":\"relative/path\",\"content\":\"complete text\"}]}. Paths must be relative and use forward slashes.")
                })
                add(buildJsonObject { put("role", "user"); put("content", prompt) })
            })
        }
        val response = request(
            "https://api.openai.com/v1/chat/completions",
            "POST",
            json.encodeToString(JsonObject.serializer(), body),
            mapOf("Authorization" to "Bearer ${settings.apiKey}")
        )
        val root = json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: error(root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "AI provider returned no project")
        val projectJson = json.parseToJsonElement(content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()).jsonObject
        val name = safeName(projectJson["name"]?.jsonPrimitive?.contentOrNull ?: "mcl-ai-project")
        val files = projectJson["files"]?.jsonArray?.map { element ->
            val file = element.jsonObject
            val path = safePath(file["path"]?.jsonPrimitive?.contentOrNull ?: error("AI returned a file without a path"))
            GeneratedFile(path, file["content"]?.jsonPrimitive?.contentOrNull ?: "")
        }.orEmpty()
        require(files.isNotEmpty()) { "AI returned an empty project" }
        require(files.sumOf { it.content.length } <= 42_000) { "Generated project is too large for the GitHub workflow input" }
        return GeneratedProject(name, files.distinctBy { it.path })
    }

    private fun dispatchBuild(project: GeneratedProject, requestId: String, settings: AiBuilderSettings) {
        val projectJson = buildJsonObject {
            put("name", project.name)
            put("files", buildJsonArray {
                project.files.forEach { file -> add(buildJsonObject { put("path", file.path); put("content", file.content) }) }
            })
        }
        val payload = Base64.getEncoder().encodeToString(json.encodeToString(JsonObject.serializer(), projectJson).toByteArray())
        require(payload.length <= 60_000) { "Generated project exceeds GitHub workflow input limits" }
        val body = buildJsonObject {
            put("ref", settings.builderRef)
            put("inputs", buildJsonObject { put("request_id", requestId); put("source_payload", payload) })
        }
        request(
            "https://api.github.com/repos/${settings.repository}/actions/workflows/ai-mod-builder.yml/dispatches",
            "POST",
            json.encodeToString(JsonObject.serializer(), body),
            githubHeaders(settings.githubToken)
        )
    }

    private suspend fun waitForJar(
        requestId: String,
        settings: AiBuilderSettings,
        onProgress: (String) -> Unit
    ): ByteArray {
        val tag = "ai-build-$requestId"
        repeat(90) { attempt ->
            delay(10_000)
            val connection = open("https://api.github.com/repos/${settings.repository}/releases/tags/$tag", "GET", githubHeaders(settings.githubToken))
            val code = connection.responseCode
            if (code == 404) {
                connection.disconnect()
                if (attempt % 3 == 2) onProgress("Still compiling… ${((attempt + 1) * 10) / 60} min")
                return@repeat
            }
            val response = readResponse(connection, code)
            require(code in 200..299) { githubError(response, code) }
            val release = json.parseToJsonElement(response).jsonObject
            val asset = release["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull?.endsWith(".jar") == true }
            if (asset != null) {
                val url = asset["url"]!!.jsonPrimitive.content
                return requestBytes(url, githubHeaders(settings.githubToken) + ("Accept" to "application/octet-stream"))
            }
            val logUrl = release["html_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            error("The generated mod did not compile. Build log: $logUrl")
        }
        error("The JAR build timed out after 15 minutes. Check the Actions tab in ${settings.repository}")
    }

    private fun zip(files: List<GeneratedFile>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip ->
            files.forEach { file -> zip.putNextEntry(ZipEntry(file.path)); zip.write(file.content.toByteArray()); zip.closeEntry() }
        }
        bytes.toByteArray()
    }

    private fun request(url: String, method: String, body: String?, headers: Map<String, String>): String {
        val connection = open(url, method, headers)
        if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        val response = readResponse(connection, code)
        require(code in 200..299) { if (url.contains("github")) githubError(response, code) else providerError(response, code) }
        return response
    }

    private fun requestBytes(url: String, headers: Map<String, String>): ByteArray {
        val connection = open(url, "GET", headers)
        val code = connection.responseCode
        require(code in 200..299) { "Could not download built JAR (HTTP $code)" }
        return connection.inputStream.use { it.readBytes() }.also { connection.disconnect() }
    }

    private fun open(url: String, method: String, headers: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 30_000; readTimeout = 120_000
            doInput = true; doOutput = method == "POST" || method == "PUT"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "MCLauncher-11-AI-Workshop")
            headers.forEach(::setRequestProperty)
        }

    private fun readResponse(connection: HttpURLConnection, code: Int): String =
        (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            .also { connection.disconnect() }

    private fun providerError(response: String, code: Int): String = runCatching {
        json.parseToJsonElement(response).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content
    }.getOrDefault("AI provider request failed (HTTP $code)")

    private fun githubError(response: String, code: Int): String = runCatching {
        json.parseToJsonElement(response).jsonObject["message"]!!.jsonPrimitive.content
    }.getOrDefault("GitHub builder request failed (HTTP $code)")

    private fun githubHeaders(token: String) = mapOf(
        "Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28"
    )

    private fun safeName(value: String) = value.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.', '_').take(60).ifBlank { "mcl-ai-project" }
    private fun safePath(value: String): String {
        val path = value.replace('\\', '/').trimStart('/')
        require(path.isNotBlank() && path.length <= 180 && path.split('/').none { it == ".." || it.isBlank() }) { "AI returned an unsafe file path" }
        return path
    }
}

private class AiCredentialStore(context: Context) {
    private val preferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "mclauncher-ai", key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }.getOrElse { context.getSharedPreferences("mclauncher-ai-fallback", Context.MODE_PRIVATE) }

    fun load() = AiBuilderSettings(
        apiKey = preferences.getString("api-key", "").orEmpty(),
        model = preferences.getString("model", "gpt-5-mini").orEmpty(),
        githubToken = preferences.getString("github-token", "").orEmpty(),
        repository = preferences.getString("repository", "evramantony-source/MCLauncher-11.0").orEmpty(),
        builderRef = preferences.getString("builder-ref", "agent/alpha13-exact-loader-green-touch").orEmpty()
    )
    fun save(value: AiBuilderSettings) { preferences.edit().putString("api-key", value.apiKey).putString("model", value.model).putString("github-token", value.githubToken).putString("repository", value.repository).putString("builder-ref", value.builderRef).apply() }
}
