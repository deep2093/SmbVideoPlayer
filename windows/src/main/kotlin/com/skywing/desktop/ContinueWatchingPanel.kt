package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
internal fun ContinueWatchingPanel(refreshKey: Int, onResume: (File, Double) -> Unit) {
    val records = remember(refreshKey) { WindowsPlaybackHistoryStore().load() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Continue Watching", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        if (records.isEmpty()) {
            Text("Videos you stop before finishing will appear here.", color = Color(0xFFC6C8D1))
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().height(420.dp)) {
                items(records, key = { it.path.lowercase() }) { record ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onResume(File(record.path), record.position) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            val position = record.position.toLong().coerceAtLeast(0)
                            val duration = record.duration.toLong().coerceAtLeast(0)
                            Text(
                                File(record.path).name + "  " +
                                    "%d:%02d / %d:%02d".format(position / 60, position % 60, duration / 60, duration % 60)
                            )
                        }
                    }
                }
            }
        }
    }
}
