package com.terrynamic.opendisplay.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Color(0xFFD8D8D8),
    onPrimary = Color(0xFF111111),
    background = Color(0xFF0B0B0B),
    onBackground = Color(0xFFE8E8E8),
    surface = Color(0xFF161616),
    onSurface = Color(0xFFE8E8E8),
    surfaceVariant = Color(0xFF222222),
    onSurfaceVariant = Color(0xFFB8B8B8),
    outline = Color(0xFF3A3A3A),
)

@Composable
fun ReceiverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
