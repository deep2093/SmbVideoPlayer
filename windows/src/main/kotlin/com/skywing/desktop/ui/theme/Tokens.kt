package com.skywing.desktop.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.SystemFont
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sun.jna.Native
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * The colours of one theme (plan 6.2, Tokens board): flat layers stacked by tone with one 1 px edge, one accent for
 * selection, focus and progress, and status colours for status chips only.
 */
@Immutable
internal data class SkyColors(
    /** The window background. */
    val base: Color,
    /** The rail and the top bar. */
    val layer1: Color,
    /** Cards and panels. */
    val layer2: Color,
    /** Menus, dialogs, and hover on layer 2. */
    val layer3: Color,
    /** Standard buttons and the unselected track of a switch. */
    val control: Color,
    /** The 1 px edge: white 8 % on dark themes, black 8 % on light ones. */
    val stroke: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val onAccent: Color,
    /** The accent at 16 %, for selected rows. */
    val accentSubtle: Color,
    /** The 2 dp focus ring: the accent, as on the Tokens board (M3 design brief, A6). */
    val focus: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /** Over video: black 55 %. */
    val scrim: Color,
    val light: Boolean,
) {
    /** The same colours with another accent: all an adaptive theme changes. */
    fun withAccent(color: Color): SkyColors = copy(accent = color, accentSubtle = color.copy(alpha = 0.16f), focus = color)
}

private fun dark(
    base: Color, layer1: Color, layer2: Color, layer3: Color, control: Color,
    accent: Color = Color(0xFF6CA8FF),
    text: Color = Color(0xFFF3F4F6),
    textSecondary: Color = Color(0xFFA9AEB8),
    textDisabled: Color = Color(0xFF6B707A),
) = SkyColors(
    base = base, layer1 = layer1, layer2 = layer2, layer3 = layer3, control = control,
    stroke = Color(0x14FFFFFF), text = text, textSecondary = textSecondary, textDisabled = textDisabled,
    accent = accent, onAccent = Color(0xFF0B1220), accentSubtle = accent.copy(alpha = 0.16f), focus = accent,
    danger = Color(0xFFFF6B6B), success = Color(0xFF4CC38A), warning = Color(0xFFFFB547), scrim = Color(0x8C000000),
    light = false,
)

internal object SkyPalettes {
    val Dark = dark(Color(0xFF111215), Color(0xFF16181C), Color(0xFF1C1E23), Color(0xFF24272D), Color(0xFF2B2F37))

    /** Blue-black. */
    val Midnight = dark(
        Color(0xFF0E1320), Color(0xFF121828), Color(0xFF182032), Color(0xFF1F293D), Color(0xFF263149),
        accent = Color(0xFF7AA7FF), text = Color(0xFFEEF2FA), textSecondary = Color(0xFFA3ADC2),
        textDisabled = Color(0xFF667089),
    )

    /** Pure black for OLED screens, with a near-black rail. */
    val Oled = dark(Color(0xFF000000), Color(0xFF0A0A0B), Color(0xFF121214), Color(0xFF1A1B1E), Color(0xFF232529))

    val Light = SkyColors(
        base = Color(0xFFF4F5F7), layer1 = Color(0xFFFAFAFB), layer2 = Color(0xFFFFFFFF), layer3 = Color(0xFFFFFFFF),
        control = Color(0xFFE9EBEF), stroke = Color(0x14000000),
        text = Color(0xFF14161A), textSecondary = Color(0xFF5B616B), textDisabled = Color(0xFF9AA0A8),
        accent = Color(0xFF1F6FE5), onAccent = Color.White, accentSubtle = Color(0xFF1F6FE5).copy(alpha = 0.16f),
        focus = Color(0xFF1F6FE5), danger = Color(0xFFD13438), success = Color(0xFF0F7B4F), warning = Color(0xFFB76E00),
        scrim = Color(0x8C000000), light = true,
    )
}

/** The 4 dp grid (plan 6.2, Tokens board). */
internal object SkySpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val x3 = 40.dp
    val x4 = 48.dp
}

/** Radius 8 for controls, 12 for cards, 16 for panels and dialogs. */
internal object SkyRadius {
    val control = 8.dp
    val card = 12.dp
    val panel = 16.dp
}

/**
 * Motion (plan 6.1): 120 ms for small state changes, 200 ms standard, 250 ms for pages, never over 300 ms. With
 * Windows' "Animation effects" off, every motion becomes instant.
 */
internal object SkyMotion {
    const val FAST_MS = 120
    const val NORMAL_MS = 200
    const val PAGE_MS = 250

    /** False when Windows' "Animation effects" is off; read once, at first use. */
    val animations: Boolean by lazy { WindowsAnimation.enabled() ?: true }

    fun <T> fast(): FiniteAnimationSpec<T> = spec(FAST_MS)
    fun <T> normal(): FiniteAnimationSpec<T> = spec(NORMAL_MS)
    fun <T> page(): FiniteAnimationSpec<T> = spec(PAGE_MS)

    private fun <T> spec(ms: Int): FiniteAnimationSpec<T> = if (animations) tween(ms, easing = FastOutSlowInEasing) else snap()
}

/** Windows' "Animation effects" setting (SPI_GETCLIENTAREAANIMATION); only read, never changed. */
private object WindowsAnimation {
    private interface User32 : StdCallLibrary {
        fun SystemParametersInfoW(action: Int, param: Int, value: IntByReference, winIni: Int): Boolean
    }

    private const val SPI_GETCLIENTAREAANIMATION = 0x1042

    fun enabled(): Boolean? = runCatching {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return null
        val user32 = Native.load("user32", User32::class.java)
        val value = IntByReference()
        if (user32.SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, value, 0)) value.value != 0 else null
    }.getOrNull()
}

/** Segoe UI Variable, Windows 11's interface font; Windows 10 falls back to the default font. */
@OptIn(ExperimentalTextApi::class)
internal val SegoeVariable: FontFamily = FontFamily(
    SystemFont("Segoe UI Variable Text", FontWeight.Normal),
    SystemFont("Segoe UI Variable Text", FontWeight.Medium),
    SystemFont("Segoe UI Variable Text", FontWeight.SemiBold),
    SystemFont("Segoe UI Variable Text", FontWeight.Bold),
)

/** The names on actor cards: Cambria, a Windows font with no file of our own; Compose uses its default font when it is missing. */
@OptIn(ExperimentalTextApi::class)
internal val SerifNames: FontFamily = FontFamily(SystemFont("Cambria"))

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = SegoeVariable, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp)

/**
 * The type ramp of the Tokens board (Display 28/600, Title 20/600, Subtitle 16/600, Body 14/400, Caption 12/400, Touch
 * body 16/400), put under Material's names so every Text picks it up.
 */
internal val SkyTypography = Typography(
    displaySmall = style(28, 36, FontWeight.SemiBold),
    headlineSmall = style(28, 36, FontWeight.SemiBold),
    titleLarge = style(20, 28, FontWeight.SemiBold),
    titleMedium = style(16, 22, FontWeight.SemiBold),
    titleSmall = style(14, 20, FontWeight.SemiBold),
    bodyLarge = style(16, 22),
    bodyMedium = style(14, 20),
    bodySmall = style(12, 16),
    labelLarge = style(14, 20, FontWeight.Medium),
    labelMedium = style(12, 16, FontWeight.Medium),
    labelSmall = style(11, 14, FontWeight.Medium),
)
