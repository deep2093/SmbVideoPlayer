package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FoldersPanel(onPlayQueue: (File, List<File>) -> Unit) {
    val indexStore = remember { WindowsMediaIndex() }
    var indexRevision by remember { mutableStateOf(0) }
    fun refreshIndex() { indexRevision++ }
    var section by remember { mutableStateOf("Library") }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Library", "This PC", "Network shares").forEach { item ->
                if (section == item) {
                    Button(onClick = { section = item }) { Text(item) }
                } else {
                    OutlinedButton(onClick = { section = item }) { Text(item) }
                }
            }
        }
        when (section) {
            "This PC" -> LocalFolderLibraryPanel(indexStore, { refreshIndex() }, onPlayQueue)
            "Network shares" -> SmbSharePanel(indexStore, { refreshIndex() }, onPlayQueue)
            else -> CombinedLibraryPanel(indexStore, indexRevision, onPlayQueue)
        }
    }
}

@Composable
private fun LocalFolderLibraryPanel(
    indexStore: WindowsMediaIndex,
    onIndexUpdated: () -> Unit,
    onPlayQueue: (File, List<File>) -> Unit,
) {
    val store = remember { LocalFolderStore() }
    val scope = rememberCoroutineScope()
    var folders by remember { mutableStateOf(runCatching { store.load() }.getOrDefault(emptyList())) }
    var videos by remember { mutableStateOf(emptyList<File>()) }
    var openedFolder by remember { mutableStateOf<File?>(null) }
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
                withContext(Dispatchers.IO) { indexStore.replaceSource("PC", "PC", "This PC", result.files) }
                videos = result.files
                onIndexUpdated()
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
        if (folders.isEmpty()) {
            videos = emptyList()
            return@LaunchedEffect
        }
        videos = withContext(Dispatchers.IO) { indexStore.load().filter { it.sourceType == "PC" }.map { it.file } }
        scan(folders)
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
                        openedFolder = null
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
                        openedFolder = null
                        if (folders.isEmpty()) {
                            videos = emptyList()
                            scope.launch {
                                withContext(Dispatchers.IO) { indexStore.replaceSource("PC", "PC", "This PC", emptyList()) }
                                onIndexUpdated()
                            }
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
        val current = openedFolder
        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !scanning, onClick = {
                    val currentPath = current.toPath().toAbsolutePath().normalize()
                    val selectedRoot = folders.mapNotNull { runCatching { File(it).toPath().toAbsolutePath().normalize() }.getOrNull() }
                        .filter { currentPath.startsWith(it) }
                        .maxByOrNull { it.nameCount }
                    if (selectedRoot == currentPath) openedFolder = null
                    else openedFolder = current.parentFile?.takeIf { parent -> selectedRoot == null || parent.toPath().toAbsolutePath().normalize().startsWith(selectedRoot) }
                }) { Text("Back") }
                Text(current.absolutePath, color = Color.White)
            }
        }
        val folderContents = remember(videos, folders, current) { localOriginalFolderContents(folders, current, videos) }
        val visibleVideos = remember(videos, query, current) {
            val term = query.trim()
            videos.filter { video ->
                val videoPath = video.toPath().toAbsolutePath().normalize()
                val inFolder = when {
                    current == null -> term.isNotEmpty()
                    term.isEmpty() -> videoPath.parent == current.toPath().toAbsolutePath().normalize()
                    else -> videoPath.startsWith(current.toPath().toAbsolutePath().normalize())
                }
                inFolder && (term.isEmpty() || video.name.contains(term, ignoreCase = true))
            }
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().height(340.dp)) {
            if (query.isBlank()) {
                items(folderContents.folders, key = { "folder:" + it.absolutePath.lowercase() }) { folder ->
                    Button(
                        enabled = !scanning,
                        onClick = { openedFolder = folder },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Folder  " + if (current == null) folder.absolutePath else folder.name) }
                }
            }
            items(visibleVideos, key = { "video:" + it.absolutePath.lowercase() }) { video ->
                Button(
                    enabled = !scanning,
                    onClick = { onPlayQueue(video, visibleVideos) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(video.name) }
            }
        }
    }
}

