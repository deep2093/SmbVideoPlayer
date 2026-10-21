package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.deepak.videolibrary.data.FolderSummary
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.ScanMode
import kotlinx.coroutines.launch
import java.util.Locale
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
internal fun FoldersHub(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    customGridState: LazyGridState,
    onOpenGroup: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onOpenSettings: () -> Unit,
    showBrand: Boolean = true,
) {
    // Pages follow the tabs chosen in Settings (some may be hidden or reordered).
    val sections = vm.folderSections
    val pager = rememberPagerState(initialPage = vm.foldersTab.coerceIn(0, sections.lastIndex), pageCount = { vm.folderSections.size })
    LaunchedEffect(sections) { if (pager.currentPage != vm.foldersTab.coerceIn(0, sections.lastIndex)) pager.scrollToPage(vm.foldersTab.coerceIn(0, sections.lastIndex)) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.settledPage) { vm.foldersTab = pager.settledPage }
    // Swiping to another tab closes the keyboard; each page also closes its own search box.
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(pager.currentPage) {
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val bars = rememberHubBars()
    var renaming by remember { mutableStateOf<Int?>(null) }
    // Tapping the open tab again scrolls its page to the top (the same as tapping Folders again).
    fun selectTab(index: Int) {
        if (pager.currentPage == index) {
            vm.ui.scrollTopRequest++
        } else {
            scope.launch { pager.animateScrollToPage(index) }
        }
    }
    // The app-name line and the tabs slide away while a page scrolls down and come back on any scroll up.
    val hubHeader = rememberCollapsingHeader()
    Column(Modifier.fillMaxSize().nestedScroll(hubHeader.connection)) {
        // Inside an Original sub-folder that page shows its own ← title line, like an opened folder elsewhere.
        val root = vm.settings.rootPath.trim('/')
        val insideOriginal = sections.getOrNull(pager.currentPage) == 2 && vm.originalPath.trim('/').let { it != root && isWithinFolder(it, root) }
        if (!insideOriginal) CollapsingHeader(hubHeader, resetKey = pager.currentPage) {
        // On phones the page buttons sit on the app-name line; on tablets each page keeps its own title row.
        if (showBrand) {
            HubBarLine(bars, pager.currentPage, APP_NAME, Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp))
        }
        androidx.compose.material3.TabRow(
            selectedTabIndex = pager.currentPage.coerceIn(0, sections.lastIndex),
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ) {
            sections.forEachIndexed { index, section ->
                // Holding a tab: rename it, hide it (one tab always stays), or bring back a hidden one.
                HubTab(vm.sectionName(section), pager.currentPage == index, onClick = { selectTab(index) }) { dismiss ->
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { dismiss(); renaming = section })
                    DropdownMenuItem(
                        text = { Text("Hide this tab") },
                        enabled = sections.size > 1,
                        onClick = { dismiss(); vm.updateFolderSections(sections - section) },
                    )
                    FolderSections.ALL.filterNot { it in sections }.forEach { hidden ->
                        DropdownMenuItem(text = { Text("Show ${vm.sectionName(hidden)}") }, onClick = { dismiss(); vm.updateFolderSections(sections + hidden) })
                    }
                }
            }
        }
        }
        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            // A focused search field asks its ancestors to scroll it into view when the keyboard opens.
            // The pager would answer by sliding to a neighbouring tab, so the request stops at the page.
            Box(Modifier.fillMaxSize().stopBringIntoViewPropagation()) {
                val active = pager.currentPage == page
                val slot = remember(page, showBrand) { if (showBrand) HubBarSlot(bars, page) else null }
                androidx.compose.runtime.CompositionLocalProvider(LocalHubBarSlot provides slot) {
                    when (sections.getOrNull(page)) {
                        null -> Unit
                        0 -> MatchingFoldersScreen(vm, customGridState, onOpenGroup, onPlay, active = active)
                        1 -> FoldersScreen(vm, gridState, onOpenGroup, onPlay, onOpenSettings, active = active)
                        2 -> OriginalFoldersScreen(vm, onPlay, active = active)
                    }
                }
            }
        }
    }
    renaming?.let { index ->
        RenameDialog(
            title = "Rename tab",
            current = vm.sectionName(index),
            hint = "Shown on the tab and at the top of the page. Leave it as \"${SECTION_DEFAULTS[index]}\" to keep the usual name.",
            onDismiss = { renaming = null },
        ) { vm.renameSection(index, it) }
    }
}

@Composable
fun FoldersScreen(
    vm: LibraryViewModel,
    gridState: LazyGridState,
    onOpenGroup: () -> Unit,
    onPlay: (VideoEntity, List<VideoEntity>) -> Unit,
    onOpenSettings: () -> Unit,
    active: Boolean = true,
) {
    val shownGroups by vm.groups.collectAsStateWithLifecycle()
    val recentlyWatched by vm.continuing.collectAsStateWithLifecycle()
    var folderQuery by vm::folderSearch
    var searchExpanded by rememberSaveable { mutableStateOf(folderQuery.isNotBlank()) }
    var sourceFolder by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf("") }
    var targetName by remember { mutableStateOf("") }
    var editFolders by rememberSaveable { mutableStateOf(false) }
    // Jump to the top only when the search text changes while this screen is visible.
    // Re-entering the screen (e.g. coming back from a folder) must keep the saved position.
    ScrollToTopOnChange(folderQuery, gridState)
    LaunchedEffect(active) {
        if (!active && searchExpanded) {
            folderQuery = ""
            searchExpanded = false
        }
    }
    val selectPage = SelectionPages.VIRTUAL
    val selection = vm.ui.selectionOn(selectPage)
    androidx.activity.compose.BackHandler(enabled = selection != null) { vm.ui.endSelection(selectPage) }
    LaunchedEffect(active) { if (!active) vm.ui.endSelection(selectPage) }
    ScrollTopOnRequest(vm, gridState, active)
    Column(Modifier.fillMaxSize().padding(horizontal = PAGE_MARGIN)) {
        HubTopBar {
        if (selection != null) {
            FolderSelectionBar(
                count = selection.keys.size,
                total = shownGroups.size,
                onClose = { vm.ui.endSelection(selectPage) },
                onSelectAll = { vm.ui.selectAllFolders(selectPage, shownGroups.map { it.groupKey }) },
                onDownload = {
                    selection.keys.forEach(vm::downloadVirtualFolder)
                    vm.ui.endSelection(selectPage)
                },
                onHide = {
                    selection.keys.forEach(vm::hideGroup)
                    vm.ui.endSelection(selectPage)
                },
                onRemove = null,
            )
        } else
        SearchableTopBar(
            title = vm.sectionName(1),
            searching = searchExpanded,
            onSearchingChange = { searchExpanded = it },
            query = folderQuery,
            onQueryChange = { folderQuery = it },
            placeholder = "Search virtual folders",
            modifier = if (LocalHubTitle.current == null) Modifier.padding(top = 8.dp) else Modifier,
        ) {
            CardShapeButton(vm, Pages.VIRTUAL)
            // Sort and list/grid live in this one menu so the top line keeps room for the app name.
            Box {
                var folderMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Folder options") }
                DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                    Text("Sort by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                    listOf(
                        "NAME" to "Name",
                        "COUNT" to "Number of items",
                        "DATE" to "Latest by date",
                        "SIZE" to "Total size",
                    ).forEach { (sort, label) ->
                        DropdownMenuItem(
                            text = { Text(label + if (vm.folderSort == sort) { if (vm.folderSortDescending) " ↓" else " ↑" } else "") },
                            leadingIcon = { Icon(Icons.Filled.Sort, contentDescription = null) },
                            onClick = {
                                vm.selectFolderSort(sort)
                                folderMenu = false
                            },
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(if (vm.folderListMode) "Show as grid" else "Show as list") },
                        leadingIcon = { Icon(if (vm.folderListMode) Icons.Filled.GridView else Icons.Filled.ViewList, contentDescription = null) },
                        onClick = {
                            vm.toggleFolderLayout()
                            folderMenu = false
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (editFolders) "Finish organizing" else "Organize folders") },
                        onClick = {
                            editFolders = !editFolders
                            folderMenu = false
                        },
                    )
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_refresh_library_564fe5e)) }, onClick = {
                        vm.startScan(ScanMode.REFRESH)
                        folderMenu = false
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_settings_c7f73bb)) }, onClick = {
                        folderMenu = false
                        onOpenSettings()
                    })
                }
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        vm.organizerError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val columns = libraryColumns(vm.folderGridPreset, floatingPadding(vm))
        val aspect = cardAspect(vm, Pages.VIRTUAL)
        FastScrollBox(
            state = gridState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            labelFor = if (vm.folderSort == "NAME") {
                { index ->
                    // Folders start at the first row; the total is at the end.
                    val at = index
                    if (at in shownGroups.indices) sectionLetter(shownGroups[at].groupKey) else null
                }
            } else {
                null
            },
            bottomInset = if (floatingPadding(vm)) 100.dp else 16.dp,
        ) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(if (vm.folderListMode) 1 else columns),
                horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
                verticalArrangement = Arrangement.spacedBy(GRID_ROW_GAP),
                contentPadding = PaddingValues(bottom = if (floatingPadding(vm))112.dp else 24.dp),
                modifier = Modifier.fillMaxSize().testTag("virtual-folder-grid"),
            ) {
                if (shownGroups.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (folderQuery.isNotBlank()) "No matching folders." else "Your virtual folders appear after a scan.",
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                gridItems(shownGroups, key = { it.groupKey }, contentType = { "folder" }) { row ->
                    Column {
                        FolderCard(vm, row, aspect, listMode = vm.folderListMode, selected = vm.ui.tick(selectPage, row.groupKey)) {
                            if (vm.ui.selectionOn(selectPage) != null) {
                                vm.ui.toggleFolder(selectPage, row.groupKey)
                                return@FolderCard
                            }
                            vm.matchFolderFilter.value = null
                            vm.groupFilter.value = row.groupKey
                            vm.query.value = ""
                            onOpenGroup()
                        }
                        var menuExpanded by remember(row.groupKey) { mutableStateOf(false) }
                        if (editFolders) {
                            Box {
                                TextButton(onClick = { menuExpanded = true }) { Text(stringResource(R.string.ui_options_87ab915)) }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_rename_folder_ebe08b9)) }, onClick = {
                                        menuExpanded = false
                                        sourceFolder = row.groupKey
                                        operation = "Rename"
                                        targetName = row.groupKey
                                    })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_merge_into_56f0e1a)) }, onClick = {
                                        menuExpanded = false
                                        sourceFolder = row.groupKey
                                        operation = "Merge"
                                        targetName = ""
                                    })
                                }
                            }
                        }
                    }
                }
                if (shownGroups.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "count") {
                    ListTotal(
                        "${shownGroups.size} folders · " + when (vm.folderSort) {
                            "COUNT" -> "Number of items"
                            "DATE" -> "Latest by date"
                            "SIZE" -> "Total size"
                            else -> "Name"
                        } + if (vm.folderSortDescending) " ↓" else " ↑",
                    )
                }
            }
        }
    }
    if (sourceFolder != null) {
        AlertDialog(
            onDismissRequest = { sourceFolder = null },
            title = { Text("$operation $sourceFolder") },
            text = {
                Column {
                    Text(
                        if (operation ==
                            "Merge"
                        ) {
                            "All videos assigned to this group will be assigned to the destination virtual folder."
                        } else {
                            "Rename this virtual folder. No SMB files are renamed."
                        },
                    )
                    PillTextField(
                        value = targetName,
                        onValueChange = { targetName = it },
                        label = if (operation == "Merge") "Destination folder" else "New name",
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val from = sourceFolder!!
                    if (operation == "Merge") vm.mergeFolders(from, targetName.trim()) else vm.renameFolder(from, targetName.trim())
                    sourceFolder = null
                }) { Text(operation) }
            },
            dismissButton = { TextButton(onClick = { sourceFolder = null }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
}

@Composable
private fun FolderCard(
    vm: LibraryViewModel,
    row: FolderSummary,
    aspect: Float,
    modifier: Modifier = Modifier,
    listMode: Boolean = false,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    var actions by remember(row.groupKey) { mutableStateOf(false) }
    var changeCover by remember(row.groupKey) { mutableStateOf(false) }
    var rename by remember(row.groupKey) { mutableStateOf(false) }
    val artworkOn = rememberArtworkEnabled(vm)
    val key = vm.coverKey("group:${row.groupKey}", row.count, row.thumbsDone, row.lastModified)
    var cover by remember(key) { mutableStateOf(vm.cachedCover(key)) }
    LaunchedEffect(key) { cover = vm.coverFor(row.groupKey, key) }
    Box(modifier) {
        FolderTile(
            title = row.groupKey.replaceFirstChar { it.titlecase(Locale.ROOT) },
            detail = "${row.count} videos",
            cover = cover,
            aspect = aspect,
            listMode = listMode,
            onClick = onClick,
            onLongPress = { if (selected != null) { onClick() } else { actions = true } },
            onRename = { rename = true },
            selected = selected,
        )
        FileActionsMenu(actions, {
            actions = false
        }, vm, row.groupKey, directory = true, virtual = true, details = "${row.groupKey}\n${row.count} videos\nVirtual library folder",
            onChangeCover = if (artworkOn) ({ changeCover = true }) else null,
            onSelect = { vm.ui.toggleFolder(SelectionPages.VIRTUAL, row.groupKey) })
        if (changeCover) {
            ChangeCoverDialog(vm, CoverTarget(com.deepak.videolibrary.artwork.ArtworkKeys.virtualFolder(row.groupKey), row.groupKey, row.groupKey), null) { changeCover = false }
        }
    }
    if (rename) {
        RenameDialog("Rename virtual folder", row.groupKey, "This changes library organisation only. No files on your drive are renamed.", { rename = false }) {
            vm.renameFolder(row.groupKey, it)
        }
    }
}
