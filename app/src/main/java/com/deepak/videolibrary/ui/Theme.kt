package com.deepak.videolibrary.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cinema-inspired AMOLED palette: red accents on true black.
private val AmoledColors = darkColorScheme(
    primary = Color(0xFFE53935),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF651B1B),
    onPrimaryContainer = Color(0xFFFFDAD7),
    secondary = Color(0xFFFF7770),
    onSecondary = Color(0xFF330807),
    tertiary = Color(0xFFE53935),
    background = Color.Black,
    onBackground = Color(0xFFF4F4F5),
    surface = Color.Black,
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF181818),
    onSurfaceVariant = Color(0xFFB6B6BB),
    surfaceTint = Color(0xFFE53935),
    outline = Color(0xFF444449),
    error = Color(0xFFFF8A80),
)

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmoledColors, content = content)
}
