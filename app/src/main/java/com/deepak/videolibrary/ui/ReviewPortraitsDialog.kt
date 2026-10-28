package com.deepak.videolibrary.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.artwork.ArtworkOverride
import com.deepak.videolibrary.artwork.online.PerformerCandidate
import kotlinx.coroutines.launch

/**
 * Folders whose portrait was not applied automatically (Manual mode, or several people with the same name). For each,
 * tap the right picture, skip the folder, or search yourself (Custom) in Change cover.
 */
@Composable
internal fun ReviewPortraitsDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val queue by vm.portraitReview.collectAsStateWithLifecycle()
    val labels by produceState(emptyMap<String, String>(), queue) { value = vm.artworkLabels(queue.map { it.key }) }
    val sources by produceState(emptyMap<String, LibraryViewModel.PortraitSource>(), queue) { value = vm.portraitSources(queue.map { it.key }) }
    var custom by remember { mutableStateOf<CoverTarget?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth()
                .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.9f).dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Review suggestions", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Tap the right picture to use it. Reject all keeps the automatic thumbnail and stops suggesting this one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (queue.isEmpty()) {
                    Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(queue, key = { it.key }) { entry ->
                            val source = sources[entry.key]
                            val label = source?.title?.takeIf { it.isNotBlank() } ?: labels[entry.key] ?: entry.key.substringAfterLast(':')
                            ReviewEntry(vm, entry, label, source, onCustom = { custom = CoverTarget(entry.key, label, label.substringAfterLast('/'), null) })
                            HorizontalDivider()
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
    custom?.let { target -> ChangeCoverDialog(vm, target, null) { custom = null } }
}

@Composable
private fun ReviewEntry(vm: LibraryViewModel, entry: ArtworkOverride, label: String, source: LibraryViewModel.PortraitSource?, onCustom: () -> Unit) {
    val scope = rememberCoroutineScope()
    var suggestions by remember(entry.key) { mutableStateOf<List<PerformerCandidate>?>(null) }
    var busy by remember(entry.key) { mutableStateOf(false) }
    var error by remember(entry.key) { mutableStateOf<String?>(null) }
    LaunchedEffect(entry.key, entry.updatedAt) { suggestions = vm.portraitSuggestions(entry.key) }

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // The files this suggestion is for: the full file name and its path on the drive.
        source?.files?.forEach { video ->
            Column {
                Text(video.name, style = MaterialTheme.typography.bodySmall)
                Text(video.relativePath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (source != null && source.total > source.files.size) {
            Text("and ${source.total - source.files.size} more files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val list = suggestions
        when {
            list == null -> Text("Loading…", style = MaterialTheme.typography.bodySmall)
            list.isEmpty() -> Text("No saved suggestions. Use Search to look it up yourself.", style = MaterialTheme.typography.bodySmall)
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.providerId + ":" + it.performerId }) { candidate ->
                    Column(
                        Modifier.width(96.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = !busy) {
                                busy = true
                                error = null
                                scope.launch {
                                    error = vm.choosePortrait(entry.key, candidate)
                                    busy = false
                                }
                            }
                            .semantics { contentDescription = "Use ${candidate.name} from ${candidate.providerName}" },
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        RemoteThumb(vm, candidate.previewUrl, Modifier.size(width = 96.dp, height = 128.dp).clip(RoundedCornerShape(10.dp)))
                        Text(candidate.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(candidate.disambiguation, candidate.providerName).joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (busy) Text("Saving…", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { vm.skipPortrait(entry.key) }, enabled = !busy) { Text("Reject all") }
            TextButton(onClick = onCustom, enabled = !busy) { Text("Search…") }
        }
    }
}
