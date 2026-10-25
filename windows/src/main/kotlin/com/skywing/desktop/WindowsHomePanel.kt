package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun WindowsHomePanel(historyRevision: Int, onOpen: (File, Double) -> Unit) {
    val indexStore = remember { WindowsMediaIndex() }
    var entries by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(indexStore) {
        loading = true
        entries = withContext(Dispatchers.IO) { indexStore.load() }
        loading = false
    }
    val history = remember(historyRevision) {
        WindowsPlaybackHistoryStore().load().filter { File(it.path).isFile }.sortedByDescending { it.updated }
    }
    val recent = remember(entries) { entries.filter { it.file.isFile }.distinctBy { it.path.lowercase() }.sortedByDescending { it.modified }.take(24) }
    val continueItems = remember(history) { history.take(16) }
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        if (loading) Text("Loading your library…", color = Color(0xFFB7C4D2))
        if (entries.isEmpty() && !loading) {
            Text("Your library is empty. Add folders from Library to see videos here.", color = Color(0xFFB7C4D2))
        } else {
            if (continueItems.isNotEmpty()) HomeMediaRow("Continue watching", continueItems.map { Triple(File(it.path), it.position, it.duration) }) { file, pos -> onOpen(file, pos) }
            if (recent.isNotEmpty()) HomeMediaRow("Recently added", recent.map { Triple(it.file, 0.0, 0.0) }) { file, pos -> onOpen(file, pos) }
        }
    }
}

@Composable
private fun HomeMediaRow(title: String, media: List<Triple<File, Double, Double>>, onOpen: (File, Double) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color(0xFFF1F6FC), style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(media, key = { it.first.absolutePath.lowercase() }) { (file, position, duration) ->
                val shades = listOf(Color(0xFF172B3B), Color(0xFF20242F), Color(0xFF28221E), Color(0xFF182A27))
                val shade = shades[(file.name.hashCode() and Int.MAX_VALUE) % shades.size]
                Column(modifier = Modifier.width(176.dp).clickable { onOpen(file, position) }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(modifier = Modifier.fillMaxWidth().height(230.dp), color = shade, shape = RoundedCornerShape(10.dp)) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.SpaceBetween) {
                            Text("SKYWING", color = Color(0xFF9ED7C0), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                            Text(file.nameWithoutExtension, color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, maxLines = 4)
                            Text(file.extension.uppercase(), color = Color(0xFFB7C4D2), style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                        }
                    }
                    Text(file.nameWithoutExtension, color = Color(0xFFE9EFF7), maxLines = 1)
                    if (duration > 0) {
                        val percent = ((position / duration) * 100).toInt().coerceIn(0, 100)
                        Text("$percent% watched", color = Color(0xFF9BAFC1), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    } else Text(file.parentFile?.name ?: "", color = Color(0xFF9BAFC1), maxLines = 1, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun WindowsSearchPanel(onPlayQueue: (File, List<File>) -> Unit) {
    val store = remember { WindowsMediaIndex() }
    var allEntries by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    var query by remember { mutableStateOf("") }
    var applied by remember { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    LaunchedEffect(store) { allEntries = withContext(Dispatchers.IO) { store.load() } }
    LaunchedEffect(allEntries, applied) {
        results = withContext(Dispatchers.Default) {
            val term = applied.trim()
            if (term.isEmpty()) emptyList() else allEntries.filter { it.file.name.contains(term, ignoreCase = true) }.take(1000)
        }
    }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color(0xFF80CBC4),
        focusedContainerColor = Color(0xFF1B1E26), unfocusedContainerColor = Color(0xFF1B1E26),
        focusedBorderColor = Color(0xFF80CBC4), unfocusedBorderColor = Color(0xFF7C8291),
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(query, { query = it }, label = { Text("Search videos") }, singleLine = true, colors = colors, modifier = Modifier.weight(1f))
            Button(onClick = { applied = query }) { Text("Search") }
        }
        if (applied.isNotBlank()) Text("${results.size} results", color = Color(0xFFE5CB87))
        androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxWidth().height(400.dp)) {
            items(results, key = { it.path.lowercase() }) { entry ->
                Button(onClick = { onPlayQueue(entry.file, results.map { it.file }) }, modifier = Modifier.fillMaxWidth()) {
                    Text(entry.file.name + "  •  " + entry.sourceName, maxLines = 1)
                }
            }
        }
    }
}

