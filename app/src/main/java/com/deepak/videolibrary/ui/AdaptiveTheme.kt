package com.deepak.videolibrary.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How an adaptive theme turns the colours of the covers on screen into its background. [values] are the brightness
 * of the three gradient colours (kept low so white text stays readable), [minSaturation]..[maxSaturation] how
 * colourful they may be, [glow] the strength of the soft lights and [scrim] a dark veil over everything.
 */
data class AdaptiveStyle(
    val values: List<Float>,
    val minSaturation: Float,
    val maxSaturation: Float,
    val glow: Float,
    val scrim: Float,
    val fallback: List<Color>,
)

/** Colours of the pictures currently on screen. Each shown picture adds one colour and removes it when it leaves. */
object ScreenColors {
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

/** The main colour of a picture: its most colourful bright area, or its average when it has none. Cached by file. */
object CoverColors {
    private val cache = LruCache<String, Int>(1024)

    fun cached(path: String): Color? = cache.get(path)?.let { Color(it) }

    suspend fun of(path: String, image: ImageBitmap): Color? = cached(path) ?: withContext(Dispatchers.Default) {
        try {
            val small = Bitmap.createScaledBitmap(image.asAndroidBitmap(), 6, 6, true)
            val pixels = IntArray(36)
            small.getPixels(pixels, 0, 6, 0, 0, 6, 6)
            if (small !== image.asAndroidBitmap()) small.recycle()
            pickMain(pixels)?.also { cache.put(path, it) }?.let { Color(it) }
        } catch (_: Exception) {
            null
        }
    }

    /** The most colourful, reasonably bright sample; the average when everything is grey. */
    internal fun pickMain(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        val hsv = FloatArray(3)
        var best = 0
        var bestScore = -1f
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            android.graphics.Color.colorToHSV(p, hsv)
            val score = if (hsv[2] < 0.15f) 0f else hsv[1] * hsv[2]
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        if (bestScore >= 0.12f) return best or (0xFF shl 24)
        val n = pixels.size
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}

/** Three gradient colours for [style] from the colours on screen. */
internal fun adaptivePalette(colors: List<Color>, style: AdaptiveStyle): List<Color> {
    if (colors.isEmpty()) return style.fallback
    val hsv = colors.map { c -> FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgbInt(), it) } }
    val first = hsv.maxBy { it[1] * it[2] }
    fun hueGap(a: Float, b: Float) = minOf(kotlin.math.abs(a - b), 360f - kotlin.math.abs(a - b))
    val second = hsv.filter { it[1] > 0.15f }.maxByOrNull { hueGap(it[0], first[0]) }
        ?.takeIf { hueGap(it[0], first[0]) > 25f } ?: floatArrayOf((first[0] + 40f) % 360f, first[1], first[2])
    val third = run {
        // The average hue (as a direction, so red near 0 and 360 averages correctly).
        var x = 0.0
        var y = 0.0
        hsv.forEach { x += kotlin.math.cos(Math.toRadians(it[0].toDouble())) * it[1]; y += kotlin.math.sin(Math.toRadians(it[0].toDouble())) * it[1] }
        val hue = ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360.0) % 360.0).toFloat()
        floatArrayOf(hue, hsv.map { it[1] }.average().toFloat(), 0.5f)
    }
    return listOf(first, second, third).mapIndexed { i, c ->
        val s = c[1].coerceIn(style.minSaturation, style.maxSaturation)
        Color(android.graphics.Color.HSVToColor(floatArrayOf(c[0], s, style.values[i])))
    }
}

private fun Color.toArgbInt(): Int =
    android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

/** The gradient colours of an adaptive theme, easing slowly from one set to the next as the screen changes. */
@Composable
internal fun rememberAdaptiveColors(style: AdaptiveStyle): List<State<Color>> {
    val target by remember(style) { derivedStateOf { adaptivePalette(ScreenColors.current(), style) } }
    return List(3) { i -> animateColorAsState(target[i], tween(1400), label = "adaptive_$i") }
}

/** Paints an adaptive wallpaper: a diagonal gradient of [colors], two soft lights and a readability veil. */
internal fun DrawScope.drawAdaptiveWallpaper(colors: List<Color>, style: AdaptiveStyle) {
    drawRect(Brush.linearGradient(listOf(colors[0], colors[2], colors[1]), start = Offset.Zero, end = Offset(size.width, size.height)))
    drawRect(
        Brush.radialGradient(
            listOf(colors[0].copy(alpha = style.glow), Color.Transparent),
            center = Offset(size.width * 0.85f, size.height * 0.08f),
            radius = size.maxDimension * 0.7f,
        ),
    )
    drawRect(
        Brush.radialGradient(
            listOf(colors[1].copy(alpha = style.glow * 0.8f), Color.Transparent),
            center = Offset(size.width * 0.1f, size.height * 0.9f),
            radius = size.maxDimension * 0.75f,
        ),
    )
    // Darker towards the bottom, where lists and the navigation bar sit.
    drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = style.scrim * 0.6f), Color.Black.copy(alpha = style.scrim))))
}
