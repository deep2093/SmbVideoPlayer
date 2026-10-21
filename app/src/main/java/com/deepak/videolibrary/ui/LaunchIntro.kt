package com.deepak.videolibrary.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first

/** Colours of the launcher icon, so the system splash, this intro and the icon read as one piece. */
internal val LaunchBackground = Color(0xFF11192A)
private val IntroBlue = Color(0xFF3184F7)
private val IntroPurple = Color(0xFFAE50F9)

/** The intro plays once per app start, not when the screen rotates or the theme changes. */
internal object LaunchIntroState {
    var played = false

    /** Set when the system splash has finished shrinking away; the intro waits for it so nothing plays unseen. */
    var splashGone by mutableStateOf(false)

    /** The library under the intro grows from 96% and fades in as the intro lifts. */
    fun Modifier.introReveal(reveal: () -> Float): Modifier = graphicsLayer {
        val r = reveal()
        if (r < 1f) {
            alpha = 0.35f + 0.65f * r
            val s = 0.96f + 0.04f * r
            scaleX = s
            scaleY = s
        }
    }
}

/**
 * The opening animation, after the system splash (the icon) has shrunk away: a play button eases in, a thin
 * bar fills from left to right underneath it, then the whole layer fades while the library settles into place
 * behind it. Everything animates on the graphics layer only, so it stays smooth while the library loads.
 *
 * [content] receives the reveal fraction (0 hidden, 1 fully shown) so it can scale/fade in under the intro.
 */
@Composable
internal fun LaunchIntro(content: @Composable (reveal: () -> Float) -> Unit) {
    var showing by remember { mutableStateOf(!LaunchIntroState.played) }
    val play = remember { Animatable(if (showing) 0f else 1f) }
    val bar = remember { Animatable(if (showing) 0f else 1f) }
    val reveal = remember { Animatable(if (showing) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!showing) return@LaunchedEffect
        LaunchIntroState.played = true
        // Start once the splash icon has gone (or after a moment if Android skipped its exit animation).
        kotlinx.coroutines.withTimeoutOrNull(3_000) {
            androidx.compose.runtime.snapshotFlow { LaunchIntroState.splashGone }.first { it }
        }
        play.animateTo(1f, tween(260, easing = LinearOutSlowInEasing))
        bar.animateTo(1f, tween(520, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)))
        reveal.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        showing = false
    }
    Box(Modifier.fillMaxSize()) {
        content { reveal.value }
        if (showing) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - reveal.value }
                    .background(LaunchBackground),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(
                        Modifier.size(56.dp).graphicsLayer {
                            alpha = play.value
                            val s = 0.7f + 0.3f * play.value
                            scaleX = s
                            scaleY = s
                        },
                    ) {
                        // A soft rounded play triangle in the icon's blue-to-purple.
                        val w = size.width
                        val h = size.height
                        val path = Path().apply {
                            moveTo(w * 0.24f, h * 0.14f)
                            lineTo(w * 0.86f, h * 0.5f)
                            lineTo(w * 0.24f, h * 0.86f)
                            close()
                        }
                        val brush = Brush.linearGradient(listOf(IntroBlue, IntroPurple), Offset.Zero, Offset(w, h))
                        drawPath(path, brush, style = Fill)
                        drawPath(path, brush, style = Stroke(width = w * 0.08f, join = StrokeJoin.Round))
                    }
                    Spacer(Modifier.height(22.dp))
                    Canvas(Modifier.width(132.dp).height(4.dp).graphicsLayer { alpha = play.value }) {
                        val r = CornerRadius(size.height / 2, size.height / 2)
                        drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = r)
                        val filled = size.width * bar.value
                        if (filled > 0f) {
                            drawRoundRect(
                                Brush.horizontalGradient(listOf(IntroBlue, IntroPurple), 0f, size.width),
                                size = Size(filled, size.height),
                                cornerRadius = r,
                            )
                        }
                    }
                }
            }
        }
    }
}
