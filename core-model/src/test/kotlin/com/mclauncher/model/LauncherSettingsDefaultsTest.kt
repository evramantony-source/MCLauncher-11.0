package com.mclauncher.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LauncherSettingsDefaultsTest {
    @Test
    fun mobileFriendlyInputAndThemeDefaultsAreEnabled() {
        val settings = LauncherSettings()

        assertEquals(LauncherThemeMode.SYSTEM, settings.themeMode)
        assertEquals(TouchLookMode.SWIPE, settings.touchLookMode)
        assertTrue(settings.virtualMouseEnabled)
        assertTrue(settings.movementJoystickEnabled)
    }

    @Test
    fun defaultLayoutIncludesCoreGameplayActions() {
        val ids = DefaultControls.layout().mapTo(mutableSetOf(), ControlElement::id)

        assertTrue("jump" in ids)
        assertTrue("sneak" in ids)
        assertTrue("sprint" in ids)
        assertTrue("attack" in ids)
        assertTrue("use" in ids)
        assertTrue("inventory" in ids)
    }

    @Test
    fun instanceSettingsInheritGlobalValuesUntilEnabled() {
        val global = LauncherSettings(
            renderer = Renderer.MOBILE_GLUES,
            memoryMb = 3584,
            fpsLimit = 90
        )

        assertEquals(global, InstanceLaunchSettings().applyTo(global))
    }

    @Test
    fun enabledInstanceSettingsOverrideOnlyTheirLaunchValues() {
        val global = LauncherSettings(
            renderer = Renderer.MOBILE_GLUES,
            graphicsDriver = GraphicsDriver.SYSTEM,
            minecraftGraphicsApi = MinecraftGraphicsApi.DEFAULT,
            memoryMb = 2048,
            width = 1280,
            height = 720,
            themeMode = LauncherThemeMode.LIGHT
        )
        val effective = InstanceLaunchSettings(
            enabled = true,
            renderer = Renderer.OPEN_LTW,
            minecraftGraphicsApi = MinecraftGraphicsApi.OPENGL,
            memoryMb = 3584,
            width = 1600,
            height = 900
        ).applyTo(global)

        assertEquals(Renderer.OPEN_LTW, effective.renderer)
        assertEquals(3584, effective.memoryMb)
        assertEquals(1600, effective.width)
        assertEquals(900, effective.height)
        assertEquals(GraphicsDriver.SYSTEM, effective.graphicsDriver)
        assertEquals(MinecraftGraphicsApi.OPENGL, effective.minecraftGraphicsApi)
        assertEquals(LauncherThemeMode.LIGHT, effective.themeMode)
    }
}
