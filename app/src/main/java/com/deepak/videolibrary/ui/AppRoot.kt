package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.deepak.videolibrary.data.VideoEntity
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import kotlinx.coroutines.launch

/** The tablet sidebar is always expanded, with labels, at a fixed width. */
private val SIDEBAR_WIDTH = 148.dp

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
    val customFoldersScrollState = rememberLazyGridState()
    val appScope = rememberCoroutineScope()
    // Opening a folder always starts at its top; coming back to a list keeps its position.
    val openFolder: () -> Unit = {
        appScope.launch { folderVideosScrollState.scrollToItem(0) }
        folderDetails = true
        tab = 1
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
    BackHandler(enabled = playing != null || folderDetails || tab != 0) {
        when {
            playing != null -> playing = null
            tab == 2 && returnToThumbnails -> {
                tab = 3
                returnToThumbnails = false
            }
            folderDetails -> {
                folderDetails = false
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
                tab = 0
            }
            else -> {
                tab = 0
                vm.groupFilter.value = null
                vm.matchFolderFilter.value = null
            }
        }
    }
    val advanceQueue: () -> Unit = { vm.nextPlaybackBatch { next, queue -> playing = next; playQueue = queue } }
    if (playing != null) {
        androidx.compose.runtime.key(playing!!.relativePath) {
        if (playQueue.any { it.name.endsWith(".wmv", true) || it.name.endsWith(".asf", true) }) {
            VlcPlayerScreen(video = playing!!, settings = vm.settings, vm = vm, playlist = playQueue, onQueueEnded = advanceQueue, onClose = {
                playing =
                    null
                playQueue = emptyList()
            })
        } else {
            PlayerScreen(video = playing!!, settings = vm.settings, vm = vm, playlist = playQueue, onQueueEnded = advanceQueue, onClose = {
                playing = null
                playQueue =
                    emptyList()
            })
        }
        }
        return
    }
    val hazeState = remember { HazeState() }
    val floating = vm.navigationPosition == "FLOATING"
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val tabletLayout = maxWidth >= 700.dp
        Scaffold(
            bottomBar = {
                if (!tabletLayout && !floating) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        TAB_LABELS.forEachIndexed { index, label ->
                            NavigationBarItem(
                                selected = tab == index,
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                onClick = {
                                    tab = index
                                    folderDetails = false
                                    vm.groupFilter.value = null
                                    vm.matchFolderFilter.value = null
                                },
                                icon = { Icon(TAB_ICONS[index], contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.padding(padding).fillMaxSize().then(if (floating)Modifier.haze(hazeState)else Modifier)) {
                    if (tabletLayout && !floating) {
                        androidx.compose.material3.NavigationRail(
                            modifier = Modifier.width(SIDEBAR_WIDTH),
                            containerColor = MaterialTheme.colorScheme.surface,
                        ) {
                            Spacer(Modifier.height(12.dp))
                            TAB_LABELS.forEachIndexed { index, label ->
                                androidx.compose.material3.NavigationRailItem(
                                    selected = tab == index,
                                    colors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.primary,
                                        selectedTextColor = MaterialTheme.colorScheme.primary,
                                        indicatorColor = Color.White.copy(alpha = 0.12f),
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    onClick = {
                                        tab = index
                                        folderDetails = false
                                        vm.groupFilter.value = null
                                        vm.matchFolderFilter.value = null
                                    },
                                    icon = { Icon(TAB_ICONS[index], contentDescription = label) },
                                    label = { Text(label) },
                                    alwaysShowLabel = true,
                                )
                            }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        if (tab == 2 && returnToThumbnails) {
                            TextButton(onClick = {
                                tab = 3
                                returnToThumbnails = false
                            }) { Text(stringResource(R.string.ui_thumbnail_failures_737d640)) }
                        }
                        FileClipboardBar(vm)
                        vm.notice?.takeIf { tab != 3 }?.let { message ->
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(message, Modifier.weight(1f).padding(8.dp), color = MaterialTheme.colorScheme.onSurface)
                                TextButton(onClick = vm::dismissNotice) { Text(stringResource(R.string.ui_dismiss_70afe9e)) }
                            }
                        }
                        Crossfade(
                            targetState = tab,
                            modifier = Modifier.weight(1f),
                            animationSpec = tween(140),
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
                                }, onOpenSettings = { tab = 4 })
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
                                        (
                                            {
                                                folderDetails = false
                                                vm.groupFilter.value = null
                                                vm.matchFolderFilter.value = null
                                                tab = 0
                                            }
                                        )
                                    } else {
                                        null
                                    },
                                )
                                2 -> ExplorerScreen(vm, onPlay = { video, queue ->
                                    playing = video
                                    playQueue = queue
                                })
                                3 -> ScanScreen(vm)
                                else -> SettingsScreen(vm)
                            }
                        }
                    }
                }
                if (floating) {
                    FloatingLibraryNavigation(
                        tab,
                        TAB_LABELS,
                        TAB_ICONS,
                        hazeState,
                        Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 12.dp),
                    ) { index ->
                        tab =
                            index
                        folderDetails = false
                        vm.groupFilter.value = null
                        vm.matchFolderFilter.value = null
                    }
                }
            }
        }
    }
}

internal fun floatingPadding(vm: LibraryViewModel) = vm.navigationPosition == "FLOATING"
