package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.VideoEntity

/** Real SMB filesystem browser. Unlike virtual folders, these actions change remote files. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ExplorerScreen(vm: LibraryViewModel, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val completed = transfers.filter { it.state.isFinished }.map { it.id }
    var priorCompleted by remember { mutableStateOf<List<java.util.UUID>?>(null) }
    LaunchedEffect(completed) {
        if (priorCompleted != null &&
            priorCompleted != completed
        ) {
            vm.browse(focus = vm.explorerFocusPath)
            if (vm.explorerQuery.isNotBlank())vm.searchExplorer(vm.explorerQuery, vm.explorerPath)
        }
        priorCompleted =
            completed
    }
    val listState = rememberLazyListState()
    var searchExpanded by rememberSaveable { mutableStateOf(vm.explorerQuery.isNotBlank()) }
    val searching = vm.explorerQuery.isNotBlank()
    val shown = if (searching) {
        vm.explorerHits
    } else {
        vm.explorerEntries.map {
            ExplorerHit(listOf(vm.explorerPath, it.name).filter { part -> part.isNotBlank() }.joinToString("/"), it)
        }
    }
    LaunchedEffect(vm.explorerFocusPath, vm.explorerBusy, shown.map { it.path }) {
        val index = shown.indexOfFirst { it.path == vm.explorerFocusPath }
        if (!vm.explorerBusy && index >= 0)listState.scrollToItem(index)
    }
    LaunchedEffect(vm.explorerPath) { if (vm.explorerFocusPath == null)listState.scrollToItem(0) }
    val playQueue = shown.filter { isPlayable(it.entry) }.map { explorerVideo(it, emptyMap()) }.distinctBy { it.relativePath }
    DisposableEffect(Unit) { onDispose { vm.stopExplorerSearch() } }
    LaunchedEffect(vm.explorerQuery, vm.explorerPath) { vm.searchExplorer(vm.explorerQuery, vm.explorerPath) }
    fun open(hit: ExplorerHit) {
        if (hit.entry.isDirectory) {
            vm.explorerQuery = ""
            vm.browse(hit.path)
        } else if (isPlayable(hit.entry)) {
            val item = explorerVideo(hit, emptyMap())
            onPlay(item, if (searching) playQueue else listOf(item))
        }
    }
    LaunchedEffect(Unit) { if (vm.explorerEntries.isEmpty()) vm.browse(focus = vm.explorerFocusPath) }
    BackHandler(enabled = searching || vm.explorerPath.isNotEmpty()) {
        if (searching) vm.explorerQuery = "" else vm.browse(vm.explorerPath.substringBeforeLast('/', ""))
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ui_file_explorer_700869d), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                searchExpanded = !searchExpanded
                if (!searchExpanded)vm.explorerQuery = ""
            }) {
                Icon(
                    if (searchExpanded)Icons.Default.Close else Icons.Default.Search,
                    if (searchExpanded)"Close explorer search" else "Search File Explorer",
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { vm.browse(vm.explorerPath.substringBeforeLast('/', "")) },
                enabled = vm.explorerPath.isNotEmpty() && !vm.explorerBusy,
            ) { Text(stringResource(R.string.ui_up_2c97c0b)) }
            Text(
                vm.explorerPath.ifEmpty { "/ (share root)" },
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = {
                vm.browse()
                vm.searchExplorer(vm.explorerQuery, vm.explorerPath)
            }, enabled = !vm.explorerBusy) { Text(stringResource(R.string.ui_refresh_56e3bad)) }
        }
        if (searchExpanded || searching) {
            OutlinedTextField(
                value = vm.explorerQuery,
                onValueChange = { vm.explorerQuery = it },
                label = { Text(stringResource(R.string.ui_search_names_anywhere_in_this_folder_and_s_7fe98c1)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                trailingIcon = { if (searching) TextButton(onClick = { vm.explorerQuery = "" }) { Text(stringResource(R.string.ui_clear_719ea39)) } },
                modifier = Modifier.fillMaxWidth(),
            )
        }
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
                    TextButton(onClick = { vm.browse() }, enabled = !vm.explorerBusy) { Text(stringResource(R.string.ui_try_again_042c862)) }
                }
            }
        }
        if (!vm.explorerBusy && !vm.explorerSearching && shown.isEmpty() && vm.explorerMessage == null) {
            Text(
                if (searching) "No matching files or folders." else "No files in this folder. Pull a new listing with Refresh, or check your SMB connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            items(shown, key = { it.path }) { hit ->
                val entry = hit.entry
                var menu by remember(entry.name) { mutableStateOf(false) }
                val fullPath = hit.path
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (hit.path ==
                                vm.explorerFocusPath
                            ) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)).semantics {
                                    contentDescription =
                                        "Target file: ${entry.name}"
                                }
                            } else {
                                Modifier
                            },
                        ).combinedClickable(onClick = { open(hit) }, onLongClick = { menu = true }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val playable = isPlayable(entry)
                    Icon(
                        if (entry.isDirectory) {
                            Icons.Filled.Folder
                        } else if (playable) {
                            Icons.Filled.Movie
                        } else {
                            Icons.Filled.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(28.dp),
                    )
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(entry.name, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                        if (searching) {
                            Text(
                                fullPath.substringBeforeLast('/', "/"),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { menu = true },
                        ) { Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${entry.name}") }
                        FileActionsMenu(
                            menu,
                            { menu = false },
                            vm,
                            fullPath,
                            directory = entry.isDirectory,
                            selectionScope = "explorer",
                            onPlay = if (playable)({ open(hit) })else null,
                            details = "${entry.name}\n$fullPath\n${entry.size} bytes",
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
