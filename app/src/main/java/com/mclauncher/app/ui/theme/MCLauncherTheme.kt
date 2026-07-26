package com.mclauncher.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

private val LauncherDarkColors = darkColorScheme(
    primary = Color(0xFF43C97A),
    onPrimary = Color(0xFF06210F),
    primaryContainer = Color(0xFF153C24),
    onPrimaryContainer = Color(0xFFB5F3CA),
    secondary = Color(0xFFB4C8B9),
    onSecondary = Color(0xFF203429),
    background = Color(0xFF111311),
    onBackground = Color(0xFFE2E3DE),
    surface = Color(0xFF171A17),
    onSurface = Color(0xFFE2E3DE),
    surfaceVariant = Color(0xFF222622),
    onSurfaceVariant = Color(0xFFC2C8C1),
    outline = Color(0xFF3B423C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun MCLauncherTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = LauncherDarkColors.background.toArgb()
            window.navigationBarColor = LauncherDarkColors.background.toArgb()
        }
    }
    MaterialTheme(
        colorScheme = LauncherDarkColors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
