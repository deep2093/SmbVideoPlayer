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
import androidx.compose.material.icons.filled.Folder
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.artwork.ArtworkRepository
import com.deepak.videolibrary.artwork.CoverCrop
import java.io.File

/**
 * One folder card, shared by every folder list so they look and behave the same: cover in the page's card shape,
 * name and a short detail line, an options button. Tapping opens the folder; holding the card opens [onLongPress]
 * (its options); holding the NAME renames it when [onRename] is given.
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
) {
    val tall = aspect < 1f
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
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    minLines = if (listMode) 1 else 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onRename != null) {
                        Modifier.combinedClickable(onClick = onClick, onLongClick = onRename, onLongClickLabel = "Rename")
                    } else {
                        Modifier
                    },
                )
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onOptions != null) IconButton(onClick = onOptions) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for $title") }
        }
    }
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
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
