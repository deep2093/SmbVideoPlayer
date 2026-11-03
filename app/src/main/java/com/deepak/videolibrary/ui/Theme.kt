package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
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
    // ---- Pixallery glass (ported 4 Nov 2026) ----
    /** One line under the theme's name in Settings. */
    val description: String = "",
    /** Fine specular edge on glass, and how strong it is (1 = usual). */
    val rim: Color = Color.White,
    val rimStrength: Float = 1f,
    /** Bars when there is nothing to blur or the glass effect is off. */
    val glassFallback: Color = Color.Unspecified,
    /** How strongly floating bars blur what scrolls under them (times the Blur slider). */
    val blurRadius: Dp = 12.dp,
    /** Frosted selection: a see-through white film instead of a solid accent fill on selected pills and buttons. */
    val accentFill: Color? = null,
    /** Frosted panels and cards: a see-through film instead of a solid card colour. */
    val panelFill: Color? = null,
    /** Text and icons on glass, and the softer secondary ink. */
    val ink: Color = Color.White,
    val inkSecondary: Color = Color(0xD9FFFFFF),
    /** Minimal layout (Calm): no card fills or shadows; settings and lists are flat rows with hairline dividers. */
    val flat: Boolean = false,
    /** One UI style (Onyx): black page, raised rounded groups without outlines, colourful round icons in Settings. */
    val grouped: Boolean = false,
)

/** What a selected pill, button or tick is filled with: the frosted film where the theme has one, else the accent. */
val LibraryTheme.accentSurface: Color get() = accentFill ?: accent

/**
 * The Pixallery glass look, shared by every theme: clear see-through bars with a little frost and depth, frosted panels,
 * frosted selection (a white film, not a colour) and white text. Only the wallpaper differs. "Milky" is the
 * almost-untinted iOS-style glass of the deep light theme.
 */
private fun glassTheme(
    id: String,
    label: String,
    description: String,
    wallpaper: List<Long>,
    glows: List<WallpaperGlow>,
    rim: Long,
    milky: Boolean,
    blur: Dp = 12.dp,
    adaptive: AdaptiveStyle? = null,
): LibraryTheme {
    val fallback = Color(if (milky) 0xE6505660 else 0xE61A1C22)
    val base = Color(wallpaper.first())
    val solid = fallback.compositeOver(base)
    val panel = Color(if (milky) 0x1FFFFFFF else 0x1AFFFFFF)
    return LibraryTheme(
        id = id,
        label = label,
        background = base,
        surface = solid,
        card = panel,
        accent = Color.White,
        glass = true,
        wallpaper = wallpaper.map { Color(it) },
        glows = glows,
        glassTint = Color(if (milky) 0x1F8A9098 else 0x730C0D10),
        menu = solid,
        light = false,
        adaptive = adaptive,
        description = description,
        rim = Color(rim),
        rimStrength = 1.1f,
        glassFallback = fallback,
        blurRadius = blur,
        accentFill = Color(if (milky) 0x38FFFFFF else 0x33FFFFFF),
        panelFill = panel,
        ink = Color.White,
        inkSecondary = Color(0xD9FFFFFF),
    )
}

/** The pale-page version of a light theme: light wallpaper, clear almost colourless glass, dark text and icons. */
private fun pale(deep: LibraryTheme, wallpaper: List<Long>, glows: List<WallpaperGlow>, ink: Long): LibraryTheme {
    val base = Color(wallpaper.first())
    val fallback = Color(0xDBF4F6FA)
    val solid = fallback.compositeOver(base)
    return deep.copy(
        light = true,
        background = base,
        wallpaper = wallpaper.map { Color(it) },
        glows = glows,
        surface = solid,
        menu = solid,
        card = Color(0x61FFFFFF),
        panelFill = Color(0x61FFFFFF),
        accentFill = Color(0x80FFFFFF),
        glassTint = Color(0x24FFFFFF),
        glassFallback = fallback,
        accent = Color(ink),
        ink = Color(ink),
        inkSecondary = Color(ink).copy(alpha = 0.72f),
    )
}

/**
 * The six Pixallery themes, with Pixallery's ids. Old SkyWing theme ids (Charcoal, Clarity, Aurora, ...) are no longer
 * listed, so a saved one falls back to [DEFAULT_THEME]. Photo Night and Vivid Night keep the ids of SkyWing's old
 * Adaptive Midnight and Adaptive Vivid, so those choices carry over.
 */
val libraryThemes: List<LibraryTheme> = listOf(
    // ---- Light ----
    glassTheme(
        "PEARL_MIST", "Warm Light", "Warm peach glass on a pale page",
        wallpaper = listOf(0xFF4E2824, 0xFF6A3830, 0xFF7C4A3C),
        glows = listOf(WallpaperGlow(0.9f, 0.05f, 0.7f, Color(0x59FF9A7A)), WallpaperGlow(0.05f, 0.6f, 0.7f, Color(0x40FFC48A)), WallpaperGlow(0.6f, 1.0f, 0.8f, Color(0x40F4B9C1))),
        rim = 0xFFFFD9C8, milky = false,
    ).let { deep ->
        pale(deep, listOf(0xFFFFF8F1, 0xFFFBEDE6, 0xFFF4E0D6),
            listOf(WallpaperGlow(0.9f, 0.05f, 0.7f, Color(0x40F4B9C1)), WallpaperGlow(0.05f, 0.6f, 0.7f, Color(0x33E8B48A)), WallpaperGlow(0.6f, 1.0f, 0.8f, Color(0x33F6C9C9))),
            ink = 0xFF000000)
    },
    glassTheme(
        "AURORA_GLASS_LIGHT", "Luminous Light", "Bright blue, cyan and violet glow",
        wallpaper = listOf(0xFF182F66, 0xFF243F7C, 0xFF2E4A90),
        glows = listOf(WallpaperGlow(0.9f, 0.05f, 0.7f, Color(0x805AC8FA)), WallpaperGlow(0.05f, 0.7f, 0.75f, Color(0x667C78FF)), WallpaperGlow(0.5f, 0.35f, 0.4f, Color(0x33FFFFFF))),
        rim = 0xFFA8CCFF, milky = false, blur = 14.dp,
    ).let { deep ->
        pale(deep, listOf(0xFFE3F1FF, 0xFFF2F7FF, 0xFFE4E0FF),
            listOf(WallpaperGlow(0.9f, 0.05f, 0.7f, Color(0x4D5AC8FA)), WallpaperGlow(0.05f, 0.7f, 0.75f, Color(0x407C78FF)), WallpaperGlow(0.5f, 0.35f, 0.4f, Color(0x80FFFFFF))),
            ink = 0xFF000000)
    },
    glassTheme(
        "LIQUID_DAY", "Liquid Day", "Clear glass over a bright sky",
        wallpaper = listOf(0xFF102F58, 0xFF1A4476, 0xFF225384),
        glows = listOf(WallpaperGlow(0.85f, 0.08f, 0.8f, Color(0x80FFD9A0)), WallpaperGlow(0.1f, 0.6f, 0.8f, Color(0x6659C8FF)), WallpaperGlow(0.7f, 0.95f, 0.7f, Color(0x55A98CFF))),
        rim = 0xFFFFFFFF, milky = false,
    ).let { deep ->
        pale(deep, listOf(0xFFF7F8FB, 0xFFEEF1F7, 0xFFE6EBF4),
            listOf(WallpaperGlow(0.85f, 0.08f, 0.8f, Color(0x33FFD9A0)), WallpaperGlow(0.1f, 0.6f, 0.8f, Color(0x2659C8FF))),
            ink = 0xFF14171C)
    },
    glassTheme(
        "CRYSTAL_GLASS_LIGHT", "Clear Light", "Neutral grey-blue glass, white text",
        wallpaper = listOf(0xFF2B3642, 0xFF394655, 0xFF465566),
        glows = listOf(WallpaperGlow(0.5f, 0.2f, 0.7f, Color(0x33FFFFFF))),
        rim = 0xFFD5DEE8, milky = true,
    ),
    // ---- Dark (colours from the covers on screen) ----
    glassTheme(
        "ADAPTIVE_MIDNIGHT", "Photo Night", "Nearly black, one soft light from your covers",
        wallpaper = listOf(0xFF2A3550, 0xFF141A26, 0xFF0A0A10),
        glows = emptyList(), rim = 0xFF6E8AA0, milky = false,
        adaptive = AdaptiveStyle(
            listOf(0.30f, 0.12f, 0.04f), 0.25f, 0.55f, glow = 0.7f, scrim = 0.30f,
            fallback = listOf(Color(0xFF2A3550), Color(0xFF141A26), Color(0xFF0A0A10)),
            layout = AdaptiveLayout.NIGHT, opacity = 1f,
        ),
    ),
    glassTheme(
        "ADAPTIVE_VIVID", "Vivid Night", "Bold, saturated colours from your covers",
        wallpaper = listOf(0xFF0E5A5F, 0xFF1D3F7A, 0xFF2B2466),
        glows = emptyList(), rim = 0xFF8AB6C8, milky = false,
        adaptive = AdaptiveStyle(
            listOf(0.46f, 0.38f, 0.30f), 0.85f, 1.0f, glow = 0.5f, scrim = 0.48f,
            fallback = listOf(Color(0xFF0E5A5F), Color(0xFF1D3F7A), Color(0xFF2B2466)),
            layout = AdaptiveLayout.DIAGONAL, opacity = 1f,
        ),
    ),
)

/**
 * Calm: the minimal pair. Solid, matte surfaces with no glass, glow or blur; soft off-white (or soft charcoal) text
 * instead of pure white (or black) to cut glare; one muted accent. Made for long evenings of browsing.
 */
val calmThemes: List<LibraryTheme> = listOf(
    LibraryTheme(
        id = "CALM_DARK", label = "Calm Dark", description = "Minimal and matte, soft contrast for the eyes",
        background = Color(0xFF15171A), surface = Color(0xFF1C1F23), card = Color(0xFF23272C), accent = Color(0xFF8FB3C9),
        menu = Color(0xFF1C1F23), light = false,
        rim = Color(0xFF3A4048), ink = Color(0xFFE3E5E8), inkSecondary = Color(0xFF9CA3AB), flat = true,
    ),
    LibraryTheme(
        id = "CALM_LIGHT", label = "Calm Light", description = "Minimal warm paper, gentle on the eyes",
        background = Color(0xFFF3F2EE), surface = Color(0xFFFBFAF7), card = Color(0xFFEAE8E2), accent = Color(0xFF4A6A80),
        menu = Color(0xFFFBFAF7), light = true,
        rim = Color(0xFFD6D3CB), ink = Color(0xFF2A2C30), inkSecondary = Color(0xFF63666C), flat = true,
    ),
)

/**
 * Onyx: inspired by Samsung One UI. A true-black page, raised rounded groups in a deep blue-black (not grey), no outlines,
 * colourful round icons for every Settings row. Onyx Vivid does the same over vivid colours taken from your covers.
 */
val onyxThemes: List<LibraryTheme> = listOf(
    LibraryTheme(
        id = "ONYX", label = "Onyx", description = "Black, with raised groups and colourful icons",
        background = Color(0xFF000000), surface = Color(0xFF15171E), card = Color(0xFF15171E), accent = Color(0xFF5E9BFF),
        menu = Color(0xFF15171E), light = false,
        rim = Color(0xFF2A2E38), ink = Color(0xFFF2F3F5), inkSecondary = Color(0xFFA2A6B0), grouped = true,
    ),
    // In between: a mid slate page (neither black nor light) with lighter raised groups, white text, colourful icons.
    LibraryTheme(
        id = "ONYX_SLATE", label = "Onyx Slate", description = "Mid-tone slate, raised groups and colourful icons",
        background = Color(0xFF3A3F4A), surface = Color(0xFF484E5A), card = Color(0xFF484E5A), accent = Color(0xFF9CC3FF),
        menu = Color(0xFF484E5A), light = false,
        rim = Color(0xFF5A606C), ink = Color(0xFFF3F4F7), inkSecondary = Color(0xFFC6CBD4), grouped = true,
    ),
    LibraryTheme(
        id = "ONYX_VIVID", label = "Onyx Vivid", description = "Onyx over vivid colours from your covers",
        background = Color(0xFF000000), surface = Color(0xE6101218), card = Color(0xD9121419), accent = Color(0xFF7FB2FF),
        glass = true,
        wallpaper = listOf(Color(0xFF1E0B3A), Color(0xFF07163A), Color(0xFF020205)),
        glassTint = Color(0x8C07080C), menu = Color(0xFF14161C), light = false,
        adaptive = AdaptiveStyle(
            listOf(0.44f, 0.36f, 0.06f), 0.8f, 1.0f, glow = 0.6f, scrim = 0.40f,
            fallback = listOf(Color(0xFF1E0B3A), Color(0xFF07163A), Color(0xFF020205)),
            layout = AdaptiveLayout.AURORA, accent = AdaptiveAccent.SAME_HUE, fallbackAccent = Color(0xFF7FB2FF), opacity = 1f,
        ),
        rim = Color(0xFF3A3F4C), glassFallback = Color(0xE614161C), blurRadius = 16.dp,
        // Raised groups stay fairly solid so text reads over bright covers.
        panelFill = Color(0xD9121419),
        ink = Color(0xFFF2F3F5), inkSecondary = Color(0xFFB0B4BE), grouped = true,
    ),
)

/** Every theme, for saved choices: the Pixallery glass themes, Calm, then Onyx. */
private val allThemes: List<LibraryTheme> get() = libraryThemes + calmThemes + onyxThemes

/** Settings sections: Calm first (minimal), then the glass themes as in Pixallery. */
val lightThemes: List<LibraryTheme> get() = libraryThemes.take(4)
val darkThemes: List<LibraryTheme> get() = libraryThemes.drop(4)

/** Liquid Day for everyone who hasn't picked one of the new themes (Pixallery's default). */
const val DEFAULT_THEME = "LIQUID_DAY"

/** The active theme, for components that draw glass effects. */
val LocalLibraryTheme = staticCompositionLocalOf { libraryThemes.first { it.id == DEFAULT_THEME } }

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
 * Outline for pills and cards. Glass themes: Pixallery's fine specular rim in the theme's rim colour, bright at the
 * top-left, fading, with a softer glint at the bottom-right. Other themes: the usual thin line.
 */
@Composable
fun Modifier.themedOutline(shape: Shape, emphasised: Boolean = false): Modifier {
    val theme = LocalLibraryTheme.current
    return if (theme.glass) {
        this.glassRim(shape, strength = if (emphasised) 1.3f else 1f)
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
    fun read() = prefs.getString("theme", DEFAULT_THEME)?.takeIf { name -> allThemes.any { it.id == name } } ?: DEFAULT_THEME
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
private fun rememberAppearanceInt(key: String, default: Int): State<Int> {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val value = remember(prefs, key) { mutableStateOf(prefs.getInt(key, default)) }
    DisposableEffect(prefs, key) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) value.value = prefs.getInt(key, default)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}

/** Blur slider steps 0..4 (Pixallery): 0 = no blur (lens glass on Android 13+), 3 = the theme's own strength. */
private val BLUR_SCALES = floatArrayOf(0f, 0.3f, 0.6f, 1f, 1.5f)
internal const val DEFAULT_BLUR_STEP = 3

@Composable
fun VideoLibraryTheme(content: @Composable () -> Unit) {
    val choice by rememberThemeChoice()
    // "Turn off glass effect" (stored under its old name, Reduce transparency): solid panels, no blur.
    val reduceTransparency by rememberAppearanceFlag("reduce_transparency")
    val blurStep by rememberAppearanceInt("blur_step", DEFAULT_BLUR_STEP)
    val glassSettings = GlassSettings(
        glassOn = !reduceTransparency,
        blurScale = BLUR_SCALES[blurStep.coerceIn(0, 4)],
        lensOn = !reduceTransparency && blurStep == 0 && android.os.Build.VERSION.SDK_INT >= 33,
    )
    val selected = allThemes.first { it.id == choice }
    val chosen = if (reduceTransparency && selected.glass) selected.copy(
        card = selected.menu, surface = selected.menu, glassTint = selected.menu, reduceTransparency = true, panelFill = null,
    ) else selected
    // Adaptive themes: colours follow the covers on screen, and so does the accent.
    val adaptiveStyle = chosen.adaptive
    val adaptiveColors = if (adaptiveStyle != null) rememberAdaptiveColors(adaptiveStyle) else null
    // Frosted (Pixallery) themes keep their white or ink accent; selection is a film, not a colour.
    val theme = if (adaptiveStyle != null && chosen.accentFill == null) chosen.copy(accent = rememberAdaptiveAccent(adaptiveStyle)) else chosen
    val ink = theme.ink
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
        onTertiary = if (theme.accent.luminance() > 0.5f) Color.Black else Color.White,
        tertiaryContainer = theme.card,
        onTertiaryContainer = ink,
        background = if (theme.glass) Color.Transparent else theme.background,
        onBackground = ink,
        surface = theme.surface,
        onSurface = ink,
        surfaceVariant = theme.card,
        onSurfaceVariant = theme.inkSecondary,
        surfaceTint = Color.Transparent,
        outline = ink.copy(alpha = 0.55f),
        outlineVariant = ink.copy(alpha = 0.16f),
        error = Color(0xFFC62828),
        onError = Color.White,
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        inverseSurface = ink,
        inverseOnSurface = Color.White,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        // Distinct tint per theme: highlighted Settings surfaces track its own accent.
        surfaceContainer = androidx.compose.ui.graphics.lerp(theme.surface, theme.accent, 0.07f),
        surfaceContainerLow = theme.menu,
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(theme.surface, theme.accent, 0.11f),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(theme.surface, theme.accent, 0.14f),
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
        onBackground = theme.ink,
        surface = theme.surface,
        onSurface = theme.ink,
        surfaceVariant = theme.card,
        onSurfaceVariant = theme.inkSecondary,
        surfaceTint = Color.Transparent,
        outline = if (theme.glass) theme.rim.copy(alpha = 0.6f) else Color(0xFF777777),
        outlineVariant = if (theme.glass) Color(0x4DFFFFFF) else Color(0xFF333333),
        error = Color(0xFFFF6B6B),
        onError = Color.Black,
        errorContainer = Color(0xFF452323),
        onErrorContainer = Color.White,
        inverseSurface = Color.White,
        inverseOnSurface = theme.background,
        inversePrimary = theme.accent,
        // Menus and dialogs float above the content, so they stay opaque for legibility.
        // Preserve each theme's identity instead of a universal blue Settings card.
        surfaceContainer = androidx.compose.ui.graphics.lerp(if (theme.glass) theme.menu else theme.surface, theme.accent, 0.13f),
        surfaceContainerLow = theme.background,
        surfaceContainerHigh = androidx.compose.ui.graphics.lerp(if (theme.glass) theme.menu else theme.surface, theme.accent, 0.19f),
        surfaceContainerHighest = androidx.compose.ui.graphics.lerp(if (theme.glass) theme.menu else theme.surface, theme.accent, 0.24f),
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
            CompositionLocalProvider(LocalLibraryTheme provides theme, LocalGlassSettings provides glassSettings) {
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
    val prefs = remember(context) { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val choice by rememberThemeChoice()
    val reduceTransparency by rememberAppearanceFlag("reduce_transparency")
    val blurStep by rememberAppearanceInt("blur_step", DEFAULT_BLUR_STEP)
    val current = allThemes.first { it.id == choice }
    fun select(id: String) = prefs.edit().putString("theme", id).apply()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsStaticCard("Theme") {
            Text("Theme: ${current.label}", style = MaterialTheme.typography.titleMedium)
            Text(current.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Calm · minimal", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeSwatchRow(calmThemes, choice, ::select)
            Text("Onyx · One UI style, colourful icons", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeSwatchRow(onyxThemes, choice, ::select)
            Text("Light", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeSwatchRow(lightThemes, choice, ::select)
            Text("Dark · colours from your covers", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ThemeSwatchRow(darkThemes, choice, ::select)
        }
        if (current.glass) SettingsStaticCard("Glass") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Glass effect", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (reduceTransparency) "Off: solid panels and bars, no blur" else "See-through bars that blur what's behind them",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = !reduceTransparency, onCheckedChange = { on ->
                    prefs.edit().putBoolean("reduce_transparency", !on).apply()
                }, modifier = Modifier.semantics { contentDescription = "Glass effect" })
            }
            if (!reduceTransparency) {
                val step = blurStep.coerceIn(0, 4)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Blur", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (step == 0) "None" else "$step of 4", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = step.toFloat(),
                    onValueChange = { v -> val next = Math.round(v); if (next != step) prefs.edit().putInt("blur_step", next).apply() },
                    valueRange = 0f..4f,
                    steps = 3,
                    modifier = Modifier.semantics { contentDescription = "Blur strength, 0 to 4" },
                )
                if (step == 0) {
                    Text(
                        if (android.os.Build.VERSION.SDK_INT >= 33) "No blur: the bars act like thick lens glass, bending what's behind them."
                        else "No blur: a clear film only.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Pixallery's round theme swatches: the theme's wallpaper with its rim colour, a ring and a tick on the chosen one. */
@Composable
private fun ThemeSwatchRow(themes: List<LibraryTheme>, selected: String, onSelect: (String) -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    // Scrolls sideways on narrow phones instead of squeezing the swatches.
    Row(
        Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()).padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        themes.forEach { option ->
            val active = option.id == selected
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Box(
                    Modifier.size(64.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .selectable(selected = active, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = { onSelect(option.id) })
                        .semantics { contentDescription = option.label },
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    if (active) Box(Modifier.size(64.dp).border(3.dp, ring, androidx.compose.foundation.shape.CircleShape))
                    Box(
                        Modifier.size(if (active) 50.dp else 56.dp).clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Brush.linearGradient((option.wallpaper.ifEmpty { listOf(option.background, option.card) }) + option.rim))
                            .border(1.dp, ring.copy(alpha = 0.25f), androidx.compose.foundation.shape.CircleShape),
                        contentAlignment = androidx.compose.ui.Alignment.Center,
                    ) {
                        if (active) Icon(androidx.compose.material.icons.Icons.Filled.Check, contentDescription = null, tint = option.ink, modifier = Modifier.size(24.dp))
                    }
                }
                Text(option.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
