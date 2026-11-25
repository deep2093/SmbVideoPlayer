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
import com.skywing.desktop.ui.adaptive.Adaptive
import com.skywing.desktop.ui.adaptive.AutoColumns
import com.skywing.desktop.ui.adaptive.LocalAdaptive
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.io.File

/** The page of the Videos section, for its grid size and card shape. */
internal const val VIDEOS_PAGE = "videos"

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
    /** What a click or Enter does when the card is not selecting; null plays the video with [queue]. */
    val open: (() -> Unit)? = null,
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
    KeepFirstItemAcrossColumns(state)
    val prefs = organizer.data.prefs
    val views = LocalLibraryViews.current
    val view = views.of(page)
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
        val result = clickSelection(
            current.filter { pick(it) != null }.map { it.key }, current.filter(::ticked).map { it.key }.toSet(), anchor, tile.key, ctrl, shift,
        )
        anchor = tile.key
        if (result != null) tickExactly(result)
        return result != null
    }
    Box(
        modifier.fillMaxSize().ctrlWheel { views.step(page, it) }.viewKeys(page).boxSelect(state) { inside, additive, started ->
            if (started) boxBase = if (additive) current.filter(::ticked).map { it.key }.toSet() else emptySet()
            tickExactly(boxBase + inside.map { it.toString() })
        },
    ) {
        LazyVerticalGrid(
            columns = cells(view, prefs.columns, LocalAdaptive.current),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (view.list) 8.dp else 14.dp),
        ) {
            if (tiles.isEmpty() && emptyText != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText) }
            }
            items(tiles, key = { it.key }, span = { if (it is LabelTile) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { tile ->
                TileView(tile, view, history, onPlay, ::clicked)
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
    view: PageView,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    /** Ctrl and Shift clicks tick cards as in Windows; returns true when the click was used for that. */
    onModifiedClick: (Tile, Boolean, Boolean) -> Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    // Keys on the focused card: its menu's shortcuts (F2, Alt+Enter), and Shift+F10 or the menu key for the menu.
    fun cardKeys(items: List<MenuAction>) = Modifier.onPreviewKeyEvent { event ->
        val shortcut = if (event.type == KeyEventType.KeyDown) cardKeyShortcut(event.key, event.isAltPressed, event.isShiftPressed) else null
        when {
            shortcut == null -> false
            shortcut == MENU_KEY -> items.isNotEmpty().also { if (it) menu = true }
            else -> shortcutAction(items, shortcut)?.let { it.action(); true } ?: false
        }
    }
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
                listMode = view.list,
                aspect = view.folderAspect,
                selected = tile.selected,
                modifier = cardKeys(tile.menu),
            )
            SkyMenu(menu, { menu = false }, tile.menu)
        }
        is VideoTile -> Box {
            VideoCard(
                entry = tile.entry,
                progress = watchedFraction(history, tile.entry),
                selected = tile.selected,
                listMode = view.list,
                aspect = view.videoAspect,
                modifier = cardKeys(tile.menu),
                onClick = {
                    if (!modified()) {
                        if (tile.selected != null) tile.onSelect?.invoke()
                        else tile.open?.invoke() ?: onPlay(tile.entry.file, tile.queue.map { it.file })
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

/** What [cardKeyShortcut] answers for Shift+F10 and the menu key: open the card's menu. */
internal const val MENU_KEY = "Menu"

/** The menu shortcut a key on a focused card stands for ("F2", "Alt+Enter", or [MENU_KEY]), or null. */
internal fun cardKeyShortcut(key: Key, alt: Boolean, shift: Boolean): String? = when {
    key == Key.Menu || (key == Key.F10 && shift) -> MENU_KEY
    key == Key.F2 && !alt && !shift -> "F2"
    (key == Key.Enter || key == Key.NumPadEnter) && alt -> "Alt+Enter"
    else -> null
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

/**
 * Keeps the video the person was looking at on the first line when the window's width changes the number of columns
 * (M3 design brief 5.4). On its own a grid keeps the start of the first line, which moves back by up to a line at
 * each change, so a few resizes drift the view away. The item stays the anchor while it is on the first line, and
 * the grid scrolls back to it after each change; scrolling past it makes the new first item the anchor.
 */
@Composable
internal fun KeepFirstItemAcrossColumns(state: LazyGridState) {
    LaunchedEffect(state) {
        var columns = 0
        var items = 0
        var anchor = -1
        snapshotFlow { Triple(state.layoutInfo.maxSpan, state.layoutInfo.totalItemsCount, state.firstVisibleItemIndex) }
            .collect { (span, count, first) ->
                val reflowed = columns != 0 && span != columns && count == items && anchor >= 0
                columns = span
                items = count
                // Applied at the next measure: this can run while the grid is being measured.
                if (reflowed) state.requestScrollToItem(anchor)
                else if (anchor !in first until first + span) anchor = first
            }
    }
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
    /** A button under [emptyText], for the step that fills the grid. */
    emptyAction: MenuAction? = null,
    menu: (WindowsMediaEntry) -> List<MenuAction> = { emptyList() },
) {
    KeepFirstItemAcrossColumns(state)
    val prefs = organizer.data.prefs
    val views = LocalLibraryViews.current
    val view = views.of(VIDEOS_PAGE)
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
        modifier.fillMaxSize().ctrlWheel { views.step(VIDEOS_PAGE, it) }.viewKeys(VIDEOS_PAGE).boxSelect(state) { inside, additive, started ->
            if (started) boxBase = if (additive) selection.paths else emptySet()
            val byKey = shown.associateBy { it.path.lowercase() }
            selection.set(boxBase + inside.mapNotNull { byKey[it.toString()]?.path })
        },
    ) {
        LazyVerticalGrid(
            columns = cells(view, prefs.columns, LocalAdaptive.current),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (view.list) 8.dp else 14.dp),
        ) {
            if (header != null) header()
            if (entries.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText, action = emptyAction) }
            }
            items(entries, key = { it.path.lowercase() }) { entry ->
                var open by remember { mutableStateOf(false) }
                Box {
                    VideoCard(
                        entry = entry,
                        progress = watchedFraction(history, entry),
                        selected = if (selection.active) entry.path in selection.paths else null,
                        listMode = view.list,
                        aspect = view.videoAspect,
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

/**
 * The line at the top of an opened folder: back arrow, name, how many videos, and its actions. In a narrow window the
 * actions go under the name ([HeaderLine]).
 */
@Composable
internal fun FolderHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    HeaderLine(modifier.heightIn(min = 56.dp), leadMin = 200.dp, gap = 10.dp, lead = {
        SkyIconButton(SkyIcon.Back, "Back", onBack)
        PageTitle(title, subtitle, Modifier.weight(1f))
    }, trail = actions)
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
        Modifier.fillMaxWidth().heightIn(min = 56.dp).skyPanel(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp),
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
        modifier.fillMaxWidth().skyPanel(RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 6.dp),
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
