package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.theme.SkyColors
import com.skywing.desktop.ui.theme.SkyPalettes
import com.skywing.desktop.ui.theme.SkyRadius
import java.util.prefs.Preferences

/**
 * The SkyWing themes (plan 6.2): Dark, Light, Midnight and OLED black are flat layers from the Tokens board; Adaptive
 * is Dark with its accent, and a soft wash on the window background, taken from the covers on screen.
 */
internal data class SkyTheme(
    val id: String,
    val label: String,
    val colors: SkyColors,
    /** The adaptive theme: how its accent and background wash follow the covers on screen. */
    val adaptive: AdaptiveStyle? = null,
) {
    val light: Boolean get() = colors.light
    val accent: Color get() = colors.accent
    val onAccent: Color get() = colors.onAccent

    // The names the screens used before the tokens.
    val background: Color get() = colors.base
    val surface: Color get() = colors.layer1
    val card: Color get() = colors.layer2
    val menu: Color get() = colors.layer3
    val ink: Color get() = colors.text
    val muted: Color get() = colors.textSecondary

    /** The same theme with another accent: what the adaptive theme shows. */
    fun withAccent(color: Color): SkyTheme = copy(colors = colors.withAccent(color))
}

/** One soft cover colour washes down from the top of the window; the accent is the main cover colour, made bright. */
private val adaptiveStyle = AdaptiveStyle(
    listOf(0.22f, 0.15f, 0.30f), 0.25f, 0.55f, glow = 0.30f, scrim = 0.30f,
    fallback = listOf(Color(0xFF1C2333), Color(0xFF14202A), Color(0xFF2A3550)),
    layout = AdaptiveLayout.WASH, accent = AdaptiveAccent.SAME_HUE, fallbackAccent = SkyPalettes.Dark.accent,
)

internal val skyThemes: List<SkyTheme> = listOf(
    SkyTheme("DARK", "Dark", SkyPalettes.Dark),
    SkyTheme("LIGHT", "Light", SkyPalettes.Light),
    SkyTheme("MIDNIGHT", "Midnight", SkyPalettes.Midnight),
    SkyTheme("OLED", "OLED black", SkyPalettes.Oled),
    SkyTheme("ADAPTIVE", "Adaptive", SkyPalettes.Dark, adaptiveStyle),
)

internal val LocalSkyTheme = staticCompositionLocalOf { skyThemes.first() }

internal object SkyThemeStore {
    private val preferences = Preferences.userNodeForPackage(SkyThemeStore::class.java).node("appearance")

    /**
     * The nine themes of builds before M3, by the set that replaced them. The saved value is not rewritten: it changes
     * only when a theme is picked, so an earlier build still finds its own.
     */
    private val earlier = mapOf(
        "LIQUID" to "DARK", "SKYWING_CRYSTAL_DARK" to "DARK",
        "SKYWING_AURORA_LIGHT" to "LIGHT", "LIQUID_GLASS" to "LIGHT",
        "AMOLED" to "OLED", "SLATE" to "MIDNIGHT",
        "ADAPTIVE_GLOW" to "ADAPTIVE", "ADAPTIVE_MIDNIGHT" to "ADAPTIVE", "ADAPTIVE_VIVID" to "ADAPTIVE",
    )

    /** The theme a saved id stands for; Dark for none or an unknown one. */
    fun themeFor(id: String?): SkyTheme {
        val current = earlier[id] ?: id
        return skyThemes.firstOrNull { it.id == current } ?: skyThemes.first()
    }

    fun load(): SkyTheme = themeFor(runCatching { preferences.get("theme", null) }.getOrNull())

    fun save(theme: SkyTheme) {
        runCatching {
            preferences.put("theme", theme.id)
            preferences.flush()
        }
    }
}

/** Material's colour roles from the tokens, so Material parts (menus, dialogs, fields, switches) use the same layers. */
internal fun SkyTheme.colorScheme(): ColorScheme {
    val c = colors
    return (if (c.light) lightColorScheme() else darkColorScheme()).copy(
        primary = c.accent,
        onPrimary = c.onAccent,
        primaryContainer = c.accentSubtle,
        onPrimaryContainer = c.text,
        secondary = c.accent,
        onSecondary = c.onAccent,
        secondaryContainer = c.accentSubtle,
        onSecondaryContainer = c.text,
        tertiary = c.accent,
        onTertiary = c.onAccent,
        background = c.base,
        onBackground = c.text,
        surface = c.layer1,
        onSurface = c.text,
        surfaceVariant = c.layer2,
        onSurfaceVariant = c.textSecondary,
        // Flat layers: no tint for raised surfaces.
        surfaceTint = Color.Transparent,
        surfaceDim = c.base,
        surfaceBright = c.layer3,
        surfaceContainerLowest = c.base,
        surfaceContainerLow = c.layer1,
        surfaceContainer = c.layer3,
        surfaceContainerHigh = c.layer3,
        surfaceContainerHighest = c.control,
        error = c.danger,
        onError = c.onAccent,
        outline = c.text.copy(alpha = 0.35f),
        outlineVariant = c.stroke,
        scrim = c.scrim,
    )
}

/** Paints the window background: the base colour; for the adaptive theme, its wash while no cover is on screen. */
internal fun DrawScope.drawSkyWallpaper(theme: SkyTheme) {
    val style = theme.adaptive
    if (style != null) drawAdaptiveWallpaper(style.fallback, style) else drawRect(theme.colors.base)
}

/** A flat panel (plan 6.2): layer 2, or [color], with the theme's 1 px edge. No glass, no blur. */
@Composable
internal fun Modifier.skyPanel(shape: Shape = RoundedCornerShape(SkyRadius.panel), color: Color? = null): Modifier {
    val c = LocalSkyTheme.current.colors
    return this.clip(shape).background(color ?: c.layer2).border(1.dp, c.stroke, shape)
}
