package com.deepak.videolibrary.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.scrollBy

/**
 * A manual folder order per list ("custom", "virtual", "original:<folder>"...). Folders you placed come first in the
 * order you chose; folders not placed yet keep their usual order after them.
 */
object FolderOrder {
    private const val FILE = "folder_order"
    private const val SEP = "\u0001"

    /** Bumped on every save so lists that read it redraw. */
    var version by mutableStateOf(0)
        private set

    fun load(context: Context, listId: String): List<String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(listId, null)
            ?.split(SEP)?.filter { it.isNotEmpty() }.orEmpty()

    fun save(context: Context, listId: String, keys: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(listId, keys.joinToString(SEP)).apply()
        version++
    }

    fun clear(context: Context, listId: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(listId).apply()
        version++
    }

    /** [items] with the saved order applied; anything not in it keeps its place after the placed ones. */
    fun <T> apply(items: List<T>, saved: List<String>, key: (T) -> String): List<T> {
        if (saved.isEmpty()) return items
        val rank = HashMap<String, Int>(saved.size)
        saved.forEachIndexed { i, k -> rank.putIfAbsent(k, i) }
        return items.withIndex()
            .sortedWith(compareBy({ rank[key(it.value)] ?: Int.MAX_VALUE }, { it.index }))
            .map { it.value }
    }

    /** Moves the entry at [from] to [to] (both 0-based); out of range does nothing. */
    fun <T> move(list: List<T>, from: Int, to: Int): List<T> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        return list.toMutableList().also { it.add(to, it.removeAt(from)) }
    }
}

/** The shown order for [items] in list [listId], following manual changes as they are saved. */
@Composable
fun <T> rememberFolderOrder(listId: String, items: List<T>, key: (T) -> String): List<T> {
    val context = androidx.compose.ui.platform.LocalContext.current
    val version = FolderOrder.version
    val saved = remember(listId, version) { FolderOrder.load(context, listId) }
    return remember(items, saved) { FolderOrder.apply(items, saved, key) }
}

/**
 * Reorder mode: the folders in their current order with arrows and "to top". Moving 5th to 3rd is two taps on the
 * up arrow. Saved when you press Done.
 */
@Composable
fun ReorderFoldersDialog(listId: String, entries: List<Pair<String, String>>, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val order = remember { mutableStateListOf<Pair<String, String>>().also { it.addAll(entries) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Reorder folders") },
        text = {
            Column {
                Text("Use the arrows to move a folder up or down. Your order stays until you change it.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp).padding(top = 8.dp)) {
                    itemsIndexed(order, key = { _, e -> e.first }) { index, entry ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${index + 1}", modifier = Modifier.padding(end = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(entry.second, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(enabled = index > 0, onClick = { val moved = FolderOrder.move(order.toList(), index, 0); order.clear(); order.addAll(moved) }) {
                                Icon(Icons.Filled.VerticalAlignTop, contentDescription = "Move to top")
                            }
                            IconButton(enabled = index > 0, onClick = { val moved = FolderOrder.move(order.toList(), index, index - 1); order.clear(); order.addAll(moved) }) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = "Move up")
                            }
                            IconButton(enabled = index < order.lastIndex, onClick = { val moved = FolderOrder.move(order.toList(), index, index + 1); order.clear(); order.addAll(moved) }) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = "Move down")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { FolderOrder.save(context, listId, order.map { it.first }); onClose() }) { Text("Done") } },
        dismissButton = {
            Row {
                TextButton(onClick = { FolderOrder.clear(context, listId); onClose() }) { Text("Reset") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

/*
 * Drag to reorder (4 Nov 2026), like Pixallery's albums: hold a folder, then slide it to where you want it. The folders
 * around make way, and the page scrolls when you hold it near the top or bottom. Holding and letting go without moving
 * still opens the folder's options as before.
 */

/** The folder grid's drag state. One per list; the grid hands it to its cards through [LocalFolderReorder]. */
@androidx.compose.runtime.Stable
internal class FolderReorder(
    private val gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val edgePx: Float,
) {
    /** Off while selecting or searching (only part of the list shows then). */
    var enabled by mutableStateOf(true)

    /** The order shown now, as saved keys. Kept up to date by the page. */
    var keys: List<String> = emptyList()

    /** Saves a new order. */
    var onPlace: (List<String>) -> Unit = {}

    /** The saved key of a grid item (its LazyGrid key), or null for items that can't move (videos, totals). */
    var keyOfItem: (Any) -> String? = { null }

    /** A small tick each time the folder takes a new place. */
    var onTick: () -> Unit = {}

    /** The folder being moved, or null. */
    var dragging by mutableStateOf<String?>(null)
        private set

    /** The grid's top-left corner on screen. */
    var gridOrigin = androidx.compose.ui.geometry.Offset.Zero

    private var finger: androidx.compose.ui.geometry.Offset? = null
    private var scroller: kotlinx.coroutines.Job? = null

    fun start(key: String) {
        dragging = key
    }

    /** The finger moved to [root] (screen coordinates). */
    fun move(root: androidx.compose.ui.geometry.Offset) {
        if (dragging == null) return
        val local = root - gridOrigin
        finger = local
        reorderAt(local)
        if (scroller == null) {
            scroller = scope.launch {
                while (true) {
                    val p = finger
                    if (p != null) {
                        val height = gridState.layoutInfo.viewportSize.height.toFloat()
                        // Faster the closer the finger is to the edge.
                        val step = when {
                            p.y < edgePx -> -(1f - p.y / edgePx).coerceIn(0f, 1f) * MAX_EDGE_STEP
                            p.y > height - edgePx -> ((p.y - (height - edgePx)) / edgePx).coerceIn(0f, 1f) * MAX_EDGE_STEP
                            else -> 0f
                        }
                        if (step != 0f) {
                            gridState.scrollBy(step)
                            reorderAt(p)
                        }
                    }
                    kotlinx.coroutines.delay(16)
                }
            }
        }
    }

    fun end() {
        scroller?.cancel()
        scroller = null
        finger = null
        dragging = null
    }

    private fun reorderAt(p: androidx.compose.ui.geometry.Offset) {
        val mine = dragging ?: return
        val info = gridState.layoutInfo
        val hit = info.visibleItemsInfo.firstOrNull { item ->
            val top = item.offset.y - info.viewportStartOffset
            p.y >= top && p.y < top + item.size.height &&
                p.x >= item.offset.x && p.x < item.offset.x + item.size.width
        } ?: return
        val target = keyOfItem(hit.key) ?: return
        if (target == mine) return
        val from = keys.indexOf(mine)
        val to = keys.indexOf(target)
        if (from < 0 || to < 0) return
        val next = FolderOrder.move(keys, from, to)
        keys = next
        onPlace(next)
        onTick()
    }

    private companion object {
        /** The most the page scrolls in one frame while a folder is held at the edge (pixels). */
        const val MAX_EDGE_STEP = 28f
    }
}

/** The grid's drag state, for the cards inside it. */
internal val LocalFolderReorder = androidx.compose.runtime.compositionLocalOf<FolderReorder?> { null }

/** The saved key of the card being drawn, for [FolderTile]. */
internal val LocalReorderKey = androidx.compose.runtime.compositionLocalOf<String?> { null }

/**
 * Drag state for one folder list. [listId] is the [FolderOrder] list; [keys] the order shown now; [keyOfItem] turns a
 * grid item key into a saved key. [enabled] is false while selecting or searching.
 */
@Composable
internal fun rememberFolderReorder(
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    listId: String,
    keys: List<String>,
    enabled: Boolean,
    keyOfItem: (Any) -> String?,
): FolderReorder {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val edge = with(androidx.compose.ui.platform.LocalDensity.current) { 96.dp.toPx() }
    val reorder = remember(gridState) { FolderReorder(gridState, scope, edge) }
    reorder.enabled = enabled && keys.size > 1
    if (reorder.dragging == null) reorder.keys = keys
    reorder.keyOfItem = keyOfItem
    reorder.onPlace = { FolderOrder.save(context, listId, it) }
    reorder.onTick = { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove) }
    return reorder
}

/** Gives the grid's drag state to its cards and records where the grid sits on screen. */
internal fun Modifier.folderReorderGrid(reorder: FolderReorder): Modifier =
    this.then(Modifier.onGloballyPositioned { reorder.gridOrigin = it.positionInRoot() })

/**
 * One movable card in a folder grid: the folders around make way when it moves, and it is drawn lifted above them
 * while held. [key] is its saved key.
 */
@Composable
internal fun androidx.compose.foundation.lazy.grid.LazyGridItemScope.ReorderableFolder(
    key: String,
    reorder: FolderReorder?,
    content: @Composable () -> Unit,
) {
    val lifted = reorder != null && reorder.dragging == key
    val shape = MaterialTheme.shapes.medium
    androidx.compose.foundation.layout.Box(
        Modifier
            .animateItem(fadeInSpec = null, fadeOutSpec = null)
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                if (lifted) {
                    scaleX = 1.06f
                    scaleY = 1.06f
                    alpha = 0.92f
                    shadowElevation = 18.dp.toPx()
                    this.shape = shape
                }
            },
    ) {
        androidx.compose.runtime.CompositionLocalProvider(LocalFolderReorder provides reorder, LocalReorderKey provides key) {
            content()
        }
    }
}
