package com.deepak.videolibrary.ui

import android.app.DownloadManager
import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.downloads.DownloadItem
import com.deepak.videolibrary.downloads.DownloadPaths

/**
 * Downloads to this device: an overall bar for what is still running, then each file with its own live progress
 * bar, and cancel / retry / remove. Files go to Download/Video Library, keeping folder names.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DownloadsSection(vm: LibraryViewModel) {
    val items by vm.downloads.collectAsStateWithLifecycle()
    ScanSection("Downloads") {
        if (items.isEmpty()) {
            Text(
                "Nothing downloaded yet. Use Download in the menu of any video or folder, in File Explorer, or on a selection. " +
                    "Files are saved to Download/${DownloadPaths.ROOT} on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            DownloadsBody(vm, items)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadsBody(vm: LibraryViewModel, items: List<DownloadItem>) {
    val context = LocalContext.current
    var showAll by rememberSaveable { mutableStateOf(false) }
    val active = items.filter { it.active }
    val finished = items.filter { it.finished }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        if (active.isNotEmpty()) {
            val total = active.sumOf { maxOf(it.size, it.bytesDone) }
            val done = active.sumOf { it.bytesDone }
            val waiting = active.count { it.libraryId != vm.currentLibraryId }
            Text(
                "${active.size} ${if (active.size == 1) "file" else "files"} left" +
                    if (total > 0) " · ${Formatter.formatShortFileSize(context, done)} of ${Formatter.formatShortFileSize(context, total)}" else "",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            if (waiting > 0) {
                Text(
                    "$waiting ${if (waiting == 1) "file belongs" else "files belong"} to another saved server and will continue when you switch back to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text("All downloads finished.", style = MaterialTheme.typography.bodyMedium)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (active.isNotEmpty()) {
                OutlinedButton(onClick = vm::resumeDownloads) { Text("Resume") }
                OutlinedButton(onClick = vm::cancelAllDownloads) { Text("Cancel all") }
            }
            if (finished.isNotEmpty()) OutlinedButton(onClick = vm::clearFinishedDownloads) { Text("Clear finished") }
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }) { Text("Open Download folder") }
        }

        val shown = if (showAll) items else items.take(VISIBLE_ROWS)
        shown.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider()
            DownloadRow(vm, item)
        }
        if (items.size > VISIBLE_ROWS) {
            OutlinedButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAll) "Show fewer" else "Show all ${items.size}")
            }
        }
    }
}

private const val VISIBLE_ROWS = 20

@Composable
private fun DownloadRow(vm: LibraryViewModel, item: DownloadItem) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val otherServer = item.active && item.libraryId != vm.currentLibraryId
    val status = when (item.status) {
        DownloadItem.QUEUED -> if (otherServer) "Waiting for its server" else if (item.bytesDone > 0) "Paused at ${(item.fraction * 100).toInt()}%" else "Queued"
        DownloadItem.RUNNING -> if (item.size > 0) "${(item.fraction * 100).toInt()}% · ${size(item.bytesDone)} of ${size(item.size)}" else "Starting…"
        DownloadItem.DONE -> "Done · ${size(maxOf(item.size, item.bytesDone))}"
        DownloadItem.FAILED -> "Failed: ${item.error ?: "unknown problem"}"
        else -> "Cancelled"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DownloadPaths.relativePath(item.targetFolder).trimEnd('/'),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.status == DownloadItem.RUNNING || (item.status == DownloadItem.QUEUED && item.bytesDone > 0)) {
                LinearProgressIndicator(progress = { item.fraction }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == DownloadItem.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            item.active -> IconButton(onClick = { vm.cancelDownload(item.id) }) { Icon(Icons.Filled.Close, "Cancel download of ${item.name}") }
            item.status == DownloadItem.FAILED || item.status == DownloadItem.CANCELLED -> {
                IconButton(onClick = { vm.retryDownload(item.id) }) { Icon(Icons.Filled.Refresh, "Retry download of ${item.name}") }
                IconButton(onClick = { vm.removeDownload(item.id) }) { Icon(Icons.Filled.DeleteOutline, "Remove ${item.name} from the list") }
            }
            else -> IconButton(onClick = { vm.removeDownload(item.id) }) { Icon(Icons.Filled.DeleteOutline, "Remove ${item.name} from the list") }
        }
    }
}
