package com.sigmabridge.app.presentation.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF229ED9),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F0FF),
    onPrimaryContainer = Color(0xFF00344D),
    secondary = Color(0xFF67506F),
    background = Color(0xFFFFFBFF),
    surface = Color(0xFFFFFBFF),
    surfaceVariant = Color(0xFFF1EAF3),
    onSurface = Color(0xFF1C1B1F),
    onSurfaceVariant = Color(0xFF49454F)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72C8F2),
    onPrimary = Color(0xFF00344D),
    primaryContainer = Color(0xFF075276),
    onPrimaryContainer = Color(0xFFD8F0FF),
    secondary = Color(0xFFD0BBD3),
    background = Color(0xFF111318),
    surface = Color(0xFF17191E),
    surfaceVariant = Color(0xFF2B2E35),
    onSurface = Color(0xFFE5E1E9),
    onSurfaceVariant = Color(0xFFC9C4CE)
)

@Composable
fun SigmaBridgeTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
