package com.skywing.desktop

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.areAnyPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Drag-box selection over a grid, as in Windows Explorer: press on an empty spot (left button) or anywhere (right
 * button), drag, and every card the box touches is selected. With Ctrl or Shift held the box adds to what is already
 * selected. [onBox] receives the keys of the cards inside the box, whether to add to the selection, and whether
 * this is the first frame of a new box.
 */
internal fun Modifier.boxSelect(state: LazyGridState, onBox: (keys: Set<Any>, additive: Boolean, started: Boolean) -> Unit): Modifier = composed {
    val theme = LocalSkyTheme.current
    var box by remember { mutableStateOf<Rect?>(null) }
    val latest by rememberUpdatedState(onBox)
    this
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val press = awaitPointerEvent(PointerEventPass.Initial)
                    if (press.type != PointerEventType.Press) continue
                    val start = press.changes.firstOrNull()?.position ?: continue
                    val items = state.layoutInfo.visibleItemsInfo
                    val onCard = items.any { Rect(it.offset.x.toFloat(), it.offset.y.toFloat(), (it.offset.x + it.size.width).toFloat(), (it.offset.y + it.size.height).toFloat()).contains(start) }
                    val right = press.buttons.isSecondaryPressed
                    if (!right && !(press.buttons.isPrimaryPressed && !onCard)) continue
                    // Leave the scroll bar at the right edge alone.
                    if (start.x > size.width - 20.dp.toPx()) continue
                    val additive = press.keyboardModifiers.isCtrlPressed || press.keyboardModifiers.isShiftPressed
                    var dragging = false
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: break
                        if (event.type == PointerEventType.Release || !event.buttons.areAnyPressed) {
                            if (dragging) event.changes.forEach { it.consume() }
                            break
                        }
                        if (event.type != PointerEventType.Move) continue
                        if (!dragging && (change.position - start).getDistance() > viewConfiguration.touchSlop) dragging = true
                        if (!dragging) continue
                        val rect = Rect(
                            Offset(minOf(start.x, change.position.x), minOf(start.y, change.position.y)),
                            Offset(maxOf(start.x, change.position.x), maxOf(start.y, change.position.y)),
                        )
                        box = rect
                        val inside = state.layoutInfo.visibleItemsInfo.filter {
                            rect.overlaps(Rect(Offset(it.offset.x.toFloat(), it.offset.y.toFloat()), Size(it.size.width.toFloat(), it.size.height.toFloat())))
                        }.map { it.key }.toSet()
                        latest(inside, additive, !started)
                        started = true
                        event.changes.forEach { it.consume() }
                    }
                    box = null
                }
            }
        }
        .drawWithContent {
            drawContent()
            box?.let { r ->
                drawRect(theme.accent.copy(alpha = 0.18f), r.topLeft, r.size)
                drawRect(theme.accent.copy(alpha = 0.85f), r.topLeft, r.size, style = Stroke(1.dp.toPx()))
            }
        }
}
