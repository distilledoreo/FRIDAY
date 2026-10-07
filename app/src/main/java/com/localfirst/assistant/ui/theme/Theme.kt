package com.localfirst.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFECECEC),
    onSecondaryContainer = Color(0xFF171717),
    primaryContainer = Color(0xFFECECEC),
    onPrimaryContainer = Color(0xFF171717),
    background = Color.White,
    onBackground = Color(0xFF171717),
    onSurface = Color(0xFF171717),
    onSurfaceVariant = Color(0xFF676767),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFECECEC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF4F4F4),
    onPrimary = Color(0xFF171717),
    secondaryContainer = Color(0xFF383838),
    onSecondaryContainer = Color(0xFFF4F4F4),
    primaryContainer = Color(0xFF383838),
    onPrimaryContainer = Color(0xFFF4F4F4),
    onBackground = Color(0xFFF4F4F4),
    onSurface = Color(0xFFF4F4F4),
    onSurfaceVariant = Color(0xFFB4B4B4),
    surface = Color(0xFF212121),
    background = Color(0xFF212121),
    surfaceContainer = Color(0xFF2F2F2F),
    surfaceContainerHigh = Color(0xFF383838),
)

/** Follows system light/dark mode with a consistent neutral chat palette. */
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
