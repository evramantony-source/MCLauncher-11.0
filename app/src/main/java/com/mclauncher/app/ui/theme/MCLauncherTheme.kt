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
    primaryContainer = Color(0xFF124326),
    onPrimaryContainer = Color(0xFFD2FFE1),
    secondary = Color(0xFF62E996),
    onSecondary = Color(0xFF00210E),
    secondaryContainer = Color(0xFF124326),
    onSecondaryContainer = Color(0xFFD2FFE1),
    tertiary = Color(0xFF62E996),
    onTertiary = Color(0xFF00210E),
    tertiaryContainer = Color(0xFF124326),
    onTertiaryContainer = Color(0xFFD2FFE1),
    background = Color(0xFF06080B),
    onBackground = Color.White,
    surface = Color(0xFF0B0E12),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF151A1F),
    onSurfaceVariant = Color(0xFFB8C2CB),
    surfaceDim = Color(0xFF06080B),
    surfaceBright = Color(0xFF252A30),
    surfaceContainerLowest = Color(0xFF030507),
    surfaceContainerLow = Color(0xFF090C10),
    surfaceContainer = Color(0xFF0D1115),
    surfaceContainerHigh = Color(0xFF12161B),
    surfaceContainerHighest = Color(0xFF181D23),
    outline = Color(0xFF3C454D),
    outlineVariant = Color(0xFF252D34),
    inverseSurface = Color(0xFFE2E7EB),
    inverseOnSurface = Color(0xFF15191D),
    inversePrimary = Color(0xFF007D3B),
    scrim = Color.Black,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

private val LauncherLightColors = lightColorScheme(
    primary = Color(0xFF00AF5C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7FFEB),
    onPrimaryContainer = Color(0xFF00210E),
    secondary = Color(0xFF007D3B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC8FAD9),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFF007D3B),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC8FAD9),
    onTertiaryContainer = Color(0xFF00210E),
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
