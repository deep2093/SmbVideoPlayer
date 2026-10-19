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
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepak.videolibrary.data.GroupRow
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import com.deepak.videolibrary.scan.ScanStatus
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.thumbs.ThumbStore
import java.io.File
import java.util.Locale

private val GRID_PRESETS = LibraryLayout.presets

private val TAB_LABELS = listOf("Folders", "Videos", "File Explorer", "Scan", "Settings")
private val TAB_ICONS = listOf(Icons.Filled.Folder, Icons.Filled.PlayArrow, Icons.Filled.Storage, Icons.Filled.Sync, Icons.Filled.Settings)


@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    var playQueue by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    val foldersScrollState = rememberLazyGridState()
    val videosScrollState = rememberLazyGridState()
    val folderVideosScrollState = rememberLazyGridState()
    var returnToThumbnails by remember {mutableStateOf(false)}
    LaunchedEffect(vm.explorerNavigationRequest) {vm.explorerNavigationRequest?.let {path->
        vm.explorerQuery="";vm.browse(path.substringBeforeLast('/',""),focus=path);tab=2;returnToThumbnails=true;vm.consumeExplorerNavigation()
    }}
    BackHandler(enabled = playing != null || folderDetails || tab != 0) {
        when {
            playing != null -> playing = null
            tab==2 && returnToThumbnails -> {tab=3;returnToThumbnails=false}
            folderDetails -> { folderDetails = false; vm.groupFilter.value = null; vm.matchFolderFilter.value=null; tab = 0 }
            else -> { tab = 0; vm.groupFilter.value = null; vm.matchFolderFilter.value=null }
        }
    }
    if (playing != null) {
        if(playQueue.any {it.name.endsWith(".wmv",true) || it.name.endsWith(".asf",true)}) VlcPlayerScreen(video=playing!!,settings=vm.settings,vm=vm,playlist=playQueue,onClose={playing=null;playQueue=emptyList()})
        else PlayerScreen(video = playing!!, settings = vm.settings, vm = vm, playlist = playQueue, onClose = { playing = null; playQueue = emptyList() })
        return
    }
    var railExpanded by remember { mutableStateOf(true) }
    val railWidth by animateDpAsState(if(railExpanded)148.dp else 72.dp,label="sidebar_width")
    val hazeState=remember {HazeState()}
    val floating=vm.navigationPosition=="FLOATING"
    val collapseOnScroll = remember { object: NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if(kotlin.math.abs(available.y)>2f) railExpanded=false
            return Offset.Zero
        }
    } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletLayout = maxWidth >= 700.dp
        Scaffold(
            bottomBar = {
                if (!tabletLayout && !floating) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TAB_LABELS.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant),
                            onClick = {
                                tab = index
                                folderDetails = false
                                vm.groupFilter.value = null; vm.matchFolderFilter.value=null
                            },
                            icon = { Icon(TAB_ICONS[index], contentDescription = label) }, label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
            Row(Modifier.padding(padding).fillMaxSize().then(if(floating)Modifier.haze(hazeState)else Modifier)) {
                if (tabletLayout && !floating) {
                    androidx.compose.material3.NavigationRail(modifier=Modifier.width(railWidth),containerColor = Color.Black) {
                        IconButton(onClick={railExpanded=!railExpanded}){Icon(Icons.Filled.Menu,contentDescription="Expand or collapse sidebar",tint=Color.White)}
                        TAB_LABELS.forEachIndexed { index, label ->
                            androidx.compose.material3.NavigationRailItem(
                                selected = tab == index,
                                colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = Color.White.copy(alpha=0.12f),
                                    unselectedIconColor = Color.White, unselectedTextColor = Color.White),
                                onClick = {
                                    railExpanded = true
                                    tab = index
                                    folderDetails = false
                                    vm.groupFilter.value = null; vm.matchFolderFilter.value=null
                                },
                                icon = { Icon(TAB_ICONS[index], contentDescription = label) },
                                label = if(railExpanded) ({ Text(label) }) else null,
                                alwaysShowLabel = true,
                            )
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight().nestedScroll(collapseOnScroll)) {
                    if(tab==2 && returnToThumbnails)TextButton(onClick={tab=3;returnToThumbnails=false}){Text("← Thumbnail failures")}
                    FileClipboardBar(vm)
                    vm.notice?.let { message -> Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Text(message,Modifier.weight(1f).padding(8.dp),color=Color.White)
                        TextButton(onClick=vm::dismissNotice){Text("Dismiss")}
                    } }
                    Crossfade(targetState=tab,modifier=Modifier.weight(1f),animationSpec=tween(140),label="section_transition") { currentTab -> when (currentTab) {
                        0 -> FoldersHub(vm, gridState = foldersScrollState, onOpenGroup = { folderDetails = true; tab = 1 }, onPlay = { video, queue -> playing = video; playQueue = queue }, onOpenSettings = { tab = 4 })
                        1 -> VideosScreen(vm, gridState = if (folderDetails) folderVideosScrollState else videosScrollState, onPlay = { video, queue -> playing = video; playQueue = queue }, onBackToFolders = if (folderDetails) ({
                            folderDetails = false; vm.groupFilter.value = null; vm.matchFolderFilter.value=null; tab = 0
                        }) else null)
                        2 -> ExplorerScreen(vm, onPlay = { video, queue -> playing = video; playQueue = queue })
                        3 -> ScanScreen(vm)
                        else -> SettingsScreen(vm)
                    } }
                }
            }
            if(floating)FloatingLibraryNavigation(tab,TAB_LABELS,TAB_ICONS,hazeState,Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(bottom=12.dp)) {index->tab=index;folderDetails=false;vm.groupFilter.value=null;vm.matchFolderFilter.value=null;railExpanded=true}
            }
        }
    }
}

@Composable
private fun FoldersHub(vm: LibraryViewModel, gridState: LazyGridState, onOpenGroup:()->Unit,
    onPlay:(VideoEntity,List<VideoEntity>)->Unit, onOpenSettings:()->Unit) {
    val pager=rememberPagerState(initialPage=vm.foldersTab,pageCount={3})
    val scope=rememberCoroutineScope()
    LaunchedEffect(pager.settledPage){vm.foldersTab=pager.settledPage}
    Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TabRow(selectedTabIndex=pager.currentPage,containerColor=Color.Black,contentColor=Color.White) {
            listOf("Custom folders","Virtual folders","Original folders").forEachIndexed { index,title ->
                androidx.compose.material3.Tab(selected=pager.currentPage==index,onClick={scope.launch{pager.animateScrollToPage(index)}},text={Text(title)})
            }
        }
        HorizontalPager(state=pager,modifier=Modifier.weight(1f)) { page ->
            when(page){0->MatchingFoldersScreen(vm,onOpenGroup,onPlay);1->FoldersScreen(vm,gridState,onOpenGroup,onPlay,onOpenSettings);else->OriginalFoldersScreen(vm,onPlay)}
        }
    }
}

// ---------------------------------------------------------------- Videos

@Composable
fun VideosScreen(vm: LibraryViewModel, gridState: LazyGridState, onPlay: (VideoEntity, List<VideoEntity>) -> Unit, onBackToFolders: (() -> Unit)? = null) {
    val videos=vm.videoPages.collectAsLazyPagingItems()
    val query by vm.query.collectAsStateWithLifecycle()
    val group by vm.groupFilter.collectAsStateWithLifecycle()
    val matchFolder by vm.matchFolderFilter.collectAsStateWithLifecycle()
    val selectedGroup=group ?: matchFolder?.name
    var searchExpanded by rememberSaveable { mutableStateOf(query.isNotBlank()) }
    val total by vm.videoCount.collectAsStateWithLifecycle()
    val count by vm.videoResultsCount.collectAsStateWithLifecycle()
    val continueWatching by vm.continuing.collectAsStateWithLifecycle()
    var addDialog by remember { mutableStateOf(false) }
    var videoSearch by remember { mutableStateOf("") }
    LaunchedEffect(query,group,matchFolder?.id){gridState.scrollToItem(0)}
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (selectedGroup != null) IconButton(onClick = {
                if (onBackToFolders != null) onBackToFolders() else vm.groupFilter.value = null; vm.matchFolderFilter.value=null
            }) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to folders") }
            Text(selectedGroup ?: "Videos", style = MaterialTheme.typography.titleLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            LibraryDisplayMenu(vm)
            IconButton(onClick = { searchExpanded = !searchExpanded; if (!searchExpanded) vm.query.value = "" }) {
                Icon(if (searchExpanded) Icons.Filled.Close else Icons.Filled.Search,
                    contentDescription = if (searchExpanded) "Close search" else "Search videos")
            }
        }
        if (searchExpanded) {
            OutlinedTextField(value = query, onValueChange = { vm.query.value = it },
                label = { Text("Search videos by filename") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp))
        }
        if (selectedGroup != null && matchFolder==null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Virtual folder", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { videoSearch = ""; vm.findVideosToAdd(selectedGroup, ""); addDialog = true }) { Text("+ Add") }
            }
        }
        val columns = LibraryLayout.columns(vm.videoGridPreset)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.videoListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = if(floatingPadding(vm))112.dp else 24.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (group == null && matchFolder==null && query.isEmpty() && continueWatching.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "continue") {
                        Column {
                            Text("Continue watching", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(vertical = 12.dp))
                            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(continueWatching, key = { it.id }) { video ->
                                    VideoCard(video, onClick = { onPlay(video, listOf(video)) }, vm = vm,
                                        modifier = Modifier.width(210.dp), onRemoveFromContinue = {
                                            vm.hideContinue(video.relativePath)
                                        })
                                }
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("$count videos · ${vm.videoSort.label}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.playLibrary("videos",onPlay=onPlay) }, enabled = count>0) { Text("Play all") }
                    }
                }
                if (count==0) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (total == 0) "No videos yet. Add your server in Settings, then run a scan."
                        else "No videos match your search.", modifier = Modifier.padding(vertical = 24.dp))
                }
                items(count=videos.itemCount,key=videos.itemKey { it.relativePath },contentType={"video"}) { index ->
                    videos[index]?.let { video -> VideoCard(video,onClick={
                        if(group!=null || matchFolder!=null || query.isNotBlank()) vm.playLibrary("videos",video,onPlay) else onPlay(video,listOf(video))
                    },vm=vm,listMode=vm.videoListMode) }
                }
            }
        }
    }

    if (addDialog && selectedGroup != null) {
        AlertDialog(onDismissRequest = { addDialog = false }, title = { Text("Add videos to $group") },
            text = { Column {
                OutlinedTextField(value = videoSearch, onValueChange = { videoSearch = it; vm.findVideosToAdd(selectedGroup, it) }, label = { Text("Search filenames") })
                LazyColumn(Modifier.heightIn(max = 350.dp)) {
                    items(vm.assignCandidates, key = { it.id }) { video ->
                        TextButton(onClick = { vm.assignVideo(video.id, selectedGroup); vm.findVideosToAdd(selectedGroup, videoSearch) }) {
                            Text(video.name, maxLines = 2)
                        }
                    }
                }
            } },
            confirmButton = { TextButton(onClick = { addDialog = false }) { Text("Done") } })
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun VideoCard(video: VideoEntity, onClick: () -> Unit, vm: LibraryViewModel, modifier: Modifier = Modifier,
    onRemoveFromContinue: (() -> Unit)? = null, listMode: Boolean = false, selectionScope:String="videos") {
    var menuOpen by remember(video.relativePath) { mutableStateOf(false) }
    val context=LocalContext.current
    val thumb:File?=if(video.thumbStatus=="DONE") ThumbStore.thumbFile(context,video.id) else null
    val namespace by vm.namespace.collectAsStateWithLifecycle()
    val progressFlow=remember(video.relativePath,namespace){vm.progressFlow(video.relativePath)}
    val progress by progressFlow.collectAsStateWithLifecycle(initialValue=null)
    val watchedMs=progress?.positionMs ?: 0L
    val totalMs=progress?.durationMs ?: 0L
    val artwork: @Composable () -> Unit = {
        Box(if (listMode) Modifier.width(120.dp).height(76.dp)
            else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            FileImage(thumb, Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp)))
            if (totalMs > 0L) Text(DateUtils.formatElapsedTime(totalMs / 1_000),
                color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(4.dp)
                    .background(androidx.compose.ui.graphics.Color(0xBB000000), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            if (totalMs > 0L && watchedMs > 0L) Box(Modifier.align(androidx.compose.ui.Alignment.BottomStart)
                .fillMaxWidth((watchedMs.toFloat() / totalMs).coerceIn(0f, 1f)).height(3.dp)
                .background(MaterialTheme.colorScheme.primary))
        }
    }
    val caption: @Composable () -> Unit = {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if(vm.selectedPaths.isNotEmpty())Checkbox(checked=video.relativePath in vm.selectedPaths,onCheckedChange={vm.toggleSelected(video.relativePath,selectionScope)})
            Column(Modifier.weight(1f).padding(start = if (listMode) 12.dp else 2.dp)) {
                Text(video.name, style = MaterialTheme.typography.bodyMedium, minLines = 2, maxLines = 2,
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
    var sourcePicker by remember { mutableStateOf(false) }
    if(sourcePicker) VirtualSourcesDialog(vm,onDismiss={sourcePicker=false})
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
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            Text("Automatic virtual folders",Modifier.weight(1f))
            Switch(checked=vm.automaticVirtualFolders,onCheckedChange=vm::updateAutomaticVirtualFolders,modifier=Modifier.semantics {contentDescription="Enable automatic virtual folders"})
        }
        TextButton(onClick={sourcePicker=true}) {Text("Choose source folders (${vm.virtualSources.size})")}
        if(!vm.automaticVirtualFolders) Text("Automatic folders are off. Your saved folders and thumbnails are kept.",style=MaterialTheme.typography.bodySmall)
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
                    Text(if (folderQuery.isNotBlank()) "No matching folders." else "Select source folders and turn on automatic virtual folders to show filename groups.",
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

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom=if(floatingPadding(vm))100.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Personalize your library, playback controls and network connection.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick={hiddenPage=true},modifier=Modifier.fillMaxWidth()){Text("Unhide files and folders")}
        Text("Navigation position",style=MaterialTheme.typography.titleMedium)
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected=vm.navigationPosition=="SIDEBAR",onClick={vm.updateNavigationPosition("SIDEBAR")});TextButton(onClick={vm.updateNavigationPosition("SIDEBAR")}){Text("Sidebar")}
            RadioButton(selected=vm.navigationPosition=="FLOATING",onClick={vm.updateNavigationPosition("FLOATING")});TextButton(onClick={vm.updateNavigationPosition("FLOATING")}){Text("Floating bottom bar")}
        }
        DiagnosticsSettings()
        var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
        var layoutExpanded by rememberSaveable { mutableStateOf(false) }
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
        Spacer(Modifier.height(8.dp))
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
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("SMB connection", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Server address") },
            placeholder = { Text("e.g. 192.168.71.250") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Text("Enter the SMB server IP or hostname and share manually. Automatic discovery was removed because it was unreliable.",
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

internal fun floatingPadding(vm:LibraryViewModel)=vm.navigationPosition=="FLOATING"
