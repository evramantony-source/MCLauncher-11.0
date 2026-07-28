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
}
