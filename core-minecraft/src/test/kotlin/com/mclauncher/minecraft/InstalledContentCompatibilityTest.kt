package com.mclauncher.minecraft

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstalledContentCompatibilityTest {
    @Test
    fun alpha01IndexWithoutIconStillLoads() {
        val item = Json.decodeFromString<InstalledContent>(
            """
            {
              "projectId": "fabric-api",
              "versionId": "example-version",
              "contentType": "MOD",
              "fileName": "fabric-api.jar",
              "title": "Fabric API",
              "versionNumber": "1.0",
              "gameVersion": "1.21.11"
            }
            """.trimIndent()
        )

        assertEquals("Fabric API", item.title)
        assertNull(item.iconUrl)
    }
}
