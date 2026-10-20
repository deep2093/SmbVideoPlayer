package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext

data class LibraryTheme(val id: String, val label: String, val background: Color, val surface: Color, val card: Color, val accent: Color, val glass: Boolean = false)
val libraryThemes = listOf(
    LibraryTheme("CHARCOAL", "Dark grey", Color(0xFF0D0D0D), Color(0xFF171717), Color(0xFF262626), Color(0xFFFF837A)),
    LibraryTheme("AMOLED", "AMOLED black", Color.Black, Color.Black, Color(0xFF101010), Color(0xFFFF837A)),
    LibraryTheme("SLATE", "Midnight blue", Color(0xFF141C29), Color(0xFF1B2738), Color(0xFF26364D), Color(0xFF3569AA)),
    LibraryTheme("FOREST", "Forest", Color(0xFF14221D), Color(0xFF1D3028), Color(0xFF2A4036), Color(0xFF26715B)),
    LibraryTheme("PLUM", "Plum", Color(0xFF241A2B), Color(0xFF30233A), Color(0xFF40304D), Color(0xFF884EAA)),
    // Liquid Glass: a deep gradient behind translucent, softly lit surfaces with a thin bright edge.
    LibraryTheme("LIQUID", "Liquid Glass", Color.Transparent, Color(0x26FFFFFF), Color(0x38FFFFFF), Color(0xFF8CD4FF), glass = true),
)

@Composable
private fun rememberThemeChoice(): State<String> {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    fun read() = prefs.getString("theme", "CHARCOAL")?.takeIf { name -> libraryThemes.any { it.id == name } } ?: "CHARCOAL"
    val selected = remember { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "theme")selected.value = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return selected
}

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    val choice by rememberThemeChoice()
    val theme = libraryThemes.first { it.id == choice }
    val colors = darkColorScheme(
        primary = Color(0xFFF5F5F5),
        onPrimary = Color(0xFF101010),
        primaryContainer = theme.card,
        onPrimaryContainer = Color.White,
        secondary = Color(0xFFF5F5F5),
        onSecondary = Color(0xFF101010),
        secondaryContainer = theme.card,
        onSecondaryContainer = Color.White,
        tertiary = theme.accent,
        onTertiary = Color.Black,
        tertiaryContainer = theme.card,
        onTertiaryContainer = Color.White,
        background = theme.background,
        onBackground = Color.White,
        surface = theme.surface,
        onSurface = Color.White,
        surfaceVariant = theme.card,
        onSurfaceVariant = Color(0xFFB8B8B8),
        surfaceTint = Color.Transparent,
        outline = if (theme.glass) Color(0x80FFFFFF) else Color(0xFF777777),
        outlineVariant = if (theme.glass) Color(0x40FFFFFF) else Color(0xFF333333),
        error = Color(0xFFFF6B6B),
        onError = Color.Black,
        errorContainer = Color(0xFF452323),
        onErrorContainer = Color.White,
        inverseSurface = Color.White,
        inverseOnSurface = theme.background,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        surfaceContainer = if (theme.glass) Color(0xFF1B2A44) else theme.surface,
        surfaceContainerLow = theme.background,
        surfaceContainerHigh = if (theme.glass) Color(0xFF22345A) else theme.card,
        surfaceContainerHighest = if (theme.glass) Color(0xFF22345A) else theme.card,
        surfaceContainerLowest = theme.background,
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp, letterSpacing = (-0.4).sp),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
            labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum"),
        ),
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(16.dp)),
        content = {
            if (theme.glass) {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(listOf(Color(0xFF0B1B3A), Color(0xFF2A1F5C), Color(0xFF0E4A5E))),
                    ),
                ) { content() }
            } else {
                content()
            }
        },
    )
}

@Composable
internal fun ThemeSettings() {
    val context = LocalContext.current
    val choice by rememberThemeChoice()
    var expanded by remember { mutableStateOf(false) }
    Text(stringResource(R.string.ui_appearance_41def7a), style = MaterialTheme.typography.titleMedium)
    Box {
        OutlinedButton(onClick = {
            expanded = true
        }, modifier = Modifier.fillMaxWidth()) { Text("Theme: ${libraryThemes.first {it.id == choice}.label}") }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            libraryThemes.forEach { theme ->
                DropdownMenuItem(text = { Text(theme.label + if (theme.id == choice)" ✓" else "") }, onClick = {
                    context
                        .getSharedPreferences("appearance", Context.MODE_PRIVATE)
                        .edit()
                        .putString("theme", theme.id)
                        .apply()
                    expanded = false
                })
            }
        }
    }
}
