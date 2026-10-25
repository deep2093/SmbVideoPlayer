package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File

internal val GRID_MIN_CARD = 188.dp

/** A grid of video cards with optional header rows above it and a scroll bar at the right edge. */
@Composable
internal fun VideoGridView(
    entries: List<WindowsMediaEntry>,
    state: LazyGridState,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
    emptyText: String = "No videos here yet.",
) {
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(GRID_MIN_CARD),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (header != null) header()
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText) }
            }
            items(entries, key = { it.path.lowercase() }) { entry ->
                VideoCard(
                    entry = entry,
                    progress = watchedFraction(history, entry),
                    selected = if (selection.active) entry.path in selection.paths else null,
                    onClick = {
                        if (selection.active) selection.toggle(entry.path)
                        else onPlay(entry.file, entries.map { it.file })
                    },
                    onLongClick = { selection.toggle(entry.path) },
                )
            }
        }
        GridScrollThumb(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** Folder cards in a grid. */
internal data class FolderItem(
    val key: String,
    val title: String,
    val detail: String,
    val cover: WindowsMediaEntry?,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

@Composable
internal fun FolderGridView(
    items: List<FolderItem>,
    state: LazyGridState,
    modifier: Modifier = Modifier,
    emptyText: String,
) {
    Box(modifier.fillMaxSize()) {
        if (items.isEmpty()) EmptyNote(emptyText)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(GRID_MIN_CARD),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(items, key = { it.key }) { item ->
                FolderCard(item.title, item.detail, item.cover, item.onClick, onLongClick = item.onLongClick)
            }
        }
        GridScrollThumb(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** The line at the top of an opened folder: back arrow, name, how many videos, and its actions. */
@Composable
internal fun FolderHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyIconButton(SkyIcon.Back, "Back", onBack)
        PageTitle(title, subtitle, Modifier.weight(1f))
        actions()
    }
}

/** Replaces a page's header while videos are ticked. */
@Composable
internal fun SelectionBar(
    selection: SelectionState,
    visible: List<WindowsMediaEntry>,
    custom: CustomFolders,
    onPlay: (File, List<File>) -> Unit,
    insideCustom: String? = null,
    modifier: Modifier = Modifier,
) {
    val theme = LocalSkyTheme.current
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    val chosen = visible.filter { it.path in selection.paths }
    Row(
        modifier.fillMaxWidth().skyGlass(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkyIconButton(SkyIcon.Close, "Stop selecting", { selection.clear() })
        Text("${selection.paths.size} selected", color = theme.ink, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        problem?.let { Text(it, color = theme.accent, style = MaterialTheme.typography.bodySmall) }
        SkyButton("Select all", { selection.paths = selection.paths + visible.map { it.path } }, primary = false)
        SkyButton("Play", { chosen.firstOrNull()?.let { onPlay(it.file, chosen.map { c -> c.file }) } }, enabled = chosen.isNotEmpty(), primary = false, icon = SkyIcon.Play)
        if (insideCustom != null) {
            SkyButton("Remove from folder", {
                problem = custom.removeFrom(insideCustom, selection.paths)
                if (problem == null) selection.clear()
            }, enabled = selection.paths.isNotEmpty(), primary = false, icon = SkyIcon.Delete)
        }
        SkyButton("Add to folder", { picking = true }, enabled = selection.paths.isNotEmpty(), icon = SkyIcon.Folder)
    }
    if (picking) {
        AddToFolderDialog(
            folders = custom.folders.map { it.name },
            onDismiss = { picking = false },
            onPick = { name ->
                problem = custom.addTo(name, selection.paths)
                if (problem == null) selection.clear()
            },
            onNew = { naming = true },
        )
    }
    if (naming) {
        NameDialog(
            title = "New custom folder",
            initial = "",
            confirmLabel = "Create",
            hint = "The selected videos go into it.",
            onDismiss = { naming = false; picking = false },
            onConfirm = { name ->
                problem = custom.create(name, selection.paths)
                if (problem == null) selection.clear()
            },
        )
    }
}

@Composable
internal fun AddToFolderDialog(
    folders: List<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
) {
    val theme = LocalSkyTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text("Add to a custom folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { name ->
                    SkyButton(name, { onPick(name); onDismiss() }, Modifier.fillMaxWidth(), primary = false, icon = SkyIcon.Folder)
                }
                SkyButton("New folder…", { onNew() }, Modifier.fillMaxWidth(), icon = SkyIcon.Add)
            }
        },
        confirmButton = {},
        dismissButton = { SkyButton("Cancel", onDismiss, primary = false) },
    )
}

/** Options for a folder card (opened by holding the card). */
@Composable
internal fun OptionsDialog(
    title: String,
    options: List<Triple<String, SkyIcon, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    val theme = LocalSkyTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title, maxLines = 2) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { (label, icon, action) ->
                    SkyButton(label, { onDismiss(); action() }, Modifier.width(280.dp), primary = false, icon = icon)
                }
            }
        },
        confirmButton = {},
        dismissButton = { SkyButton("Close", onDismiss, primary = false) },
    )
}
