package com.mclauncher.minecraft

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleEvaluatorTest {
    private val rules = Json.parseToJsonElement(
        """[{"action":"allow","os":{"name":"linux","arch":"x86_64"}}]"""
    ).jsonArray

    @Test
    fun `architecture-specific rules use the selected APK ABI`() {
        assertTrue(RuleEvaluator().withArchitecture("x86_64").allows(rules))
        assertFalse(RuleEvaluator().withArchitecture("aarch64").allows(rules))
    }

    @Test
    fun `empty rules remain allowed`() {
        assertTrue(RuleEvaluator().allows(null))
    }
}
