package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * Movie folders whose poster was not certain. For each: tap the right movie to use its poster, or "None of these" to
 * stop suggesting it (you can still pick a poster yourself in Change cover).
 */
@Composable
internal fun ReviewMoviesDialog(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val queue by vm.movieReview.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Review movie posters", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Tap the right movie to use its poster. None of these stops suggesting that folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) Text("That poster could not be downloaded. Try again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (queue.isEmpty()) {
                    Text("Nothing is waiting.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(Modifier.heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(queue, key = { it.identity }) { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                item.candidates.forEach { movie ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        RemoteThumb(vm, movie.previewUrl, Modifier.size(width = 56.dp, height = 84.dp).clip(RoundedCornerShape(8.dp)))
                                        Column(Modifier.weight(1f)) {
                                            Text(movie.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text(
                                                listOfNotNull(movie.year, movie.site).joinToString(" · ").ifEmpty { "ThePornDB" },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        OutlinedButton(enabled = !busy && movie.posterUrl != null, onClick = {
                                            busy = true
                                            failed = false
                                            scope.launch {
                                                if (!vm.chooseMovie(item.identity, movie)) failed = true
                                                busy = false
                                            }
                                        }) { Text("Use") }
                                    }
                                }
                                TextButton(enabled = !busy, onClick = { vm.rejectMovie(item.identity) }) { Text("None of these") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}
