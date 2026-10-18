package com.deepak.videolibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF00296B),
    background = Color.Black,
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF0A0A0D),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF16161B),
    onSurfaceVariant = Color(0xFFA9A9B3),
    error = Color(0xFFFF8A80),
)

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledColors, content = content)
}
