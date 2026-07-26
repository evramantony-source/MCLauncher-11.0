package com.mclauncher.minecraft

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Resolves Mojang/Fabric/Quilt-style inherited version JSON files. */
class VersionResolver(
    private val layout: MinecraftLayout,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun resolve(versionId: String): JsonObject = resolve(versionId, linkedSetOf())

    fun baseVersionId(versionId: String): String {
        var current = versionId
        val seen = linkedSetOf<String>()
        while (true) {
            require(seen.add(current)) { "Circular version inheritance: ${seen.joinToString(" -> ")} -> $current" }
            val file = layout.versionJson(current)
            require(file.isFile) { "Version metadata missing: ${file.absolutePath}" }
            val document = json.parseToJsonElement(file.readText()).jsonObject
            val parent = document["inheritsFrom"]?.jsonPrimitive?.content
            if (parent.isNullOrBlank()) return current
            current = parent
        }
    }

    private fun resolve(versionId: String, stack: MutableSet<String>): JsonObject {
        require(stack.add(versionId)) { "Circular version inheritance: ${stack.joinToString(" -> ")} -> $versionId" }
        val file = layout.versionJson(versionId)
        require(file.isFile) { "Version metadata missing: ${file.absolutePath}" }
        val child = json.parseToJsonElement(file.readText()).jsonObject
        val parentId = child["inheritsFrom"]?.jsonPrimitive?.content
        val result = if (parentId.isNullOrBlank()) child else merge(resolve(parentId, stack), child)
        stack.remove(versionId)
        return result
    }

    private fun merge(parent: JsonObject, child: JsonObject): JsonObject = buildJsonObject {
        parent.forEach { (key, value) -> put(key, value) }
        child.forEach { (key, value) ->
            when (key) {
                "libraries" -> put(key, mergeLibraries(parent[key] as? JsonArray, value as? JsonArray))
                "arguments" -> put(key, mergeArguments(parent[key] as? JsonObject, value as? JsonObject))
                "minecraftArguments" -> {
                    val p = parent[key]?.jsonPrimitive?.content.orEmpty()
                    val c = value.jsonPrimitive.content
                    put(key, listOf(p, c).filter(String::isNotBlank).joinToString(" "))
                }
                else -> put(key, value)
            }
        }
    }

    private fun mergeLibraries(parent: JsonArray?, child: JsonArray?): JsonArray {
        val ordered = linkedMapOf<String, JsonElement>()
        parent.orEmpty().forEach { element ->
            val name = (element as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: element.toString()
            ordered[name] = element
        }
        child.orEmpty().forEach { element ->
            val name = (element as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: element.toString()
            ordered[name] = element
        }
        return JsonArray(ordered.values.toList())
    }

    private fun mergeArguments(parent: JsonObject?, child: JsonObject?): JsonObject = buildJsonObject {
        val keys = (parent?.keys.orEmpty() + child?.keys.orEmpty()).toSet()
        keys.forEach { key ->
            val p = parent?.get(key)
            val c = child?.get(key)
            when {
                p is JsonArray && c is JsonArray -> put(key, buildJsonArray { p.forEach(::add); c.forEach(::add) })
                c != null -> put(key, c)
                p != null -> put(key, p)
            }
        }
    }
}
