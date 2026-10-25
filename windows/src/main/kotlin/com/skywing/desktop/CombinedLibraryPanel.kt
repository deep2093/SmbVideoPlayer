package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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

private data class VirtualLibraryGroup(val key: String, val title: String, val entries: List<WindowsMediaEntry>)

@Composable
internal fun CombinedLibraryPanel(
    indexStore: WindowsMediaIndex,
    indexRevision: Int,
    onPlayQueue: (File, List<File>) -> Unit,
) {
    var sourceFilter by remember { mutableStateOf("All") }
    var view by remember { mutableStateOf("Videos") }
    var query by remember { mutableStateOf("") }
    var appliedQuery by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    var openedVirtual by remember { mutableStateOf<String?>(null) }
    var openedCustom by remember { mutableStateOf<String?>(null) }
    val customStore = remember { WindowsCustomFolderStore() }
    var customFolders by remember { mutableStateOf(runCatching { customStore.load() }.getOrDefault(emptyList())) }
    var allEntries by remember(indexStore) { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    var indexLoading by remember { mutableStateOf(true) }
    LaunchedEffect(indexStore, indexRevision) {
        indexLoading = true
        allEntries = withContext(Dispatchers.IO) { indexStore.load() }
        indexLoading = false
    }
    var entries by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    LaunchedEffect(allEntries, sourceFilter) {
        entries = withContext(Dispatchers.Default) {
            (when (sourceFilter) {
                "This PC" -> allEntries.filter { it.sourceType == "PC" }
                "SMB" -> allEntries.filter { it.sourceType == "SMB" }
                else -> allEntries
            }).distinctBy { it.path.lowercase() }.sortedBy { it.file.name.lowercase() }
        }
    }
    var matchingEntries by remember { mutableStateOf(emptyList<WindowsMediaEntry>()) }
    LaunchedEffect(entries, appliedQuery) {
        matchingEntries = withContext(Dispatchers.Default) {
            val term = appliedQuery.trim()
            if (term.isEmpty()) entries else entries.filter { it.file.name.contains(term, ignoreCase = true) }
        }
    }
    var virtualGroups by remember { mutableStateOf(emptyList<VirtualLibraryGroup>()) }
    LaunchedEffect(entries) {
        virtualGroups = withContext(Dispatchers.Default) {
            val grouped = linkedMapOf<String, Pair<String, LinkedHashMap<String, WindowsMediaEntry>>>()
            entries.forEach { entry ->
                WindowsVirtualNames.of(entry.file.name).forEach { title ->
                    val key = WindowsVirtualNames.identity(title)
                    val current = grouped.getOrPut(key) { title to linkedMapOf() }
                    current.second.putIfAbsent(entry.path.lowercase(), entry)
                }
            }
            grouped.map { (key, value) -> VirtualLibraryGroup(key, value.first, value.second.values.toList()) }
                .sortedBy { it.title.lowercase() }
        }
    }
    val openedVirtualGroup = openedVirtual?.let { key -> virtualGroups.firstOrNull { it.key == key } }
    val openedCustomFolder = openedCustom?.let { name -> customFolders.firstOrNull { it.name == name } }
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

    fun queue(files: List<WindowsMediaEntry>) {
        val list = files.distinctBy { it.path.lowercase() }.map { it.file }
        list.firstOrNull()?.let { onPlayQueue(it, list) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Unified library", color = Color.White, style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
        if (indexLoading) Text("Loading saved library…", color = Color(0xFFC6C8D1))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("All", "This PC", "SMB").forEach { option ->
                Button(onClick = { sourceFilter = option }) { Text(option + if (option == sourceFilter) " ✓" else "") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Videos", "Virtual folders", "Custom folders").forEach { option ->
                Button(onClick = {
                    view = option
                    openedVirtual = null
                    openedCustom = null
                    selectedPaths = emptySet()
                }) { Text(option + if (option == view) " ✓" else "") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search library") },
                singleLine = true,
                colors = fieldColors,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { appliedQuery = query }) { Text("Search") }
        }
        when (view) {
            "Virtual folders" -> {
                if (openedVirtualGroup != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openedVirtual = null }) { Text("Back") }
                        Text(openedVirtualGroup.title, color = Color.White)
                        Button(enabled = openedVirtualGroup.entries.isNotEmpty(), onClick = { queue(openedVirtualGroup.entries) }) { Text("Play all") }
                    }
                    val groupVideos = openedVirtualGroup.entries.filter { entry ->
                        appliedQuery.isBlank() || entry.file.name.contains(appliedQuery.trim(), ignoreCase = true)
                    }
                    LibraryMediaList(groupVideos, emptySet(), {}, onPlayQueue)
                } else {
                    val matchingGroups = virtualGroups.filter { group ->
                        appliedQuery.isBlank() || group.title.contains(appliedQuery.trim(), ignoreCase = true) ||
                            group.entries.any { it.file.name.contains(appliedQuery.trim(), ignoreCase = true) }
                    }
                    Text(matchingGroups.size.toString() + " virtual folders", color = Color(0xFFE5CB87))
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                        items(matchingGroups, key = { it.key }) { group ->
                            Button(onClick = { openedVirtual = group.key }, modifier = Modifier.fillMaxWidth()) {
                                Text(group.title + "  (" + group.entries.size + ")")
                            }
                        }
                    }
                }
            }
            "Custom folders" -> {
                if (openedCustomFolder != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { openedCustom = null }) { Text("Back") }
                        Text(openedCustomFolder.name, color = Color.White)
                        val customEntries = openedCustomFolder.paths.mapNotNull { path ->
                            entries.firstOrNull { it.path.equals(path, ignoreCase = true) }
                        }.filter { appliedQuery.isBlank() || it.file.name.contains(appliedQuery.trim(), ignoreCase = true) }
                        Button(enabled = customEntries.isNotEmpty(), onClick = { queue(customEntries) }) { Text("Play all") }
                        Button(onClick = {
                            customFolders = customStore.delete(openedCustomFolder.name)
                            openedCustom = null
                        }) { Text("Remove folder") }
                    }
                    val customEntries = openedCustomFolder.paths.mapNotNull { path ->
                        entries.firstOrNull { it.path.equals(path, ignoreCase = true) }
                    }.filter { appliedQuery.isBlank() || it.file.name.contains(appliedQuery.trim(), ignoreCase = true) }
                    LibraryMediaList(customEntries, emptySet(), {}, onPlayQueue)
                } else {
                    Text(customFolders.size.toString() + " custom folders", color = Color(0xFFE5CB87))
                    LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                        items(customFolders, key = { it.name.lowercase() }) { folder ->
                            val count = folder.paths.count { path -> entries.any { it.path.equals(path, ignoreCase = true) } }
                            Button(onClick = { openedCustom = folder.name }, modifier = Modifier.fillMaxWidth()) {
                                Text(folder.name + "  (" + count + ")")
                            }
                        }
                    }
                    if (customFolders.isEmpty()) Text("Select videos in Videos, then create a custom folder.", color = Color.White)
                }
            }
            else -> {
                val visible = matchingEntries
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = visible.isNotEmpty(), onClick = { queue(visible) }) { Text("Play all") }
                    Button(
                        enabled = selectedPaths.isNotEmpty() && customName.isNotBlank(),
                        onClick = {
                            try {
                                customFolders = customStore.save(customName, selectedPaths.toList())
                                customName = ""
                                selectedPaths = emptySet()
                            } catch (_: Exception) {
                                // Validation is reflected by leaving the name and selection available to correct.
                            }
                        },
                    ) { Text("Create custom folder") }
                }
                OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it },
                    label = { Text("New custom folder name") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(visible.size.toString() + " indexed videos", color = Color(0xFFE5CB87))
                LibraryMediaList(visible, selectedPaths, { path ->
                    selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                }, onPlayQueue)
            }
        }
    }
}

@Composable
private fun LibraryMediaList(
    entries: List<WindowsMediaEntry>,
    selectedPaths: Set<String>,
    onSelectionChange: (String) -> Unit,
    onPlayQueue: (File, List<File>) -> Unit,
) {
    val queue = entries.map { it.file }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(300.dp)) {
        items(entries, key = { it.path.lowercase() }) { entry ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(
                    checked = entry.path in selectedPaths,
                    onCheckedChange = { onSelectionChange(entry.path) },
                )
                Button(onClick = { onPlayQueue(entry.file, queue) }, modifier = Modifier.weight(1f)) {
                    Text(entry.file.name + "  •  " + entry.sourceName)
                }
            }
        }
    }
}

