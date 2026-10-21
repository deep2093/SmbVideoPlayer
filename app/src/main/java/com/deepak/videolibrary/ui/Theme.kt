package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** A soft radial light painted on a glass theme's wallpaper (positions and radius are fractions of the screen). */
data class WallpaperGlow(val x: Float, val y: Float, val radius: Float, val color: Color)

data class LibraryTheme(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val card: Color,
    val accent: Color,
    val glass: Boolean = false,
    /** Glass themes: wallpaper gradient (top-left to bottom-right) and its glows. */
    val wallpaper: List<Color> = emptyList(),
    val glows: List<WallpaperGlow> = emptyList(),
    /** Glass themes: tint laid over the blurred content behind floating bars, and the opaque menu colour. */
    val glassTint: Color = Color.Transparent,
    val menu: Color = Color.Unspecified,
    /** Light themes use dark text and a light colour scheme. */
    val light: Boolean = false,
)

val libraryThemes = listOf(
    LibraryTheme("CHARCOAL", "Dark grey", Color(0xFF0D0D0D), Color(0xFF171717), Color(0xFF262626), Color(0xFFFF837A)),
    LibraryTheme("AMOLED", "AMOLED black", Color.Black, Color.Black, Color(0xFF101010), Color(0xFFFF837A)),
    LibraryTheme("SLATE", "Midnight blue", Color(0xFF141C29), Color(0xFF1B2738), Color(0xFF26364D), Color(0xFF3569AA)),
    LibraryTheme("FOREST", "Forest", Color(0xFF14221D), Color(0xFF1D3028), Color(0xFF2A4036), Color(0xFF26715B)),
    LibraryTheme("PLUM", "Plum", Color(0xFF241A2B), Color(0xFF30233A), Color(0xFF40304D), Color(0xFF884EAA)),
    // The first glass theme, renamed: a deep navy, indigo and teal gradient under smoky glass.
    // (The id stays "LIQUID" so people who picked it keep it.)
    LibraryTheme(
        "LIQUID", "Aurora Glass", Color.Transparent, Color(0x2E060B22), Color(0x24FFFFFF), Color(0xFF8CD4FF),
        glass = true,
        wallpaper = listOf(Color(0xFF0B1B3A), Color(0xFF2A1F5C), Color(0xFF0E4A5E)),
        glows = listOf(WallpaperGlow(0.85f, 0.1f, 0.7f, Color(0x403FB8D6)), WallpaperGlow(0.1f, 0.85f, 0.8f, Color(0x40653CB4))),
        glassTint = Color(0x8C14112E),
        menu = Color(0xFF1E1E44),
    ),
    // Apple-style Liquid Glass on a creamy white wallpaper: warm ivory with soft light, clear frosted glass that
    // blurs what is behind it, a fine specular edge and floating capsule controls. Text is dark.
    LibraryTheme(
        "LIQUID_GLASS", "Liquid Glass", Color.Transparent, Color(0x80FFFFFF), Color(0xB3FFFFFF), Color(0xFF0A6CD6),
        glass = true,
        wallpaper = listOf(Color(0xFFFFFCF3), Color(0xFFFAF3E3), Color(0xFFFFF8EA), Color(0xFFF4EBD8)),
        glows = listOf(
            WallpaperGlow(0.9f, 0.05f, 0.7f, Color(0x40FFD9A8)),
            WallpaperGlow(0.05f, 0.6f, 0.7f, Color(0x33BFDDF7)),
            WallpaperGlow(0.6f, 1.0f, 0.8f, Color(0x33F6C9C9)),
            WallpaperGlow(0.35f, 0.25f, 0.45f, Color(0x66FFFFFF)),
        ),
        glassTint = Color(0x99FFFBF0),
        menu = Color(0xFFFFFBF1),
        light = true,
    ),
)

/** The active theme, for components that draw glass effects. */
val LocalLibraryTheme = staticCompositionLocalOf { libraryThemes.first() }

/** Paints a glass theme's wallpaper: the base gradient, its glows and a faint diagonal sheen. */
internal fun DrawScope.drawGlassWallpaper(theme: LibraryTheme) {
    drawRect(Brush.linearGradient(theme.wallpaper, start = Offset.Zero, end = Offset(size.width, size.height)))
    theme.glows.forEach { glow ->
        drawRect(
            Brush.radialGradient(
                listOf(glow.color, Color.Transparent),
                center = Offset(size.width * glow.x, size.height * glow.y),
                radius = size.maxDimension * glow.radius,
            ),
        )
    }
    drawRect(
        Brush.linearGradient(
            0f to Color.Transparent,
            0.45f to Color(0x0FFFFFFF),
            0.55f to Color(0x14FFFFFF),
            0.65f to Color.Transparent,
            start = Offset(0f, size.height * 0.2f),
            end = Offset(size.width, size.height * 0.6f),
        ),
    )
}

/**
 * Outline for pills and cards: in glass themes a specular rim (bright at the top-left, fading, then a
 * softer glint at the bottom-right) like light catching the edge of glass; otherwise the usual thin line.
 */
@Composable
fun Modifier.themedOutline(shape: Shape, emphasised: Boolean = false): Modifier {
    val theme = LocalLibraryTheme.current
    return if (theme.glass && theme.light) {
        // On a light wallpaper a white rim vanishes, so the edge is a fine warm-grey line with a bright glint.
        val edge = Color(0xFF5B4A2E)
        this.border(
            1.dp,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.95f),
                0.35f to edge.copy(alpha = if (emphasised) 0.32f else 0.2f),
                0.7f to edge.copy(alpha = if (emphasised) 0.26f else 0.14f),
                1f to Color.White.copy(alpha = 0.8f),
            ),
            shape,
        )
    } else if (theme.glass) {
        this.border(
            1.dp,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (emphasised) 0.75f else 0.55f),
                0.35f to Color.White.copy(alpha = 0.12f),
                0.7f to Color.White.copy(alpha = 0.06f),
                1f to Color.White.copy(alpha = 0.3f),
            ),
            shape,
        )
    } else {
        this.border(
            1.dp,
            if (emphasised) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outlineVariant,
            shape,
        )
    }
}

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
    val ink = Color(0xFF1F1B13)
    val colors = if (theme.light) lightColorScheme(
        primary = ink,
        onPrimary = Color.White,
        primaryContainer = theme.card,
        onPrimaryContainer = ink,
        secondary = ink,
        onSecondary = Color.White,
        secondaryContainer = theme.card,
        onSecondaryContainer = ink,
        tertiary = theme.accent,
        onTertiary = Color.White,
        tertiaryContainer = theme.card,
        onTertiaryContainer = ink,
        background = Color(0xFFFFFBF1),
        onBackground = ink,
        surface = theme.surface,
        onSurface = ink,
        surfaceVariant = theme.card,
        onSurfaceVariant = Color(0xFF4F4A3E),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF6B6455),
        outlineVariant = Color(0x335B4A2E),
        error = Color(0xFFC62828),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        inverseSurface = ink,
        inverseOnSurface = Color.White,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        surfaceContainer = theme.menu,
        surfaceContainerLow = Color(0xFFFFFBF1),
        surfaceContainerHigh = theme.menu,
        surfaceContainerHighest = theme.menu,
        surfaceContainerLowest = Color(0xFFFFFBF1),
    ) else darkColorScheme(
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
        onSurfaceVariant = if (theme.glass) Color(0xD9FFFFFF) else Color(0xFFB8B8B8),
        surfaceTint = Color.Transparent,
        outline = if (theme.glass) Color(0x99FFFFFF) else Color(0xFF777777),
        outlineVariant = if (theme.glass) Color(0x4DFFFFFF) else Color(0xFF333333),
        error = Color(0xFFFF6B6B),
        onError = Color.Black,
        errorContainer = Color(0xFF452323),
        onErrorContainer = Color.White,
        inverseSurface = Color.White,
        inverseOnSurface = theme.background,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        surfaceContainer = if (theme.glass) theme.menu else theme.surface,
        surfaceContainerLow = theme.background,
        surfaceContainerHigh = if (theme.glass) theme.menu else theme.card,
        surfaceContainerHighest = if (theme.glass) theme.menu else theme.card,
        surfaceContainerLowest = theme.background,
    )
    // Dark status-bar icons on a light theme (the player sets its own bars while playing).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window
            if (window != null) androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = theme.light
        }
    }
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
        // Every theme uses the same soft, concentric corners as Liquid Glass, so cards, menus and fields match.
        shapes = Shapes(
            extraSmall = RoundedCornerShape(10.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = {
            CompositionLocalProvider(LocalLibraryTheme provides theme) {
                if (theme.glass) {
                    Box(Modifier.fillMaxSize().drawBehind { drawGlassWallpaper(theme) }) { content() }
                } else {
                    content()
                }
            }
        },
    )
}

@Composable
internal fun ThemeSettings() {
    val context = LocalContext.current
    val choice by rememberThemeChoice()
    var expanded by remember { mutableStateOf(false) }
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
