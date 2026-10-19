package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.scan.ScanMode
import com.deepak.videolibrary.scan.ScanStatus

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScanScreen(vm: LibraryViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val pending by vm.pendingDirs.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val active by vm.scanActive.collectAsStateWithLifecycle()
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val thumbsActive by vm.thumbsActive.collectAsStateWithLifecycle()
    val thumbsDone by vm.thumbsDone.collectAsStateWithLifecycle()
    val thumbsPending by vm.thumbsPending.collectAsStateWithLifecycle()
    val thumbsFailed by vm.thumbsFailed.collectAsStateWithLifecycle()
    val busy = active || thumbsActive || vm.artworkMaintenance
    var scanMenu by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showFailures by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    var showErrors by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    // Centered, readable column on tablets; full width on phones.
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 760.dp).fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Text(stringResource(R.string.ui_scan_sync_33eca10), style = MaterialTheme.typography.titleLarge) }

            vm.notice?.let { message ->
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, end = 4.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(message, Modifier.weight(1f).padding(vertical = 12.dp))
                            TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.ui_ok_9ce3bd4)) }
                        }
                    }
                }
            }

            // ---- 1. Library scan: status, numbers, actions ----
            item {
                ScanSection("Library scan") {
                    val s = session
                    if (s == null) {
                        Text(stringResource(R.string.ui_no_scan_has_run_yet_save_your_server_in_se_1e216fb),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val shownStatus = if (s.status == ScanStatus.RUNNING && !active) "INTERRUPTED" else s.status
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                prettyStatus(shownStatus),
                                style = MaterialTheme.typography.titleSmall,
                                color = when (shownStatus) {
                                    ScanStatus.RUNNING -> MaterialTheme.colorScheme.primary
                                    ScanStatus.FAILED -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${s.mode.lowercase().replaceFirstChar { it.uppercase() }} scan",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (active) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanStat("Folders scanned", s.dirsScanned.toString(), Modifier.weight(1f))
                            ScanStat("Waiting", pending.toString(), Modifier.weight(1f))
                            ScanStat("Errors", s.errorCount.toString(), Modifier.weight(1f), warn = s.errorCount > 0)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ScanStat("Found this scan", s.videosFound.toString(), Modifier.weight(1f))
                            ScanStat("In library", total.toString(), Modifier.weight(1f))
                            ScanStat(
                                "Finished",
                                s.finishedAt?.let { DateUtils.getRelativeTimeSpanString(it).toString() } ?: "—",
                                Modifier.weight(1f),
                            )
                        }
                        if (active && s.currentDir.isNotEmpty()) {
                            Text(
                                "Scanning: ${s.currentDir}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { vm.startScan(if (session == null) ScanMode.INITIAL else ScanMode.REFRESH) }, enabled = !busy) {
                            Text(if (session == null) "Scan library" else "Refresh library")
                        }
                        if (active || thumbsActive) OutlinedButton(onClick = vm::stopScan) { Text(stringResource(R.string.ui_stop_9e25347)) }
                        Box {
                            TextButton(onClick = { scanMenu = true }, enabled = !busy) { Text(stringResource(R.string.ui_more_actions_a1e34f9)) }
                            androidx.compose.material3.DropdownMenu(scanMenu, { scanMenu = false }) {
                                listOf("Full scan" to ScanMode.INITIAL, "Resume interrupted scan" to ScanMode.RESUME, "Retry scan errors" to ScanMode.RETRY).forEach { (label, mode) ->
                                    androidx.compose.material3.DropdownMenuItem(text = { Text(label) }, onClick = { scanMenu = false; vm.startScan(mode) })
                                }
                            }
                        }
                    }
                }
            }

            // ---- 2. Thumbnails ----
            item {
                val all = thumbsDone + thumbsPending + thumbsFailed
                ScanSection("Thumbnails") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ScanStat("Ready", thumbsDone.toString(), Modifier.weight(1f))
                        ScanStat("Waiting", thumbsPending.toString(), Modifier.weight(1f))
                        ScanStat("Failed", thumbsFailed.toString(), Modifier.weight(1f), warn = thumbsFailed > 0)
                    }
                    if (busy && thumbsPending > 0) {
                        LinearProgressIndicator(
                            progress = { thumbsDone.toFloat() / all.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    HorizontalDivider()
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::startThumbnails, enabled = !busy && thumbsPending > 0) { Text(stringResource(R.string.ui_generate_fc45f9b)) }
                        OutlinedButton(onClick = vm::retryFailedThumbnails, enabled = !busy && thumbsFailed > 0) { Text(stringResource(R.string.ui_retry_failed_7b6ca4d)) }
                        TextButton(onClick = vm::clearThumbnailCache, enabled = !busy) { Text(if(vm.artworkMaintenance) "Clearing…" else "Clear cache") }
                    }
                    if (thumbsFailed > 0) {
                        TextButton(onClick = { showFailures = !showFailures }) { Text(if (showFailures) "Hide failed thumbnails" else "View failed thumbnails") }
                        if (showFailures) ThumbnailFailures(vm)
                    }
                }
            }

            // ---- 3. Automatic sync ----
            item { AutomaticSyncSummary() }

            // ---- 4. Scan errors ----
            if (errors.isNotEmpty()) {
                item { TextButton(onClick = { showErrors = !showErrors }) { Text(if (showErrors) "Hide scan errors" else "View scan errors (${errors.size})") } }
                if (showErrors) items(errors, key = { it.id }) { e ->
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Text(
                            e.path.ifEmpty { "(connection)" },
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        HorizontalDivider(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private fun prettyStatus(status: String) =
    status.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** A titled card; every Scan section uses the same padding, spacing and colours. */
@Composable
private fun ScanSection(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** Compact value-over-label tile used for scan and thumbnail counters. */
@Composable
private fun ScanStat(label: String, value: String, modifier: Modifier = Modifier, warn: Boolean = false) {
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Compose lint misses the explicit producer value assignments below.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun AutomaticSyncSummary() {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.getSharedPreferences(
            com.deepak.videolibrary.scan.PeriodicSyncWorker.DIAGNOSTICS + "-" + com.deepak.videolibrary.ServiceLocator.get(context).libraryId,
            android.content.Context.MODE_PRIVATE,
        )
    }
    val snapshot by produceState(initialValue = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L), prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        awaitDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val status = when (val raw = snapshot.first.orEmpty()) {
        "RUNNING" -> "Syncing…"
        "COMPLETED" -> "Up to date"
        "COMPLETED_WITH_ERRORS" -> "Finished with scan errors"
        "INTERRUPTED" -> "Interrupted · will try again"
        else -> when {
            raw.startsWith("SKIPPED:") -> raw.removePrefix("SKIPPED:").trim()
            raw.startsWith("FAILED:") -> "Couldn't sync · will retry"
            else -> "Not run yet"
        }
    }
    ScanSection("Automatic sync") {
        Text(stringResource(R.string.ui_runs_about_every_12_hours_when_your_server_8ce90d9),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanStat("Status", status, Modifier.weight(1f))
            ScanStat(
                "Last successful sync",
                if (snapshot.second == 0L) {
                    "Never"
                } else {
                    java.text.DateFormat
                        .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(java.util.Date(snapshot.second))
                },
                Modifier.weight(1f),
            )
        }
    }
}
