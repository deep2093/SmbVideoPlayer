package com.deepak.videolibrary.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** The index letter for a name: its first letter, or "#" for names that start with a digit or symbol. */
fun sectionLetter(name: String): String {
    val first = name.trim().firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * Wraps a library grid with a scrolling aid:
 * - Fast scroll: drag along the right edge to jump through the list. While dragging, a bubble shows the
 *   letter of the current position when [labelFor] gives one (lists sorted by name); otherwise it just scrolls.
 *
 * Taps near the edge still reach the grid; only a vertical drag that starts there becomes a fast scroll.
 */
@Composable
fun FastScrollBox(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    labelFor: ((Int) -> String?)? = null,
    bottomInset: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val edgePx = with(density) { 28.dp.toPx() }
    val trackTopPx = with(density) { 8.dp.toPx() }
    val trackBottomPx = with(density) { (bottomInset + 8.dp).toPx() }
    val thumbHeightPx = with(density) { 48.dp.toPx() }
    val label by rememberUpdatedState(labelFor)

    val total by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember { derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    // Only long lists (more than about three screens) get a fast scroller.
    val scrollerEnabled by remember { derivedStateOf { visible > 0 && total > visible * 3 } }

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var target by remember { mutableIntStateOf(-1) }
    var boxHeight by remember { mutableFloatStateOf(0f) }
    val listFraction by remember {
        derivedStateOf {
            val range = (state.layoutInfo.totalItemsCount - state.layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / range).coerceIn(0f, 1f)
        }
    }

    // Drive the list from the drag position; the latest target wins.
    LaunchedEffect(state) {
        snapshotFlow { target }.collectLatest { index -> if (index >= 0) state.scrollToItem(index) }
    }

    // The thumb shows while the list moves or is being dragged, then fades away.
    var thumbShown by remember { mutableStateOf(false) }
    LaunchedEffect(state.isScrollInProgress, dragging) {
        if (state.isScrollInProgress || dragging) {
            thumbShown = true
        } else {
            delay(1500)
            thumbShown = false
        }
    }
    val thumbAlpha by animateFloatAsState(if (thumbShown && scrollerEnabled) 1f else 0f, tween(250), label = "fast_scroll_thumb")

    Box(
        modifier
            .onSizeChanged { boxHeight = it.height.toFloat() }
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (!scrollerEnabled || down.position.x < size.width - edgePx) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (!started) {
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (abs(dy) > slop && abs(dy) > abs(dx)) {
                                started = true
                                dragging = true
                            } else if (abs(dx) > slop) {
                                break // A sideways swipe is not a fast scroll.
                            }
                        }
                        if (started) {
                            // Claimed before the grid sees it, so the grid does not scroll by itself as well.
                            change.consume()
                            val track = (boxHeight - trackTopPx - trackBottomPx).coerceAtLeast(1f)
                            dragFraction = ((change.position.y - trackTopPx) / track).coerceIn(0f, 1f)
                            target = (dragFraction * (state.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)).roundToInt()
                        }
                    }
                    dragging = false
                }
            },
    ) {
        content()

        // Thumb on the right edge.
        val fraction = if (dragging) dragFraction else listFraction
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val h = if (boxHeight > 0f) boxHeight else 0f
                    val track = (h - trackTopPx - trackBottomPx - thumbHeightPx).coerceAtLeast(0f)
                    IntOffset(0, (trackTopPx + fraction * track).roundToInt())
                }
                .padding(end = 2.dp)
                .alpha(thumbAlpha)
                .size(width = if (dragging) 8.dp else 6.dp, height = 48.dp)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = if (dragging) 0.85f else 0.55f), RoundedCornerShape(50)),
        )

        // Letter bubble beside the thumb while dragging a list sorted by name.
        val letter = if (dragging && target >= 0) label?.invoke(target) else null
        if (letter != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset {
                        val track = (boxHeight - trackTopPx - trackBottomPx - thumbHeightPx).coerceAtLeast(0f)
                        val bubble = with(density) { 64.dp.toPx() }
                        IntOffset(
                            -with(density) { 28.dp.roundToPx() },
                            (trackTopPx + fraction * track + thumbHeightPx / 2 - bubble / 2).roundToInt(),
                        )
                    }
                    .size(64.dp)
                    .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(letter, color = MaterialTheme.colorScheme.onTertiary, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            }
        }

    }
}
