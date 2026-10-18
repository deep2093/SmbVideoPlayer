package com.deepak.videolibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AmoledColors = darkColorScheme(
    primary = Color(0xFF8DBBFF),
    onPrimary = Color(0xFF051427),
    background = Color.Black,
    onBackground = Color(0xFFE6E6EA),
    surface = Color(0xFF090B0F),
    onSurface = Color(0xFFE6E6EA),
    surfaceVariant = Color(0xFF171B23),
    onSurfaceVariant = Color(0xFFABB5C6),
    secondary = Color(0xFF75C8A3),
    tertiary = Color(0xFFE53935),
    outline = Color(0xFF353C48),
    error = Color(0xFFFF8A80),
)

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledColors, content = content)
}
