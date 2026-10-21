package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.*

@Composable
internal fun MatchingFoldersScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onOpen: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    active: Boolean = true,
) {
    val folders by vm.matchingFolders.collectAsStateWithLifecycle()
    val query by vm.matchFolderQuery.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ScrollToTopOnChange(query, gridState)
    LaunchedEffect(active) {
        if (!active && search) {
            vm.matchFolderQuery.value = ""
            search = false
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        SearchableTopBar(
            title = stringResource(R.string.ui_custom_folders_097a811),
            searching = search,
            onSearchingChange = { search = it },
            query = query,
            onQueryChange = { vm.matchFolderQuery.value = it },
            placeholder = stringResource(R.string.ui_search_custom_folders_89257e5),
        ) {
            Box {
                FilledTonalButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, null); Text(stringResource(R.string.ui_add_61cc55a)) }
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_choose_drive_folders_f890e96)) }, onClick = { addMenu = false; picking = true })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_create_filename_collection_fa7e2fd)) }, onClick = { addMenu = false; name = ""; creating = true })
                }
            }
        }
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f),
            // Custom folders are always listed by name.
            labelFor = { index -> folders.getOrNull(index)?.let { sectionLetter(it.folder.name) } },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.fillMaxSize(),
            columns = GridCells.Fixed(if (vm.folderListMode)1 else libraryColumns(vm.folderGridPreset, floatingPadding(vm))),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = if (floatingPadding(vm))112.dp else 16.dp),
        ) {
            if (folders.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (query.isBlank()) "Keep your favourite folders together" else "No custom folders found", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.ui_add_folders_from_your_drive_or_create_a_co_60f3aaf), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(folders, key = { it.folder.id }) { row -> MatchingFolderCard(vm, row, onOpen, onPlay) }
        }
        }
    }
    if (picking) SourceFolderPicker(vm, onClose = { picking = false })
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.ui_create_automatic_custom_folder_1b8d339)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_the_folder_name_is_matched_anywhere_in_vid_a4aa69a))
                    PillTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.ui_folder_name_filename_match_e8e1149),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.createMatchingFolder(name)
                    creating = false
                }) { Text(stringResource(R.string.ui_create_6e157c5)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    creating =
                        false
                }) { Text(stringResource(R.string.ui_cancel_77dfd21)) }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchingFolderCard(
    vm: LibraryViewModel,
    row: MatchFolderRow,
    onOpen: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
) {
    val folder = row.folder
    val key = vm.coverKey("match:${folder.id}:${folder.term}", row.count, row.thumbsDone, row.lastModified)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.matchingCover(folder, key) }
    var menu by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    var dialog by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    fun open() {
        vm.groupFilter.value = null
        vm.matchFolderFilter.value = folder
        vm.query.value = ""
        onOpen()
    }
    Box {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().combinedClickable(onClick = ::open, onLongClick = { menu = true })) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    if (cover !=
                        null
                    ) {
                        FileImage(cover, Modifier.fillMaxSize())
                    } else {
                        Icon(Icons.Default.Folder, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${row.count} videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = {
                        menu =
                            true
                    }) { Icon(Icons.Default.MoreVert, "Options for ${folder.name}") }
                }
            }
        }
        if (changeCover) {
            ChangeCoverDialog(vm, CoverTarget(com.deepak.videolibrary.artwork.ArtworkKeys.matchFolder(folder.id), folder.name, folder.name, null), null) { changeCover = false }
        }
        DropdownMenu(menu, { menu = false }) {
            if (artworkOn) {
                DropdownMenuItem(text = { Text("Change cover") }, onClick = {
                    menu = false
                    changeCover = true
                })
            }
            DropdownMenuItem(text = { Text("Download") }, onClick = {
                menu = false
                vm.downloadMatchingFolder(folder)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_play_all_ebb2ffd)) }, onClick = {
                menu = false
                vm.matchFolderFilter.value = folder
                vm.groupFilter.value = null
                vm.query.value =
                    ""
                vm.playLibrary("videos", onPlay = onPlay)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_select_all_913afff)) }, onClick = {
                menu = false
                open()
                vm.selectAll("videos")
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_cut_38d13bd)) }, onClick = {
                menu = false
                vm.stageMatchingFolder(folder, true)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_copy_af74f7c)) }, onClick = {
                menu = false
                vm.stageMatchingFolder(folder, false)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_paste_9db2f69)) }, enabled = vm.explorerClipboard != null, onClick = {
                menu = false
                vm.pasteDestination =
                    vm.settings.rootPath
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_rename_d3f4cb8)) }, onClick = {
                menu = false
                name = folder.name
                dialog = "Rename"
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_merge_into_56f0e1a)) }, onClick = {
                menu = false
                name = ""
                dialog = "Merge"
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_details_dc3decb)) }, onClick = {
                menu = false
                dialog = "Details"
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_remove_hide_3c3e1da)) }, onClick = {
                menu = false
                vm.hideMatchingFolder(folder)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_delete_custom_folder_e50ebb4)) }, onClick = {
                menu = false
                dialog = "Delete"
            })
        }
    }
    if (dialog.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text("$dialog ${folder.name}") },
            text = {
                Column {
                    Text(
                        when (dialog) {
                            "Rename" -> if (folder.sourcePath == null) "Renaming also changes the automatic filename match." else "Change the display name. Your drive folder keeps its name."
                            "Merge" -> "Assign matching videos to a destination virtual folder. This automatic collection remains available."
                            "Delete" -> if(folder.sourcePath==null) "Remove this automatic collection? Original files and existing virtual assignments are kept." else "Remove this folder from Custom folders? Files on your drive and virtual assignments are kept."
                            else -> folder.sourcePath?.let { "${row.count} indexed videos in $it. " + if (folder.includeSubfolders) "Includes subfolders." else "This folder only." } ?: "${row.count} visible videos with '${folder.term}' anywhere in the filename, ignoring case. Membership updates automatically."
                        },
                    )
                    if (dialog == "Rename" ||
                        dialog == "Merge"
                    ) {
                        PillTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = if (dialog == "Merge") {
                                "Destination virtual folder"
                            } else if (folder.sourcePath != null) {
                                "Display name"
                            } else {
                                "Name / filename match"
                            },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = dialog !in listOf("Rename", "Merge") || name.isNotBlank(), onClick = {
                    when (dialog) {
                        "Rename" -> vm.renameMatchingFolder(folder, name)
                        "Merge" -> vm.mergeMatchingFolder(folder, name)
                        "Delete" -> vm.deleteMatchingFolder(folder)
                    }
                    dialog =
                        ""
                }) {
                    Text(
                        if (dialog ==
                            "Details"
                        ) {
                            "Close"
                        } else {
                            dialog
                        },
                    )
                }
            },
            dismissButton = { if (dialog != "Details")TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
}
