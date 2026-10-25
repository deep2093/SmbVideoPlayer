package com.skywing.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How an adaptive theme turns the colours of the covers on screen into its wallpaper and accent, the same three styles
 * as the Android app (Glow, Midnight, Vivid).
 */
internal data class AdaptiveStyle(
    /** Brightness of the three wallpaper colours. */
    val values: List<Float>,
    val minSaturation: Float,
    val maxSaturation: Float,
    val glow: Float,
    val scrim: Float,
    /** The wallpaper while no cover is on screen. */
    val fallback: List<Color>,
    val layout: AdaptiveLayout,
    val accent: AdaptiveAccent,
    val fallbackAccent: Color,
)

/** GLOW: a diagonal gradient with two soft lights. WASH: one colour washing down from the top over near-black. AURORA: three bright blobs. */
internal enum class AdaptiveLayout { GLOW, WASH, AURORA }

/** SAME_HUE: a bright version of the main cover colour. SOFT: a pale tint of the average colour. OPPOSITE: the complementary colour. */
internal enum class AdaptiveAccent { SAME_HUE, SOFT, OPPOSITE }

/** Colours of the pictures currently on screen. Each shown picture adds one colour and removes it when it leaves. */
internal object ScreenColors {
    private val shown = mutableStateMapOf<Long, Color>()
    private var next = 0L

    fun add(color: Color): Long {
        val token = next++
        shown[token] = color
        return token
    }

    fun remove(token: Long) {
        shown.remove(token)
    }

    fun current(): List<Color> = shown.values.toList()
}

private fun hsv(color: Color): FloatArray {
    val argb = color.toArgb()
    val hsb = java.awt.Color.RGBtoHSB((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF, null)
    return floatArrayOf(hsb[0] * 360f, hsb[1], hsb[2])
}

private fun fromHsv(h: Float, s: Float, v: Float): Color = Color(java.awt.Color.HSBtoRGB(((h % 360f) + 360f) % 360f / 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

/** The main colour of a picture: its most colourful bright area, or its average when it has none. Cached by key. */
internal object CoverColors {
    private val cache = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?): Boolean = size > 1024
    }

    fun cached(key: String): Color? = synchronized(cache) { cache[key] }?.let { Color(it) }

    suspend fun of(key: String, image: ImageBitmap): Color? = cached(key) ?: withContext(Dispatchers.Default) {
        runCatching {
            val map = image.toPixelMap()
            val samples = List(36) { i -> map[((i % 6) * 2 + 1) * map.width / 12, ((i / 6) * 2 + 1) * map.height / 12].toArgb() }
            pickMain(samples.toIntArray())
        }.getOrNull()?.also { synchronized(cache) { cache[key] = it } }?.let { Color(it) }
    }

    /** The most colourful, reasonably bright sample; the average when everything is grey. */
    fun pickMain(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var best = 0
        var bestScore = -1f
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            val c = hsv(Color(p))
            val score = if (c[2] < 0.15f) 0f else c[1] * c[2]
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        if (bestScore >= 0.12f) return best or (0xFF shl 24)
        val n = pixels.size
        return Color((r / n).toInt(), (g / n).toInt(), (b / n).toInt()).toArgb()
    }
}

/** The average hue of [colors] (as a direction, so red near 0 and 360 averages correctly), weighted by saturation. */
private fun averageHue(colors: List<FloatArray>): Float {
    var x = 0.0
    var y = 0.0
    colors.forEach { x += cos(Math.toRadians(it[0].toDouble())) * it[1]; y += sin(Math.toRadians(it[0].toDouble())) * it[1] }
    return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
}

/** Three wallpaper colours for [style] from the colours on screen. */
internal fun adaptivePalette(colors: List<Color>, style: AdaptiveStyle): List<Color> {
    if (colors.isEmpty()) return style.fallback
    val all = colors.map(::hsv)
    val first = all.maxBy { it[1] * it[2] }
    fun hueGap(a: Float, b: Float) = minOf(abs(a - b), 360f - abs(a - b))
    val second = all.filter { it[1] > 0.15f }.maxByOrNull { hueGap(it[0], first[0]) }
        ?.takeIf { hueGap(it[0], first[0]) > 25f } ?: floatArrayOf((first[0] + 40f) % 360f, first[1], first[2])
    val third = floatArrayOf(averageHue(all), all.map { it[1] }.average().toFloat(), 0.5f)
    return listOf(first, second, third).mapIndexed { i, c -> fromHsv(c[0], c[1].coerceIn(style.minSaturation, style.maxSaturation), style.values[i]) }
}

/** The accent of an adaptive theme from the colours on screen; each style picks it its own way. */
internal fun adaptiveAccent(colors: List<Color>, style: AdaptiveStyle): Color {
    if (colors.isEmpty()) return style.fallbackAccent
    val all = colors.map(::hsv)
    val main = all.maxBy { it[1] * it[2] }
    return when (style.accent) {
        AdaptiveAccent.SAME_HUE -> fromHsv(main[0], 0.55f, 1f)
        AdaptiveAccent.SOFT -> fromHsv(averageHue(all), 0.28f, 0.96f)
        AdaptiveAccent.OPPOSITE -> fromHsv(main[0] + 180f, 0.75f, 1f)
    }
}

/**
 * The theme as shown: an adaptive theme takes its accent from the covers on screen. The hue moves in steps of 30
 * degrees, so the app restyles only when the screen's colours really change, never on every scroll.
 */
@Composable
internal fun rememberShownTheme(theme: SkyTheme): SkyTheme {
    val style = theme.adaptive ?: return theme
    val accent by remember(style) {
        derivedStateOf {
            val c = hsv(adaptiveAccent(ScreenColors.current(), style))
            fromHsv((c[0] / 30f).roundToInt() * 30f, c[1], c[2])
        }
    }
    return remember(theme, accent) { theme.copy(accent = accent) }
}

/** The three wallpaper colours of an adaptive theme, easing slowly from one set to the next as the screen changes. */
@Composable
private fun rememberAdaptiveColors(style: AdaptiveStyle): List<State<Color>> {
    val target by remember(style) { derivedStateOf { adaptivePalette(ScreenColors.current(), style) } }
    return List(3) { i -> animateColorAsState(target[i], tween(1400), label = "adaptive_$i") }
}

/** Paints the theme's wallpaper behind the content; an adaptive theme's colours follow the covers on screen. */
@Composable
internal fun Modifier.skyWallpaper(theme: SkyTheme): Modifier {
    val style = theme.adaptive ?: return drawBehind { drawSkyWallpaper(theme) }
    val colors = rememberAdaptiveColors(style)
    return drawBehind { drawAdaptiveWallpaper(colors.map { it.value }, style) }
}

/** Adds a shown picture's main colour to the adaptive wallpaper while it is on screen (adaptive themes only). */
@Composable
internal fun ReportScreenColor(key: String, picture: ImageBitmap?) {
    if (LocalSkyTheme.current.adaptive == null || picture == null) return
    val color by produceState(CoverColors.cached(key), key, picture) { value = CoverColors.of(key, picture) }
    DisposableEffect(color) {
        val shown = color?.let(ScreenColors::add)
        onDispose { shown?.let(ScreenColors::remove) }
    }
}

/**
 * Paints an adaptive wallpaper laid out the style's own way, then a dark veil so light text stays easy to read.
 * [colors] are the three wallpaper colours.
 */
internal fun DrawScope.drawAdaptiveWallpaper(colors: List<Color>, style: AdaptiveStyle) {
    when (style.layout) {
        AdaptiveLayout.GLOW -> {
            drawRect(Brush.linearGradient(listOf(colors[0], colors[2], colors[1]), start = Offset.Zero, end = Offset(size.width, size.height)))
            drawRect(Brush.radialGradient(listOf(colors[0].copy(alpha = style.glow), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.08f), size.maxDimension * 0.7f))
            drawRect(Brush.radialGradient(listOf(colors[1].copy(alpha = style.glow * 0.8f), Color.Transparent), Offset(size.width * 0.1f, size.height * 0.9f), size.maxDimension * 0.75f))
        }
        AdaptiveLayout.WASH -> {
            drawRect(Color(0xFF07070A))
            drawRect(
                Brush.verticalGradient(
                    0f to colors[2].copy(alpha = style.glow + 0.2f),
                    0.55f to colors[2].copy(alpha = style.glow * 0.4f),
                    1f to Color.Transparent,
                ),
            )
            drawRect(Brush.radialGradient(listOf(colors[0].copy(alpha = style.glow * 0.6f), Color.Transparent), Offset(size.width, size.height), size.maxDimension * 0.6f))
        }
        AdaptiveLayout.AURORA -> {
            drawRect(colors[2])
            drawRect(Brush.radialGradient(listOf(colors[0].copy(alpha = style.glow), Color.Transparent), Offset(size.width * 0.2f, size.height * 0.15f), size.maxDimension * 0.55f))
            drawRect(Brush.radialGradient(listOf(colors[1].copy(alpha = style.glow), Color.Transparent), Offset(size.width * 0.9f, size.height * 0.45f), size.maxDimension * 0.55f))
            drawRect(Brush.radialGradient(listOf(colors[0].copy(alpha = style.glow * 0.7f), Color.Transparent), Offset(size.width * 0.35f, size.height * 0.95f), size.maxDimension * 0.5f))
        }
    }
    // Darker towards the bottom, where lists end.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = style.scrim * 0.6f), Color.Black.copy(alpha = style.scrim))))
}
