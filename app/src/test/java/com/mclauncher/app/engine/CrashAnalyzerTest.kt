package com.mclauncher.app.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CrashAnalyzerTest {
    @Test
    fun normalAuthenticationServiceLoggerIsNotAnExpiredSession() {
        val result = CrashAnalyzer.analyzeText(
            """
            <log4j:Event logger="com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService" level="INFO">
              <log4j:Message>Environment: Environment[sessionHost=https://sessionserver.mojang.com]</log4j:Message>
            </log4j:Event>
            Setting user: Player
            """.trimIndent()
        )

        assertNull(result)
    }

    @Test
    fun missingModernOpenGlFunctionHasSpecificDiagnosis() {
        val result = CrashAnalyzer.analyzeText(
            "FATAL ERROR: No context is current or a function that is not available\n" +
                "at org.lwjgl.opengl.GL33C.nglGenSamplers"
        )

        assertEquals("OpenGL renderer too old", result?.title)
    }

    @Test
    fun explicitInvalidTokenStillReportsExpiredSession() {
        val result = CrashAnalyzer.analyzeText("Authentication failed: invalid token")

        assertEquals("Account session expired", result?.title)
    }

    @Test
    fun jnaVersionSelectsMatchingAndroidNativeAbi() {
        assertEquals(
            "jna-7",
            jnaNativeDirectoryName(
                listOf(
                    "/libraries/net/java/dev/jna/jna-platform/5.17.0/jna-platform-5.17.0.jar",
                    "/libraries/net/java/dev/jna/jna/5.17.0/jna-5.17.0.jar"
                )
            )
        )
        assertEquals(
            "jna-6",
            jnaNativeDirectoryName(
                listOf("/libraries/net/java/dev/jna/jna/5.13.0/jna-5.13.0.jar")
            )
        )
        assertNull(
            jnaNativeDirectoryName(
                listOf("/libraries/net/java/dev/jna/jna/4.5.0/jna-4.5.0.jar")
            )
        )
    }
}
