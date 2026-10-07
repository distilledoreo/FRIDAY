package com.localfirst.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF10A37F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6F5EC),
    onPrimaryContainer = Color(0xFF00382A),
    surface = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF4F4F4),
    surfaceContainerHigh = Color(0xFFECECEC),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF19C394),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B4F3E),
    onPrimaryContainer = Color(0xFFD6F5EC),
    surface = Color(0xFF212121),
    background = Color(0xFF212121),
    surfaceContainer = Color(0xFF2F2F2F),
    surfaceContainerHigh = Color(0xFF383838),
)

/** Follows the system light/dark setting, and Material You colors on Android 12+. */
@Composable
fun AssistantTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
