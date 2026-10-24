package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The bounded batch already loaded by the engine. Queue boundary loading remains engine-owned. */
@Composable
internal fun SkyWingQueueDialog(names: List<String>, current: Int, hasMore: Boolean, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback queue") },
        text = {
            Column {
                if (hasMore) Text("More videos load automatically at the end of this batch.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    itemsIndexed(names) { index, name ->
                        TextButton(onClick = { onSelect(index) }, modifier = Modifier.fillMaxWidth().semantics { selected = index == current }) {
                            Text("${index + 1}. $name", modifier = Modifier.weight(1f), color = if (index == current) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
