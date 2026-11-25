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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
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
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.platform.LocalFocusManager

/** The page of the Videos section, for its grid size and card shape. */
internal const val VIDEOS_PAGE = "videos"

/** One card or line in a folder page grid. */
internal sealed interface Tile {
    val key: String
    val title: String
}

/**
 * A folder card. [selected] is null when this page is not selecting; [menu] opens on hold or right click. An [actor] card
 * shows a photo or [initials], the name in a serif font, and a [dot] when it has new videos.
 */
internal data class FolderTile(
    override val key: String,
    override val title: String,
    val detail: String,
    /** The folder's cover key in the artwork store (null: the first video's thumbnail). */
    val coverKey: String?,
    /** The videos the cover may be made from. */
    val coverVideos: List<WindowsMediaEntry>,
    val onOpen: () -> Unit,
    /** Built when the menu opens or a card key arrives, never for every card on every composition. */
    val menu: () -> List<MenuAction> = { emptyList() },
    val selected: Boolean? = null,
    val onSelect: (() -> Unit)? = null,
    val actor: Boolean = false,
    val dot: Boolean = false,
    val initials: String? = null,
    /** The key the picture moves under into the actor page's header ([actorPhotoKey]); null where it does not move. */
    val photoKey: String? = null,
) : Tile

/** A video card; clicking plays it with [queue]. */
internal data class VideoTile(
    override val key: String,
    val entry: WindowsMediaEntry,
    val queue: List<WindowsMediaEntry>,
    /** Built when the menu opens or a card key arrives, never for every card on every composition. */
    val menu: () -> List<MenuAction> = { emptyList() },
    val selected: Boolean? = null,
    val onSelect: (() -> Unit)? = null,
    /** What a click or Enter does when the card is not selecting; null plays the video with [queue]. */
    val open: (() -> Unit)? = null,
    /** The length, watched tick and "New" tag on its picture (the actor page); null shows none of them. */
    val badges: VideoBadges? = null,
) : Tile {
    override val title: String get() = entry.file.name
}

/** What a video card shows on its picture besides the progress bar: its length when known, a tick when watched, and "New". */
internal data class VideoBadges(val length: Double? = null, val watched: Boolean = false, val new: Boolean = false)

/** A heading across the whole grid ("Folders", "Videos"). */
internal data class LabelTile(override val key: String, override val title: String) : Tile

/** The tile key of an actor's card, for [HubState.focusTile]. */
internal fun actorFocusKey(identity: String): String = "group:" + identity

/** The tile key of a video's card, for [HubState.focusTile]. */
internal fun videoFocusKey(file: File): String = "file:" + file.path.lowercase(Locale.ROOT)

/** Whether the card at [index] needs a scroll to be seen: it is not among the [visible] ones (spec 5.5). */
internal fun scrollNeeded(index: Int, visible: List<Int>): Boolean = index !in visible

/** The first of [titles] that starts with [typed], ignoring case; null when none does or nothing was typed. */
internal fun typeToJump(titles: List<String>, typed: String): Int? =
    if (typed.isEmpty()) null else titles.indexOfFirst { it.startsWith(typed, ignoreCase = true) }.takeIf { it >= 0 }

/** How long type-to-jump keeps what was typed: after a second without a key it starts again. */
internal const val TYPE_RESET_MS = 1000L

/** What type-to-jump looks for once [char] is typed at [now], when [typed] was last typed to at [lastAt]. */
internal fun typedText(typed: String, lastAt: Long, char: Char, now: Long): String =
    if (now - lastAt > TYPE_RESET_MS) char.toString() else typed + char

private val LETTER_KEYS = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)
private val DIGIT_KEYS = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** The letter or digit a key types, for type-to-jump; null for any other key, and with Ctrl, Alt or the Windows key held. */
private fun typedChar(event: KeyEvent): Char? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    val code = event.utf16CodePoint
    if (code in 1..0xFFFE && Character.isLetterOrDigit(code)) return code.toChar()
    LETTER_KEYS.indexOf(event.key).takeIf { it >= 0 }?.let { return 'a' + it }
    DIGIT_KEYS.indexOf(event.key).takeIf { it >= 0 }?.let { return '0' + it }
    return null
}

/** What type-to-jump has typed so far on one grid, and when. */
private class TypeAhead {
    var text = ""
    var at = 0L
}

/** How many frames a card that is coming into view is waited for before focus is given up. */
private const val FOCUS_TRIES = 5

/** Where an arrow key moves the focus (spec 5.10: arrows move between cards); null for other keys and with a modifier held. */
private fun arrowDirection(event: KeyEvent): FocusDirection? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isShiftPressed || event.isMetaPressed) return null
    return when (event.key) {
        Key.DirectionUp -> FocusDirection.Up
        Key.DirectionDown -> FocusDirection.Down
        Key.DirectionLeft -> FocusDirection.Left
        Key.DirectionRight -> FocusDirection.Right
        else -> null
    }
}

/**
 * The two places focus passes through for a moment when Tab leaves a one-Tab-stop area: [before] its content for
 * Shift+Tab, [after] it for Tab. Only the one in use can take focus, and only while Tab leaves.
 */
private class TabExits {
    val before = FocusRequester()
    val after = FocusRequester()
    var open = 0

    /** Moves the focus out of the area, backwards with [back]; false when the exit is not there yet. */
    fun leave(back: Boolean, focus: FocusManager): Boolean {
        open = if (back) -1 else 1
        try {
            if (runCatching { (if (back) before else after).requestFocus() }.isFailure) return false
            // Nothing past the area (it is all there is): come back into it.
            if (!focus.moveFocus(if (back) FocusDirection.Previous else FocusDirection.Next)) {
                focus.moveFocus(if (back) FocusDirection.Next else FocusDirection.Previous)
            }
            return true
        } finally {
            open = 0
        }
    }
}

@Composable
private fun TabExit(requester: FocusRequester, open: () -> Boolean) {
    Box(Modifier.focusRequester(requester).focusProperties { canFocus = open() }.focusTarget())
}

/** Tab and Shift+Tab leave the area at once through [exits]; the arrows move to the nearest control that way. */
private fun Modifier.oneTabStop(exits: TabExits, focus: FocusManager): Modifier =
    onPreviewKeyEvent { event ->
        event.type == KeyEventType.KeyDown && event.key == Key.Tab && !event.isCtrlPressed && !event.isAltPressed &&
            exits.leave(event.isShiftPressed, focus)
    }.onKeyEvent { event -> arrowDirection(event)?.let { focus.moveFocus(it) } ?: false }

/**
 * [content] as one Tab stop (spec 5.10: the grid, the actor list and the sort bar are one Tab stop each): Tab and
 * Shift+Tab leave it at once, and the arrows move between the controls in it. Keys a control uses itself (a text
 * field's arrows) stay with it.
 */
@Composable
internal fun OneTabStop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val exits = remember { TabExits() }
    Box(modifier.oneTabStop(exits, LocalFocusManager.current)) {
        TabExit(exits.before) { exits.open < 0 }
        content()
        TabExit(exits.after) { exits.open > 0 }
    }
}

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
    /** A button under [emptyText] ("Clear search"). */
    emptyAction: MenuAction? = null,
    footer: String? = null,
    letters: Boolean = true,
    /** The letters are for the actor cards only, and only when they fill more than about two screens. */
    lettersOnlyForActors: Boolean = false,
    /**
     * The key of the card to give the keyboard focus to ([HubState.focusTile]). It scrolls only when that card is out of
     * view; [onFocused] is called once it is done, and also when no card has that key (focus then stays where it is).
     */
    focusKey: String? = null,
    onFocused: () -> Unit = {},
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
        // As in M3: a range click keeps the anchor; every other click moves it.
        val keys = current.filter { pick(it) != null }.map { it.key }
        if (!(shift && anchor in keys && tile.key in keys)) anchor = tile.key
        if (result != null) tickExactly(result)
        return result != null
    }
    // The cards on screen, by key, so focus can be given to one of them.
    val requesters = remember { HashMap<String, FocusRequester>() }
    val scope = rememberCoroutineScope()
    val focused by rememberUpdatedState(onFocused)
    val ahead = remember { TypeAhead() }
    val exits = remember { TabExits() }
    val focusManager = LocalFocusManager.current
    /** Gives the keyboard focus to the card at [index], scrolling first only when it is out of view. */
    suspend fun land(index: Int) {
        if (scrollNeeded(index, state.layoutInfo.visibleItemsInfo.map { it.index })) state.scrollToItem(index)
        repeat(FOCUS_TRIES) {
            val requester = current.getOrNull(index)?.let { requesters[it.key] }
            if (requester != null && runCatching { requester.requestFocus() }.isSuccess) return
            withFrameNanos {}
        }
    }
    LaunchedEffect(focusKey) {
        val wanted = focusKey ?: return@LaunchedEffect
        // Once the grid has laid out the cards it shows now, so it is known which are on screen.
        withFrameNanos {}
        val index = current.indexOfFirst { it.key == wanted }
        if (index >= 0) land(index)
        focused()
    }
    Box(
        modifier.fillMaxSize().ctrlWheel { views.step(page, it) }.viewKeys(page).boxSelect(state) { inside, additive, started ->
            if (started) boxBase = if (additive) current.filter(::ticked).map { it.key }.toSet() else emptySet()
            tickExactly(boxBase + inside.map { it.toString() })
        }.onKeyEvent { event ->
            // Typing a name jumps to the first card it starts (spec 5.10); a pause of a second starts the name again.
            val char = typedChar(event) ?: return@onKeyEvent false
            val now = System.currentTimeMillis()
            ahead.text = typedText(ahead.text, ahead.at, char, now)
            ahead.at = now
            typeToJump(current.map { if (it is LabelTile) "" else it.title }, ahead.text)?.let { index -> scope.launch { land(index) } }
            true
        }.oneTabStop(exits, focusManager),
    ) {
        TabExit(exits.before) { exits.open < 0 }
        LazyVerticalGrid(
            columns = cells(view, prefs.columns, LocalAdaptive.current),
            state = state,
            modifier = Modifier.fillMaxSize().padding(end = 14.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(if (view.list) 8.dp else 14.dp),
        ) {
            if (tiles.isEmpty() && emptyText != null) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyNote(emptyText, action = emptyAction) }
            }
            items(tiles, key = { it.key }, span = { if (it is LabelTile) GridItemSpan(maxLineSpan) else GridItemSpan(1) }) { tile ->
                val requester = remember { FocusRequester() }
                DisposableEffect(tile.key) {
                    requesters[tile.key] = requester
                    onDispose { if (requesters[tile.key] === requester) requesters.remove(tile.key) }
                }
                TileView(tile, view, history, onPlay, ::clicked, Modifier.focusRequester(requester))
            }
            if (footer != null && tiles.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "total") { ListTotal(footer) }
            }
        }
        GridScrollbar(
            state,
            Modifier.align(Alignment.CenterEnd),
            labelFor = if (letters) ({ index ->
                tiles.getOrNull(index)
                    ?.takeIf { it !is LabelTile && (!lettersOnlyForActors || ((it as? FolderTile)?.actor == true && lettersWanted(tiles.count { t -> (t as? FolderTile)?.actor == true }, state.layoutInfo.visibleItemsInfo.size))) }
                    ?.title?.let(FolderLogic::sectionLetter)
            }) else null,
        )
        TabExit(exits.after) { exits.open > 0 }
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
    /** Lets the grid give the card the keyboard focus. */
    focus: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    fun cardKeys(items: () -> List<MenuAction>) = focus.cardKeys(items) { menu = true }
    fun modified(): Boolean = onModifiedClick(tile, window.keyboardModifiers.isCtrlPressed, window.keyboardModifiers.isShiftPressed)
    when (tile) {
        is LabelTile -> SectionLabel(tile.title, Modifier.padding(top = 4.dp))
        is FolderTile -> Box {
            FolderCard(
                tile.title, tile.detail, tile.coverKey, tile.coverVideos,
                onClick = { if (!modified()) { if (tile.selected != null) tile.onSelect?.invoke() else tile.onOpen() } },
                onLongClick = {
                    if (tile.selected != null) tile.onSelect?.invoke()
                    else if (tile.menu().isNotEmpty()) menu = true
                    else tile.onSelect?.invoke()
                },
                listMode = view.list,
                aspect = view.folderAspect,
                selected = tile.selected,
                modifier = cardKeys(tile.menu),
                actor = tile.actor,
                dot = tile.dot,
                initials = tile.initials,
                pictureModifier = tile.photoKey?.let { Modifier.movingPhoto(it) } ?: Modifier,
            )
            SkyMenu(menu, { menu = false }, if (menu) tile.menu() else emptyList())
        }
        is VideoTile -> Box {
            VideoCard(
                entry = tile.entry,
                progress = watchedFraction(history, tile.entry),
                selected = tile.selected,
                listMode = view.list,
                aspect = view.videoAspect,
                badges = tile.badges,
                modifier = cardKeys(tile.menu),
                onClick = {
                    if (!modified()) {
                        if (tile.selected != null) tile.onSelect?.invoke()
                        else tile.open?.invoke() ?: onPlay(tile.entry.file, tile.queue.map { it.file })
                    }
                },
                onLongClick = {
                    if (tile.selected != null) tile.onSelect?.invoke()
                    else if (tile.menu().isNotEmpty()) menu = true
                    else tile.onSelect?.invoke()
                },
            )
            SkyMenu(menu, { menu = false }, if (menu) tile.menu() else emptyList())
        }
    }
}

/** Keys on a focused card: its menu's shortcuts (F2, Alt+Enter), and Shift+F10 or the menu key for [openMenu]. */
internal fun Modifier.cardKeys(items: () -> List<MenuAction>, openMenu: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val shortcut = if (event.type == KeyEventType.KeyDown) cardKeyShortcut(event.key, event.isAltPressed, event.isShiftPressed) else null
    when {
        shortcut == null -> false
        shortcut == MENU_KEY -> items().isNotEmpty().also { if (it) openMenu() }
        else -> shortcutAction(items(), shortcut)?.let { it.action(); true } ?: false
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

/** Esc or Alt+← (spec 5.10): back, as the mouse back button. */
internal fun isBackShortcut(key: Key, alt: Boolean, ctrl: Boolean, shift: Boolean): Boolean =
    !ctrl && !shift && ((key == Key.Escape && !alt) || (key == Key.DirectionLeft && alt))

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
    /** What a click or Enter does on a card that is not selecting; the default plays it with the page's queue. */
    onOpen: ((WindowsMediaEntry) -> Unit)? = null,
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
                        modifier = Modifier.cardKeys({ menu(entry) }) { open = true },
                        onClick = {
                            if (modifiedClick(entry)) Unit
                            else if (selection.active) selection.toggle(entry.path)
                            else onOpen?.invoke(entry) ?: onPlay(entry.file, entries.map { it.file })
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
            title = "${confirm.label.removeSuffix("…")} $count ${if (count == 1) "item" else "items"}?",
            text = confirmText,
            confirmLabel = confirm.label.removeSuffix("…"),
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
    onSetWatched: (List<File>, Boolean) -> Unit,
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
        SkyButton("Mark as watched", { onSetWatched(chosen.map { it.file }, true); selection.clear() }, enabled = chosen.isNotEmpty(), primary = false, icon = SkyIcon.Check)
        SkyButton("Mark as unwatched", { onSetWatched(chosen.map { it.file }, false); selection.clear() }, enabled = chosen.isNotEmpty(), primary = false, icon = SkyIcon.Close)
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
