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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
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
    /** Adaptive themes: the wallpaper takes its colours from the covers on screen. */
    val adaptive: AdaptiveStyle? = null,
    /** The phone's own wallpaper shows through the app (with a veil for readable text). */
    val showsWallpaper: Boolean = false,
    val reduceTransparency: Boolean = false,
)

/** The look shared by the adaptive themes: smoky dark glass over a wallpaper made from the covers on screen. */
private fun adaptiveTheme(id: String, label: String, style: AdaptiveStyle, card: Color, tint: Color, menu: Color) = LibraryTheme(
    id, label, Color.Transparent, Color(0x1F000000), card, style.fallbackAccent,
    glass = true,
    wallpaper = style.fallback,
    glassTint = tint,
    menu = menu,
    adaptive = style,
)

val libraryThemes = listOf(
    // Clarity: opaque, accessible panels with a restrained accent and clean typography.
    // Explicitly non-glass to retain readable text behind every settings control.
    LibraryTheme("CLARITY_DARK", "Clarity · Dark", Color(0xFF0B111B), Color(0xFF192434), Color(0xFF233348), Color(0xFF78B6FF), menu = Color(0xFF192434)),
    LibraryTheme("CLARITY_LIGHT", "Clarity · Light", Color(0xFFF5F7FB), Color(0xFFFFFFFF), Color(0xFFE7EDF5), Color(0xFF2266B5), menu = Color(0xFFFFFFFF), light = true),
    LibraryTheme("CHARCOAL", "Dark grey", Color(0xFF0D0D0D), Color(0xFF171717), Color(0xFF262626), Color(0xFFFF837A)),
    LibraryTheme("AMOLED", "AMOLED black", Color.Black, Color.Black, Color(0xFF101010), Color(0xFFFF837A)),
    LibraryTheme("SLATE", "Midnight blue", Color(0xFF141C29), Color(0xFF1B2738), Color(0xFF26364D), Color(0xFF3569AA)),
    LibraryTheme("FOREST", "Forest", Color(0xFF14221D), Color(0xFF1D3028), Color(0xFF2A4036), Color(0xFF26715B)),
    LibraryTheme("PLUM", "Plum", Color(0xFF241A2B), Color(0xFF30233A), Color(0xFF40304D), Color(0xFF884EAA)),
    // Preserve saved LIQUID / LIQUID_GLASS selections while adding both new variants.
    *skyWingGlassThemes.toTypedArray(),
    // Each adapts in its own way: Glow lays the cover colours in a diagonal gradient with a bright accent of the
    // same colour; Midnight washes one soft colour down from the top over near-black with a pale accent; Vivid paints
    // bright aurora blobs with the opposite colour as accent. Panels are clear glass, so the colours show through them.
    adaptiveTheme(
        "ADAPTIVE_GLOW", "Adaptive Glow",
        AdaptiveStyle(
            listOf(0.36f, 0.26f, 0.16f), 0.35f, 0.75f, glow = 0.32f, scrim = 0.22f,
            fallback = listOf(Color(0xFF3A2A5A), Color(0xFF1E3A4A), Color(0xFF241A30)),
            layout = AdaptiveLayout.GLOW, accent = AdaptiveAccent.SAME_HUE, fallbackAccent = Color(0xFFFFC27A), opacity = 1f,
        ),
        card = Color(0x1FFFFFFF), tint = Color(0x8C101016), menu = Color(0xFF1B1B24),
    ),
    adaptiveTheme(
        "ADAPTIVE_MIDNIGHT", "Adaptive Midnight",
        AdaptiveStyle(
            listOf(0.22f, 0.15f, 0.30f), 0.25f, 0.55f, glow = 0.30f, scrim = 0.30f,
            fallback = listOf(Color(0xFF1C2333), Color(0xFF14202A), Color(0xFF2A3550)),
            layout = AdaptiveLayout.WASH, accent = AdaptiveAccent.SOFT, fallbackAccent = Color(0xFFCFE3FF), opacity = 1f,
        ),
        card = Color(0x14FFFFFF), tint = Color(0xB3060609), menu = Color(0xFF111117),
    ),
    adaptiveTheme(
        "ADAPTIVE_VIVID", "Adaptive Vivid",
        AdaptiveStyle(
            listOf(0.62f, 0.55f, 0.20f), 0.6f, 0.95f, glow = 0.55f, scrim = 0.34f,
            fallback = listOf(Color(0xFF8A2C7C), Color(0xFF1F6F9E), Color(0xFF2A1240)),
            layout = AdaptiveLayout.AURORA, accent = AdaptiveAccent.OPPOSITE, fallbackAccent = Color(0xFF5CF2C2), opacity = 1f,
        ),
        card = Color(0x29FFFFFF), tint = Color(0x80180A24), menu = Color(0xFF221430),
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
        val edge = Color(0xFF54708C)
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
private fun rememberAppearanceFlag(key: String): State<Boolean> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val value = remember(prefs, key) { mutableStateOf(prefs.getBoolean(key, false)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) value.value = prefs.getBoolean(key, false)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    val choice by rememberThemeChoice()
    val reduceTransparency by rememberAppearanceFlag("reduce_transparency")
    val selected = libraryThemes.first { it.id == choice }
    val chosen = if (reduceTransparency && selected.glass) selected.copy(
        card = selected.menu, surface = selected.menu, glassTint = selected.menu, reduceTransparency = true,
    ) else selected
    // Adaptive themes: colours follow the covers on screen, and so does the accent.
    val adaptiveStyle = chosen.adaptive
    val adaptiveColors = if (adaptiveStyle != null) rememberAdaptiveColors(adaptiveStyle) else null
    val theme = if (adaptiveStyle != null) chosen.copy(accent = rememberAdaptiveAccent(adaptiveStyle)) else chosen
    val ink = if (theme.id == "CLARITY_LIGHT") Color(0xFF17263A) else Color(0xFF14243A)
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
        background = if (theme.glass) Color.Transparent else theme.background,
        onBackground = ink,
        surface = theme.surface,
        onSurface = ink,
        surfaceVariant = theme.card,
        onSurfaceVariant = if (theme.id == "CLARITY_LIGHT") Color(0xFF34465C) else Color(0xFF43556B),
        surfaceTint = Color.Transparent,
        outline = Color(0xFF5C7088),
        outlineVariant = Color(0x3354708C),
        error = Color(0xFFC62828),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        inverseSurface = ink,
        inverseOnSurface = Color.White,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        surfaceContainer = theme.menu,
        surfaceContainerLow = theme.menu,
        surfaceContainerHigh = theme.menu,
        surfaceContainerHighest = theme.menu,
        surfaceContainerLowest = theme.menu,
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
        background = if (theme.glass) Color.Transparent else theme.background,
        onBackground = Color.White,
        surface = theme.surface,
        onSurface = Color.White,
        surfaceVariant = theme.card,
        onSurfaceVariant = if (theme.glass) Color(0xD9FFFFFF) else if (theme.id == "CLARITY_DARK") Color(0xFFDAE4EF) else Color(0xFFB8B8B8),
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
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = theme.light
                // See-through themes show the phone wallpaper behind the app; the others keep a solid window. The
                // window must also switch to a translucent surface: an opaque one with a clear background is never
                // wiped between frames, which left trails of earlier screens and hid the wallpaper.
                val showing = (window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER) != 0
                if (showing != theme.showsWallpaper) {
                    if (theme.showsWallpaper) {
                        window.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
                        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.BLACK))
                        window.setFormat(android.graphics.PixelFormat.OPAQUE)
                    }
                }
            }
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
                if (adaptiveStyle != null && adaptiveColors != null) {
                    Box(Modifier.fillMaxSize().drawBehind { drawAdaptiveWallpaper(adaptiveColors.map { it.value }, adaptiveStyle) }) { content() }
                } else if (theme.showsWallpaper) {
                    Box(Modifier.fillMaxSize().drawBehind { drawTransparentVeil() }) { content() }
                } else if (theme.glass) {
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
    val reduceTransparency by rememberAppearanceFlag("reduce_transparency")
    var expanded by remember { mutableStateOf(false) }
    fun select(id: String) {
        context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putString("theme", id).apply()
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Keep all saved themes easy to reach before the richer glass previews.
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Theme: ${libraryThemes.first { it.id == choice }.label}")
            }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                libraryThemes.forEach { theme ->
                    DropdownMenuItem(text = { Text(theme.label + if (theme.id == choice) " ✓" else "") }, onClick = {
                        select(theme.id)
                        expanded = false
                    })
                }
            }
        }
        SkyWingThemeChoices(choice, onSelect = ::select)
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Reduce transparency", style = MaterialTheme.typography.bodyLarge)
                Text("Solid panels and no backdrop blur", style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = reduceTransparency, onCheckedChange = { value ->
                context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit().putBoolean("reduce_transparency", value).apply()
            }, modifier = Modifier.semantics { contentDescription = "Reduce transparency" })
        }
    }
}
