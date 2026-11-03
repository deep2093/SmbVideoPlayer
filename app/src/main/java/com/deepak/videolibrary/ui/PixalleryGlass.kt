package com.deepak.videolibrary.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/*
 * Pixallery's glass, ported to SkyWing (4 Nov 2026): the specular rim, floating "chrome" glass that blurs what scrolls
 * under it, frosted panels, depth (a light top edge and a shaded bottom edge), frosted selection edges, soft shadows
 * under toolbar text and icons, and lens glass (Android 13+, Blur slider at 0). The video picture is never tinted or
 * blurred: none of this is used in the player.
 */

/** How glass behaves right now: Settings > Appearance > Glass. */
@Immutable
data class GlassSettings(
    /** False with "Glass effect" off: solid panels and bars, no blur. */
    val glassOn: Boolean = true,
    /** Blur strength: 1 = the theme's own, 0 = none. The Blur slider (0 to 4) sets it. */
    val blurScale: Float = 1f,
    /** Lens glass on floating bars: Android 13+, while the Blur slider is at 0. */
    val lensOn: Boolean = false,
)

val LocalGlassSettings = staticCompositionLocalOf { GlassSettings() }

/** Given only to floating bars, never to anything inside the content they record (it would draw itself). */
val LocalLensGlass = staticCompositionLocalOf<SkyWingLensGlass?> { null }

/** A fine specular rim: bright at the top-left, fading, with a softer glint at the bottom-right. */
@Composable
fun Modifier.glassRim(shape: Shape, strength: Float = 1f): Modifier {
    val theme = LocalLibraryTheme.current
    val rim = theme.rim
    val k = theme.rimStrength * strength
    return border(
        1.dp,
        Brush.linearGradient(listOf(rim.copy(alpha = (0.7f * k).coerceAtMost(1f)), rim.copy(alpha = (0.12f * k).coerceAtMost(1f)), rim.copy(alpha = (0.4f * k).coerceAtMost(1f)))),
        shape,
    )
}

/**
 * Depth for glass: a soft light along the top and a little shade along the bottom, so a bar or card reads as a thick
 * pane above the page. Off with the glass effect off.
 */
@Composable
fun Modifier.glassDepth(): Modifier {
    if (!LocalGlassSettings.current.glassOn) return this
    return this.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.4f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.14f),
            ),
        )
    }
}

/**
 * Glass for floating chrome (navigation dock, top bars) over scrolling covers. Blurs what passes underneath through
 * [hazeState] when glass is on; Blur at 0 gives lens glass (Android 13+) or a clear film; otherwise the solid fallback.
 * Android 10 and 11 cannot blur: the blur library falls back to the same tint by itself.
 */
@Composable
fun Modifier.glassChrome(shape: Shape, hazeState: HazeState?, elevation: Dp = 16.dp): Modifier {
    val theme = LocalLibraryTheme.current
    val settings = LocalGlassSettings.current
    val glassOn = settings.glassOn && !theme.reduceTransparency
    val blur = glassOn && hazeState != null
    val lens = LocalLensGlass.current
    val lensActive = lens != null && settings.lensOn && glassOn
    val fallback = if (theme.glassFallback == Color.Unspecified) theme.menu else theme.glassFallback
    val shadowColor = Color.Black.copy(alpha = if (theme.light) 0.18f else 0.6f)
    return this
        .shadow(elevation, shape, ambientColor = shadowColor, spotColor = shadowColor)
        .clip(shape)
        // Always in place when a lens exists (it only draws while active), so switching it on needs no re-attach.
        .then(if (lens != null) lens.lens(shape, theme.glassTint.copy(alpha = LENS_TINT_ALPHA), lensActive) else Modifier)
        .then(
            when {
                lensActive -> Modifier
                !glassOn -> Modifier.background(theme.menu.copy(alpha = 1f))
                blur && settings.blurScale <= 0f ->
                    // No blur: a clear film only, a little stronger so text still reads over sharp covers.
                    Modifier.background(theme.glassTint.copy(alpha = maxOf(theme.glassTint.alpha, 0.30f)))
                blur && hazeState != null -> Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        // Transparent, so where nothing scrolls under a bar the real wallpaper shows through.
                        backgroundColor = Color.Transparent,
                        tints = listOf(HazeTint(theme.glassTint)),
                        blurRadius = theme.blurRadius * settings.blurScale,
                        noiseFactor = 0f,
                        fallbackTint = HazeTint(fallback),
                    ),
                )
                else -> Modifier.background(fallback)
            },
        )
        .then(if (glassOn) Modifier.glassDepth() else Modifier)
        .glassRim(shape)
}

/** Glass for panels and cards on the wallpaper: a see-through frosted film with depth, solid with the glass effect off. */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val theme = LocalLibraryTheme.current
    val film = theme.panelFill
    return if (film != null && LocalGlassSettings.current.glassOn && !theme.reduceTransparency) {
        this.clip(shape).background(film).glassDepth().glassRim(shape)
    } else {
        val solid = if (theme.glassFallback == Color.Unspecified) theme.card else theme.menu
        this.clip(shape).background(solid).glassRim(shape)
    }
}

/** On a pill filled with [accentSurface]: in frosted themes a fine bright edge so the film reads as glass. */
@Composable
fun Modifier.frostedEdge(shape: Shape): Modifier {
    val theme = LocalLibraryTheme.current
    val edge = theme.rim
    return if (theme.accentFill == null) this else this.border(
        1.dp,
        Brush.linearGradient(listOf(edge.copy(alpha = 0.55f), edge.copy(alpha = 0.12f), edge.copy(alpha = 0.3f))),
        shape,
    )
}

/** Soft shadows under toolbar text and icons while lens glass is on, so labels stand off the see-through glass. */
@Composable
fun glassDepthOn(): Boolean {
    val settings = LocalGlassSettings.current
    return settings.lensOn && settings.glassOn && LocalLibraryTheme.current.glass
}

@Composable
private fun depthColor(): Color = Color.Black.copy(alpha = if (LocalLibraryTheme.current.light) 0.32f else 0.6f)

@Composable
fun DepthIcon(icon: ImageVector, contentDescription: String?, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (glassDepthOn()) {
            Icon(icon, contentDescription = null, tint = depthColor(),
                modifier = Modifier.matchParentSize().offset(y = 1.5.dp).blur(2.dp, BlurredEdgeTreatment.Unbounded))
        }
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.matchParentSize())
    }
}

@Composable
fun DepthText(
    text: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    Box(modifier) {
        if (glassDepthOn()) {
            Text(text, color = depthColor(), style = style, maxLines = maxLines, overflow = overflow,
                modifier = Modifier.offset(y = 1.5.dp).blur(2.dp, BlurredEdgeTreatment.Unbounded))
        }
        Text(text, color = color, style = style, maxLines = maxLines, overflow = overflow)
    }
}

/** Lens glass is more see-through than the normal blur; labels lean on the soft shadow under them. */
private const val LENS_TINT_ALPHA = 0.42f

/**
 * Lens glass (Pixallery, Android 13+): the content behind a floating bar is recorded once, and the bar draws it bent like
 * the thick edge of a piece of glass, with a light blur, instead of only blurring it.
 */
interface SkyWingLensGlass {
    /** On the content that scrolls behind the bar. Stays attached; only records while [active]. */
    fun source(active: Boolean): Modifier

    /** On the bar: when [active], draws the recorded content bent like glass, then [tint] over it. */
    @Composable
    fun lens(shape: Shape, tint: Color, active: Boolean): Modifier
}

@Composable
fun rememberLensGlass(): SkyWingLensGlass? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val layer = rememberGraphicsLayer()
    return remember(layer) { AndroidLensGlass(layer) }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidLensGlass(private val sourceLayer: GraphicsLayer) : SkyWingLensGlass {
    private var sourceOrigin by mutableStateOf(Offset.Zero)

    override fun source(active: Boolean): Modifier = Modifier
        .onGloballyPositioned { val o = it.positionInRoot(); if (o != sourceOrigin) sourceOrigin = o }
        .drawWithContent {
            if (active) {
                sourceLayer.record { this@drawWithContent.drawContent() }
                drawLayer(sourceLayer)
            } else {
                drawContent()
            }
        }

    @Composable
    override fun lens(shape: Shape, tint: Color, active: Boolean): Modifier {
        val effectLayer = rememberGraphicsLayer()
        val shader = remember { RuntimeShader(LENS_SHADER) }
        var barOrigin by remember { mutableStateOf(Offset.Zero) }
        val applied = remember { FloatArray(3) { -1f } }
        return Modifier
            .onGloballyPositioned {
                val o = it.positionInRoot()
                if (o != barOrigin) barOrigin = o
            }
            .drawWithContent {
                if (!active) {
                    drawContent()
                    return@drawWithContent
                }
                val outline = shape.createOutline(size, layoutDirection, this)
                val radius = if (outline is Outline.Rounded) outline.roundRect.topLeftCornerRadius.x else 0f
                if (applied[0] != size.width || applied[1] != size.height || applied[2] != radius) {
                    shader.setFloatUniform("size", size.width, size.height)
                    shader.setFloatUniform("radius", radius)
                    shader.setFloatUniform("bevel", minOf(size.height * 0.5f, 36.dp.toPx()))
                    effectLayer.renderEffect = RenderEffect.createChainEffect(
                        RenderEffect.createRuntimeShaderEffect(shader, "content"),
                        RenderEffect.createBlurEffect(6f, 6f, Shader.TileMode.CLAMP),
                    ).asComposeRenderEffect()
                    applied[0] = size.width
                    applied[1] = size.height
                    applied[2] = radius
                }
                effectLayer.record {
                    translate(sourceOrigin.x - barOrigin.x, sourceOrigin.y - barOrigin.y) { drawLayer(sourceLayer) }
                }
                drawLayer(effectLayer)
                drawRect(tint)
                drawContent()
            }
    }
}

/** Near the bar's rounded edge the picture is taken from further in (bent and magnified); the whole lens zooms a little. */
private const val LENS_SHADER = """
uniform shader content;
uniform float2 size;
uniform float radius;
uniform float bevel;

float sdRound(float2 p, float2 hs, float r) {
    float2 q = abs(p) - hs + float2(r);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 fragCoord) {
    float2 hs = size * 0.5;
    float2 p = fragCoord - hs;
    float d = sdRound(p, hs, radius);
    float depth = clamp(-d, 0.0, bevel);
    float t = 1.0 - depth / bevel;
    float2 g = float2(
        sdRound(p + float2(1.0, 0.0), hs, radius) - sdRound(p - float2(1.0, 0.0), hs, radius),
        sdRound(p + float2(0.0, 1.0), hs, radius) - sdRound(p - float2(0.0, 1.0), hs, radius));
    float2 n = g / max(length(g), 0.0001);
    float bend = t * t * bevel * 0.9;
    float2 samplePos = fragCoord - n * bend;
    samplePos = hs + (samplePos - hs) * 0.94;
    return content.eval(samplePos);
}
"""

/** Dark colours for the player, whatever the app theme; same type and shapes as the rest of the app. */
@Composable
fun PlayerDarkColors(content: @Composable () -> Unit) {
    androidx.compose.material3.MaterialTheme(
        colorScheme = androidx.compose.material3.darkColorScheme(
            primary = Color.White, onPrimary = Color.Black,
            secondary = Color.White, onSecondary = Color.Black,
            tertiary = Color.White, onTertiary = Color.Black,
            background = Color.Black, onBackground = Color.White,
            surface = Color(0xFF1A1A1E), onSurface = Color.White,
            surfaceVariant = Color(0xFF2A2A30), onSurfaceVariant = Color(0xD9FFFFFF),
            surfaceContainerHigh = Color(0xFF26262C), outline = Color(0x99FFFFFF), outlineVariant = Color(0x4DFFFFFF),
        ),
        typography = androidx.compose.material3.MaterialTheme.typography,
        shapes = androidx.compose.material3.MaterialTheme.shapes,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.White, content = content)
    }
}
