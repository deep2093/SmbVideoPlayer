package com.deepak.videolibrary.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * Swipe between the app's sections: right-to-left goes to the next section, left-to-right to the previous one.
 *
 * Two paths feed it:
 * - a horizontal drag that nothing inside used (for example on a vertical list), and
 * - the part of a horizontal scroll that an inner pager could not use because it was already at its first or
 *   last page (the Folders tabs), so swiping past "Original folders" continues to "Videos".
 */
@Composable
fun Modifier.sectionSwipe(onNext: () -> Unit, onPrevious: () -> Unit): Modifier {
    val density = LocalDensity.current
    val distance = with(density) { 72.dp.toPx() }
    val flingSpeed = with(density) { 900.dp.toPx() }
    val next by rememberUpdatedState(onNext)
    val previous by rememberUpdatedState(onPrevious)

    val connection = remember {
        object : NestedScrollConnection {
            var leftover = 0f

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || abs(available.x) <= abs(available.y)) return Offset.Zero
                leftover += available.x
                // Taking the leftover also stops the edge "stretch" while a section change is coming.
                return Offset(available.x, 0f)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                val travelled = leftover
                leftover = 0f
                when {
                    travelled < 0f && (travelled <= -distance || available.x <= -flingSpeed) -> next()
                    travelled > 0f && (travelled >= distance || available.x >= flingSpeed) -> previous()
                    else -> Unit
                }
                return Velocity.Zero
            }
        }
    }

    return this
        .nestedScroll(connection)
        .pointerInput(Unit) {
            var travelled = 0f
            detectHorizontalDragGestures(
                onDragStart = { travelled = 0f },
                onDragCancel = { travelled = 0f },
                onDragEnd = {
                    if (travelled <= -distance) next() else if (travelled >= distance) previous()
                    travelled = 0f
                },
            ) { change, amount ->
                change.consume()
                travelled += amount
            }
        }
}
