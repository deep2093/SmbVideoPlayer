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
