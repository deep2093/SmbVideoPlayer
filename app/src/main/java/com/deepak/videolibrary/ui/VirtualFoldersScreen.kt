package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.data.VirtualContent
import com.deepak.videolibrary.data.VirtualGroup
import com.deepak.videolibrary.data.VirtualGroups
import com.deepak.videolibrary.data.VirtualSet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Virtual folders: folders you make and name yourself ("Hollywood Movies"). Each reads the drive folders you choose
 * and builds its subfolders from the names in the file names. Nothing on the drive changes.
 */
@Composable
internal fun VirtualFoldersScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onOpenSettings: () -> Unit,
    active: Boolean = true,
) {
    val sets by vm.virtualSets.collectAsStateWithLifecycle()
    val openId = vm.openVirtualSetId?.takeIf { id -> sets.any { it.id == id } }
    // Opening and closing a folder slides and fades like the other folder pages.
    AnimatedContent(
        targetState = openId,
        transitionSpec = { folderSlide(forward = targetState != null) },
        label = "virtual_folder_open",
    ) { id ->
        val opened = sets.firstOrNull { it.id == id }
        if (opened != null) {
            VirtualSetPage(vm, opened, sets, onPlay)
        } else {
            VirtualSetList(vm, sets, gridState, onOpenSettings, active)
        }
    }
}

/** The same slide-and-fade as the other folder pages: forward when opening, back when closing. */
private fun folderSlide(forward: Boolean): ContentTransform =
    (
        slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (forward) width / 5 else -width / 5 } +
            fadeIn(tween(260, delayMillis = 40))
        ) togetherWith (
        slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (forward) -width / 5 else width / 5 } +
            fadeOut(tween(160))
        )

@Composable
private fun VirtualSetList(
    vm: LibraryViewModel,
    sets: List<VirtualSet>,
    gridState: LazyGridState,
    onOpenSettings: () -> Unit,
    active: Boolean,
) {
    var query by vm::folderSearch
    var search by remember { mutableStateOf(query.isNotBlank()) }
    var naming by remember { mutableStateOf(false) }
    // A new folder goes straight on to choosing its drive folders.
    var fillNew by remember { mutableStateOf<String?>(null) }
    val topLevel = sets.filter { it.parentId == null }
    val shown = if (query.isBlank()) topLevel else topLevel.filter { it.name.contains(query.trim(), ignoreCase = true) }
    val aspect = cardAspect(vm, Pages.VIRTUAL)
    ScrollToTopOnChange(query, gridState)
    LaunchedEffect(active) {
        if (!active && search) {
            query = ""
            search = false
        }
    }
    val selectPage = SelectionPages.VIRTUAL
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler(enabled = selection != null) { vm.ui.endSelection(selectPage) }
    LaunchedEffect(active) { if (!active) vm.ui.endSelection(selectPage) }
    ScrollTopOnRequest(vm, gridState, active)
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        HubTopBar {
            if (selection != null) {
                val chosen = sets.filter { it.id in selection.keys }
                FolderSelectionBar(
                    count = selection.keys.size,
                    total = shown.size,
                    onClose = { vm.ui.endSelection(selectPage) },
                    onSelectAll = { vm.ui.selectAllFolders(selectPage, shown.map { it.id }) },
                    onDownload = {
                        chosen.forEach { set -> vm.downloadVirtualSet(set) }
                        vm.ui.endSelection(selectPage)
                    },
                    onHide = null,
                    onRemove = {
                        chosen.forEach { vm.deleteVirtualSet(it.id) }
                        vm.ui.endSelection(selectPage)
                    },
                )
            } else {
                SearchableTopBar(
                    title = vm.sectionName(1),
                    searching = search,
                    onSearchingChange = { search = it },
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search virtual folders",
                    modifier = if (LocalHubTitle.current == null) Modifier.padding(top = 8.dp) else Modifier,
                ) {
                    RefreshLibraryAction(vm)
                    IconAction(Icons.Default.Add, "New virtual folder", onClick = { naming = true })
                    CardShapeButton(vm, Pages.VIRTUAL)
                    VirtualMenu(vm, showSort = false) { dismiss ->
                        DropdownMenuItem(text = { Text("Scan now") }, onClick = { dismiss(); vm.rescanVirtual() })
                        DropdownMenuItem(text = { Text("Settings") }, onClick = { dismiss(); onOpenSettings() })
                    }
                }
            }
        }
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            labelFor = { index -> shown.getOrNull(index)?.name?.let(::sectionLetter) },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
            LibraryPullRefresh(vm, Modifier.fillMaxSize()) { LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else libraryColumns(vm, folders = true)),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
                contentPadding = PaddingValues(top = 12.dp, bottom = if (floatingPadding(vm)) 112.dp else 24.dp),
                modifier = Modifier.fillMaxSize().testTag("virtual-folder-grid"),
            ) {
                if (shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(if (query.isBlank()) "Make your own virtual folders" else "No matching folders.", style = MaterialTheme.typography.titleMedium)
                            if (query.isBlank()) {
                                Text(
                                    "Tap + to make a folder, such as Hollywood Movies. Choose the drive folders it reads and its subfolders are made from the names in your file names.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Button(onClick = { naming = true }) { Text("New virtual folder") }
                            }
                        }
                    }
                }
                items(shown, key = { "set:" + it.id }, contentType = { "set" }) { set ->
                    VirtualSetCard(vm, set, aspect, vm.ui.tick(selectPage, set.id))
                }
                if (shown.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    ListTotal("${shown.size} ${if (shown.size == 1) "folder" else "folders"}")
                }
            } }
        }
    }
    if (naming) {
        RenameDialog(
            title = "New virtual folder",
            current = "",
            hint = "Give it any name, then choose the drive folders it reads. Nothing on your drive changes.",
            onDismiss = { naming = false },
            onRename = { typed -> fillNew = vm.createVirtualSet(typed) },
        )
    }
    fillNew?.let { id ->
        sets.firstOrNull { it.id == id }?.let { set -> VirtualFolderPicker(vm, set) { fillNew = null } }
    }
}

/** Sort and list/grid in one menu, like the other folder pages; [extra] adds this page's own entries. */
@Composable
private fun VirtualMenu(vm: LibraryViewModel, showSort: Boolean, extra: @Composable (dismiss: () -> Unit) -> Unit) {
    Box {
        var open by remember { mutableStateOf(false) }
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (showSort) {
                Text(
                    "Sort by",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
                listOf("NAME" to "Name", "COUNT" to "Number of items", "DATE" to "Latest by date", "SIZE" to "Total size").forEach { (sort, label) ->
                    DropdownMenuItem(
                        text = { Text(label + if (vm.folderSort == sort) { if (vm.folderSortDescending) " ↓" else " ↑" } else "") },
                        leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null) },
                        onClick = { vm.selectFolderSort(sort); open = false },
                    )
                }
                HorizontalDivider()
            }
            extra { open = false }
        }
    }
}

/** Choosing the drive folders and single videos a Virtual folder reads. Folders include everything inside them. */
@Composable
internal fun VirtualFolderPicker(vm: LibraryViewModel, set: VirtualSet, onClose: () -> Unit) {
    DrivePicker(
        vm = vm,
        title = "Choose for ${set.name}",
        explanation = "Tick drive folders (everything inside them is included) and single videos. They are sorted into subfolders by the names in the file names. Untick to take something out. Files on your drive are not changed.",
        confirmLabel = "Done",
        withFiles = true,
        initialFolders = set.folders.toSet(),
        initialFiles = set.files.toSet(),
        onDismiss = onClose,
    ) { folders, files ->
        vm.setVirtualSetMembers(set.id, folders, files)
        onClose()
    }
}

@Composable
private fun VirtualSetCard(vm: LibraryViewModel, set: VirtualSet, aspect: Float, selected: Boolean?) {
    val countFlow = remember(set.folders) { vm.virtualSetCount(set) }
    val count by countFlow.collectAsStateWithLifecycle(initialValue = 0)
    val key = vm.coverKey("vset:${set.id}:${set.folders.hashCode()}", count, count)
    // Keeps showing the current cover while a new one is prepared, so cards never blink empty.
    var cover by remember { mutableStateOf<File?>(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.virtualSetCover(set, key) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val selectPage = SelectionPages.VIRTUAL
    Box {
        FolderTile(
            title = set.name,
            detail = if (set.isEmpty) "Empty · tap to choose" else "$count videos",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = {
                when {
                    selected != null -> vm.ui.toggleFolder(selectPage, set.id)
                    set.isEmpty -> edit = true
                    else -> vm.openVirtualSetId = set.id
                }
            },
            onLongPress = { if (selected != null) vm.ui.toggleFolder(selectPage, set.id) else menu = true },
            onRename = { rename = true },
            selected = selected,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; vm.openVirtualSetId = set.id })
            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; vm.ui.toggleFolder(selectPage, set.id) })
            DropdownMenuItem(text = { Text("Choose folders and files") }, onClick = { menu = false; edit = true })
            DropdownMenuItem(text = { Text("Scan now") }, onClick = { menu = false; vm.rescanVirtual() })
            if (artworkOn) DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; changeCover = true })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.downloadVirtualSet(set) })
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Take out of Virtual folders…") }, onClick = { menu = false; remove = true })
        }
    }
    if (rename) RenameDialog("Rename virtual folder", set.name, "Only the name in this app changes.", { rename = false }) { vm.renameVirtualSet(set.id, it) }
    if (edit) VirtualFolderPicker(vm, set) { edit = false }
    if (changeCover) {
        ChangeCoverDialog(vm, CoverTarget(vm.virtualSetKey(set.id), set.name, set.name, null), null) { changeCover = false }
    }
    if (remove) {
        AlertDialog(
            onDismissRequest = { remove = false },
            title = { Text("Take out ${set.name}?") },
            text = { Text("The virtual folder and its subfolders are removed from this app. Your videos and folders on the drive are not touched.") },
            confirmButton = { TextButton(onClick = { remove = false; vm.deleteVirtualSet(set.id) }) { Text("Take out") } },
            dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The inside of a Virtual folder: one subfolder per name found in the file names of its drive folders. A video with
 * several names before the first hyphen is listed in each of those subfolders, never copied. Holding a subfolder
 * offers the same actions as the other folder pages.
 */
@Composable
private fun VirtualSetPage(vm: LibraryViewModel, set: VirtualSet, allSets: List<VirtualSet>, onPlay: (VideoEntity, List<VideoEntity>) -> Unit) {
    // The open subfolder is kept in the view model, so coming back from the player opens it again.
    var openGroup by remember(set.id) { mutableStateOf(vm.virtualOpenGroup[set.id]) }
    var folderQuery by remember(set.id, openGroup) { mutableStateOf("") }
    val children = allSets.filter { it.parentId == set.id }
    val parent = allSets.firstOrNull { it.id == set.parentId }
    var naming by remember { mutableStateOf(false) }
    var fillNew by remember { mutableStateOf<String?>(null) }
    val selectPage = "select:vset:" + set.id
    val selection = vm.ui.selectionOn(selectPage)
    BackHandler {
        when {
            selection != null -> vm.ui.endSelection(selectPage)
            openGroup != null -> openGroup = null
            else -> vm.openVirtualSetId = set.parentId
        }
    }
    var edit by remember { mutableStateOf(false) }
    // Grouping thousands of videos runs off the main thread, and the last result is shown at once when the folder is
    // opened again, so opening, closing and scrolling stay smooth while scans and thumbnails update the index.
    val content by produceState(vm.cachedVirtualContent(set), set, vm.folderSort, vm.folderSortDescending, vm.virtualGrouping) {
        val sort = vm.folderSort
        val descending = vm.folderSortDescending
        val rules = vm.virtualGrouping
        vm.virtualSetVideos(set).conflate().collect { list ->
            val built = withContext(Dispatchers.Default) {
                VirtualContent(list, VirtualGroups.sorted(VirtualGroups.build(set, list, rules), sort, descending), loaded = true)
            }
            value = built
            vm.rememberVirtualContent(set, built)
        }
    }
    val videos = content.videos
    val groups = content.groups
    val current = groups.firstOrNull { it.identity == openGroup }
    val term = folderQuery.trim()
    fun visible(name: String): Boolean = term.isEmpty() || name.contains(term, ignoreCase = true)
    val visibleGroups = groups.filter { visible(it.title) }
    val visibleChildren = children.filter { visible(it.name) }
    // Subfolder cards and the videos inside a subfolder each keep their own card shape (portrait or landscape).
    val page = if (openGroup == null) Pages.virtualSet(set.id) else Pages.virtualSetVideos(set.id)
    // A subfolder that disappears (renamed, taken out, emptied by a rescan) returns to the list.
    LaunchedEffect(content.loaded, current == null, openGroup) { if (content.loaded && openGroup != null && current == null) openGroup = null }
    val shownVideos = current?.videos.orEmpty()
    val visibleVideos = shownVideos.filter { visible(it.name) }
    // Each view keeps its own scroll position; the list of subfolders is where it was after closing one.
    val states = remember(set.id) { HashMap<String, LazyGridState>() }
    fun stateFor(id: String?): LazyGridState = states.getOrPut(id ?: "") {
        val saved = vm.virtualScroll[set.id + "|" + (id ?: "")]
        LazyGridState(saved?.first ?: 0, saved?.second ?: 0)
    }
    // Scroll positions are kept too, for when this page comes back after playing a video.
    androidx.compose.runtime.DisposableEffect(set.id) {
        onDispose {
            states.forEach { (id, state) -> vm.virtualScroll[set.id + "|" + id] = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        }
    }
    ScrollTopOnRequest(vm, stateFor(openGroup))
    LaunchedEffect(openGroup) {
        vm.ui.endSelection(selectPage)
        openGroup.let { if (it == null) vm.virtualOpenGroup.remove(set.id) else vm.virtualOpenGroup[set.id] = it }
    }
    fun toggle(key: String) = vm.ui.toggleFolder(selectPage, key)
    fun videosOf(keys: Set<String>): List<VideoEntity> = keys.flatMap { key ->
        when {
            key.startsWith("group:") -> groups.firstOrNull { it.identity == key.removePrefix("group:") }?.videos.orEmpty()
            key.startsWith("file:") -> shownVideos.filter { it.relativePath == key.removePrefix("file:") }
            else -> emptyList()
        }
    }.distinctBy { it.relativePath }
    val allKeys = if (current != null) shownVideos.map { "file:" + it.relativePath } else groups.map { "group:" + it.identity }
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        if (selection != null) {
            FolderSelectionBar(
                count = selection.keys.size,
                total = allKeys.size,
                onClose = { vm.ui.endSelection(selectPage) },
                onSelectAll = { vm.ui.selectAllFolders(selectPage, allKeys) },
                onDownload = {
                    vm.downloadVirtualGroup(current?.title ?: set.name, videosOf(selection.keys))
                    vm.ui.endSelection(selectPage)
                },
                onHide = null,
                onRemove = if (current == null) ({
                    vm.hideVirtualSubfolders(set.id, selection.keys.filter { it.startsWith("group:") }.map { it.removePrefix("group:") })
                    vm.ui.endSelection(selectPage)
                }) else null,
                removeLabel = "Take out",
            )
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (openGroup != null) openGroup = null else vm.openVirtualSetId = set.parentId }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = if (openGroup != null) "Back to ${set.name}" else "Back to ${parent?.name ?: vm.sectionName(1)}")
                }
                Text(
                    current?.title ?: set.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                RefreshLibraryAction(vm)
                if (current == null) {
                    IconAction(Icons.Default.CreateNewFolder, "New folder inside ${set.name}", onClick = { naming = true })
                    IconAction(Icons.Default.Edit, "Choose folders and files", onClick = { edit = true })
                    if (set.hidden.isNotEmpty()) {
                        IconAction(Icons.Default.Visibility, "Show folders you took out", onClick = { vm.restoreVirtualSubfolders(set.id) })
                    }
                    IconAction(
                        Icons.Default.PlayArrow, "Play all", enabled = videos.isNotEmpty(),
                        onClick = { videos.firstOrNull()?.let { onPlay(it, videos) } },
                    )
                } else {
                    IconAction(
                        Icons.Default.PlayArrow, "Play all", enabled = shownVideos.isNotEmpty(),
                        onClick = { shownVideos.firstOrNull()?.let { onPlay(it, shownVideos) } },
                    )
                }
                CardShapeButton(vm, page)
                VirtualMenu(vm, showSort = current == null) { }
                // On phones the download button is already on the app-name line above.
                if (LocalHubBarSlot.current == null) LocalTopBarEnd.current()
            }
        }
        if (selection == null) FolderLevelSearch(folderQuery, { folderQuery = it })
        if (set.isEmpty && children.isEmpty()) {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Text("Choose the drive folders and videos this virtual folder reads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { edit = true }) { Text("Choose folders and files") }
            }
        } else if (!set.isEmpty && content.loaded && groups.isEmpty()) {
            Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Looking for videos…", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Subfolders appear as the chosen drive folders are scanned. If nothing shows up, check the connection and scan again.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { vm.rescanVirtual() }) { Text("Scan now") }
            }
        }
        // Opening a subfolder slides in, and closing it slides back to the list where it was.
        AnimatedContent(
            targetState = openGroup,
            transitionSpec = { folderSlide(forward = targetState != null) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "virtual_subfolder_open",
        ) { groupId ->
            val shown = groups.firstOrNull { it.identity == groupId }
            val inside = shown?.videos.orEmpty().filter { visible(it.name) }
            val aspect = cardAspect(vm, if (groupId == null) Pages.virtualSet(set.id) else Pages.virtualSetVideos(set.id))
            val gridState = stateFor(groupId)
            // The letter rail on the right, like the other folder pages.
            FastScrollBox(
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                labelFor = { index ->
                    if (groupId == null) {
                        (children.map { it.name } + groups.map { it.title }).getOrNull(index)?.let(::sectionLetter)
                    } else {
                        inside.getOrNull(index)?.name?.let(::sectionLetter)
                    }
                },
                bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
            ) {
            LibraryPullRefresh(vm, Modifier.fillMaxSize()) { LazyVerticalGrid(
                state = gridState,
                modifier = Modifier.fillMaxSize().testTag(if (groupId == null) "virtual-subfolder-grid" else "virtual-subfolder-videos"),
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else libraryColumns(vm, folders = true)),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
                contentPadding = PaddingValues(top = 8.dp, bottom = if (floatingPadding(vm)) 112.dp else 16.dp),
            ) {
                // Folders you made inside this one come first, then the generated name folders.
                if (groupId == null) {
                    items(visibleChildren, key = { "set:" + it.id }, contentType = { "set" }) { child ->
                        VirtualSetCard(vm, child, aspect, null)
                    }
                }
                if (groupId == null) {
                    items(visibleGroups, key = { "group:" + it.identity }, contentType = { "group" }) { group ->
                        VirtualGroupCard(
                            vm, set, group, aspect,
                            selected = vm.ui.tick(selectPage, "group:" + group.identity),
                            onOpen = { openGroup = group.identity },
                            onSelect = { toggle("group:" + group.identity) },
                        )
                    }
                } else {
                    items(inside, key = { "file:" + it.relativePath }, contentType = { "video" }) { video ->
                        val ticked = vm.ui.tick(selectPage, "file:" + video.relativePath)
                        Box {
                            VideoCard(
                                video,
                                onClick = { if (ticked != null) toggle("file:" + video.relativePath) else onPlay(video, inside) },
                                vm = vm,
                                listMode = vm.folderListMode,
                                selectionScope = "own",
                                insideVirtualGeneratedFolder = true,
                                aspect = aspect,
                            )
                            if (ticked != null) SelectionBadge(ticked, Modifier.align(Alignment.TopEnd).padding(8.dp))
                        }
                    }
                }
                if (groups.isNotEmpty() || children.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                        val folders = groups.size + children.size
                        ListTotal(
                            if (groupId == null) "$folders ${if (folders == 1) "folder" else "folders"} · ${videos.size} videos"
                            else "${inside.size} ${if (inside.size == 1) "video" else "videos"}",
                        )
                    }
                }
            } }
            }
        }
    }
    if (edit) VirtualFolderPicker(vm, set) { edit = false }
    if (naming) {
        RenameDialog(
            title = "New folder inside ${set.name}",
            current = "",
            hint = "Give it any name, then choose the drive folders and videos it reads. Nothing on your drive changes.",
            onDismiss = { naming = false },
            onRename = { typed -> fillNew = vm.createVirtualSet(typed, parentId = set.id) },
        )
    }
    fillNew?.let { id -> allSets.firstOrNull { it.id == id }?.let { child -> VirtualFolderPicker(vm, child) { fillNew = null } } }
}

/** A generated subfolder. Hold it for Select, Change cover, Download, Cut, Copy, Paste, Rename and Take out. */
@Composable
private fun VirtualGroupCard(
    vm: LibraryViewModel,
    set: VirtualSet,
    group: VirtualGroup,
    aspect: Float,
    selected: Boolean?,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
) {
    val count = group.videos.size
    val key = vm.coverKey("vgroup:${set.id}:${group.identity}", count, count, group.lastModified)
    // Keeps showing the current cover while a new one is prepared, so cards never blink empty.
    var cover by remember { mutableStateOf<File?>(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.virtualSubCover(set.id, group.identity, group.videos, key) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var changeCover by remember { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    Box {
        FolderTile(
            title = group.title,
            detail = "$count ${if (count == 1) "video" else "videos"}",
            cover = cover,
            aspect = aspect,
            listMode = vm.folderListMode,
            onClick = { if (selected != null) onSelect() else onOpen() },
            onLongPress = { if (selected != null) onSelect() else menu = true },
            onRename = { rename = true },
            selected = selected,
        )
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
            if (artworkOn) DropdownMenuItem(text = { Text("Change cover") }, onClick = { menu = false; changeCover = true })
            DropdownMenuItem(text = { Text("Select") }, onClick = { menu = false; onSelect() })
            DropdownMenuItem(text = { Text("Download") }, onClick = { menu = false; vm.downloadVirtualGroup(group.title, group.videos) })
            DropdownMenuItem(text = { Text("Cut") }, onClick = { menu = false; vm.stageVideos(group.videos, true) })
            DropdownMenuItem(text = { Text("Copy") }, onClick = { menu = false; vm.stageVideos(group.videos, false) })
            if (vm.explorerClipboard != null) {
                DropdownMenuItem(text = { Text("Paste") }, onClick = { menu = false; vm.pasteDestination = vm.settings.rootPath })
            }
            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; rename = true })
            DropdownMenuItem(text = { Text("Details") }, onClick = { menu = false; details = true })
            DropdownMenuItem(text = { Text("Take out of this folder") }, onClick = { menu = false; vm.hideVirtualSubfolders(set.id, listOf(group.identity)) })
        }
    }
    if (rename) {
        RenameDialog(
            "Rename folder", group.title,
            "Only the name in this app changes. Giving it the name of another subfolder joins the two. Your files keep their names.",
            { rename = false },
        ) { vm.renameVirtualSubfolder(set.id, group.identity, it) }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text("Details") },
            text = { Text("${group.title}\n$count ${if (count == 1) "video" else "videos"}\nSubfolder of ${set.name}\nA video listed in several subfolders is the same file, never a copy.") },
            confirmButton = { TextButton(onClick = { details = false }) { Text("Close") } },
        )
    }
    if (changeCover) {
        ChangeCoverDialog(vm, CoverTarget(vm.virtualSubKey(set.id, group.identity), group.title, group.title, null), null) { changeCover = false }
    }
}
