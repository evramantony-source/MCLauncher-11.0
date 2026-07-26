package com.mclauncher.minecraft

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RuleEvaluator(
    private val osName: String = "linux",
    private val architecture: String = "aarch64",
    private val osVersion: String = System.getProperty("os.version") ?: "",
    private val features: Map<String, Boolean> = mapOf(
        "has_custom_resolution" to true,
        "is_demo_user" to false,
        "has_quick_plays_support" to false,
        "is_quick_play_singleplayer" to false,
        "is_quick_play_multiplayer" to false,
        "is_quick_play_realms" to false
    )
) {
    fun withArchitecture(value: String): RuleEvaluator = RuleEvaluator(
        osName = osName,
        architecture = value,
        osVersion = osVersion,
        features = features
    )

    fun allows(rules: JsonArray?): Boolean {
        if (rules == null || rules.isEmpty()) return true
        var allowed = false
        for (element in rules) {
            val rule = element.jsonObject
            if (matches(rule)) {
                allowed = rule["action"]?.jsonPrimitive?.content == "allow"
            }
        }
        return allowed
    }

    private fun matches(rule: JsonObject): Boolean {
        val os = rule["os"] as? JsonObject
        if (os != null) {
            val name = os["name"]?.jsonPrimitive?.content
            if (name != null && name != osName) return false
            val arch = os["arch"]?.jsonPrimitive?.content
            if (arch != null && arch != architecture) return false
            val versionRegex = os["version"]?.jsonPrimitive?.content
            if (versionRegex != null && !Regex(versionRegex).containsMatchIn(osVersion)) return false
        }

        val requiredFeatures = rule["features"] as? JsonObject
        if (requiredFeatures != null) {
            for ((name, expectedValue) in requiredFeatures) {
                val expected = expectedValue.jsonPrimitive.content.toBooleanStrictOrNull() ?: false
                if ((features[name] ?: false) != expected) return false
            }
        }
        return true
    }
}
