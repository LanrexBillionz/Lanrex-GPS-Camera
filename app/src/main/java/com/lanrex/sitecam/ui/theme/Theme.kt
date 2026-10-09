package com.lanrex.sitecam.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Construction "safety orange" brand colours. */
private val Light = lightColorScheme(
    primary = Color(0xFFB33F00),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF52606D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9E3F0),
    onSecondaryContainer = Color(0xFF0F1D2A),
    tertiary = Color(0xFF2E6B30),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB4F1AE),
    onTertiaryContainer = Color(0xFF002106),
    background = Color(0xFFFFFBF8),
    surface = Color(0xFFFFFBF8),
    surfaceVariant = Color(0xFFF4DED5),
    onSurfaceVariant = Color(0xFF53433D),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB596),
    onPrimary = Color(0xFF5E1A00),
    primaryContainer = Color(0xFF862E00),
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = Color(0xFFBDC7D4),
    onSecondary = Color(0xFF26323E),
    secondaryContainer = Color(0xFF3C4855),
    onSecondaryContainer = Color(0xFFD9E3F0),
    tertiary = Color(0xFF99D594),
    onTertiary = Color(0xFF00390F),
    tertiaryContainer = Color(0xFF14521B),
    onTertiaryContainer = Color(0xFFB4F1AE),
    background = Color(0xFF1A1210),
    surface = Color(0xFF1A1210),
    surfaceVariant = Color(0xFF53433D),
    onSurfaceVariant = Color(0xFFD8C2B9),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

@Composable
fun SiteCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
