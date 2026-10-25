package com.skywing.desktop

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
    custom: CustomFolders,
    hub: HubState,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    onResume: (File, Double) -> Unit,
) {
    val shown by produceState(emptyList<WindowsMediaEntry>(), library.all, hub.videoSource, hub.videoSort, hub.videoQuery) {
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
        }
    }
    val byPath = remember(library.all) { library.all.associateBy { it.path.lowercase() } }
    val continueItems = remember(history, byPath) {
        history.values.sortedByDescending { it.updated }.mapNotNull { record ->
            byPath[record.path.lowercase()]?.let { it to record }
        }.take(14)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (selection.active) {
            SelectionBar(selection, shown, custom, onPlay)
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle(
                    "Videos",
                    if (library.loading) "Loading your library…" else "${shown.size} of ${library.all.size} videos",
                    Modifier.weight(1f),
                )
                SkyField(hub.videoQuery, { hub.videoQuery = it }, "Search videos", Modifier.width(300.dp), leading = SkyIcon.Search)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SkyChipBar(SOURCE_NAMES, hub.videoSource, { hub.videoSource = it })
                Row(Modifier.weight(1f)) {}
                SkyButton("Sort: " + SORT_NAMES[hub.videoSort.coerceIn(0, SORT_NAMES.lastIndex)], {
                    hub.videoSort = (hub.videoSort + 1) % SORT_NAMES.size
                }, primary = false, icon = SkyIcon.Sort)
                SkyButton("Select", { selection.active = true }, primary = false, icon = SkyIcon.Check, enabled = shown.isNotEmpty())
                SkyButton("Play all", {
                    shown.firstOrNull()?.let { onPlay(it.file, shown.map { e -> e.file }) }
                }, enabled = shown.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        val header: LazyGridScope.() -> Unit = {
            if (continueItems.isNotEmpty() && hub.videoQuery.isBlank() && !selection.active) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel("Continue watching")
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(continueItems.size) { index ->
                                val (entry, record) = continueItems[index]
                                VideoCard(
                                    entry = entry,
                                    modifier = Modifier.width(230.dp),
                                    progress = (record.position / record.duration).toFloat().coerceIn(0f, 1f),
                                    detail = formatClock(record.position) + " of " + formatClock(record.duration),
                                    onClick = { onResume(entry.file, record.position) },
                                )
                            }
                        }
                        SectionLabel("All videos")
                    }
                }
            }
        }
        VideoGridView(
            entries = shown,
            state = hub.grid("videos"),
            selection = selection,
            history = history,
            onPlay = onPlay,
            modifier = Modifier.weight(1f),
            header = header,
            emptyText = if (library.loading) "Loading…" else "No videos yet. Add folders or a network share in Files.",
        )
    }
}
