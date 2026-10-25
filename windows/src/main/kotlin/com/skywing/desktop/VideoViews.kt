package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File

/** The page of the Videos section, for its grid size and card shape. */
internal const val VIDEOS_PAGE = "videos"

/**
 * The grid columns: one row per item in list mode, a fixed number chosen in Settings, otherwise as many cards of
 * the size picked with the grid size buttons as fit.
 */
internal fun FolderPrefs.cells(page: String): GridCells = when {
    listMode -> GridCells.Fixed(1)
    columns in 2..5 -> GridCells.Fixed(columns)
    else -> GridCells.Adaptive(CardSizes.of(cardSizeFor(page)).dp)
}

internal fun FolderPrefs.cardSizeFor(page: String): Int = if (page == VIDEOS_PAGE) videoCardSize else folderCardSize

internal fun FolderPrefs.isTall(page: String): Boolean = page in tallPages

/** Smaller (-1) or larger (+1) cards on [page]; a fixed column count from Settings gives way to the chosen size. */
internal fun Organizer.stepCardSize(page: String, direction: Int) = updatePrefs { p ->
    val next = CardSizes.step(p.cardSizeFor(page), direction)
    if (page == VIDEOS_PAGE) p.copy(videoCardSize = next, columns = 0) else p.copy(folderCardSize = next, columns = 0)
}

internal fun Organizer.toggleTall(page: String) = updatePrefs { p ->
    p.copy(tallPages = if (page in p.tallPages) p.tallPages - page else p.tallPages + page)
}

/** The grid size buttons and the card shape button, shown on the Videos page and on every Folders page. */
@Composable
internal fun GridControls(organizer: Organizer, page: String) {
    val prefs = organizer.data.prefs
    val size = CardSizes.of(prefs.cardSizeFor(page))
    val tall = prefs.isTall(page)
    Row(verticalAlignment = Alignment.CenterVertically) {
        SkyIconButton(SkyIcon.ZoomOut, "Smaller cards (Ctrl + mouse wheel)", { organizer.stepCardSize(page, -1) }, enabled = !prefs.listMode && size > CardSizes.STEPS.first())
        SkyIconButton(SkyIcon.ZoomIn, "Larger cards (Ctrl + mouse wheel)", { organizer.stepCardSize(page, 1) }, enabled = !prefs.listMode && size < CardSizes.STEPS.last())
        SkyIconButton(
            if (tall) SkyIcon.CropLandscape else SkyIcon.CropPortrait,
            if (tall) "Show landscape cards" else "Show taller portrait cards",
            { organizer.toggleTall(page) },
        )
    }
}

/** One card or line in a folder page grid. */
internal sealed interface Tile {
    val key: String
    val title: String
}

/** A folder card. [selected] is null when this page is not selecting; [menu] opens on hold or right click. */
internal data class FolderTile(
    override val key: String,
    override val title: String,
    val detail: String,
    /** The folder's cover key in the artwork store (null: the first video's thumbnail). */
    val coverKey: String?,
    /** The videos the cover may be made from. */
    val coverVideos: List<WindowsMediaEntry>,
    val onOpen: () -> Unit,
    val menu: List<MenuAction> = emptyList(),
    val selected: Boolean? = null,
    val onSelect: (() -> Unit)? = null,
) : Tile

/** A video card; clicking plays it with [queue]. */
internal data class VideoTile(
    override val key: String,
    val entry: WindowsMediaEntry,
    val queue: List<WindowsMediaEntry>,
    val menu: List<MenuAction> = emptyList(),
    val selected: Boolean? = null,
    val onSelect: (() -> Unit)? = null,
) : Tile {
    override val title: String get() = entry.file.name
}

/** A heading across the whole grid ("Folders", "Videos"). */
internal data class LabelTile(override val key: String, override val title: String) : Tile

/**
 * The grid every folder page uses: folders and videos scroll top to bottom together, with the scroll bar (and its
 * letter bubble when [letters] is on) at the right edge and the total at the end.
 */
@Composable
internal fun TileGrid(
    tiles: List<Tile>,
    state: LazyGridState,
    organizer: Organizer,
    page: String,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    modifier: Modifier = Modifier,
    emptyText: String? = null,
    footer: String? = null,
    letters: Boolean = true,
) {
    val prefs = organizer.data.prefs
    val tall = prefs.isTall(page)
    val current by rememberUpdatedState(tiles)
    var anchor by remember { mutableStateOf<String?>(null) }
    var boxBase by remember { mutableStateOf(emptySet<String>()) }
    fun ticked(tile: Tile) = (tile as? FolderTile)?.selected ?: (tile as? VideoTile)?.selected ?: false
    fun pick(tile: Tile) = (tile as? FolderTile)?.onSelect ?: (tile as? VideoTile)?.onSelect
    /** Ticks exactly the cards whose keys are in [wanted], among the cards that can be ticked. */
    fun tickExactly(wanted: Set<String>) {
        current.forEach { tile -> if (pick(tile) != null && (tile.key in wanted) != ticked(tile)) pick(tile)?.invoke() }
    }
    /** Ctrl+click ticks one card, Shift+click a range from the last card clicked; true when the click was used. */
    fun clicked(tile: Tile, ctrl: Boolean, shift: Boolean): Boolean {
        val from = anchor?.let { key -> current.indexOfFirst { it.key == key } } ?: -1
        val at = current.indexOf(tile)
        return when {
            shift && from >= 0 && at >= 0 -> {
                val range = current.subList(minOf(from, at), maxOf(from, at) + 1).map { it.key }.toSet()
                tickExactly((if (ctrl) current.filter(::ticked).map { it.key }.toSet() else emptySet()) + range)
                true
            }
            ctrl || shift -> {
                pick(tile)?.invoke()
                anchor = tile.key
                true
            }
            else -> {
                anchor = tile.key
                false
            }
        }
    }
    Box(
        modifier.fillMaxSize().ctrlWheel { organizer.stepCardSize(page, it) }.boxSelect(state) { inside, additive, started ->
            if (started) boxBase = if (additive) current.filter(::ticked).map { it.key }.toSet() else emptySet()
            tickExactly(boxBase + inside.map { it.toString() })
        },
    ) {
        LazyVerticalGrid(
            columns = prefs.cells(page),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (prefs.listMode) 8.dp else 14.dp),
        ) {
            if (tiles.isEmpty() && emptyText != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText) }
            }
            items(tiles, key = { it.key }, span = { if (it is LabelTile) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { tile ->
                TileView(tile, prefs.listMode, tall, history, onPlay, ::clicked)
            }
            if (footer != null && tiles.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "total") { ListTotal(footer) }
            }
        }
        GridScrollbar(
            state,
            Modifier.align(Alignment.CenterEnd),
            labelFor = if (letters) ({ index -> tiles.getOrNull(index)?.takeIf { it !is LabelTile }?.title?.let(FolderLogic::sectionLetter) }) else null,
        )
    }
}

@Composable
private fun TileView(
    tile: Tile,
    listMode: Boolean,
    tall: Boolean,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    /** Ctrl and Shift clicks tick cards as in Windows; returns true when the click was used for that. */
    onModifiedClick: (Tile, Boolean, Boolean) -> Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    fun modified(): Boolean = onModifiedClick(tile, window.keyboardModifiers.isCtrlPressed, window.keyboardModifiers.isShiftPressed)
    when (tile) {
        is LabelTile -> SectionLabel(tile.title, Modifier.padding(top = 4.dp))
        is FolderTile -> Box {
            FolderCard(
                tile.title, tile.detail, tile.coverKey, tile.coverVideos,
                onClick = { if (!modified()) { if (tile.selected != null) tile.onSelect?.invoke() else tile.onOpen() } },
                onLongClick = {
                    if (tile.selected != null) tile.onSelect?.invoke()
                    else if (tile.menu.isNotEmpty()) menu = true
                    else tile.onSelect?.invoke()
                },
                listMode = listMode,
                tall = tall,
                selected = tile.selected,
            )
            SkyMenu(menu, { menu = false }, tile.menu)
        }
        is VideoTile -> Box {
            VideoCard(
                entry = tile.entry,
                progress = watchedFraction(history, tile.entry),
                selected = tile.selected,
                listMode = listMode,
                tall = tall,
                onClick = {
                    if (!modified()) {
                        if (tile.selected != null) tile.onSelect?.invoke()
                        else onPlay(tile.entry.file, tile.queue.map { it.file })
                    }
                },
                onLongClick = {
                    if (tile.selected != null) tile.onSelect?.invoke()
                    else if (tile.menu.isNotEmpty()) menu = true
                    else tile.onSelect?.invoke()
                },
            )
            SkyMenu(menu, { menu = false }, tile.menu)
        }
    }
}

/** The total at the end of a list ("581 folders · 4,200 videos"). */
@Composable
internal fun ListTotal(text: String) {
    val theme = LocalSkyTheme.current
    Text(
        text,
        color = theme.muted,
        style = MaterialTheme.typography.labelMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
    )
}

/** A grid of video cards (the Videos page) with optional rows above it and the scroll bar at the right edge. */
@Composable
internal fun VideoGridView(
    entries: List<WindowsMediaEntry>,
    state: LazyGridState,
    organizer: Organizer,
    selection: SelectionState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    modifier: Modifier = Modifier,
    header: (LazyGridScope.() -> Unit)? = null,
    emptyText: String = "No videos here yet.",
    menu: (WindowsMediaEntry) -> List<MenuAction> = { emptyList() },
) {
    val prefs = organizer.data.prefs
    val tall = prefs.isTall(VIDEOS_PAGE)
    val shown by rememberUpdatedState(entries)
    val window = LocalWindowInfo.current
    var anchor by remember { mutableStateOf<String?>(null) }
    var boxBase by remember { mutableStateOf(emptySet<String>()) }
    /** Ctrl+click ticks one video, Shift+click a range from the last one clicked; true when the click was used. */
    fun modifiedClick(entry: WindowsMediaEntry): Boolean {
        val ctrl = window.keyboardModifiers.isCtrlPressed
        val shift = window.keyboardModifiers.isShiftPressed
        val from = anchor?.let { a -> shown.indexOfFirst { it.path == a } } ?: -1
        val at = shown.indexOf(entry)
        return when {
            shift && from >= 0 && at >= 0 -> {
                val range = shown.subList(minOf(from, at), maxOf(from, at) + 1).map { it.path }.toSet()
                selection.set((if (ctrl) selection.paths else emptySet()) + range)
                true
            }
            ctrl || shift -> {
                selection.set(if (entry.path in selection.paths) selection.paths - entry.path else selection.paths + entry.path)
                anchor = entry.path
                true
            }
            else -> {
                anchor = entry.path
                false
            }
        }
    }
    Box(
        modifier.fillMaxSize().ctrlWheel { organizer.stepCardSize(VIDEOS_PAGE, it) }.boxSelect(state) { inside, additive, started ->
            if (started) boxBase = if (additive) selection.paths else emptySet()
            val byKey = shown.associateBy { it.path.lowercase() }
            selection.set(boxBase + inside.mapNotNull { byKey[it.toString()]?.path })
        },
    ) {
        LazyVerticalGrid(
            columns = prefs.cells(VIDEOS_PAGE),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (prefs.listMode) 8.dp else 14.dp),
        ) {
            if (header != null) header()
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText) }
            }
            items(entries, key = { it.path.lowercase() }) { entry ->
                var open by remember { mutableStateOf(false) }
                Box {
                    VideoCard(
                        entry = entry,
                        progress = watchedFraction(history, entry),
                        selected = if (selection.active) entry.path in selection.paths else null,
                        listMode = prefs.listMode,
                        tall = tall,
                        onClick = {
                            if (modifiedClick(entry)) Unit
                            else if (selection.active) selection.toggle(entry.path)
                            else onPlay(entry.file, entries.map { it.file })
                        },
                        onLongClick = {
                            // Hold or right click: the card's options, or picking while selecting.
                            if (selection.active) selection.toggle(entry.path)
                            else if (menu(entry).isNotEmpty()) open = true
                            else selection.toggle(entry.path)
                        },
                    )
                    if (open) SkyMenu(true, { open = false }, menu(entry))
                }
            }
        }
        GridScrollbar(state, Modifier.align(Alignment.CenterEnd))
    }
}

/** The line at the top of an opened folder: back arrow, name, how many videos, and its actions. */
@Composable
internal fun FolderHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyIconButton(SkyIcon.Back, "Back", onBack)
        PageTitle(title, subtitle, Modifier.weight(1f))
        actions()
    }
}

/**
 * The top line while cards are ticked: stop, how many, Select all (Unselect all once everything is ticked), then the
 * page's actions. [confirm] asks first, because it takes things off the page.
 */
@Composable
internal fun FolderSelectionBar(
    count: Int,
    total: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    actions: List<MenuAction>,
    confirm: MenuAction? = null,
    confirmText: String = "They are taken off this page only. Files on your disk are not touched.",
) {
    val theme = LocalSkyTheme.current
    var asking by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).skyGlass(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkyIconButton(SkyIcon.Close, "Stop selecting", onClose)
        Text("$count selected", color = theme.ink, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        val all = total in 1..count
        SkyButton(if (all) "Unselect all" else "Select all", { if (all) onClose() else onSelectAll() }, primary = false)
        actions.forEach { action ->
            SkyButton(action.label, action.action, enabled = action.enabled && count > 0, primary = false, icon = action.icon)
        }
        if (confirm != null) {
            SkyButton(confirm.label, { asking = true }, enabled = confirm.enabled && count > 0, icon = confirm.icon)
        }
    }
    if (asking && confirm != null) {
        ConfirmDialog(
            title = "${confirm.label} $count ${if (count == 1) "item" else "items"}?",
            text = confirmText,
            confirmLabel = confirm.label,
            onDismiss = { asking = false },
            onConfirm = confirm.action,
        )
    }
}

/** Replaces the Videos page header while videos are ticked. */
@Composable
internal fun SelectionBar(
    selection: SelectionState,
    visible: List<WindowsMediaEntry>,
    organizer: Organizer,
    onPlay: (File, List<File>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalSkyTheme.current
    val chosen = visible.filter { it.path in selection.paths }
    var filing by remember { mutableStateOf<Boolean?>(null) }
    Row(
        modifier.fillMaxWidth().skyGlass(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SkyIconButton(SkyIcon.Close, "Stop selecting", { selection.clear() })
        Text("${selection.paths.size} selected", color = theme.ink, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        organizer.problem?.let { Text(it, color = theme.accent, style = MaterialTheme.typography.bodySmall) }
        SkyButton("Select all", { selection.paths = selection.paths + visible.map { it.path } }, primary = false)
        SkyButton("Play", { chosen.firstOrNull()?.let { onPlay(it.file, chosen.map { c -> c.file }) } }, enabled = chosen.isNotEmpty(), primary = false, icon = SkyIcon.Play)
        SkyButton("Add to virtual folder", { filing = true }, enabled = selection.paths.isNotEmpty(), primary = false, icon = SkyIcon.Grid)
        SkyButton("Add to folder", { filing = false }, enabled = selection.paths.isNotEmpty(), icon = SkyIcon.Folder)
    }
    filing?.let { virtual ->
        FileIntoFolderDialog(organizer, virtual, selection.paths, onDismiss = { filing = null }, onDone = { selection.clear() })
    }
}

/**
 * Puts videos into a Custom folder ([virtual] false) or a Virtual folder ([virtual] true): pick one, or make a new
 * one with a name. The videos stay where they are on disk.
 */
@Composable
internal fun FileIntoFolderDialog(
    organizer: Organizer,
    virtual: Boolean,
    paths: Collection<String>,
    onDismiss: () -> Unit,
    onDone: () -> Unit = {},
) {
    var naming by remember { mutableStateOf(false) }
    val choices = if (virtual) organizer.data.sets.map { it.id to it.name } else organizer.data.own.map { it.id to it.name }
    val noun = if (virtual) "virtual folder" else "custom folder"
    if (naming) {
        NameDialog(
            title = "New $noun",
            initial = "",
            confirmLabel = "Create",
            hint = "The selected videos go into it. Nothing on your disk changes.",
            onDismiss = onDismiss,
            onConfirm = { name ->
                val id = if (virtual) organizer.createSet(name) else organizer.createOwn(name, files = paths)
                if (id != null) {
                    if (virtual) organizer.addToSet(id, paths)
                    onDone()
                }
            },
        )
        return
    }
    PickDialog(
        title = "Add to a $noun",
        choices = choices.sortedBy { it.second.lowercase() },
        onDismiss = onDismiss,
        onPick = { id ->
            if (virtual) organizer.addToSet(id, paths) else organizer.addToOwn(id, paths)
            onDone()
        },
        newLabel = "New $noun…",
        onNew = { naming = true },
    )
}

/** A list of names to choose one from, with an optional New… button. */
@Composable
internal fun PickDialog(
    title: String,
    choices: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    newLabel: String? = null,
    onNew: (() -> Unit)? = null,
) {
    val theme = LocalSkyTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                choices.forEach { (id, name) ->
                    SkyButton(name, { onPick(id); onDismiss() }, Modifier.width(320.dp), primary = false, icon = SkyIcon.Folder)
                }
                if (newLabel != null && onNew != null) SkyButton(newLabel, onNew, Modifier.width(320.dp), icon = SkyIcon.Add)
            }
        },
        confirmButton = {},
        dismissButton = { SkyButton("Cancel", onDismiss, primary = false) },
    )
}

/** Asks before something is removed. */
@Composable
internal fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val theme = LocalSkyTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title) },
        text = { Text(text, color = theme.muted) },
        confirmButton = { SkyButton(confirmLabel, { onDismiss(); onConfirm() }) },
        dismissButton = { SkyButton("Cancel", onDismiss, primary = false) },
    )
}

/** Shows a few lines of facts (Details). */
@Composable
internal fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    val theme = LocalSkyTheme.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { SkyButton("Close", onDismiss, primary = false) },
    )
}
