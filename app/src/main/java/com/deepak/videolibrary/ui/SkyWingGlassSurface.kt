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
 * Reusable glass surface, now Pixallery's glass: floating chrome (blurs [hazeState] content; the parent must use
 * `Modifier.hazeSource(hazeState)`) when [liveBlur] and a haze state are given, otherwise a frosted panel. Video surfaces
 * that Haze cannot safely capture pass `liveBlur = false`.
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
    if (!theme.glass) return this.clip(shape).background(theme.card)
    if (reduceTransparency) return this.clip(shape).background(theme.menu.copy(alpha = 1f)).glassRim(shape)
    return if (liveBlur && hazeState != null) {
        this.glassChrome(shape, hazeState, elevation = if (elevated) 16.dp else 0.dp)
    } else {
        this.then(if (elevated) Modifier.shadow(12.dp, shape) else Modifier).glassPanel(shape)
    }
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
