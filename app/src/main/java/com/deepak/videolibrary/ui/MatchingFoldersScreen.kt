package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
    val selectPage = SelectionPages.CUSTOM
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler(enabled = selection != null) { vm.ui.endSelection(selectPage) }
    LaunchedEffect(active) { if (!active) vm.ui.endSelection(selectPage) }
    ScrollTopOnRequest(vm, gridState, active)
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        HubTopBar {
        if (selection != null) {
            val chosenOwn = own.filter { "own:" + it.id in selection.keys }
            val chosenMatch = folders.map { it.folder }.filter { "match:" + it.id in selection.keys }
            FolderSelectionBar(
                count = selection.keys.size,
                total = shownOwn.size + folders.size,
                onClose = { vm.ui.endSelection(selectPage) },
                onSelectAll = { vm.ui.selectAllFolders(selectPage, shownOwn.map { "own:" + it.id } + folders.map { "match:" + it.folder.id }) },
                onDownload = {
                    chosenOwn.forEach(vm::downloadOwnFolder)
                    chosenMatch.forEach(vm::downloadMatchingFolder)
                    vm.ui.endSelection(selectPage)
                },
                // Folders you made yourself are removed rather than hidden.
                onHide = if (chosenOwn.isEmpty()) ({
                    chosenMatch.forEach(vm::hideMatchingFolder)
                    vm.ui.endSelection(selectPage)
                }) else null,
                onRemove = {
                    chosenOwn.forEach { vm.deleteOwnFolder(it.id) }
                    chosenMatch.forEach(vm::deleteMatchingFolder)
                    vm.ui.endSelection(selectPage)
                },
            )
        } else
        SearchableTopBar(
            title = vm.sectionName(0),
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
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
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
                items(shownOwn, key = { "own:" + it.id }) { folder -> OwnFolderCard(vm, folder, aspect, vm.ui.tick(selectPage, "own:" + folder.id)) }
                items(folders, key = { it.folder.id }) { row -> MatchingFolderCard(vm, row, aspect, onOpen, onPlay, vm.ui.tick(selectPage, "match:" + row.folder.id)) }
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
private fun OwnFolderCard(vm: LibraryViewModel, folder: OwnFolder, aspect: Float, selected: Boolean?) {
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
            onClick = {
                when {
                    selected != null -> vm.ui.toggleFolder(SelectionPages.CUSTOM, "own:" + folder.id)
                    parts == 0 -> { edit = true }
                    else -> { vm.openOwnFolderId = folder.id }
                }
            },
            onLongPress = { if (selected != null) { vm.ui.toggleFolder(SelectionPages.CUSTOM, "own:" + folder.id) } else { menu = true } },
            onRename = { rename = true },
            selected = selected,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openOwnFolderId = folder.id })
            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; vm.ui.toggleFolder(SelectionPages.CUSTOM, "own:" + folder.id) })
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

/**
 * The inside of an own folder: its drive folders as cards, then its single videos. Single videos are grouped into
 * virtual folders by the library's naming rule ("Tom Cruise - movie" goes into Tom Cruise) unless that is switched
 * off with the button at the top, which lists them one by one as before.
 *
 * Holding a card offers Select: several folders, groups and videos can then be downloaded or taken out of this
 * folder together.
 */
@Composable
private fun OwnFolderPage(vm: LibraryViewModel, folder: OwnFolder, onOpen: () -> Unit, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    var openGroup by remember(folder.id) { mutableStateOf<String?>(null) }
    val selectPage = "select:own:" + folder.id
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler {
        when {
            selection != null -> vm.ui.endSelection(selectPage)
            openGroup != null -> openGroup = null
            else -> vm.openOwnFolderId = null
        }
    }
    val page = Pages.ownFolder(folder.id)
    val aspect = cardAspect(vm, page)
    var edit by remember { mutableStateOf(false) }
    val files by produceState(emptyList<VideoEntity>(), folder) { value = vm.ownFolderFiles(folder) }
    val grouped = vm.ownFolderGroupsFiles(folder.id)
    val groups = remember(files) { files.groupBy { it.groupKey }.toSortedMap() }
    val shownFiles = openGroup?.let { groups[it].orEmpty() } ?: files
    val gridState = remember(folder.id, openGroup) { LazyGridState() }
    ScrollTopOnRequest(vm, gridState)
    LaunchedEffect(openGroup) { vm.ui.endSelection(selectPage) }
    fun toggle(key: String) = vm.ui.toggleFolder(selectPage, key)
    // Everything a selection key stands for, as videos to download or members to take out.
    fun pathsOf(keys: Set<String>): List<String> = keys.flatMap { key ->
        when {
            key.startsWith("group:") -> groups[key.removePrefix("group:")].orEmpty().map { it.relativePath }
            key.startsWith("file:") -> listOf(key.removePrefix("file:"))
            else -> emptyList()
        }
    }
    val allKeys = if (openGroup != null) {
        shownFiles.map { "file:" + it.relativePath }
    } else {
        folder.folders.map { "dir:$it" } + if (grouped) groups.keys.map { "group:$it" } else files.map { "file:" + it.relativePath }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        if (selection != null) {
            FolderSelectionBar(
                count = selection.keys.size,
                total = allKeys.size,
                onClose = { vm.ui.endSelection(selectPage) },
                onSelectAll = { vm.ui.selectAllFolders(selectPage, allKeys) },
                onDownload = {
                    selection.keys.filter { it.startsWith("dir:") }.forEach { vm.downloadDirectory(it.removePrefix("dir:")) }
                    pathsOf(selection.keys).takeIf { it.isNotEmpty() }?.let(vm::downloadFiles)
                    vm.ui.endSelection(selectPage)
                },
                onHide = null,
                onRemove = {
                    val dirs = selection.keys.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }.toSet()
                    val gone = pathsOf(selection.keys).toSet()
                    vm.setOwnFolderMembers(folder.id, folder.folders - dirs, folder.files - gone)
                    vm.ui.endSelection(selectPage)
                },
                removeLabel = "Take out",
            )
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (openGroup != null) openGroup = null else vm.openOwnFolderId = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = if (openGroup != null) "Back to ${folder.name}" else "Back to Custom folders")
                }
                Text(
                    openGroup?.let(::groupTitle) ?: folder.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (openGroup == null) {
                    IconAction(Icons.Default.Edit, "Choose folders and files", onClick = { edit = true })
                    if (files.isNotEmpty()) {
                        IconAction(
                            if (grouped) Icons.Default.ViewModule else Icons.Default.Topic,
                            if (grouped) "Show single videos one by one" else "Group single videos into virtual folders",
                            onClick = { vm.setOwnFolderGroupsFiles(folder.id, !grouped) },
                        )
                    }
                    IconAction(Icons.Default.PlayArrow, "Play all", enabled = !folder.isEmpty, onClick = { vm.playLibrary("own:${folder.id}", onPlay = onPlay) })
                } else {
                    IconAction(Icons.Default.PlayArrow, "Play all", enabled = shownFiles.isNotEmpty(), onClick = { shownFiles.firstOrNull()?.let { onPlay(it, shownFiles) } })
                }
                CardShapeButton(vm, page)
                // On phones the download button is already on the app-name line above.
                if (LocalHubBarSlot.current == null) LocalTopBarEnd.current()
            }
        }
        if (folder.isEmpty) {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Text("Choose drive folders and videos for this folder.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { edit = true }) { Text("Choose folders and files") }
            }
        }
        LazyVerticalGrid(
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            columns = GridCells.Fixed(if (vm.folderListMode) 1 else libraryColumns(vm.folderGridPreset, floatingPadding(vm))),
            horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
            verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
            contentPadding = PaddingValues(top = 8.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
        ) {
            if (openGroup == null) {
                items(folder.folders, key = { "dir:$it" }) { path ->
                    DriveFolderCard(
                        vm, path, aspect, onOpen,
                        selected = vm.ui.tick(selectPage, "dir:$path"),
                        onSelect = { toggle("dir:$path") },
                        onTakeOut = { vm.setOwnFolderMembers(folder.id, folder.folders - path, folder.files) },
                    )
                }
                if (grouped) {
                    items(groups.keys.toList(), key = { "group:$it" }) { key ->
                        FileGroupCard(
                            vm, key, groups[key].orEmpty(), aspect,
                            selected = vm.ui.tick(selectPage, "group:$key"),
                            onOpen = { openGroup = key },
                            onSelect = { toggle("group:$key") },
                        )
                    }
                }
            }
            if (openGroup != null || !grouped) {
                items(shownFiles, key = { "file:" + it.relativePath }) { video ->
                    val ticked = vm.ui.tick(selectPage, "file:" + video.relativePath)
                    Box {
                        VideoCard(
                            video,
                            onClick = { if (ticked != null) toggle("file:" + video.relativePath) else onPlay(video, shownFiles) },
                            vm = vm,
                            listMode = vm.folderListMode,
                            selectionScope = "own",
                            aspect = aspect,
                        )
                        if (ticked != null) SelectionBadge(ticked, Modifier.align(Alignment.TopEnd).padding(8.dp))
                    }
                }
            }
        }
    }
    if (edit) OwnFolderContentsPicker(vm, folder) { edit = false }
}

private fun groupTitle(key: String) = key.split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(java.util.Locale.ROOT) } }

/** A tick shown on a card while selecting. */
@Composable
internal fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = if (selected) "Selected" else "Not selected",
        tint = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.White,
        modifier = modifier.size(26.dp).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f), androidx.compose.foundation.shape.CircleShape),
    )
}

/** Single videos of an own folder that share a name, shown as one virtual folder. */
@Composable
private fun FileGroupCard(
    vm: LibraryViewModel,
    key: String,
    videos: List<VideoEntity>,
    aspect: Float,
    selected: Boolean?,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
) {
    val coverKey = vm.coverKey("group:$key", videos.size, videos.size)
    var cover by remember(coverKey) { mutableStateOf(vm.cachedCover(coverKey)) }
    LaunchedEffect(coverKey) { cover = vm.coverFor(key, coverKey) }
    var menu by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    Box {
        FolderTile(
            title = groupTitle(key),
            detail = "${videos.size} ${if (videos.size == 1) "video" else "videos"}",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = { if (selected != null) onSelect() else onOpen() },
            onLongPress = { if (selected != null) { onSelect() } else { menu = true } },
            selected = selected,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() })
            if (artworkOn) DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; changeCover = true })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.downloadFiles(videos.map { it.relativePath }) })
        }
    }
    if (changeCover) {
        // Same cover as the library's virtual folder of this name.
        ChangeCoverDialog(vm, CoverTarget(ArtworkKeys.virtualFolder(key), groupTitle(key), key), null) { changeCover = false }
    }
}

/** A drive folder inside an own folder: opens as the list of its videos (with sub-folders). */
@Composable
private fun DriveFolderCard(
    vm: LibraryViewModel,
    path: String,
    aspect: Float,
    onOpen: () -> Unit,
    selected: Boolean?,
    onSelect: () -> Unit,
    onTakeOut: () -> Unit,
) {
    val countFlow = remember(path) { vm.driveFolderCount(path) }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val key = vm.coverKey("original:$path", count, count)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.originalCover(path, key) }
    var rename by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val title = vm.originalDisplayName(path)
    Box {
        FolderTile(
            title = title,
            detail = "$count videos",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = {
                if (selected != null) {
                    onSelect()
                } else {
                    vm.openDriveFolder(path)
                    onOpen()
                }
            },
            onLongPress = { if (selected != null) { onSelect() } else { menu = true } },
            onRename = { rename = true },
            selected = selected,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() })
            if (artworkOn) DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; changeCover = true })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.downloadDirectory(path) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Take out of this folder") }, onClick = { menu = false; onTakeOut() })
        }
    }
    if (changeCover) {
        ChangeCoverDialog(vm, CoverTarget(ArtworkKeys.originalFolder(path), title, path.substringAfterLast('/'), null), null) { changeCover = false }
    }
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
    selected: Boolean?,
) {
    val folder = row.folder
    val selectKey = "match:" + folder.id
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
            onClick = { if (selected != null) vm.ui.toggleFolder(SelectionPages.CUSTOM, selectKey) else open() },
            onLongPress = { if (selected != null) { vm.ui.toggleFolder(SelectionPages.CUSTOM, selectKey) } else { menu = true } },
            onRename = ::startRename,
            selected = selected,
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
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_select_8598222)) }, onClick = {
                menu = false
                vm.ui.toggleFolder(SelectionPages.CUSTOM, selectKey)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_cut_38d13bd)) }, onClick = {
                menu = false
                vm.stageMatchingFolder(folder, true)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_copy_af74f7c)) }, onClick = {
                menu = false
                vm.stageMatchingFolder(folder, false)
            })
            if (vm.explorerClipboard != null) DropdownMenuItem(text = { Text(stringResource(R.string.ui_paste_9db2f69)) }, onClick = {
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
