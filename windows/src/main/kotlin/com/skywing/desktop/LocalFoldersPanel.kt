package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.launch

@Composable
internal fun FoldersPanel(onVideo: (File) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        LocalFolderLibraryPanel(onVideo)
        Text("Network shares", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        SmbSharePanel(onVideo)
    }
}

@Composable
private fun LocalFolderLibraryPanel(onVideo: (File) -> Unit) {
    val store = remember { LocalFolderStore() }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList())) }
    var videos by remember { mutableStateOf(emptyList<File>()) }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Choose folders on this PC. SkyWing scans only the folders you add.") }
    var scanning by remember { mutableStateOf(false) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        cursorColor = Color(0xFF80CBC4),
        focusedContainerColor = Color(0xFF1B1E26),
        unfocusedContainerColor = Color(0xFF1B1E26),
        focusedBorderColor = Color(0xFF80CBC4),
        unfocusedBorderColor = Color(0xFF7C8291),
        focusedLabelColor = Color(0xFF80CBC4),
        unfocusedLabelColor = Color(0xFFC6C8D1),
    )

    fun scan(paths: List<String>) {
        if (scanning) return
        scanning = true
        message = "Scanning selected folders…"
        scope.launch {
            try {
                val result = LocalVideoScanner.scan(paths)
                videos = result.files
                message = buildString {
                    append(result.files.size).append(" videos found")
                    if (result.unavailableFolders > 0) append("; ").append(result.unavailableFolders).append(" folder(s) unavailable")
                    if (result.truncated) append("; showing the first 20,000")
                }
            } catch (error: Exception) {
                message = error.message ?: "Folder scan failed"
            } finally {
                scanning = false
            }
        }
    }

    LaunchedEffect(folders) {
        if (folders.isNotEmpty()) scan(folders)
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("This PC", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !scanning, onClick = {
                val chooser = JFileChooser().apply {
                    dialogTitle = "Add a SkyWing video folder"
                    fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                    isAcceptAllFileFilterUsed = false
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val folder = chooser.selectedFile
                    try {
                        folders = store.add(folder.absolutePath)
                        message = "Folder added. Scanning videos…"
                    } catch (error: Exception) {
                        message = error.message ?: "Could not add folder"
                    }
                }
            }) { Text("Add folder") }
            Button(enabled = !scanning && folders.isNotEmpty(), onClick = { scan(folders) }) { Text("Scan folders") }
        }
        folders.forEach { folder ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(folder, color = Color(0xFFC6C8D1), modifier = Modifier.weight(1f))
                Button(enabled = !scanning, onClick = {
                    try {
                        folders = store.remove(folder)
                        if (folders.isEmpty()) {
                            videos = emptyList()
                            message = "No local folders selected."
                        }
                    } catch (error: Exception) {
                        message = error.message ?: "Could not remove folder"
                    }
                }) { Text("Remove") }
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search local videos") },
            singleLine = true,
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(message, color = Color(0xFFE5CB87))
        val visibleVideos = remember(videos, query) {
            val term = query.trim()
            if (term.isEmpty()) videos else videos.filter { it.name.contains(term, ignoreCase = true) }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
            items(visibleVideos, key = { it.absolutePath.lowercase() }) { video ->
                Button(
                    enabled = !scanning,
                    onClick = { onVideo(video) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(video.name) }
            }
        }
    }
}
