package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.data.VideoEntity

/** Real SMB filesystem browser. Unlike virtual folders, these actions change remote files. */
@Composable
fun ExplorerScreen(vm: LibraryViewModel, onPlay: (VideoEntity) -> Unit) {
    var selected by remember { mutableStateOf<SmbEntry?>(null) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var pasteDialog by remember { mutableStateOf(false) }
    var renameValue by remember { mutableStateOf("") }
    val playableExtensions = remember { setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "3gp") }
    LaunchedEffect(Unit) { vm.browse("") }
    BackHandler(enabled = vm.explorerPath.isNotEmpty()) { vm.browse(vm.explorerPath.substringBeforeLast('/', "")) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Network file explorer", style = MaterialTheme.typography.titleLarge)
        Text("Changes here affect the ORIGINAL files on your SMB drive.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.browse(vm.explorerPath.substringBeforeLast('/', "")) },
                enabled = vm.explorerPath.isNotEmpty() && !vm.explorerBusy) { Text("← Up") }
            Text(vm.explorerPath.ifEmpty { "/ (share root)" }, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { vm.browse() }, enabled = !vm.explorerBusy) { Text("Refresh") }
        }
        if (vm.explorerClipboard != null) {
            val clip = vm.explorerClipboard!!
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${if (clip.third) "Move" else "Copy"}: ${clip.first.substringAfterLast('/')}", modifier = Modifier.weight(1f), maxLines = 1)
                Button(onClick = { pasteDialog = true }, enabled = !vm.explorerBusy) { Text("Paste here") }
            }
        }
        if (vm.explorerBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.explorerMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(vm.explorerEntries, key = { it.name }) { entry ->
                var menu by remember(entry.name) { mutableStateOf(false) }
                val fullPath = listOf(vm.explorerPath, entry.name).filter { it.isNotBlank() }.joinToString("/")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val playable = !entry.isDirectory && entry.name.substringAfterLast('.', "").lowercase() in playableExtensions
                    Text(if (entry.isDirectory) "📁" else if (playable) "▶" else "📄", modifier = Modifier.padding(8.dp))
                    Text(entry.name, modifier = Modifier.weight(1f).clickable(enabled = entry.isDirectory || playable) {
                        if (entry.isDirectory) vm.browse(fullPath) else if (playable) onPlay(VideoEntity(
                            relativePath = fullPath, name = entry.name, parentDir = vm.explorerPath,
                            size = entry.size, modified = entry.modified, groupKey = "",
                            lastSeenSession = 0L, addedAt = System.currentTimeMillis()
                        ))
                    },
                        maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                    Box {
                        TextButton(onClick = { selected = entry; menu = true }) { Text("⋮", style = MaterialTheme.typography.titleMedium) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (playable) DropdownMenuItem(text = { Text("▶ Play video") }, onClick = {
                                menu = false
                                onPlay(VideoEntity(relativePath = fullPath, name = entry.name, parentDir = vm.explorerPath,
                                    size = entry.size, modified = entry.modified, groupKey = "",
                                    lastSeenSession = 0L, addedAt = System.currentTimeMillis()))
                            })
                            DropdownMenuItem(text = { Text("Rename original") }, onClick = {
                                menu = false; selected = entry; renameValue = entry.name; renameDialog = true
                            })
                            DropdownMenuItem(text = { Text("Copy") }, onClick = {
                                menu = false; vm.stageExplorer(fullPath, entry.isDirectory, false)
                            })
                            DropdownMenuItem(text = { Text("Cut / Move") }, onClick = {
                                menu = false; vm.stageExplorer(fullPath, entry.isDirectory, true)
                            })
                            DropdownMenuItem(text = { Text("Delete original…") }, onClick = {
                                menu = false; selected = entry; deleteDialog = true
                            })
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (pasteDialog && vm.explorerClipboard != null) {
        val clip = vm.explorerClipboard!!
        val target = listOf(vm.explorerPath, clip.first.substringAfterLast('/')).filter { it.isNotBlank() }.joinToString("/")
        AlertDialog(
            onDismissRequest = { pasteDialog = false },
            title = { Text(if (clip.third) "Move original SMB item?" else "Copy original SMB item?") },
            text = { Text("From: ${clip.first}\nTo: $target\n\nThis changes files on your network drive. An existing destination will not be overwritten.") },
            confirmButton = {
                TextButton(onClick = { vm.pasteExplorer(); pasteDialog = false },
                    enabled = !vm.explorerBusy && target != clip.first) {
                    Text(if (clip.third) "Move" else "Copy")
                }
            },
            dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text("Cancel") } },
        )
    }
    if (renameDialog && selected != null) {
        val entry = selected!!
        AlertDialog(onDismissRequest = { renameDialog = false },
            title = { Text("Rename original ${if (entry.isDirectory) "folder" else "file"}") },
            text = { OutlinedTextField(renameValue, onValueChange = { renameValue = it }, label = { Text("New name") }) },
            confirmButton = { TextButton(onClick = {
                vm.renameOriginal(listOf(vm.explorerPath, entry.name).filter { it.isNotBlank() }.joinToString("/"), renameValue, entry.isDirectory)
                renameDialog = false
            }, enabled = renameValue.isNotBlank() && renameValue != entry.name && !renameValue.contains("/") && !renameValue.contains("\\")) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } })
    }
    if (deleteDialog && selected != null) {
        val entry = selected!!
        AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete from network drive?") },
            text = { Text("Permanently delete '${entry.name}'${if (entry.isDirectory) " and everything inside it" else ""}? This cannot be undone.") },
            confirmButton = { TextButton(onClick = {
                vm.deleteOriginal(listOf(vm.explorerPath, entry.name).filter { it.isNotBlank() }.joinToString("/"), entry.isDirectory)
                deleteDialog = false
            }) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
    }
}
