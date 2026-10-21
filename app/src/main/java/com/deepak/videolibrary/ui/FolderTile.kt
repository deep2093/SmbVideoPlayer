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
    onOptions: (() -> Unit)? = null,
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
    val caption: @Composable () -> Unit = {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = if (onOptions == null) 12.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).onGloballyPositioned { name = it }) {
                Text(
                    title,
                    minLines = if (listMode) 1 else 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (selected != null) {
                Icon(
                    if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                    contentDescription = if (selected) "Selected" else "Not selected",
                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp).size(24.dp),
                )
            } else if (onOptions != null) {
                IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for $title") }
            }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
            .background(if (selected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .onGloballyPositioned { card = it }
            .pointerInput(Unit) {
                // Watches where each press starts without taking the touch away from the click handling below.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
                            downAt = event.changes.first().position
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = { if (onRename != null && selected == null && heldName()) onRename() else onLongPress() },
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
