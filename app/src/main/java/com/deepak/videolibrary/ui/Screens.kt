package com.deepak.videolibrary.ui

import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.navigationBarsPadding
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Menu
import com.deepak.videolibrary.data.FolderSummary
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.l…5432 tokens truncated…             Text(video.name, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(Formatter.formatShortFileSize(context, video.size),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Options for ${video.name}")
            }
        }
    }
    Box(modifier) {
        Column(Modifier.fillMaxWidth().combinedClickable(onClick = {if(vm.selectedPaths.isNotEmpty())vm.toggleSelected(video.relativePath,selectionScope) else onClick()}, onLongClick = { menuOpen = true })) {
            if (listMode) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                artwork()
                Box(Modifier.weight(1f)) { caption() }
            } else {
                artwork()
                caption()
            }
        }
        FileActionsMenu(menuOpen,{menuOpen=false},vm,video.relativePath,onPlay=onClick,
            selectionScope=selectionScope,
            onPlayFromStart={vm.playFromStart(video.relativePath,onClick)},onRemoveContinue=onRemoveFromContinue,
            details="${video.name}\n${Formatter.formatFileSize(context,video.size)}\n${vm.originalVideoLocation(video.relativePath)}")
    }
}

// ---------------------------------------------------------------- Virtual folders

@Composable
fun FoldersScreen(vm: LibraryViewModel, gridState: LazyGridState, onOpenGroup: () -> Unit, onPlay: (VideoEntity, List<VideoEntity>) -> Unit, onOpenSettings: () -> Unit) {
    val shownGroups by vm.groups.collectAsStateWithLifecycle()
    val recentlyWatched by vm.continuing.collectAsStateWithLifecycle()
    var folderQuery by vm::folderSearch
    var searchExpanded by rememberSaveable { mutableStateOf(folderQuery.isNotBlank()) }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var editFolders by rememberSaveable { mutableStateOf(false) }
    val matchingVideos=vm.folderPages.collectAsLazyPagingItems()
    val matchingCount by vm.folderResultsCount.collectAsStateWithLifecycle()
    LaunchedEffect(folderQuery){gridState.scrollToItem(0)}
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) folderQuery = "" }) {
                Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close folder search" else "Search folders")
            }
            Box {
                var sortExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { sortExpanded = true }) {
                    Icon(Icons.Filled.Sort, contentDescription = "Sort virtual folders")
                }
                DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                    listOf("NAME" to "Name", "COUNT" to "Number of items",
                        "DATE" to "Latest by date", "SIZE" to "Total size").forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = { Text(label + if (vm.folderSort == sort) {
                                if (vm.folderSortDescending) " ↓" else " ↑"
                            } else "") },
                            onClick = { vm.selectFolderSort(sort); sortExpanded = false }
                        )
                    }
                }
            }
            IconButton(onClick = vm::toggleFolderLayout) {
                Icon(if (vm.folderListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
                    contentDescription = if (vm.folderListMode) "Show folder grid" else "Show folder list")
            }
            Box {
                var folderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    DropdownMenuItem(text = { Text(if (editFolders) "Finish organizing" else "Organize folders") },
                        onClick = { editFolders = !editFolders; folderMenu = false })
                    DropdownMenuItem(text = { Text("Refresh library") }, onClick = { vm.startScan(ScanMode.REFRESH); folderMenu = false })
                    DropdownMenuItem(text = { Text("Settings") }, onClick = { folderMenu = false; onOpenSettings() })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (searchExpanded) {
            OutlinedTextField(value = folderQuery, onValueChange = { folderQuery = it },
                label = { Text("Search folder and video names") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        val columns = LibraryLayout.columns(vm.folderGridPreset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = if(floatingPadding(vm))112.dp else 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                    Column {
        if (recentlyWatched.isNotEmpty() && folderQuery.isBlank()) {
            Text("Continue Watching", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp))
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(recentlyWatched, key = { it.id }) { video ->
                    Box(Modifier.width(218.dp)) {
                        VideoCard(video, onClick = { onPlay(video, listOf(video)) }, vm = vm,
                            onRemoveFromContinue = {
                                vm.hideContinue(video.relativePath)
                            })
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Text("${shownGroups.size} folders · " + when (vm.folderSort) {
                        "COUNT" -> "Number of items"; "DATE" -> "Latest by date"; "SIZE" -> "Total size"; else -> "Name"
                    } + if (vm.folderSortDescending) " ↓" else " ↑", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                }
                if (folderQuery.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }, key = "play-matches") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("$matchingCount matching videos", modifier = Modifier.weight(1f))
                        TextButton(enabled = matchingCount>0, onClick = { vm.playLibrary("folders",onPlay=onPlay) }) { Text("Play all results") }
                    }
                }
                if(folderQuery.isNotBlank()) items(count=matchingVideos.itemCount,key=matchingVideos.itemKey {"result:${it.relativePath}"}) { index ->
                    matchingVideos[index]?.let { video -> VideoCard(video,onClick={vm.playLibrary("folders",video,onPlay)},vm=vm,listMode=vm.folderListMode,selectionScope="folders") }
                }
                if (shownGroups.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (folderQuery.isNotBlank()) "No matching folders." else "Your virtual folders appear after a scan.",
                        modifier = Modifier.padding(vertical = 24.dp))
                }
                gridItems(shownGroups, key = { it.groupKey }, contentType = { "folder" }) { row ->
                    Column {
                        FolderCard(vm, row, listMode = vm.folderListMode) {
                            vm.matchFolderFilter.value=null
                            vm.groupFilter.value = row.groupKey
                            vm.query.value = if (matchesFilename(row.groupKey, folderQuery)) "" else folderQuery
                            onOpenGroup()
                        }
                        var menuExpanded by remember(row.groupKey) { mutableStateOf(false) }
                        if (editFolders) Box {
                            TextButton(onClick = { menuExpanded = true }) { Text("⋮ Options") }
                            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                DropdownMenuItem(text = { Text("Rename folder") }, onClick = {
                                    menuExpanded = false; sourceFolder = row.groupKey; operation = "Rename"; targetName = row.groupKey
                                })
                                DropdownMenuItem(text = { Text("Merge into…") }, onClick = {
                                    menuExpanded = false; sourceFolder = row.groupKey; operation = "Merge"; targetName = ""
                                })
                            }
                        }
                    }
                }
            }
        }
    }
    if (sourceFolder != null) {
        AlertDialog(onDismissRequest = { sourceFolder = null }, title = { Text("$operation ${sourceFolder}") },
            text = { Column {
                Text(if (operation == "Merge") "All videos assigned to this group will be assigned to the destination virtual folder." else "Rename this virtual folder. No SMB files are renamed.")
                OutlinedTextField(value = targetName, onValueChange = { targetName = it }, label = { Text(if (operation == "Merge") "Destination folder" else "New name") })
            } },
            confirmButton = { TextButton(onClick = {
                val from = sourceFolder!!
                if (operation == "Merge") vm.mergeFolders(from, targetName.trim()) else vm.renameFolder(from, targetName.trim())
                sourceFolder = null
            }) { Text(operation) } },
            dismissButton = { TextButton(onClick = { sourceFolder = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderCard(vm:LibraryViewModel,row:FolderSummary,modifier:Modifier=Modifier,listMode:Boolean=false,onClick:()->Unit){
    var actions by remember(row.groupKey){mutableStateOf(false)}
    val key=vm.coverKey("group:${row.groupKey}",row.count,row.thumbsDone,row.lastModified)
    var cover by remember(key){mutableStateOf(vm.cachedCover(key))}
    LaunchedEffect(key){cover=vm.coverFor(row.groupKey,key)}
    val artwork:@Composable ()->Unit={
        Box((if(listMode)Modifier.width(104.dp).height(68.dp)else Modifier.fillMaxWidth().aspectRatio(16f/9f)),contentAlignment=androidx.compose.ui.Alignment.Center){
            if(cover!=null)FileImage(cover,Modifier.fillMaxSize())else Icon(Icons.Filled.Folder,null,modifier=Modifier.height(36.dp).width(36.dp))
        }
    }
    val caption:@Composable ()->Unit={Column(Modifier.padding(6.dp)){
        Text(row.groupKey.replaceFirstChar{it.titlecase(Locale.ROOT)},minLines=2,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text("${row.count} videos",style=MaterialTheme.typography.labelSmall)
    }}
    Box(modifier){
        Column(Modifier.fillMaxWidth().combinedClickable(onClick=onClick,onLongClick={actions=true})){
            if(listMode)Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){artwork();Box(Modifier.weight(1f)){caption()}}else{artwork();caption()}
        }
        FileActionsMenu(actions,{actions=false},vm,row.groupKey,directory=true,virtual=true,details="${row.groupKey}\n${row.count} videos\nVirtual library folder")
    }
}

@Composable
private fun LibraryDisplayMenu(vm: LibraryViewModel) {
    IconButton(onClick = vm::toggleVideoLayout) {
        Icon(if (vm.videoListMode) Icons.Filled.GridView else Icons.Filled.ViewList,
            contentDescription = if (vm.videoListMode) "Show video grid" else "Show video list")
    }
    Box {
        var expanded by remember { mutableStateOf(false) }
        IconButton(onClick = { expanded = true }) { Icon(Icons.Filled.Sort, contentDescription = "Sort videos") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            VideoSort.entries.forEach { sort ->
                DropdownMenuItem(text = { Text(sort.label + if (vm.videoSort == sort) {
                    if (vm.sortDescending) " ↓" else " ↑"
                } else "") }, onClick = { vm.selectVideoSort(sort); expanded = false })
            }
        }
    }
}

/** Four equally sized choices, with no incomplete last row or screen-height coupling. */
@Composable
private fun GridPresetSetting(label: String, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        GRID_PRESETS.chunked(2).forEach { options ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Row(Modifier.weight(1f).heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (selected == option) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface)
                        .selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) })
                        .padding(horizontal = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text("$option columns", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

}

// ---------------------------------------------------------------- Scan & Sync

@Composable
fun ScanScreen(vm: LibraryViewModel) {
    val session by vm.session.collectAsStateWithLifecycle()
    val pending by vm.pendingDirs.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val active by vm.scanActive.collectAsStateWithLifecycle()
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val thumbsActive by vm.thumbsActive.collectAsStateWithLifecycle()
    val thumbsDone by vm.thumbsDone.collectAsStateWithLifecycle()
    val thumbsPending by vm.thumbsPending.collectAsStateWithLifecycle()
    val thumbsFailed by vm.thumbsFailed.collectAsStateWithLifecycle()

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top=16.dp,bottom=if(floatingPadding(vm))112.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Scan & Sync", style = MaterialTheme.typography.titleLarge) }
        item { AutomaticSyncSummary() }

        vm.notice?.let { message ->
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(message)
                        TextButton(onClick = vm::dismissNotice) { Text("OK") }
                    }
                }
            }
        }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val s = session
                    if (s == null) {
                        Text("No scan has run yet.")
                    } else {
                        val shownStatus = if (s.status == ScanStatus.RUNNING && !active) {
                            "INTERRUPTED"
                        } else {
                            s.status
                        }
                        Text("Status: ${shownStatus.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }}")
                        Text("Mode: ${s.mode.lowercase().replaceFirstChar { it.uppercase() }}")
                        Text("Folders scanned: ${s.dirsScanned}   Waiting: $pending")
                        Text("Videos found this scan: ${s.videosFound}   In library: $total")
                        Text("Errors: ${s.errorCount}")
                        if (active && s.currentDir.isNotEmpty()) {
                            Text(
                                "Last folder: ${s.currentDir}",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        s.finishedAt?.let {
                            Text(
                                "Finished ${DateUtils.getRelativeTimeSpanString(it)}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (active) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startScan(ScanMode.INITIAL) }, enabled = !active) { Text("Full scan") }
                    OutlinedButton(onClick = { vm.startScan(ScanMode.REFRESH) }, enabled = !active) { Text("Refresh") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { vm.startScan(ScanMode.RESUME) }, enabled = !active) { Text("Resume") }
                    OutlinedButton(onClick = { vm.startScan(ScanMode.RETRY) }, enabled = !active) { Text("Retry failed") }
                    if (active || thumbsActive) TextButton(onClick = vm::stopScan) { Text("Stop") }
                }
            }
        }

        item {
            val busy = active || thumbsActive
            val all = thumbsDone + thumbsPending + thumbsFailed
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Thumbnails", style = MaterialTheme.typography.titleMedium)
                    Text("Ready: $thumbsDone   Waiting: $thumbsPending   Failed: $thumbsFailed")
                    if (busy && thumbsPending > 0) {
                        LinearProgressIndicator(
                            progress = { thumbsDone.toFloat() / all.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                    Row(
                        Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = vm::startThumbnails, enabled = !busy && thumbsPending > 0) {
                            Text("Generate")
                        }
                        OutlinedButton(
                            onClick = vm::retryFailedThumbnails,
                            enabled = !busy && thumbsFailed > 0,
                        ) { Text("Retry failed") }
                    }
                    TextButton(onClick = vm::clearThumbnailCache, enabled = !busy) {
                        Text("Clear thumbnail cache")
                    }
                    ThumbnailFailures(vm)
                }
            }
        }

        if (errors.isNotEmpty()) {
            item { Text("Errors", style = MaterialTheme.typography.titleMedium) }
            items(errors, key = { it.id }) { e ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text(
                        e.path.ifEmpty { "(connection)" },
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        e.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    HorizontalDivider(Modifier.padding(top = 6.dp))
                }
            }
        }
    }
}

// Compose lint misses the explicit producer value assignments below.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun AutomaticSyncSummary() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(
        com.deepak.videolibrary.scan.PeriodicSyncWorker.DIAGNOSTICS, android.content.Context.MODE_PRIVATE) }
    val snapshot by produceState(initialValue = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L), prefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        value = prefs.getString("last_status", "Not run yet") to prefs.getLong("last_success", 0L)
        awaitDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val status = when (val raw = snapshot.first.orEmpty()) {
        "RUNNING" -> "Syncing…"
        "COMPLETED" -> "Up to date"
        "INTERRUPTED" -> "Interrupted · will try again"
        else -> when {
            raw.startsWith("SKIPPED:") -> raw.removePrefix("SKIPPED:").trim()
            raw.startsWith("FAILED:") -> "Couldn't sync · will retry"
            else -> "Not run yet"
        }
    }
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Automatic library sync", style = MaterialTheme.typography.titleMedium)
            Text("About every 12 hours when your server is reachable.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Text("Last successful sync: " + if (snapshot.second == 0L) "Never" else
                java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(java.util.Date(snapshot.second)), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- Settings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenPage by rememberSaveable { mutableStateOf(false) }
    if(hiddenPage) { HiddenItemsScreen(vm){hiddenPage=false};return }

    val saved = vm.settings
    var host by remember { mutableStateOf(saved.host) }
    var share by remember { mutableStateOf(saved.share) }
    var root by remember { mutableStateOf(saved.rootPath) }
    var user by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var savePassword by remember { mutableStateOf(saved.savePassword) }
    val context = LocalContext.current
    val gesturePrefs = remember(context) { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    var swipeGesturesEnabled by remember {
        mutableStateOf(gesturePrefs.getBoolean("volume_gesture_enabled", true) &&
            gesturePrefs.getBoolean("brightness_gesture_enabled", true))
    }
    val keyboardScope = rememberCoroutineScope()
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    fun current() = SmbSettings(
        host = host,
        share = share,
        rootPath = root,
        username = user,
        password = password,
        savePassword = savePassword,
    )

    BackHandler(enabled=section!=null) { section=null }
    val scroll=rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(scroll).padding(16.dp).padding(bottom=if(floatingPadding(vm))100.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if(section!=null) TextButton(onClick={section=null}) { Text("← All settings") }
        Text(section ?: "Settings", style=MaterialTheme.typography.headlineMedium)
        if(section==null) {
            listOf(
                "Connection" to "Server, account and saved password",
                "Appearance" to "Theme, navigation and grid layout",
                "Playback" to "Brightness and volume gestures",
                "Library" to "Hidden files and folders",
                "Diagnostics" to "Playback reports and troubleshooting"
            ).forEach { (title,description) ->
                Card(Modifier.fillMaxWidth().clickable(role=Role.Button) { section=title }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title,style=MaterialTheme.typography.titleMedium)
                            Text(description,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›",style=MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        if(section=="Library") {
        OutlinedButton(onClick={hiddenPage=true},modifier=Modifier.fillMaxWidth()){Text("Unhide files and folders")}
        }
        if(section=="Appearance") {
        ThemeSettings()
        Text("Navigation position",style=MaterialTheme.typography.titleMedium)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected=vm.navigationPosition=="SIDEBAR",onClick={vm.updateNavigationPosition("SIDEBAR")});TextButton(onClick={vm.updateNavigationPosition("SIDEBAR")}){Text("Sidebar")}
            RadioButton(selected=vm.navigationPosition=="FLOATING",onClick={vm.updateNavigationPosition("FLOATING")});TextButton(onClick={vm.updateNavigationPosition("FLOATING")}){Text("Floating bottom bar")}
        }
        }
        if(section=="Diagnostics") DiagnosticsSettings()
        var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
        var layoutExpanded by rememberSaveable { mutableStateOf(false) }
        if(section=="Playback") {
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { gesturesExpanded = !gesturesExpanded },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Player gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (gesturesExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                }
                if (gesturesExpanded) {
                    Text("Volume is controlled from the right side; brightness from the left.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Brightness and volume swipe")
                            Text("Left: brightness · Right: volume", style = MaterialTheme.typography.labelSmall)
                        }
                        androidx.compose.material3.Switch(checked = swipeGesturesEnabled, onCheckedChange = {
                            swipeGesturesEnabled = it
                            gesturePrefs.edit().putBoolean("volume_gesture_enabled", it)
                                .putBoolean("brightness_gesture_enabled", it).apply()
                        })
                    }
                }
            }
        }
        }
        if(section=="Appearance") {
        Card(shape = RoundedCornerShape(8.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth().clickable { layoutExpanded = !layoutExpanded },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Library layout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(if (layoutExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                }
                if (layoutExpanded) {
                    Text("Choose the number of columns. Thumbnails keep their 16:9 shape in every layout.", style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Show videos as a list", modifier = Modifier.weight(1f))
                        androidx.compose.material3.Switch(checked = vm.videoListMode, onCheckedChange = { vm.toggleVideoLayout() })
                    }
                    GridPresetSetting("Folders grid", vm.folderGridPreset, vm::updateFolderGridPreset)
                    GridPresetSetting("Videos grid", vm.videoGridPreset, vm::updateVideoGridPreset)
                }
            }
        }
        }
        if(section=="Connection") {

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server address") },
            placeholder = { Text("e.g. 192.168.71.250") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Enter your server address and share name.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = share,
            onValueChange = { share = it },
            label = { Text("Share name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = root,
            onValueChange = { root = it },
            label = { Text("Starting folder (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username (blank for guest)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().bringIntoViewRequester(passwordBringIntoView)
                .onFocusChanged { state ->
                    if (state.isFocused) keyboardScope.launch {
                        kotlinx.coroutines.delay(250)
                        passwordBringIntoView.bringIntoView()
                    }
                },
        )
        Row(
            Modifier.fillMaxWidth().clickable { savePassword = !savePassword },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
            Text(
                "Save password (encrypted with the Android Keystore)",
                modifier = Modifier.padding(top = 12.dp),
            )
        }
        if (!savePassword) {
            Text(
                "Without a saved password, background scans only work while the app has been opened since it last started.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(current()) }) { Text("Save") }
            OutlinedButton(onClick = { vm.testConnection(current()) }) { Text("Test connection") }
        }

        when (val t = vm.testState) {
            TestState.Idle -> Unit
            TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is TestState.Success -> Text(t.message, color = Color.White)
            is TestState.Failure -> Text(t.message, color = MaterialTheme.colorScheme.error)
        }
        }
    }
}

internal fun floatingPadding(vm:LibraryViewModel)=vm.navigationPosition=="FLOATING"
