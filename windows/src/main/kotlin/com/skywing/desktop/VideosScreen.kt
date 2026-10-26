package com.skywing.desktop

import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val SORT_NAMES = listOf("Name", "Recently added", "File size")
private val SOURCE_NAMES = listOf("All", "This PC", "Network")

/** Every video in one grid, with Continue watching on top, like the Videos tab on Android. */
@Composable
internal fun VideosScreen(
    library: LibraryModel,
    organizer: Organizer,
    hub: HubState,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    onResume: (File, Double) -> Unit,
    onRename: (File) -> Unit,
) {
    // Starts with the last list shown, so the grid keeps its place when coming back from a video.
    val shown by produceState(hub.lastResult<List<WindowsMediaEntry>>(VIDEOS_PAGE) ?: emptyList(), library.all, hub.videoSource, hub.videoSort, hub.videoQuery) {
        val source = hub.videoSource
        val sort = hub.videoSort
        val term = hub.videoQuery.trim()
        value = withContext(Dispatchers.Default) {
            val filtered = library.all.filter { entry ->
                (source == 0 || (source == 1 && entry.sourceType == "PC") || (source == 2 && entry.sourceType == "SMB")) &&
                    (term.isEmpty() || entry.file.name.contains(term, ignoreCase = true))
            }
            when (sort) {
                1 -> filtered.sortedByDescending { it.modified }
                2 -> filtered.sortedByDescending { it.size }
                else -> filtered
            }
        }.also { hub.keepResult(VIDEOS_PAGE, it) }
    }
    val byPath = remember(library.all) { library.all.associateBy { it.path.lowercase() } }
    val artworkOn = LocalCovers.current?.artwork?.settings?.enabled == true
    var cover by remember { mutableStateOf<CoverTarget?>(null) }
    var filing by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val continueItems = remember(history, byPath) {
        history.values.sortedByDescending { it.updated }.mapNotNull { record ->
            byPath[record.path.lowercase()]?.let { it to record }
        }.take(14)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (selection.active) {
            SelectionBar(selection, shown, organizer, onPlay)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageTitle(
                    "Videos",
                    if (library.loading) "Loading your library…" else "${shown.size} of ${library.all.size} videos",
                    Modifier.weight(1f),
                )
                SkyField(hub.videoQuery, { hub.videoQuery = it }, "Search videos", Modifier.width(260.dp), leading = SkyIcon.Search)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SkyChipBar(SOURCE_NAMES, hub.videoSource, { hub.videoSource = it })
                Row(Modifier.weight(1f)) {}
                SkyButton("Sort: " + SORT_NAMES[hub.videoSort.coerceIn(0, SORT_NAMES.lastIndex)], {
                    hub.videoSort = (hub.videoSort + 1) % SORT_NAMES.size
                }, primary = false, icon = SkyIcon.Sort)
                GridControls(organizer, VIDEOS_PAGE)
                SkyButton("Select", { selection.active = true }, primary = false, icon = SkyIcon.Check, enabled = shown.isNotEmpty())
                SkyButton("Play all", {
                    shown.firstOrNull()?.let { onPlay(it.file, shown.map { e -> e.file }) }
                }, enabled = shown.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        // Continue watching sits at the top of the same grid, so the whole page scrolls top to bottom.
        val header: LazyGridScope.() -> Unit = {
            if (continueItems.isNotEmpty() && hub.videoQuery.isBlank() && !selection.active) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "continue-label") { SectionLabel("Continue watching") }
                items(continueItems, key = { "continue:" + it.first.path.lowercase() }) { (entry, record) ->
                    VideoCard(
                        entry = entry,
                        progress = (record.position / record.duration).toFloat().coerceIn(0f, 1f),
                        detail = formatClock(record.position) + " of " + formatClock(record.duration),
                        listMode = organizer.data.prefs.listMode,
                        tall = organizer.data.prefs.isTall(VIDEOS_PAGE),
                        onClick = { onResume(entry.file, record.position) },
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }, key = "all-label") { SectionLabel("All videos") }
            }
        }
        VideoGridView(
            entries = shown,
            state = hub.grid(VIDEOS_PAGE),
            organizer = organizer,
            selection = selection,
            history = history,
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
            header = header,
            emptyText = if (library.loading) "Loading…" else "No videos yet. Add folders or a network share in Files.",
            menu = { entry ->
                listOfNotNull(
                    MenuAction("Play", SkyIcon.Play) { onPlay(entry.file, shown.map { it.file }) },
                    MenuAction("Add to folder…", SkyIcon.Folder) { filing = false to entry.path },
                    MenuAction("Add to virtual folder…", SkyIcon.Grid) { filing = true to entry.path },
                    MenuAction("Rename video…", SkyIcon.Edit) { onRename(entry.file) },
                    if (artworkOn) MenuAction("Change cover", SkyIcon.Image) { cover = videoCoverTarget(entry) } else null,
                )
            },
        )
    }
    cover?.let { target -> ChangeCoverDialog(target) { cover = null } }
    filing?.let { (virtual, path) -> FileIntoFolderDialog(organizer, virtual, listOf(path), onDismiss = { filing = null }, onDone = {}) }
}
