package com.deepak.videolibrary.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Reusable glass optical surface. Uses existing Haze 1.6.1 (NOT Haze 2 APIs).
 *
 * The `hazeState` must belong to a parent content area with `Modifier.hazeSource(hazeState)`.
 * For video surfaces that Haze cannot safely capture, pass `liveBlur = false`:
 * this gives a readable frosted-looking tint without repeatedly capturing frames.
 */
@Composable
fun Modifier.skyWingGlassSurface(
    hazeState: HazeState? = null,
    shape: Shape = RoundedCornerShape(22.dp),
    liveBlur: Boolean = true,
    elevated: Boolean = false,
    reduceTransparency: Boolean = false,
): Modifier {
    val theme = LocalLibraryTheme.current
    if (!theme.glass) {
        return this.clip(shape).background(theme.card)
    }
    val crystal = theme.skyWingGlassFamily() == "CRYSTAL"
    val edge = if (theme.light) Color(0x6654708C) else Color(0x70D7EEFF)
    val tint = when {
        reduceTransparency -> if (theme.light) Color(0xFFF6FAFF) else Color(0xFF122038)
        theme.light && crystal -> Color(0xCFFFFFFF)
        theme.light -> Color(0xBDECF5FF)
        crystal -> Color(0xB8203347)
        else -> Color(0xB3162050)
    }
    return this
        .then(if (elevated) Modifier.shadow(12.dp, shape) else Modifier)
        .clip(shape)
        .then(
            if (liveBlur && hazeState != null && !reduceTransparency) {
                Modifier.hazeEffect(
                    state = hazeState,
                    style = HazeStyle(
                        backgroundColor = theme.menu,
                        tints = listOf(HazeTint(theme.glassTint), HazeTint(tint.copy(alpha = 0.24f))),
                        blurRadius = if (crystal) 20.dp else 28.dp,
                        noiseFactor = 0.035f,
                        fallbackTint = HazeTint(tint),
                    ),
                )
            } else {
                Modifier.background(tint)
            },
        )
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

@Composable
fun SkyWingGlassCard(
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    liveBlur: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.skyWingGlassSurface(hazeState = hazeState, liveBlur = liveBlur)
            .padding(14.dp),
        content = content,
    )
}
