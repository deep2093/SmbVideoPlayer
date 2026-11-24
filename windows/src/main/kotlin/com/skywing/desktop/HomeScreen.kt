package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import com.skywing.desktop.ui.theme.SkyRadius
import java.io.File

internal const val HOME_PAGE = "home"

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * The start page (M3 design brief 5.3, decision O3): Continue watching, then the videos added most recently. No pinned
 * folders. With nothing in the library it says how to start; while the library loads it shows the shape of the page.
 */
@Composable
internal fun HomeScreen(
    library: LibraryModel,
    hub: HubState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    onResume: (File, Double) -> Unit,
    /** Opens Videos, where every video is. */
    onSeeAll: () -> Unit,
    /** Opens Files, where folders are added. */
    onAddFolder: () -> Unit,
) {
    val adaptive = LocalAdaptive.current
    val byPath = library.byPath
    val continueItems = remember(history, byPath) {
        history.values.sortedByDescending { it.updated }.mapNotNull { record ->
            byPath[record.path.lowercase()]?.let { it to record }
        }.take(14)
    }
    val recent = remember(library.all) { library.all.sortedByDescending { it.modified } }
    val newThisWeek = remember(recent) {
        val since = System.currentTimeMillis() - WEEK_MS
        recent.count { it.modified >= since }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageTitle("Home", library.notice ?: if (library.loading) "Loading your library…" else null)
        when {
            library.loading && library.all.isEmpty() -> HomeSkeleton(Modifier.weight(1f))
            library.all.isEmpty() -> HomeEmpty(onAddFolder, Modifier.weight(1f))
            else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val gap = adaptive.gridGap
                val columns = adaptive.gridColumns(maxWidth)
                val column = (maxWidth - gap * (columns - 1)) / columns
                // Continue watching cards are at least 260 dp, so the time left and the title stay readable.
                val continueWidth = maxOf(260.dp, column)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = hub.grid(HOME_PAGE),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (continueItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue-label") {
                            HomeSectionHeader("Continue watching", action = "See all", onAction = onSeeAll)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                                items(continueItems, key = { "continue:" + it.first.path.lowercase() }) { (entry, record) ->
                                    VideoCard(
                                        entry = entry,
                                        progress = if (record.duration > 0) (record.position / record.duration).toFloat().coerceIn(0f, 1f) else null,
                                        detail = timeLeft(record),
                                        onClick = { onResume(entry.file, record.position) },
                                        modifier = Modifier.width(continueWidth),
                                    )
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "recent-label") {
                        HomeSectionHeader("Recently added", detail = if (newThisWeek > 0) "$newThisWeek new this week" else null)
                    }
                    val shown = recent.take(columns * 2)
                    items(shown, key = { "recent:" + it.path.lowercase() }) { entry ->
                        VideoCard(entry = entry, onClick = { onPlay(entry.file, recent.map { it.file }) })
                    }
                }
            }
        }
    }
}

/** "1:12:05 left", for a video stopped partway. */
private fun timeLeft(record: WindowsWatchRecord): String =
    if (record.duration > record.position) formatClock(record.duration - record.position) + " left" else formatClock(record.duration)

@Composable
private fun HomeSectionHeader(title: String, detail: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    val c = LocalSkyTheme.current.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, color = c.text, style = MaterialTheme.typography.titleMedium)
        if (detail != null) Text(detail, color = c.textSecondary, style = MaterialTheme.typography.bodySmall)
        Box(Modifier.weight(1f))
        if (action != null) SkyButton(action, onAction, subtle = true)
    }
}

/** What Home says with no videos yet, and the way to add some. */
@Composable
private fun HomeEmpty(onAddFolder: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSkyTheme.current.colors
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        SkyIconView(SkyIcon.VideoLibrary, c.textSecondary, size = 40.dp)
        Text("Add a folder to see your videos here", color = c.text, style = MaterialTheme.typography.titleMedium)
        SkyButton("Add folder", onAddFolder, icon = SkyIcon.Add)
    }
}

/** Grey card shapes where Home's cards will be, while the library loads. Still, so nothing moves before it's ready. */
@Composable
private fun HomeSkeleton(modifier: Modifier = Modifier) {
    val adaptive = LocalAdaptive.current
    val c = LocalSkyTheme.current.colors
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val gap = adaptive.gridGap
        val columns = adaptive.gridColumns(maxWidth)
        val width: Dp = (maxWidth - gap * (columns - 1)) / columns
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            Box(Modifier.width(180.dp).height(20.dp).clip(RoundedCornerShape(6.dp)).background(c.layer2))
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    repeat(columns) {
                        Box(Modifier.width(width).aspectRatio(16f / 9f).clip(RoundedCornerShape(SkyRadius.card)).background(c.layer2))
                    }
                }
            }
        }
    }
}
