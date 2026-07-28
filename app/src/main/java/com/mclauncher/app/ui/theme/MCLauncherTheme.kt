package com.mclauncher.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.mclauncher.model.LauncherThemeMode

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

private val LauncherLightColors = lightColorScheme(
    primary = Color(0xFF167844),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F2CB),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF4E6354),
    onSecondary = Color.White,
    background = Color(0xFFF7FAF6),
    onBackground = Color(0xFF191C19),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C19),
    surfaceVariant = Color(0xFFE1E9E1),
    onSurfaceVariant = Color(0xFF414942),
    outline = Color(0xFF717972),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

@Composable
fun MCLauncherTheme(
    themeMode: LauncherThemeMode = LauncherThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themeMode) {
        LauncherThemeMode.SYSTEM -> isSystemInDarkTheme()
        LauncherThemeMode.DARK -> true
        LauncherThemeMode.LIGHT -> false
    }
    val colors = if (darkTheme) LauncherDarkColors else LauncherLightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colors.background.toArgb()
            window.navigationBarColor = colors.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content
    )
}
