package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.data.VideoEntity

/** Real SMB filesystem browser. Unlike virtual folders, these actions change remote files. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    LaunchedEffect(transfers.filter { it.state.isFinished }.map { it.id }) { if(transfers.any {it.state==androidx.work.WorkInfo.State.SUCCEEDED}) {vm.browse();if(vm.explorerQuery.isNotBlank())vm.searchExplorer(vm.explorerQuery,vm.explorerPath)} }
    val searching = vm.explorerQuery.isNotBlank()
    val shown = if (searching) vm.explorerHits else vm.explorerEntries.map {
        ExplorerHit(listOf(vm.explorerPath, it.name).filter { part -> part.isNotBlank() }.joinToString("/"), it)
    }
    val playQueue = shown.filter { isPlayable(it.entry) }.map { explorerVideo(it, emptyMap()) }.distinctBy { it.relativePath }
    DisposableEffect(Unit) { onDispose { vm.stopExplorerSearch() } }
    LaunchedEffect(vm.explorerQuery, vm.explorerPath) { vm.searchExplorer(vm.explorerQuery, vm.explorerPath) }
    fun open(hit: ExplorerHit) {
        if (hit.entry.isDirectory) { vm.explorerQuery = ""; vm.browse(hit.path) }
        else if (isPlayable(hit.entry)) {
            val item = explorerVideo(hit, emptyMap())
            onPlay(item, if (searching) playQueue else listOf(item))
        }
    }
    LaunchedEffect(Unit) { if (vm.explorerEntries.isEmpty()) vm.browse() }
    BackHandler(enabled = searching || vm.explorerPath.isNotEmpty()) {
        if (searching) vm.explorerQuery = "" else vm.browse(vm.explorerPath.substringBeforeLast('/', ""))
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Browse", style = MaterialTheme.typography.titleLarge)
        Text("Local network · ${vm.settings.host.ifBlank { "SMB server" }}", style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary)
        Text("Changes here affect the ORIGINAL files on your SMB drive.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.browse(vm.explorerPath.substringBeforeLast('/', "")) },
                enabled = vm.explorerPath.isNotEmpty() && !vm.explorerBusy) { Text("← Up") }
            Text(vm.explorerPath.ifEmpty { "/ (share root)" }, modifier = Modifier.weight(1f), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { vm.browse(); vm.searchExplorer(vm.explorerQuery, vm.explorerPath) }, enabled = !vm.explorerBusy) { Text("Refresh") }
        }
        OutlinedTextField(value = vm.explorerQuery, onValueChange = { vm.explorerQuery = it },
            label = { Text("Search names anywhere in this folder and subfolders") },
            singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            trailingIcon = { if (searching) TextButton(onClick = { vm.explorerQuery = "" }) { Text("Clear") } },
            modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${playQueue.size} ${if (searching) "matching" else "playable"} videos", modifier = Modifier.weight(1f))
            TextButton(enabled = playQueue.isNotEmpty(), onClick = { playQueue.firstOrNull()?.let { onPlay(it, playQueue) } }) {
                Text(if (vm.explorerSearching) "Play found so far" else "Play all")
            }
        }
        if (vm.explorerSearching) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.explorerSearchMessage?.takeIf { searching }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.explorerBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
        vm.explorerMessage?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { vm.browse() }, enabled = !vm.explorerBusy) { Text("Try again") }
                }
            }
        }
        if (!vm.explorerBusy && !vm.explorerSearching && shown.isEmpty() && vm.explorerMessage == null) {
            Text(if (searching) "No matching files or folders." else "No files in this folder. Pull a new listing with Refresh, or check your SMB connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            items(shown, key = { it.path }) { hit ->
                val entry = hit.entry
                var menu by remember(entry.name) { mutableStateOf(false) }
                val fullPath = hit.path
                Row(Modifier.fillMaxWidth().combinedClickable(onClick={open(hit)},onLongClick={menu=true}), verticalAlignment = Alignment.CenterVertically) {
                    val playable = isPlayable(entry)
                    Icon(if (entry.isDirectory) Icons.Filled.Folder else if (playable) Icons.Filled.Movie else Icons.Filled.InsertDriveFile,
                        contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp).size(28.dp))
                    Column(Modifier.weight(1f).padding(vertical=10.dp)) {
                        Text(entry.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        if (searching) Text(fullPath.substringBeforeLast('/', "/"), maxLines = 1,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    Box {
                        IconButton(onClick={menu=true}){Icon(Icons.Filled.MoreVert,contentDescription="Options for ${entry.name}")}
                        FileActionsMenu(menu,{menu=false},vm,fullPath,directory=entry.isDirectory,
                            selectionScope="explorer",
                            onPlay=if(playable)({open(hit)})else null,
                            details="${entry.name}\n$fullPath\n${entry.size} bytes")
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
