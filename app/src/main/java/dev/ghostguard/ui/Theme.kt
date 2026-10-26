package dev.ghostguard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C9CF5)
private val Danger = Color(0xFFE2726E)

private val DarkColors = darkColorScheme(
    primary = Accent,
    secondary = Accent,
    error = Danger,
    background = Color(0xFF101216),
    surface = Color(0xFF171A20),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF3A5BD9),
    secondary = Color(0xFF3A5BD9),
    error = Color(0xFFB3261E),
)

@Composable
fun GhostGuardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
