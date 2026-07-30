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
    fun minecraft262OpenLtwSymbolFailureNamesTheCompatibilityBuild() {
        val result = CrashAnalyzer.analyzeText(
            """
            Resolved graphics renderer=openltw (requested=openltw)
            Prepared LWJGL OpenGL library /renderers/openltw/libltw.so
            FATAL ERROR: No context is current or a function that is not available
            at org.lwjgl.opengl.GL11C.nglGetFloatv(Native Method)
            """.trimIndent()
        )

        assertEquals("OpenLTW needs the 26.2 compatibility build", result?.title)
    }

    @Test
    fun explicitInvalidTokenStillReportsExpiredSession() {
        val result = CrashAnalyzer.analyzeText("Authentication failed: invalid token")

        assertEquals("Account session expired", result?.title)
    }

    @Test
    fun sodiumAndroidBlockIsNotMisreportedAsRendererFailure() {
        val result = CrashAnalyzer.analyzeText(
            """
            OpenGL Renderer: Adreno (TM) 710 | OpenGL ES 3.2
            java.io.UncheckedIOException: java.nio.file.AccessDeniedException:
            /sys/devices/system/cpu/bus_dcvs/DDR/cur_freq
            Sodium-PostlaunchChecks: Detected presence of environment variable POJAV_LAUNCHER
            java.lang.RuntimeException: It appears that you are using PojavLauncher,
            which is not supported when using Sodium. Please check your mods list.
            """.trimIndent()
        )

        assertEquals("Sodium blocked the Android launch", result?.title)
    }

    @Test
    fun unrelatedNotSupportedTextDoesNotBecomeOpenGlFailure() {
        val result = CrashAnalyzer.analyzeText(
            """
            OpenGL Version: 4.0.0 MobileGlues 1.3.5
            Optional telemetry feature is not supported
            """.trimIndent()
        )

        assertNull(result)
    }

    @Test
    fun staleOpenLtwManifestGetsSpecificDiagnosis() {
        val result = CrashAnalyzer.analyzeText(
            "java.lang.IllegalStateException: No Android OpenGL library matched " +
                "OpenLTW / LTW (MCLAUNCHER_RENDERER_TOKEN=opengles3_desktopgl_angle_vulkan)"
        )

        assertEquals("Renderer package metadata mismatch", result?.title)
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
