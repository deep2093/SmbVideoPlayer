package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
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
import com.deepak.videolibrary.artwork.ArtworkKeys
import com.deepak.videolibrary.data.*

/**
 * Custom folders: folders you named and filled yourself, drive folders you added, and filename collections, all in
 * one grid. One Add button offers the three ways to make one.
 */
@Composable
internal fun MatchingFoldersScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onOpen: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    active: Boolean = true,
) {
    val folders by vm.matchingFolders.collectAsStateWithLifecycle()
    val own by vm.ownFolders.collectAsStateWithLifecycle()
    val opened = own.firstOrNull { it.id == vm.openOwnFolderId }
    if (opened != null) {
        OwnFolderPage(vm, opened, onOpen, onPlay)
        return
    }
    val query by vm.matchFolderQuery.collectAsStateWithLifecycle()
    var search by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    // A new own folder goes straight on to choosing what it shows.
    var fillNew by remember { mutableStateOf<String?>(null) }
    val shownOwn = if (query.isBlank()) own else own.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val aspect = cardAspect(vm, Pages.CUSTOM)
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
            CardShapeButton(vm, Pages.CUSTOM)
            Box {
                IconAction(Icons.Default.Add, "Add custom folder", onClick = { addMenu = true })
                DropdownMenu(addMenu, { addMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("New folder") },
                        leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                        onClick = { addMenu = false; name = ""; naming = true },
                    )
                    DropdownMenuItem(
                        text = { Text("Add drive folders") },
                        leadingIcon = { Icon(Icons.Default.Folder, null) },
                        onClick = { addMenu = false; picking = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.ui_create_filename_collection_fa7e2fd)) },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        onClick = { addMenu = false; name = ""; creating = true },
                    )
                }
            }
        }
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f),
            // Custom folders are listed by name: own folders first, then the others.
            labelFor = { index -> (shownOwn.map { it.name } + folders.map { it.folder.name }).getOrNull(index)?.let(::sectionLetter) },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
            LazyVerticalGrid(
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else libraryColumns(vm.folderGridPreset, floatingPadding(vm))),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
            ) {
                if (folders.isEmpty() && shownOwn.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (query.isBlank()) "Keep your favourite folders together" else "No custom folders found", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Tap + to make a folder with your own name, add folders from your drive, or collect videos by filename.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(shownOwn, key = { "own:" + it.id }) { folder -> OwnFolderCard(vm, folder, aspect) }
                items(folders, key = { it.folder.id }) { row -> MatchingFolderCard(vm, row, aspect, onOpen, onPlay) }
            }
        }
    }
    if (picking) SourceFolderPicker(vm, folders.map { it.folder }, onClose = { picking = false })
    if (naming) {
        RenameDialog(
            title = "New folder",
            current = "",
            hint = "Give it any name, then choose the drive folders and files it shows. Nothing on your drive changes.",
            onDismiss = { naming = false },
            onRename = { typed -> fillNew = vm.createOwnFolder(typed) },
        )
    }
    fillNew?.let { id ->
        own.firstOrNull { it.id == id }?.let { folder ->
            OwnFolderContentsPicker(vm, folder) { fillNew = null }
        }
    }
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
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
}

/** Picking the drive folders and single files an own folder shows. */
@Composable
internal fun OwnFolderContentsPicker(vm: LibraryViewModel, folder: OwnFolder, onClose: () -> Unit) {
    DrivePicker(
        vm = vm,
        title = "Choose what ${folder.name} shows",
        explanation = "Tick folders (everything inside them is included) and single videos. Untick to take them out. Files on your drive are not changed.",
        confirmLabel = "Done",
        withFiles = true,
        initialFolders = folder.folders.toSet(),
        initialFiles = folder.files.toSet(),
        onDismiss = onClose,
    ) { folders, files ->
        vm.setOwnFolderMembers(folder.id, folders, files)
        onClose()
    }
}

@Composable
private fun OwnFolderCard(vm: LibraryViewModel, folder: OwnFolder, aspect: Float) {
    val countFlow = remember(folder) { vm.ownFolderCount(folder) }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val key = vm.coverKey("own:${folder.id}:${folder.folders.hashCode()}:${folder.files.hashCode()}", count, count)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.ownFolderCover(folder, key) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val parts = folder.folders.size + folder.files.size
    Box {
        FolderTile(
            title = folder.name,
            detail = if (parts == 0) "Empty · tap to choose" else "$count videos",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = { if (parts == 0) edit = true else vm.openOwnFolderId = folder.id },
            onLongPress = { menu = true },
            onRename = { rename = true },
            onOptions = { menu = true },
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openOwnFolderId = folder.id })
            DropdownMenuItem(text = { Text("Choose folders and files") }, onClick = { menu = false; edit = true })
            if (artworkOn) DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; changeCover = true })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.downloadOwnFolder(folder) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Remove from Custom folders…") }, onClick = { menu = false; remove = true })
        }
    }
    if (rename) {
        RenameDialog("Rename folder", folder.name, "Only the name in this app changes.", { rename = false }) { vm.renameOwnFolder(folder.id, it) }
    }
    if (edit) OwnFolderContentsPicker(vm, folder) { edit = false }
    if (changeCover) {
        ChangeCoverDialog(vm, CoverTarget(vm.ownFolderKey(folder.id), folder.name, folder.name, null), null) { changeCover = false }
    }
    if (remove) {
        AlertDialog(
            onDismissRequest = { remove = false },
            title = { Text("Remove ${folder.name}?") },
            text = { Text("The folder is removed from Custom folders. Files on your drive are not touched.") },
            confirmButton = { TextButton(onClick = { remove = false; vm.deleteOwnFolder(folder.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } },
        )
    }
}

/** The inside of an own folder: its drive folders as cards, then its single videos. */
@Composable
private fun OwnFolderPage(vm: LibraryViewModel, folder: OwnFolder, onOpen: () -> Unit, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    BackHandler { vm.openOwnFolderId = null }
    val page = Pages.ownFolder(folder.id)
    val aspect = cardAspect(vm, page)
    var edit by remember { mutableStateOf(false) }
    val files by produceState(emptyList<VideoEntity>(), folder) { value = vm.ownFolderFiles(folder) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openOwnFolderId = null }) { Icon(Icons.Default.ArrowBack, contentDescription = "Back to Custom folders") }
            Text(folder.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconAction(Icons.Default.Edit, "Choose folders and files", onClick = { edit = true })
            IconAction(Icons.Default.PlayArrow, "Play all", enabled = !folder.isEmpty, onClick = { vm.playLibrary("own:${folder.id}", onPlay = onPlay) })
            CardShapeButton(vm, page)
            LocalTopBarEnd.current()
        }
        if (folder.isEmpty) {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Text("Choose drive folders and videos for this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { edit = true }) { Text("Choose folders and files") }
            }
        }
        LazyVerticalGrid(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            columns = GridCells.Fixed(if (vm.folderListMode) 1 else libraryColumns(vm.folderGridPreset, floatingPadding(vm))),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
        ) {
            items(folder.folders, key = { "dir:$it" }) { path -> DriveFolderCard(vm, path, aspect, onOpen) }
            items(files, key = { "file:" + it.relativePath }) { video ->
                VideoCard(video, onClick = { onPlay(video, files) }, vm = vm, listMode = vm.folderListMode, selectionScope = "own", aspect = aspect)
            }
        }
    }
    if (edit) OwnFolderContentsPicker(vm, folder) { edit = false }
}

/** A drive folder inside an own folder: opens as the list of its videos (with sub-folders). */
@Composable
private fun DriveFolderCard(vm: LibraryViewModel, path: String, aspect: Float, onOpen: () -> Unit) {
    val countFlow = remember(path) { vm.driveFolderCount(path) }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val key = vm.coverKey("original:$path", count, count)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.originalCover(path, key) }
    var rename by remember { mutableStateOf(false) }
    val title = vm.originalDisplayName(path)
    FolderTile(
        title = title,
        detail = "$count videos",
        cover = cover,
        aspect = aspect,
        listMode = vm.folderListMode,
        onClick = {
            vm.openDriveFolder(path)
            onOpen()
        },
        onLongPress = { rename = true },
        onRename = { rename = true },
    )
    if (rename) {
        RenameDialog("Rename folder", title, "Only the name shown in this app changes; the folder on your drive keeps its name.", { rename = false }) {
            vm.renameOriginalDisplay(path, it)
        }
    }
}

@Composable
private fun MatchingFolderCard(
    vm: LibraryViewModel,
    row: MatchFolderRow,
    aspect: Float,
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
    fun startRename() {
        name = folder.name
        dialog = "Rename"
    }
    Box {
        FolderTile(
            title = folder.name,
            detail = "${row.count} videos",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = ::open,
            onLongPress = { menu = true },
            onRename = ::startRename,
            onOptions = { menu = true },
        )
        if (changeCover) {
            ChangeCoverDialog(vm, CoverTarget(ArtworkKeys.matchFolder(folder.id), folder.name, folder.name, null), null) { changeCover = false }
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
                vm.query.value = ""
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
                vm.pasteDestination = vm.settings.rootPath
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_rename_d3f4cb8)) }, onClick = {
                menu = false
                startRename()
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
            DropdownMenuItem(text = { Text("Remove from Custom folders…") }, onClick = {
                menu = false
                dialog = "Remove"
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
                            "Remove" -> if (folder.sourcePath == null) "Remove this automatic collection? Original files and existing virtual assignments are kept." else "Remove this folder from Custom folders? Files on your drive and virtual assignments are kept."
                            else -> folder.sourcePath?.let { "${row.count} indexed videos in $it. " + if (folder.includeSubfolders) "Includes subfolders." else "This folder only." }
                                ?: "${row.count} visible videos with '${folder.term}' anywhere in the filename, ignoring case. Membership updates automatically."
                        },
                    )
                    if (dialog == "Rename" || dialog == "Merge") {
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
                        "Remove" -> vm.deleteMatchingFolder(folder)
                    }
                    dialog = ""
                }) { Text(if (dialog == "Details") "Close" else dialog) }
            },
            dismissButton = { if (dialog != "Details") TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
}
