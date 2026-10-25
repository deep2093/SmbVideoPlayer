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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import java.util.prefs.Preferences

/** A soft light in the wallpaper: position and radius are fractions of the window. */
internal data class SkyGlow(val x: Float, val y: Float, val radius: Float, val color: Color)

/**
 * The four SkyWing glass themes, with the same ids, colours and wallpapers as the Android app so
 * a theme looks the same on both. (Aurora = vivid, Crystal = restrained.)
 */
internal data class SkyTheme(
    val id: String,
    val label: String,
    val background: Color,
    val surface: Color,
    val card: Color,
    val accent: Color,
    val wallpaper: List<Color>,
    val glows: List<SkyGlow>,
    val glassTint: Color,
    val menu: Color,
    val light: Boolean,
    val crystal: Boolean,
) {
    val ink: Color get() = if (light) Color(0xFF14202E) else Color(0xFFEAF2FF)
    val muted: Color get() = ink.copy(alpha = if (light) 0.66f else 0.70f)
    val onAccent: Color get() = if (light) Color.White else Color(0xFF06213A)
}

internal val skyThemes: List<SkyTheme> = listOf(
    SkyTheme(
        id = "LIQUID",
        label = "Aurora Glass · Dark",
        background = Color(0xFF070D26),
        surface = Color(0x7A111C46),
        card = Color(0x3D86A8F9),
        accent = Color(0xFF7FCDFF),
        wallpaper = listOf(Color(0xFF050B22), Color(0xFF192562), Color(0xFF271458), Color(0xFF071B39)),
        glows = listOf(
            SkyGlow(0.00f, 0.08f, 0.60f, Color(0x8B243FBD)),
            SkyGlow(0.98f, 0.22f, 0.70f, Color(0x804E28B8)),
            SkyGlow(0.48f, 0.95f, 0.65f, Color(0x5520C6E9)),
        ),
        glassTint = Color(0x9A10173A),
        menu = Color(0xFF15234C),
        light = false,
        crystal = false,
    ),
    SkyTheme(
        id = "SKYWING_AURORA_LIGHT",
        label = "Aurora Glass · Light",
        background = Color(0xFFF0F4FE),
        surface = Color(0xBCE9F5FF),
        card = Color(0xA6FFFFFF),
        accent = Color(0xFF1468EF),
        wallpaper = listOf(Color(0xFFEDF6FF), Color(0xFFE6EEFF), Color(0xFFF5EAFD), Color(0xFFDBF7FC)),
        glows = listOf(
            SkyGlow(0.10f, 0.00f, 0.58f, Color(0x9996DFFF)),
            SkyGlow(0.92f, 0.62f, 0.58f, Color(0x80C3A8FF)),
            SkyGlow(0.25f, 0.93f, 0.55f, Color(0x66A8F0FF)),
        ),
        glassTint = Color(0x98E9F4FF),
        menu = Color(0xFFF1F5FF),
        light = true,
        crystal = false,
    ),
    SkyTheme(
        id = "SKYWING_CRYSTAL_DARK",
        label = "Crystal Glass · Dark",
        background = Color(0xFF101B28),
        surface = Color(0x7C172C3C),
        card = Color(0x3C8CACB9),
        accent = Color(0xFF96CFFF),
        wallpaper = listOf(Color(0xFF0A1622), Color(0xFF1E3548), Color(0xFF101C2A), Color(0xFF293F51)),
        glows = listOf(
            SkyGlow(0.62f, 0.16f, 0.76f, Color(0x557DAAC8)),
            SkyGlow(0.08f, 0.92f, 0.75f, Color(0x4D4B7DA0)),
        ),
        glassTint = Color(0xA21A2A3D),
        menu = Color(0xFF203348),
        light = false,
        crystal = true,
    ),
    SkyTheme(
        id = "LIQUID_GLASS",
        label = "Crystal Glass · Light",
        background = Color(0xFFF6F9FC),
        surface = Color(0xB8FFFFFF),
        card = Color(0xACFFFFFF),
        accent = Color(0xFF2774D6),
        wallpaper = listOf(Color(0xFFFAFCFE), Color(0xFFEBF3F9), Color(0xFFF6F5F2), Color(0xFFE8F2F7)),
        glows = listOf(
            SkyGlow(0.88f, 0.05f, 0.70f, Color(0x66C8E9FB)),
            SkyGlow(0.12f, 0.75f, 0.78f, Color(0x669ECBDD)),
            SkyGlow(0.65f, 1.00f, 0.68f, Color(0x55E8D4C4)),
        ),
        glassTint = Color(0xA8F4FAFF),
        menu = Color(0xFFF6F9FC),
        light = true,
        crystal = true,
    ),
)

internal val LocalSkyTheme = staticCompositionLocalOf { skyThemes.first() }

internal object SkyThemeStore {
    private val preferences = Preferences.userNodeForPackage(SkyThemeStore::class.java).node("appearance")

    fun load(): SkyTheme {
        val id = runCatching { preferences.get("theme", skyThemes.first().id) }.getOrDefault(skyThemes.first().id)
        return skyThemes.firstOrNull { it.id == id } ?: skyThemes.first()
    }

    fun save(theme: SkyTheme) {
        runCatching {
            preferences.put("theme", theme.id)
            preferences.flush()
        }
    }
}

internal fun SkyTheme.colorScheme(): ColorScheme =
    if (light) {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.18f),
            onPrimaryContainer = ink,
            secondary = accent,
            tertiary = accent,
            background = background,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = card,
            onSurfaceVariant = muted,
            outline = ink.copy(alpha = 0.35f),
            outlineVariant = ink.copy(alpha = 0.16f),
        )
    } else {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accent.copy(alpha = 0.26f),
            onPrimaryContainer = ink,
            secondary = accent,
            tertiary = accent,
            background = background,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            surfaceVariant = card,
            onSurfaceVariant = muted,
            outline = ink.copy(alpha = 0.35f),
            outlineVariant = ink.copy(alpha = 0.16f),
        )
    }

/** Paints the theme's wallpaper: base gradient, its glows and a faint diagonal sheen (as on Android). */
internal fun DrawScope.drawSkyWallpaper(theme: SkyTheme) {
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

/** Glass tint used for panels, taken from the Android glass surface so the two apps match. */
internal fun SkyTheme.panelTint(): Color = when {
    light && crystal -> Color(0xCFFFFFFF)
    light -> Color(0xBDECF5FF)
    crystal -> Color(0xB8203347)
    else -> Color(0xB3162050)
}

/** A rounded glass panel: translucent tint with a bright rim, like light catching the edge of glass. */
@Composable
internal fun Modifier.skyGlass(shape: Shape = RoundedCornerShape(22.dp), tint: Color? = null): Modifier {
    val theme = LocalSkyTheme.current
    val edge = if (theme.light) Color(0x6654708C) else Color(0x70D7EEFF)
    return this
        .clip(shape)
        .background(tint ?: theme.panelTint())
        .border(
            1.dp,
            Brush.linearGradient(
                0f to Color.White.copy(alpha = if (theme.light) 0.96f else 0.72f),
                0.42f to edge.copy(alpha = 0.45f),
                1f to edge.copy(alpha = 0.28f),
            ),
            shape,
        )
}
