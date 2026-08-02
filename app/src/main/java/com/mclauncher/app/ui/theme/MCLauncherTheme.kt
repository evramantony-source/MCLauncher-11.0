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
    primary = Color(0xFF1BD96A),
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF15482C),
    onPrimaryContainer = Color(0xFFB8FAD2),
    secondary = Color(0xFFB0BAC5),
    onSecondary = Color(0xFF202329),
    background = Color(0xFF16181C),
    onBackground = Color.White,
    surface = Color(0xFF1D1F23),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF27292E),
    onSurfaceVariant = Color(0xFFB0BAC5),
    outline = Color(0xFF42444A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LauncherLightColors = lightColorScheme(
    primary = Color(0xFF00AF5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7FFEB),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF484D54),
    onSecondary = Color.White,
    background = Color(0xFFEBEBEB),
    onBackground = Color(0xFF1A202C),
    surface = Color(0xFFF5F5F5),
    onSurface = Color(0xFF1A202C),
    surfaceVariant = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFF484D54),
    outline = Color(0xFFDDDDDD),
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
