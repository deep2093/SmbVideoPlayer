package com.deepak.videolibrary.ui

import android.app.DownloadManager
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.downloads.DownloadItem
import com.deepak.videolibrary.downloads.DownloadPaths
import com.deepak.videolibrary.downloads.DownloadPrefs
import com.deepak.videolibrary.downloads.DownloadSpeed

/** Overall progress of the downloads still running for this server, or null when nothing is downloading. */
private fun activeProgress(items: List<DownloadItem>): Float? {
    val active = items.filter { it.active }
    if (active.isEmpty()) return null
    val total = active.sumOf { maxOf(it.size, it.bytesDone) }
    return if (total > 0) (active.sumOf { it.bytesDone }.toDouble() / total).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * The download button shown at the top right of every page. While files are downloading, a small bar under the
 * icon fills with the overall progress.
 */
@Composable
internal fun DownloadsButton(vm: LibraryViewModel) {
    val items by vm.downloads.collectAsStateWithLifecycle()
    val progress = activeProgress(items)
    IconAction(Icons.Filled.Download, if (progress != null) "Downloads, ${(progress * 100).toInt()}% done" else "Downloads", onClick = vm::openDownloads) {
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).width(20.dp).height(3.dp),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}

/** An icon button whose name appears when it is long-pressed (and is read by screen readers). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box {
            IconButton(onClick = onClick, enabled = enabled) { Icon(icon, contentDescription = label) }
            overlay()
        }
    }
}

/**
 * The Downloads page, opened from the download button: each file with its own bar and speed. Files go to Download/Veyra, keeping folder names.
 */
@Composable
internal fun DownloadsPage(vm: LibraryViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val items by vm.downloads.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)
    // Speeds are measured on a steady tick, so a stalled file drops to zero instead of keeping its last figure.
    val speed = remember { DownloadSpeed() }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            tick = System.currentTimeMillis()
        }
    }
    val running = items.filter { it.status == DownloadItem.RUNNING }
    // Recorded while composing so each row reads an up-to-date speed; the result is when it was measured.
    val measuredAt = remember(items, tick) {
        val now = System.currentTimeMillis()
        running.forEach { speed.record(it.id, it.bytesDone, now) }
        speed.keepOnly(running.map { it.id }.toSet())
        now
    }
    val active = items.filter { it.active }
    val completed = items.count { it.status == DownloadItem.DONE }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
            Text("Downloads", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1)
            if (active.isNotEmpty()) {
                IconAction(Icons.Filled.PlayArrow, "Resume downloads", onClick = vm::resumeDownloads)
                IconAction(Icons.Filled.Stop, "Cancel all downloads", onClick = vm::cancelAllDownloads)
            }
            IconAction(Icons.Filled.Close, "Clear completed downloads", enabled = completed > 0, onClick = vm::clearCompletedDownloads)
            IconAction(Icons.Filled.FolderOpen, "Open Download folder", onClick = {
                runCatching { context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            })
        }
        ParallelDownloadsSetting(vm, Modifier.padding(vertical = 4.dp))
        val waiting = active.count { it.libraryId != vm.currentLibraryId }
        if (waiting > 0) {
            Text(
                "$waiting ${if (waiting == 1) "file belongs" else "files belong"} to another saved server and will continue when you switch back to it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (items.isEmpty()) {
            Text(
                "Nothing downloaded yet. Use Download in the menu of any video or folder, in File Explorer, or on a selection. " +
                    "Files are saved to Download/${DownloadPaths.ROOT} on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(top = 8.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
        ) {
            items(items, key = { it.id }) { item ->
                DownloadRow(vm, item, if (item.status == DownloadItem.RUNNING && measuredAt > 0) speed.bytesPerSecond(item.id) else null)
                HorizontalDivider()
            }
        }
    }
}

/**
 * "Simultaneous downloads": 1 to 4 (1 by default), shown as one compact line with the current number. Tapping it
 * opens the choices; picking one closes them again. A new choice applies to the next file that starts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ParallelDownloadsSetting(vm: LibraryViewModel, modifier: Modifier = Modifier) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { open = !open }.padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Simultaneous downloads", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(vm.downloadParallel.toString(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Icon(
                if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (open) "Hide choices" else "Change simultaneous downloads",
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
            )
        }
        if (open) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
                val options = (DownloadPrefs.MIN_PARALLEL..DownloadPrefs.MAX_PARALLEL).toList()
                options.forEachIndexed { index, value ->
                    SegmentedButton(
                        selected = vm.downloadParallel == value,
                        onClick = {
                            vm.updateDownloadParallel(value)
                            open = false
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        icon = {},
                    ) { Text(value.toString()) }
                }
            }
        }
    }
}

/** A plain left-to-right progress bar: filled part, then the track, with no gap or end dot. */
@Composable
internal fun FillBar(progress: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.coerceIn(0f, 1f) },
        modifier = modifier.height(4.dp),
        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

@Composable
private fun DownloadRow(vm: LibraryViewModel, item: DownloadItem, bytesPerSecond: Double?) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val otherServer = item.active && item.libraryId != vm.currentLibraryId
    val status = when (item.status) {
        DownloadItem.QUEUED -> if (otherServer) "Waiting for its server" else if (item.bytesDone > 0) "Paused at ${(item.fraction * 100).toInt()}%" else "Queued"
        DownloadItem.RUNNING -> buildString {
            if (item.size > 0) append("${(item.fraction * 100).toInt()}% · ${size(item.bytesDone)} of ${size(item.size)}") else append("Starting…")
            if (bytesPerSecond != null) append(" · ${DownloadSpeed.format(bytesPerSecond)}")
        }
        DownloadItem.DONE -> "Done · ${size(maxOf(item.size, item.bytesDone))}"
        DownloadItem.FAILED -> "Failed: ${item.error ?: "unknown problem"}"
        else -> "Cancelled"
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                FillBar(item.fraction, Modifier.fillMaxWidth())
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
            item.active -> IconAction(Icons.Filled.Close, "Cancel download of ${item.name}", onClick = { vm.cancelDownload(item.id) })
            item.status == DownloadItem.FAILED || item.status == DownloadItem.CANCELLED -> {
                IconAction(Icons.Filled.Refresh, "Retry download of ${item.name}", onClick = { vm.retryDownload(item.id) })
                IconAction(Icons.Filled.DeleteOutline, "Remove ${item.name} from the list", onClick = { vm.removeDownload(item.id) })
            }
            else -> IconAction(Icons.Filled.DeleteOutline, "Remove ${item.name} from the list", onClick = { vm.removeDownload(item.id) })
        }
    }
}
