package com.deepak.videolibrary.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The Folders section shows the page buttons (search, card shape, add, download…) on the "Veyra" line
 * instead of a second title row under the tabs. Each folder page hands its top bar to the hub, and the hub shows
 * the bar of the page that is in view.
 */

/** Where one folder page puts its top bar: the hub's slot for that page. */
internal class HubBarSlot(val bars: MutableMap<Int, @Composable () -> Unit>, val index: Int)

internal val LocalHubBarSlot = compositionLocalOf<HubBarSlot?> { null }

/** Title used by a top bar that sits on the hub line ("Veyra"), or null for the page's own title. */
internal val LocalHubTitle = compositionLocalOf<String?> { null }

/** A page's top bar: handed to the hub when there is one, otherwise drawn in place. */
@Composable
internal fun HubTopBar(content: @Composable () -> Unit) {
    val slot = LocalHubBarSlot.current
    if (slot == null) {
        content()
        return
    }
    SideEffect { slot.bars[slot.index] = content }
    DisposableEffect(slot) { onDispose { slot.bars.remove(slot.index) } }
}

/** The hub's top line: the bar of [page], or just the title and the download button when the page has none. */
@Composable
internal fun HubBarLine(bars: Map<Int, @Composable () -> Unit>, page: Int, title: String, modifier: Modifier = Modifier) {
    // A fixed height, so switching tabs never moves the tabs or the page up or down.
    Box(modifier.fillMaxWidth().height(HUB_LINE_HEIGHT), contentAlignment = Alignment.CenterStart) {
        val bar = bars[page]
        CompositionLocalProvider(LocalHubTitle provides title) {
            if (bar != null) {
                bar()
            } else {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppIconMark(modifier = Modifier.padding(end = 10.dp))
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f), maxLines = 1)
                    LocalTopBarEnd.current()
                }
            }
        }
    }
}

private val HUB_LINE_HEIGHT = 56.dp

@Composable
internal fun rememberHubBars(): MutableMap<Int, @Composable () -> Unit> = remember { mutableStateMapOf<Int, @Composable () -> Unit>() }

/** One of the Folders tabs, whose name can be held to rename it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HubTab(title: String, selected: Boolean, onClick: () -> Unit, onRename: () -> Unit) {
    Box(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onRename, onLongClickLabel = "Rename $title")
            .padding(vertical = 14.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------- Selecting several folders on one page ---------- */

/** Folders ticked on one page; [page] keeps a selection from leaking into another page. */
data class FolderSelection(val page: String, val keys: Set<String>)

internal object SelectionPages {
    const val CUSTOM = "select:custom"
    const val VIRTUAL = "select:virtual"
    const val ORIGINAL = "select:original"
}

internal fun UiSession.selectionOn(page: String): FolderSelection? = folderSelection?.takeIf { it.page == page }

/** Null when [page] is not selecting, otherwise whether [key] is ticked. */
internal fun UiSession.tick(page: String, key: String): Boolean? = selectionOn(page)?.let { key in it.keys }

internal fun UiSession.toggleFolder(page: String, key: String) {
    val current = selectionOn(page)
    folderSelection = if (current == null) {
        FolderSelection(page, setOf(key))
    } else {
        val keys = if (key in current.keys) current.keys - key else current.keys + key
        if (keys.isEmpty()) null else current.copy(keys = keys)
    }
}

internal fun UiSession.selectAllFolders(page: String, keys: Collection<String>) {
    if (keys.isNotEmpty()) folderSelection = FolderSelection(page, keys.toSet())
}

internal fun UiSession.endSelection(page: String) {
    if (folderSelection?.page == page) folderSelection = null
}

/** The top bar while folders are being selected: close, count, select all, then the page's actions. */
@Composable
internal fun FolderSelectionBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onDownload: () -> Unit,
    onHide: (() -> Unit)?,
    onRemove: (() -> Unit)?,
    removeLabel: String = "Remove",
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Stop selecting") }
        Text("$count selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1)
        SelectAllToggle(allSelected = total in 1..count, onSelectAll = onSelectAll, onDeselectAll = onClose)
        SelectionActions(onDownload, onHide, onRemove?.let { { confirmRemove = true } })
    }
    if (confirmRemove && onRemove != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("$removeLabel $count ${if (count == 1) "folder" else "folders"}?") },
            text = { Text("They are taken off this page only. Files on your drive are not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text(removeLabel) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RowScope.SelectionActions(onDownload: () -> Unit, onHide: (() -> Unit)?, onRemove: (() -> Unit)?) {
    IconAction(Icons.Filled.Download, "Download selected folders", onClick = onDownload)
    if (onHide != null) IconAction(Icons.Filled.VisibilityOff, "Hide selected folders from this page", onClick = onHide)
    if (onRemove != null) IconAction(Icons.Filled.DeleteOutline, "Remove selected folders from this page", onClick = onRemove)
}

/* ---------- Tapping the current section again ---------- */

/** Runs [action] each time the section's button is tapped again while this page is in view. */
@Composable
internal fun OnScrollTopRequest(vm: LibraryViewModel, active: Boolean = true, action: suspend () -> Unit) {
    val request = vm.ui.scrollTopRequest
    val seen = remember { intArrayOf(request) }
    val latest = androidx.compose.runtime.rememberUpdatedState(action)
    val isActive = androidx.compose.runtime.rememberUpdatedState(active)
    androidx.compose.runtime.LaunchedEffect(request) {
        if (request == seen[0]) return@LaunchedEffect
        seen[0] = request
        if (isActive.value) latest.value()
    }
}

/** Scrolls a grid to the top on a repeated tap of its section's button. */
@Composable
internal fun ScrollTopOnRequest(vm: LibraryViewModel, state: androidx.compose.foundation.lazy.grid.LazyGridState, active: Boolean = true) {
    OnScrollTopRequest(vm, active) { state.glideToTop() }
}

/** From far down, jump most of the way first so the glide to the top stays short and smooth. */
internal suspend fun androidx.compose.foundation.lazy.grid.LazyGridState.glideToTop() {
    if (firstVisibleItemIndex > 24) scrollToItem(12)
    animateScrollToItem(0)
}

internal suspend fun androidx.compose.foundation.lazy.LazyListState.glideToTop() {
    if (firstVisibleItemIndex > 24) scrollToItem(12)
    animateScrollToItem(0)
}


/**
 * One button for Select all and Unselect all: it shows Select all until everything is ticked, then turns into
 * Unselect all. The icon flips with a short scale-and-fade so the change of job is visible.
 */
@Composable
internal fun SelectAllToggle(allSelected: Boolean, onSelectAll: () -> Unit, onDeselectAll: () -> Unit) {
    val label = if (allSelected) "Unselect all" else "Select all"
    IconButton(onClick = { if (allSelected) onDeselectAll() else onSelectAll() }) {
        androidx.compose.animation.AnimatedContent(
            targetState = allSelected,
            transitionSpec = {
                (androidx.compose.animation.fadeIn(tween(180)) + androidx.compose.animation.scaleIn(tween(220), initialScale = 0.6f)) togetherWith
                    (androidx.compose.animation.fadeOut(tween(120)) + androidx.compose.animation.scaleOut(tween(160), targetScale = 0.6f))
            },
            label = "select_all_toggle",
        ) { all ->
            Icon(if (all) Icons.Filled.Deselect else Icons.Filled.SelectAll, contentDescription = label)
        }
    }
}


/* ---------- Choosing and ordering the Folders tabs ---------- */

/** Visible Folders tabs in order, stored as "0,1,2" (0 Custom, 1 Virtual, 2 Original). */
object FolderSections {
    val ALL = listOf(0, 1, 2)

    fun parse(text: String?): List<Int> {
        val parsed = text.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in ALL }.distinct()
        return parsed.ifEmpty { ALL }
    }

    /** All three in display order: shown ones first in their order, then hidden ones. */
    fun arranged(shown: List<Int>): List<Int> = shown + ALL.filterNot { it in shown }
}

/**
 * Settings › Appearance: which Folders tabs show and in what order. Untick a tab to hide it (one always stays);
 * the arrows move a tab left or right.
 */
@Composable
internal fun FolderTabsSetting(vm: LibraryViewModel) {
    val shown = vm.folderSections
    val rows = FolderSections.arranged(shown)
    Text("Folders tabs", style = MaterialTheme.typography.titleMedium)
    Text(
        "Choose which tabs Folders shows and their order. At least one stays on.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    rows.forEach { section ->
        val on = section in shown
        val at = shown.indexOf(section)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(
                checked = on,
                enabled = !on || shown.size > 1,
                onCheckedChange = { tick -> vm.updateFolderSections(if (tick) shown + section else shown - section) },
            )
            Text(vm.sectionName(section), modifier = Modifier.weight(1f), color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(enabled = on && at > 0, onClick = {
                vm.updateFolderSections(shown.toMutableList().apply { add(at - 1, removeAt(at)) })
            }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move ${vm.sectionName(section)} earlier") }
            IconButton(enabled = on && at in 0 until shown.lastIndex, onClick = {
                vm.updateFolderSections(shown.toMutableList().apply { add(at + 1, removeAt(at)) })
            }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move ${vm.sectionName(section)} later") }
        }
    }
}
