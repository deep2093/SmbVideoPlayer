package com.deepak.videolibrary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepak.videolibrary.artwork.ArtworkRepository
import com.deepak.videolibrary.artwork.CoverCrop
import java.io.File

/**
 * One folder card, shared by every folder list so they look and behave the same: cover in the page's card shape,
 * name and a short detail line, an options button. Tapping opens the folder; holding the card opens [onLongPress]
 * (its options); holding the NAME (or its detail line) renames it when [onRename] is given.
 *
 * In selection mode ([selected] is not null) a tap ticks or unticks the card instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderTile(
    title: String,
    detail: String,
    cover: File?,
    aspect: Float,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    listMode: Boolean = false,
    onRename: (() -> Unit)? = null,
    selected: Boolean? = null,
) {
    val tall = aspect < 1f
    // Where the finger went down, and where the name sits, both in the card's own coordinates. One gesture
    // handler for the whole card decides on long-press whether the name was held (rename) or the rest (options).
    var downAt by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Unspecified) }
    var card by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var name by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    fun heldName(): Boolean {
        val c = card ?: return false
        val n = name ?: return false
        if (!c.isAttached || !n.isAttached || downAt == androidx.compose.ui.geometry.Offset.Unspecified) return false
        return c.localBoundingBoxOf(n).inflate(6f).contains(downAt)
    }
    val shown = remember(cover, tall) { ArtworkRepository.forShape(cover, tall) }
    val artwork: @Composable () -> Unit = {
        Box(
            if (listMode) Modifier.width(104.dp).height(if (tall) 138.dp else 68.dp) else Modifier.fillMaxWidth().aspectRatio(aspect),
            contentAlignment = Alignment.Center,
        ) {
            if (shown != null) {
                FileImage(shown, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                }
            }
        }
    }
    // A slim caption (same type as video cards) so the picture takes most of the card.
    val caption: @Composable () -> Unit = {
        Row(Modifier.padding(start = 8.dp, top = 5.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).onGloballyPositioned { name = it }) {
                Text(title, style = CardTitleStyle(), maxLines = if (listMode) 2 else 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = CardDetailStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected != null) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(20.dp),
                )
            }
        }
    }
    // Drag to reorder: holding the card and then sliding moves it (the grid's [FolderReorder]); holding and letting go
    // without moving opens the options as before. Off while selecting.
    val reorder = LocalFolderReorder.current
    val reorderKey = LocalReorderKey.current
    val canDrag = reorder != null && reorderKey != null && reorder.enabled && selected == null
    val gesture = remember { TileGesture() }
    val longAction: () -> Unit = { if (onRename != null && selected == null && heldName()) onRename() else onLongPress() }
    val currentLongAction by androidx.compose.runtime.rememberUpdatedState(longAction)
    val currentCanDrag by androidx.compose.runtime.rememberUpdatedState(canDrag)
    val currentReorder by androidx.compose.runtime.rememberUpdatedState(reorder)
    val currentKey by androidx.compose.runtime.rememberUpdatedState(reorderKey)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            // Calm (flat) themes: the caption sits on the page itself, no card.
            .background(
                if (selected == true) MaterialTheme.colorScheme.primaryContainer
                else if (LocalLibraryTheme.current.flat) androidx.compose.ui.graphics.Color.Transparent
                else MaterialTheme.colorScheme.surface,
            )
            .onGloballyPositioned { card = it }
            .pointerInput(Unit) {
                // Watches each press before the click handling below sees it: where it starts, and (once held) whether
                // the finger slides on to move the card.
                try { awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                                downAt = change.position
                                gesture.down = true
                                gesture.held = false
                                gesture.dragging = false
                            }
                            androidx.compose.ui.input.pointer.PointerEventType.Move -> if (gesture.held && currentCanDrag) {
                                val r = currentReorder
                                val key = currentKey
                                if (!gesture.dragging && r != null && key != null &&
                                    (change.position - downAt).getDistance() > viewConfiguration.touchSlop
                                ) {
                                    gesture.dragging = true
                                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    r.start(key)
                                }
                                if (gesture.dragging) {
                                    card?.takeIf { it.isAttached }?.let { r?.move(it.localToRoot(change.position)) }
                                    change.consume()
                                }
                            }
                            androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                                gesture.down = false
                                if (gesture.dragging) {
                                    currentReorder?.end()
                                    change.consume()
                                } else if (gesture.held) {
                                    currentLongAction()
                                }
                                gesture.held = false
                                gesture.dragging = false
                            }
                            else -> Unit
                        }
                    }
                } } finally {
                    // The card left the screen mid-move: let the grid go.
                    if (gesture.dragging) { gesture.dragging = false; currentReorder?.end() }
                }
            }
            .combinedClickable(
                onClick = onClick,
                // While a finger is down on a movable card, the hold only arms the move; what happens is decided when it
                // slides (move) or lifts (options). Accessibility long-clicks have no finger down and act at once.
                onLongClick = { if (canDrag && gesture.down) gesture.held = true else longAction() },
                onLongClickLabel = if (onRename != null) "Rename or options" else "Options",
            ),
    ) {
        if (listMode) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                artwork()
                Box(Modifier.weight(1f)) { caption() }
            }
        } else {
            artwork()
            caption()
        }
    }
}

/** What a finger on a folder card is doing (not drawn, so not state). */
private class TileGesture {
    var down = false
    var held = false
    var dragging = false
}

/** A simple rename dialog. [hint] says what renaming changes. */
@Composable
internal fun RenameDialog(title: String, current: String, hint: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodySmall)
                PillTextField(name, { name = it }, label = "Name", modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && '/' !in name && '\\' !in name, onClick = {
                onRename(name.trim())
                onDismiss()
            }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Aspect used when no page setting applies. */
internal const val DEFAULT_CARD_ASPECT = CoverCrop.CARD_ASPECT


/** Card name text, shared by video and folder cards so every grid reads the same (2 sizes under body text). */
@Composable
internal fun CardTitleStyle() = MaterialTheme.typography.bodyMedium.copy(
    fontSize = 12.sp,
    lineHeight = 15.sp,
    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
)

/** Card detail line (size, number of videos). */
@Composable
internal fun CardDetailStyle() = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp)

/** Grid spacing shared by every folder and video grid: slim margins and gaps so the pictures get the room. */
internal val PAGE_MARGIN = 10.dp
internal val GRID_GAP = 8.dp
internal val GRID_ROW_GAP = 12.dp
