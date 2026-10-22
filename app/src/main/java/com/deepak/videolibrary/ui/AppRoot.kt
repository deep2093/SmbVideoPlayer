package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.launch

/** The tablet sidebar is always expanded, with labels, at a fixed width. */
private val SIDEBAR_WIDTH = 204.dp

// Downloads open from the download button at the top right of every page; scan and thumbnails live in Settings.
private val TAB_LABELS = listOf("Folders", "Videos", "Files", "Settings")
private val TAB_ICONS = listOf(Icons.Filled.Folder, Icons.Filled.PlayArrow, Icons.Filled.Storage, Icons.Filled.Settings)
private const val SETTINGS_TAB = 3
private const val NOTICE_MS = 8_000L
private const val SECTION_SLIDE_MS = 360L

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    var playQueue by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    val foldersScrollState = rememberLazyGridState()
    val videosScrollState = rememberLazyGridState()
    val folderVideosScrollState = rememberLazyGridState()
    val customFoldersScrollState = rememberLazyGridState()
    val appScope = rememberCoroutineScope()
    // Opening a folder always starts at its top; coming back to a list keeps its position.
    val openFolder: () -> Unit = {
        appScope.launch { folderVideosScrollState.scrollToItem(0) }
        folderDetails = true
        tab = 1
    }
    // Status notices are informational: they clear themselves after a while (a newer notice restarts the timer).
    LaunchedEffect(vm.notice) {
        if (vm.notice != null) {
            kotlinx.coroutines.delay(NOTICE_MS)
            vm.dismissNotice()
        }
    }
    var returnToThumbnails by remember { mutableStateOf(false) }
    LaunchedEffect(vm.explorerNavigationRequest) {
        vm.explorerNavigationRequest?.let { path ->
            vm.explorerQuery = ""
            vm.browse(path.substringBeforeLast('/', ""), focus = path)
            tab = 2
            returnToThumbnails = true
            vm.consumeExplorerNavigation()
        }
    }
    // Leaving an opened folder: switch section first and clear the folder only after the slide, so the page
    // sliding out keeps showing that folder instead of flashing the full Videos list.
    val leaveFolderTo: (Int) -> Unit = { target ->
        vm.downloadsOpen = false
        tab = target
        appScope.launch {
            kotlinx.coroutines.delay(SECTION_SLIDE_MS)
            if (tab != 1) {
                folderDetails = false
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
            }
        }
    }
    var closing by remember { mutableStateOf(false) }
    // Closing fades the player out first; the real teardown happens when that animation ends.
    val closePlayer: () -> Unit = { if (!closing) closing = true }
    BackHandler(enabled = playing != null || folderDetails || tab != 0) {
        when {
            playing != null -> closePlayer()
            tab == 2 && returnToThumbnails -> {
                tab = SETTINGS_TAB
                returnToThumbnails = false
            }
            folderDetails -> leaveFolderTo(0)
            else -> {
                tab = 0
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
            }
        }
    }
    val advanceQueue: () -> Unit = { vm.nextPlaybackBatch { next, queue -> playing = next; playQueue = queue } }
    if (playing != null) {
        // The player fades and scales in when it opens (not on every queue change).
        val enter = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(closing) {
            if (closing) {
                enter.animateTo(0f, tween(240, easing = androidx.compose.animation.core.FastOutLinearInEasing))
                playing = null
                playQueue = emptyList()
                vm.resetPlaybackQueue()
                closing = false
            } else {
                enter.animateTo(1f, tween(300, easing = FastOutSlowInEasing))
            }
        }
        val entering by androidx.compose.runtime.remember { androidx.compose.runtime.derivedStateOf { enter.value < 1f } }
        Box(
            Modifier.fillMaxSize().then(
                if (entering) {
                    Modifier.graphicsLayer {
                        alpha = enter.value
                        val scale = 0.94f + 0.06f * enter.value
                        scaleX = scale
                        scaleY = scale
                    }
                } else {
                    Modifier
                },
            ),
        ) {
            androidx.compose.runtime.key(playing!!.relativePath) {
                if(playQueue.any { it.name.endsWith(".wmv",true) || it.name.endsWith(".asf",true) }) {
                    VlcPlayerScreen(video=playing!!,settings=vm.settings,vm=vm,playlist=playQueue,onQueueEnded=advanceQueue,onClose=closePlayer)
                } else {
                    PlayerScreen(video=playing!!,settings=vm.settings,vm=vm,playlist=playQueue,onQueueEnded=advanceQueue,onClose=closePlayer)
                }
            }
        }
        return
    }
    // Tapping the section you are already in takes its page back to the top. Inside an opened folder on Videos,
    // a tap when already at the top goes back to all videos (as tapping Videos always did).
    val onTabTapped: (Int) -> Unit = { index ->
        val reselect = index == tab && !vm.downloadsOpen
        if (!reselect && folderDetails && tab == 1) {
            leaveFolderTo(index)
        } else if (!reselect) {
            vm.downloadsOpen = false
            tab = index
            folderDetails = false
            vm.groupFilter.value = null
            vm.matchFolderFilter.value = null
        } else if (index == 1) {
            val state = if (folderDetails) folderVideosScrollState else videosScrollState
            val atTop = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
            if (folderDetails && atTop) {
                folderDetails = false
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
            } else {
                appScope.launch { state.glideToTop() }
            }
        } else {
            vm.ui.scrollTopRequest++
        }
    }
    val floating = vm.navigationPosition == "FLOATING"
    // Swiping between sections follows the order Custom, Virtual, Original folders, Videos, Files, Settings. Inside Folders the tab pager moves between its three pages itself.
    val selectSection: (Int) -> Unit = { index ->
        if (folderDetails && tab == 1) {
            leaveFolderTo(index)
        } else {
            vm.downloadsOpen = false
            tab = index
            folderDetails = false
            vm.groupFilter.value = null
            vm.matchFolderFilter.value = null
        }
    }
    val nextSection: () -> Unit = {
        when {
            tab == 0 && vm.foldersTab < vm.folderSections.lastIndex -> Unit
            tab < TAB_LABELS.lastIndex -> selectSection(tab + 1)
            else -> Unit
        }
    }
    val previousSection: () -> Unit = {
        when {
            tab == 0 -> Unit
            tab == 1 && folderDetails -> selectSection(0) // Back to the folder list it was opened from.
            tab == 1 -> {
                vm.foldersTab = vm.folderSections.lastIndex
                selectSection(0)
            }
            else -> selectSection(tab - 1)
        }
    }
    val glassTheme = LocalLibraryTheme.current.glass
    val hazeState = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletLayout = maxWidth >= 700.dp
        Scaffold(
            // The player hides the system bars; using their size even while hidden keeps the library from
            // jumping when it comes back after a video (most visible on tablets in landscape).
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBarsIgnoringVisibility,
            bottomBar = {
                if (!tabletLayout && !floating) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                        TAB_LABELS.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                onClick = { onTabTapped(index) },
                                icon = { Icon(TAB_ICONS[index], contentDescription = label) },
                                label = { Text(label, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                // Glass themes: the content is the source the floating bar blurs.
                Row(
                    Modifier.padding(padding).fillMaxSize()
                        .then(if (glassTheme && floating) Modifier.hazeSource(hazeState) else Modifier),
                ) {
                    if (tabletLayout && !floating) {
                        LibrarySidebar(selected = tab, labels = TAB_LABELS, icons = TAB_ICONS, modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(), onSelect = onTabTapped)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
                        if (tab == 2 && returnToThumbnails) {
                            TextButton(onClick = {
                                tab = SETTINGS_TAB
                                returnToThumbnails = false
                            }) { Text(stringResource(R.string.ui_thumbnail_failures_737d640)) }
                        }
                        ArtworkProgressStrip(vm)
                        VpnStatusBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                        FileClipboardBar(vm)
                        vm.notice?.let { message ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f).padding(8.dp), color = MaterialTheme.colorScheme.onSurface)
                                TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.ui_dismiss_70afe9e)) }
                            }
                        }
                        }
                        val downloadsButton: @Composable () -> Unit = { DownloadsButton(vm) }
                        androidx.compose.runtime.CompositionLocalProvider(LocalTopBarEnd provides downloadsButton) {
                        if (vm.downloadsOpen) {
                            DownloadsPage(vm, onClose = { vm.downloadsOpen = false })
                        } else
                        // Sections and folder pages slide in the direction of travel and fade, like VLC.
                        AnimatedContent(
                            targetState = tab,
                            modifier = Modifier.weight(1f).sectionSwipe(onNext = nextSection, onPrevious = previousSection),
                            transitionSpec = {
                                val forward = targetState > initialState
                                val enter = slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { width ->
                                    if (forward) width / 5 else -width / 5
                                } + fadeIn(tween(260, delayMillis = 40))
                                val exit = slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { width ->
                                    if (forward) -width / 5 else width / 5
                                } + fadeOut(tween(160))
                                enter togetherWith exit
                            },
                            label = "section_transition",
                        ) { currentTab ->
                            when (currentTab) {
                                0 -> FoldersHub(vm, gridState = foldersScrollState, customGridState = customFoldersScrollState, onOpenGroup = openFolder, onPlay = {
                                    video,
                                    queue,
                                    ->
                                    playing =
                                        video
                                    playQueue = queue
                                }, onOpenSettings = { tab = SETTINGS_TAB }, showBrand = !tabletLayout || floating)
                                1 -> VideosScreen(
                                    vm,
                                    gridState = if (folderDetails) folderVideosScrollState else videosScrollState,
                                    onPlay = {
                                        video,
                                        queue,
                                        ->
                                        playing =
                                            video
                                        playQueue = queue
                                    },
                                    onBackToFolders = if (folderDetails) {
                                        ({ leaveFolderTo(0) })
                                    } else {
                                        null
                                    },
                                )
                                2 -> ExplorerScreen(vm, onPlay = { video, queue ->
                                    vm.resetPlaybackQueue()
                                    playing = video
                                    playQueue = queue
                                })
                                else -> SettingsScreen(vm)
                            }
                        }
                        }
                    }
                }
                if (floating) {
                    FloatingLibraryNavigation(
                        tab,
                        TAB_LABELS,
                        TAB_ICONS,
                        Modifier.align(androidx.compose.ui.Alignment.BottomCenter).windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.navigationBarsIgnoringVisibility).padding(bottom = 12.dp),
                        hazeState = if (glassTheme) hazeState else null,
                        onSelect = onTabTapped,
                    )
                }
            }
        }
    }
}

internal fun floatingPadding(vm: LibraryViewModel) = vm.navigationPosition == "FLOATING"
