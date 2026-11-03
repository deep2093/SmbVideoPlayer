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
import androidx.compose.material.icons.filled.PhoneAndroid
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
private val TAB_LABELS = listOf("Phone", "Folders", "Videos", "Files", "Settings")
private val TAB_ICONS = listOf(Icons.Filled.PhoneAndroid, Icons.Filled.Folder, Icons.Filled.PlayArrow, Icons.Filled.Storage, Icons.Filled.Settings)
private const val PHONE_TAB = 0
private const val FOLDERS_TAB = 1 // The home section: the app opens here and Back returns here.
private const val VIDEOS_TAB = 2
private const val FILES_TAB = 3
private const val SETTINGS_TAB = 4
private const val NOTICE_MS = 8_000L
private const val SECTION_SLIDE_MS = 360L

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AppRoot(vm: LibraryViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(FOLDERS_TAB) }
    var folderDetails by rememberSaveable { mutableStateOf(false) }
    var playing by remember { mutableStateOf<VideoEntity?>(null) }
    var playQueue by remember { mutableStateOf<List<VideoEntity>>(emptyList()) }
    val foldersScrollState = rememberLazyGridState()
    val phoneFoldersScrollState = rememberLazyGridState()
    val phoneVideosScrollState = rememberLazyGridState()
    val videosScrollState = rememberLazyGridState()
    val folderVideosScrollState = rememberLazyGridState()
    val customFoldersScrollState = rememberLazyGridState()
    val appScope = rememberCoroutineScope()
    // Opening a folder always starts at its top; coming back to a list keeps its position.
    val openFolder: () -> Unit = {
        appScope.launch { folderVideosScrollState.scrollToItem(0) }
        folderDetails = true
        tab = VIDEOS_TAB
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
            tab = FILES_TAB
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
            if (tab != VIDEOS_TAB) {
                folderDetails = false
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
            }
        }
    }
    var closing by remember { mutableStateOf(false) }
    // Closing fades the player out first; the real teardown happens when that animation ends.
    val closePlayer: () -> Unit = { if (!closing) closing = true }
    BackHandler(enabled = playing != null || folderDetails || tab != FOLDERS_TAB) {
        when {
            playing != null -> closePlayer()
            tab == FILES_TAB && returnToThumbnails -> {
                tab = SETTINGS_TAB
                returnToThumbnails = false
            }
            folderDetails -> leaveFolderTo(FOLDERS_TAB)
            else -> {
                tab = FOLDERS_TAB
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
            // The player is always dark (its controls sit over video), whatever the app theme: light themes would
            // otherwise give its menus and buttons dark text on dark panels.
            PlayerDarkColors {
                androidx.compose.runtime.key(playing!!.relativePath) {
                    if(playQueue.any { it.name.endsWith(".wmv",true) || it.name.endsWith(".asf",true) }) {
                        VlcPlayerScreen(video=playing!!,settings=vm.settings,vm=vm,playlist=playQueue,onQueueEnded=advanceQueue,onClose=closePlayer)
                    } else {
                        PlayerScreen(video=playing!!,settings=vm.settings,vm=vm,playlist=playQueue,onQueueEnded=advanceQueue,onClose=closePlayer)
                    }
                }
            }
        }
        return
    }
    // Tapping the section you are already in takes its page back to the top. Inside an opened folder on Videos,
    // a tap when already at the top goes back to all videos (as tapping Videos always did).
    val onTabTapped: (Int) -> Unit = { index ->
        val reselect = index == tab && !vm.downloadsOpen
        if (!reselect && folderDetails && tab == VIDEOS_TAB) {
            leaveFolderTo(index)
        } else if (!reselect) {
            vm.downloadsOpen = false
            tab = index
            folderDetails = false
            vm.groupFilter.value = null
            vm.matchFolderFilter.value = null
        } else if (index == VIDEOS_TAB) {
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
        if (folderDetails && tab == VIDEOS_TAB) {
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
            tab == FOLDERS_TAB && vm.foldersTab < vm.folderSections.lastIndex -> Unit
            tab < TAB_LABELS.lastIndex -> selectSection(tab + 1)
            else -> Unit
        }
    }
    val previousSection: () -> Unit = {
        when {
            tab == PHONE_TAB -> Unit
            tab == FOLDERS_TAB -> if (vm.foldersTab == 0) selectSection(PHONE_TAB) else Unit
            tab == VIDEOS_TAB && folderDetails -> selectSection(FOLDERS_TAB) // Back to the folder list it was opened from.
            tab == VIDEOS_TAB -> {
                vm.foldersTab = vm.folderSections.lastIndex
                selectSection(FOLDERS_TAB)
            }
            else -> selectSection(tab - 1)
        }
    }
    val glassTheme = LocalLibraryTheme.current.glass
    val hazeState = androidx.compose.runtime.remember { dev.chrisbanes.haze.HazeState() }
    // Lens glass (Android 13+, Blur at 0): the dock bends the recorded content behind it.
    val lensGlass = rememberLensGlass()
    val lensOn = LocalGlassSettings.current.lensOn
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletLayout = maxWidth >= 700.dp
        val useDock = floating || (glassTheme && !tabletLayout)
        Scaffold(
            // The player hides the system bars; using their size even while hidden keeps the library from
            // jumping when it comes back after a video (most visible on tablets in landscape).
            contentWindowInsets = androidx.compose.foundation.layout.WindowInsets.systemBarsIgnoringVisibility,
            bottomBar = {
                if (!tabletLayout && !useDock) {
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
                        .then(if (glassTheme && useDock) Modifier.hazeSource(hazeState) else Modifier)
                        .then(if (glassTheme && useDock && lensGlass != null) lensGlass.source(lensOn) else Modifier),
                ) {
                    if (tabletLayout && !useDock) {
                        LibrarySidebar(selected = tab, labels = TAB_LABELS, icons = TAB_ICONS, modifier = Modifier.width(SIDEBAR_WIDTH).fillMaxHeight(), onSelect = onTabTapped)
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.animateContentSize(tween(260, easing = FastOutSlowInEasing))) {
                        if (tab == FILES_TAB && returnToThumbnails) {
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
                                PHONE_TAB -> PhoneVideosScreen(vm, foldersState = phoneFoldersScrollState, videosState = phoneVideosScrollState, onPlay = { video, queue ->
                                    vm.resetPlaybackQueue()
                                    playing = video
                                    playQueue = queue
                                })
                                FOLDERS_TAB -> FoldersHub(vm, gridState = foldersScrollState, customGridState = customFoldersScrollState, onOpenGroup = openFolder, onPlay = {
                                    video,
                                    queue,
                                    ->
                                    playing =
                                        video
                                    playQueue = queue
                                }, onOpenSettings = { tab = SETTINGS_TAB }, showBrand = !tabletLayout || useDock)
                                VIDEOS_TAB -> VideosScreen(
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
                                        ({ leaveFolderTo(FOLDERS_TAB) })
                                    } else {
                                        null
                                    },
                                )
                                FILES_TAB -> ExplorerScreen(vm, onPlay = { video, queue ->
                                    vm.resetPlaybackQueue()
                                    playing = video
                                    playQueue = queue
                                })
                                else -> SettingsScreen(vm, onPreviewVideo = { video ->
                                    // Playback from metadata review uses only this file, never a folder queue.
                                    // The Settings tab, open Metadata page and review-scroll snapshot survive
                                    // until the built-in player closes through its normal Back handling.
                                    vm.resetPlaybackQueue()
                                    playQueue = listOf(video)
                                    closing = false
                                    playing = video
                                })
                            }
                        }
                        }
                    }
                }
                // The notes show once per release, before the review pop-up; Settings > What's new opens them again.
                val appContext = androidx.compose.ui.platform.LocalContext.current
                var whatsNewDue by remember { mutableStateOf(!WhatsNew.seen(appContext)) }
                val whatsNewShowing = whatsNewDue || vm.ui.whatsNewOpen
                if (whatsNewShowing && playing == null) {
                    WhatsNewDialog(onClose = {
                        WhatsNew.markSeen(appContext)
                        whatsNewDue = false
                        vm.ui.whatsNewOpen = false
                    })
                }
                LookupConditionPrompt(canShow = playing == null && !whatsNewShowing)
                MatchReviewPrompt(vm, canShow = playing == null && !whatsNewShowing, onPreviewVideo = { video ->
                    vm.resetPlaybackQueue()
                    playQueue = listOf(video)
                    closing = false
                    playing = video
                })
                if (useDock) androidx.compose.runtime.CompositionLocalProvider(LocalLensGlass provides (if (glassTheme) lensGlass else null)) {
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

@androidx.compose.runtime.Composable
internal fun floatingPadding(vm: LibraryViewModel): Boolean = vm.navigationPosition == "FLOATING" ||
    (LocalLibraryTheme.current.glass && androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 700)
