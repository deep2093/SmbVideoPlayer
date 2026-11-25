package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkKeys
import com.skywing.desktop.artwork.ArtworkRepository
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The Folders page, laid out like the Android app: Custom, Virtual and Original folders as tabs (which can be
 * renamed, hidden and reordered), every folder a card with a picture from its first video, and every page scrolling
 * top to bottom with a scroll bar. Holding a card (or a right click) opens its options; Select ticks several.
 */

private fun plural(count: Int, one: String, many: String = one + "s") = "$count ${if (count == 1) one else many}"

private fun leafOf(path: String) = path.trimEnd('\\').substringAfterLast('\\').ifBlank { path }

/** Where the folders added under This PC are kept between pages, while the store is read again. */
private const val LOCAL_ROOTS = "local-roots"

/**
 * Work that can take a moment on a big library runs off the screen thread. The last result is kept in [hub] under
 * [name], so a page shown again (after playing a video) starts with it and keeps its scroll position.
 */
@Composable
private fun <T> computed(hub: HubState, name: String, vararg keys: Any?, block: () -> T): T? =
    produceState(hub.lastResult<T>(name), *keys) {
        value = withContext(Dispatchers.Default) { block() }.also { hub.keepResult(name, it) }
    }.value

/* ---------- Dialogs opened from menus, shown once at the page root ---------- */

internal data class ConfirmSpec(val title: String, val text: String, val label: String, val action: () -> Unit)
internal data class NameSpec(val title: String, val initial: String, val hint: String, val label: String, val action: (String) -> Unit)
internal data class PickerSpec(
    val title: String,
    val explanation: String,
    val withFiles: Boolean,
    val folders: List<String>,
    val files: List<String>,
    val subfolderChoice: Boolean,
    val roots: List<String>? = null,
    val onDone: (List<String>, List<String>, Boolean) -> Unit,
)

/** The notice shown after Hide: [text], and what Undo does. */
internal class UndoSpec(val text: String, val onUndo: () -> Unit)

internal class FolderDialogs {
    var undo by mutableStateOf<UndoSpec?>(null)
    var filing by mutableStateOf<Pair<Boolean, List<String>>?>(null)
    var info by mutableStateOf<Pair<String, String>?>(null)
    var confirm by mutableStateOf<ConfirmSpec?>(null)
    var naming by mutableStateOf<NameSpec?>(null)
    var picker by mutableStateOf<PickerSpec?>(null)
    var cover by mutableStateOf<CoverTarget?>(null)
}

/** Videos shown by each Custom and Virtual folder, worked out together. */
internal class Memberships(
    val own: Map<String, List<WindowsMediaEntry>>,
    val match: Map<String, List<WindowsMediaEntry>>,
    val sets: Map<String, List<WindowsMediaEntry>>,
)

/** What every Folders page needs. [onOpenedActor] notes that an actor's page was opened (its new dot clears). */
internal class Folders(
    val library: LibraryModel,
    val organizer: Organizer,
    val hub: HubState,
    val history: Map<String, WindowsWatchRecord>,
    val onPlay: (File, List<File>) -> Unit,
    val onPlayAll: (List<File>) -> Unit,
    val onResume: (file: File, position: Double, queue: List<File>, autoNext: Boolean) -> Unit,
    val onRename: (File) -> Unit,
    val onSetWatched: (List<File>, Boolean) -> Unit,
    val marks: LibraryMarks,
    val scope: CoroutineScope,
    val artwork: ArtworkRepository?,
    val roots: List<String>,
    val dialogs: FolderDialogs,
    val members: Memberships?,
    val artworkOn: Boolean,
    val views: LibraryViews,
    val onOpenedActor: (String) -> Unit = {},
) {
    val data: OrganizerData get() = organizer.data
    val prefs: FolderPrefs get() = organizer.data.prefs
    val query: String get() = hub.query.trim()

    fun matches(name: String) = query.isEmpty() || name.contains(query, ignoreCase = true)

    fun play(list: List<WindowsMediaEntry>) {
        if (list.isNotEmpty()) onPlayAll(list.map { it.file })
    }

    /** Moves somewhere else on the Folders page; the search box starts empty there. */
    fun go(change: HubState.() -> Unit) {
        hub.change()
        hub.query = ""
    }

    fun byPaths(paths: Collection<String>): List<WindowsMediaEntry> {
        val wanted = paths.map { it.lowercase(Locale.ROOT) }.toHashSet()
        return library.all.filter { it.path.lowercase(Locale.ROOT) in wanted }
    }

    /** "Change cover" for a card, when artwork is on (Settings > Artwork). */
    fun coverAction(key: String, title: String, videos: List<WindowsMediaEntry>, video: WindowsMediaEntry? = null): MenuAction? =
        if (!artworkOn) null else MenuAction("Change cover", SkyIcon.Image) { dialogs.cover = CoverTarget(key, title, video, videos) }

    /** Clicking a video card or pressing Enter on it: a half-watched video goes on where it stopped. */
    fun open(entry: WindowsMediaEntry, queue: List<WindowsMediaEntry>) {
        val at = resumePosition(history, entry)
        if (at != null) onResume(entry.file, at, queue.map { it.file }, false) else onPlay(entry.file, queue.map { it.file })
    }

    /** The options of a video card (the same on every library page). */
    fun videoMenu(entry: WindowsMediaEntry, queue: List<WindowsMediaEntry>, page: String): List<MenuAction> = libraryVideoMenu(
        entry, queue, history, marks, artwork, artworkOn, onPlay, onResume, onSetWatched, onRename,
        changePicture = { dialogs.cover = videoCoverTarget(entry) },
        addToFolder = { dialogs.filing = false to listOf(entry.path) },
        addToVirtual = { dialogs.filing = true to listOf(entry.path) },
        details = { scope.launch { dialogs.info = entry.file.name to videoDetailsText(entry, marks.isWatched(entry)) } },
        select = { hub.toggleFolder(page, "file:" + entry.path) },
    )

    /** The video cards of [list]; [badges] adds the length, the watched tick and New (the actor page). */
    fun videoTiles(list: List<WindowsMediaEntry>, page: String, badges: ((WindowsMediaEntry) -> VideoBadges)? = null): List<VideoTile> = list.map { entry ->
        VideoTile(
            "file:" + entry.path.lowercase(Locale.ROOT), entry, list, { videoMenu(entry, list, page) },
            hub.ticked(page, "file:" + entry.path), { hub.toggleFolder(page, "file:" + entry.path) },
            open = { open(entry, list) },
            badges = badges?.invoke(entry),
        )
    }

    /** The card focus comes back to ([HubState.focusTile]) was focused, or is not on the grid that showed: forget it. */
    fun focusTaken() {
        hub.focusTile = null
    }

    /** Leaves an actor page with [leave] and ticks the card [key] on the grid [gridPage] there (Select on an actor page). */
    fun tickOnGrid(gridPage: String, key: String, leave: HubState.() -> Unit) {
        hub.tickAfterMove = gridPage to key
        go(leave)
    }

    /** Selection actions that work on any ticked videos. */
    fun videoActions(videos: () -> List<WindowsMediaEntry>): List<MenuAction> = listOf(
        MenuAction("Play", SkyIcon.Play) { play(videos()); hub.endFolderSelection() },
        MenuAction("Add to folder", SkyIcon.Folder) { dialogs.filing = false to videos().map { it.path } },
        MenuAction("Add to virtual folder", SkyIcon.Grid) { dialogs.filing = true to videos().map { it.path } },
        MenuAction("Mark as watched", SkyIcon.Check) { onSetWatched(videos().map { it.file }, true); hub.endFolderSelection() },
        MenuAction("Mark as unwatched", SkyIcon.Close) { onSetWatched(videos().map { it.file }, false); hub.endFolderSelection() },
    )

    /** What Rename says on an actor, instead of "folder". */
    private val actorRenameHint = "Only the name in SkyWing changes. Giving it the name of another actor joins the two. Your files keep their names."

    /**
     * One actor card of the page [page] ("set:<id>" or "own:<id>"). [containerName] is the folder it is in; [rename] is null
     * where the group has no rename. The menu is built when it opens, never for every card on every composition.
     */
    fun actorTile(
        entry: ActorEntry,
        page: String,
        coverKey: String,
        containerName: String,
        removal: ActorRemoval,
        onOpen: () -> Unit,
        rename: ((String) -> Unit)?,
        remove: () -> Unit,
    ): FolderTile {
        val group = entry.group
        val key = actorFocusKey(group.identity)
        return FolderTile(
            key, group.title, entry.info.detail, coverKey, emptyList(), onOpen,
            menu = { actorMenuOf(group.title, group.videos, coverKey, containerName, removal, false, onOpen, rename, remove) { hub.toggleFolder(page, key) } },
            selected = hub.ticked(page, key),
            onSelect = { hub.toggleFolder(page, key) },
            actor = true,
            dot = entry.info.dot,
            initials = actorInitials(group.title),
            photoKey = actorPhotoKey(actorKey(page, group.identity)),
        )
    }

    /**
     * The actor menu (spec 5.6) of [title] with [videos], on a card or, with [onActorPage], on the actor's own page. Play
     * all and Shuffle play [playList] (the actor page's grid list there); both are off when it is empty. Built when a menu
     * opens or a key arrives, never for every card on every composition.
     */
    fun actorMenuOf(
        title: String,
        videos: List<WindowsMediaEntry>,
        coverKey: String,
        containerName: String,
        removal: ActorRemoval,
        onActorPage: Boolean,
        onOpen: () -> Unit,
        rename: ((String) -> Unit)?,
        remove: () -> Unit,
        playList: List<WindowsMediaEntry> = videos,
        select: () -> Unit,
    ): List<MenuAction> = actorMenu(
        ActorMenuState(containerName, onActorPage, artworkOn, rename != null, actorFolders(videos), removal, canPlay = playList.isNotEmpty()),
        ActorMenuActions(
            open = onOpen,
            playAll = { play(playList) },
            shuffle = { play(playList.shuffled()) },
            changePhoto = { dialogs.cover = CoverTarget(coverKey, title, null, videos) },
            rename = { rename?.let { act -> dialogs.naming = NameSpec("Rename actor", title, actorRenameHint, "Rename", act) } },
            showFolder = { showInExplorer(File(it), select = false) },
            details = { scope.launch { dialogs.info = "Details" to actorDetailsText(title, videos, containerName) } },
            select = select,
            remove = remove,
        ),
    )

    /**
     * The actors of a grid with the lines they show, worked out off the screen thread. [source] is what [groups] is made
     * from (it is compared by identity in practice, so a long list is not compared again on every composition).
     */
    @Composable
    fun actorEntries(name: String, container: String, source: Any?, vararg keys: Any?, groups: () -> List<VirtualGroup>): List<ActorEntry> {
        val current = marks
        val worked = computed(hub, name, source, current, container, *keys) {
            val now = System.currentTimeMillis()
            groups().map { ActorEntry(it, actorInfo(actorKey(container, it.identity), it.videos, current, now)) }
        }
        // Until that is ready the cards show their counts only.
        return worked ?: groups().map { ActorEntry(it, ActorInfo(plural(it.videos.size, "video"), false)) }
    }

    /** Hides actors of the Virtual folder [setId] and offers Undo. */
    fun hideActors(setId: String, identities: List<String>) {
        organizer.hideSubfolders(setId, identities)
        dialogs.undo = UndoSpec("Hidden.") { organizer.restoreSubfolders(setId, identities) }
    }

    /** Asks, then takes the videos of actors out of the Custom folder [folder] (they are not hidden: they leave it). */
    fun takeActorsOut(folder: OwnFolder, title: String, paths: List<String>) {
        dialogs.confirm = ConfirmSpec("Take out $title?", takeOutText(folder), "Take out") { organizer.takeOutOfOwn(folder.id, emptyList(), paths) }
    }

    fun takeOutText(folder: OwnFolder) = "They are taken out of ${folder.name} only. Files on your disk are not touched."

    /** The buttons of a selection bar for [names] ([selectionActionsFor]), without Take out, which the bar asks about. */
    fun selectionButtons(names: List<String>, videos: () -> List<WindowsMediaEntry>, hide: () -> Unit): List<MenuAction> = (
        listOf(
            MenuAction("Play all", SkyIcon.Play) { play(videos()); hub.endFolderSelection() },
            MenuAction("Shuffle", SkyIcon.Queue) { play(videos().shuffled()); hub.endFolderSelection() },
            MenuAction("Hide", SkyIcon.Close, action = hide),
        ) + videoActions(videos)
        ).filter { it.label in names }

    fun ownTile(folder: OwnFolder, page: String?): FolderTile {
        val videos = members?.own?.get(folder.id).orEmpty()
        return FolderTile(
            key = "own:" + folder.id,
            title = folder.name,
            detail = if (folder.isEmpty) "Empty · click to choose" else plural(videos.size, "video"),
            coverKey = ArtworkKeys.ownFolder(folder.id),
            coverVideos = videos,
            onOpen = { if (folder.isEmpty) chooseOwn(folder) else go { ownId = folder.id; ownGroup = null; drivePath = null } },
            menu = { listOfNotNull(
                MenuAction("Open", SkyIcon.Folder) { go { ownId = folder.id; ownGroup = null; drivePath = null } },
                page?.let { MenuAction("Select", SkyIcon.Check) { hub.toggleFolder(it, "own:" + folder.id) } },
                MenuAction("Choose folders and files", SkyIcon.Edit) { chooseOwn(folder) },
                MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { play(videos) },
                coverAction(ArtworkKeys.ownFolder(folder.id), folder.name, videos),
                MenuAction("Rename", SkyIcon.Edit) {
                    dialogs.naming = NameSpec("Rename folder", folder.name, "Only the name in this app changes.", "Rename") { organizer.renameOwn(folder.id, it) }
                },
                MenuAction("Remove from Custom folders…", SkyIcon.Delete) {
                    dialogs.confirm = ConfirmSpec("Remove ${folder.name}?", "The folder is removed from Custom folders. Files on your disk are not touched.", "Remove") {
                        organizer.deleteOwn(folder.id)
                    }
                },
            ) },
            selected = page?.let { hub.ticked(it, "own:" + folder.id) },
            onSelect = page?.let { { hub.toggleFolder(it, "own:" + folder.id) } },
        )
    }

    fun chooseOwn(folder: OwnFolder) {
        dialogs.picker = PickerSpec(
            "Choose what ${folder.name} shows",
            "Tick folders (everything inside them is included) and single videos. Untick to take them out. Files on your disk are not changed.",
            withFiles = true, folders = folder.folders, files = folder.files, subfolderChoice = false,
        ) { dirs, files, _ -> organizer.setOwnMembers(folder.id, dirs, files) }
    }

    fun setTile(set: VirtualSet, page: String?): FolderTile {
        val videos = members?.sets?.get(set.id).orEmpty()
        return FolderTile(
            key = "set:" + set.id,
            title = set.name,
            detail = if (set.isEmpty) "Empty · click to choose" else plural(videos.size, "video"),
            coverKey = ArtworkKeys.virtualSet(set.id),
            coverVideos = videos,
            onOpen = { if (set.isEmpty) chooseSet(set) else go { setId = set.id; setGroup = null } },
            menu = { listOfNotNull(
                MenuAction("Open", SkyIcon.Folder) { go { setId = set.id; setGroup = null } },
                page?.let { MenuAction("Select", SkyIcon.Check) { hub.toggleFolder(it, "set:" + set.id) } },
                MenuAction("Choose folders and files", SkyIcon.Edit) { chooseSet(set) },
                MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { play(videos) },
                coverAction(ArtworkKeys.virtualSet(set.id), set.name, videos),
                MenuAction("Rename", SkyIcon.Edit) {
                    dialogs.naming = NameSpec("Rename virtual folder", set.name, "Only the name in this app changes.", "Rename") { organizer.renameSet(set.id, it) }
                },
                MenuAction("Delete virtual folder…", SkyIcon.Delete) {
                    dialogs.confirm = ConfirmSpec(
                        "Delete ${set.name}?",
                        "The virtual folder and its subfolders are removed from this app. Your videos and folders on disk are not touched.",
                        "Delete",
                    ) { organizer.deleteSet(set.id) }
                },
            ) },
            selected = page?.let { hub.ticked(it, "set:" + set.id) },
            onSelect = page?.let { { hub.toggleFolder(it, "set:" + set.id) } },
        )
    }

    fun chooseSet(set: VirtualSet) {
        dialogs.picker = PickerSpec(
            "Choose for ${set.name}",
            "Tick folders (everything inside them is included) and single videos. They are sorted into subfolders by the names in the file names. Untick to take something out. Files on your disk are not changed.",
            withFiles = true, folders = set.folders, files = set.files, subfolderChoice = false,
        ) { dirs, files, _ -> organizer.setSetMembers(set.id, dirs, files) }
    }

    fun chooseDirectSet(set: VirtualSet) {
        dialogs.picker = PickerSpec(
            "Add directly to ${set.name}",
            "Choose SMB folders or single videos to show on this folder's main page. Existing name-based subfolders stay as they are.",
            withFiles = true, folders = set.directFolders, files = set.directFiles, subfolderChoice = false,
            roots = roots.filter { it.startsWith("\\\\") },
        ) { dirs, files, _ -> organizer.setDirectSetMembers(set.id, dirs, files) }
    }

    fun addToSetGroup(set: VirtualSet, group: String) {
        dialogs.picker = PickerSpec(
            "Add to $group",
            "Choose more folders or videos. Videos already in the library will stay in $group even when their file names suggest another subfolder.",
            withFiles = true, folders = emptyList(), files = emptyList(), subfolderChoice = false,
        ) { dirs, files, _ -> organizer.addToSetGroup(set.id, group, dirs, files, library.all) }
    }
}

/* ---------- The page ---------- */

@Composable
internal fun FoldersScreen(
    library: LibraryModel,
    organizer: Organizer,
    hub: HubState,
    history: Map<String, WindowsWatchRecord>,
    onPlay: (File, List<File>) -> Unit,
    onPlayAll: (List<File>) -> Unit,
    onResume: (file: File, position: Double, queue: List<File>, autoNext: Boolean) -> Unit,
    onRename: (File) -> Unit,
    onSetWatched: (List<File>, Boolean) -> Unit,
    marks: LibraryMarks = LibraryMarks(),
    onOpenedActor: (String) -> Unit = {},
    /** Esc or Alt+← went back: the keyboard is in use, so the card focus lands on shows its ring (spec 5.4). */
    onKeyboardBack: () -> Unit = {},
) {
    val prefs = organizer.data.prefs
    val tabs = prefs.tabs
    LaunchedEffect(tabs) { if (hub.tab !in tabs) hub.tab = tabs.first() }

    // The places added in Files: folders on this PC and network shares. The folder choosers start there.
    // Read off the screen thread (B8): the store asks Windows for each folder's drive type.
    val localRoots by produceState(hub.lastResult<List<String>>(LOCAL_ROOTS) ?: emptyList(), library.revision) {
        value = withContext(Dispatchers.IO) { runCatching { LocalFolderStore().load() }.getOrDefault(emptyList()) }
            .also { hub.keepResult(LOCAL_ROOTS, it) }
    }
    val roots = remember(localRoots, library.shareRoots) { localRoots + library.shareRoots }
    val dialogs = remember { FolderDialogs() }
    val data = organizer.data
    val members = computed(hub, "members", data.own, data.match, data.sets, library.all) {
        Memberships(
            own = data.own.associate { it.id to FolderLogic.members(it.folders, it.files, library.all) },
            match = data.match.associate { it.id to FolderLogic.matchVideos(it, library.all) },
            sets = data.sets.associate { it.id to FolderLogic.members(it.folders + it.directFolders, it.files + it.directFiles, library.all) },
        )
    }
    val scope = rememberCoroutineScope()
    val artwork = LocalCovers.current?.artwork
    val f = Folders(
        library, organizer, hub, history, onPlay, onPlayAll, onResume, onRename, onSetWatched, marks, scope, artwork,
        roots, dialogs, members, artwork?.settings?.enabled == true, LocalLibraryViews.current, onOpenedActor,
    )

    val inside = when (hub.tab) {
        0 -> hub.ownId != null || hub.matchId != null
        else -> hub.setId != null
    }
    // Moving to another place ends a selection, like on Android.
    LaunchedEffect(hub.tab, hub.ownId, hub.ownGroup, hub.drivePath, hub.matchId, hub.setId, hub.setGroup) {
        hub.endFolderSelection()
        // Select on an actor page: the actor's card is ticked on the grid it went back to.
        hub.tickAfterMove?.let { (page, key) -> hub.toggleFolder(page, key) }
        hub.tickAfterMove = null
    }

    // Esc and Alt+← go back as the mouse back button does (spec 5.10); a search with text takes Esc first.
    val backKeys = Modifier.backKeys({ hub.navigateBack(organizer.data) }, onKeyboardBack)
    Column(Modifier.fillMaxSize().then(backKeys), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!inside) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PageTitle(
                    "Folders",
                    when {
                        library.notice != null -> library.notice!!
                        library.loading -> "Loading your library…"
                        library.all.isEmpty() -> "Add folders or a network share in Files"
                        else -> plural(library.all.size, "video")
                    },
                    Modifier.weight(1f),
                )
                FolderSearchField(hub, "Search " + prefs.tabName(hub.tab).lowercase(), Modifier.width(300.dp))
            }
            FolderTabsRow(f)
        }
        organizer.problem?.let { Text(it, color = LocalSkyTheme.current.accent, style = MaterialTheme.typography.bodySmall) }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (hub.tab) {
                0 -> when {
                    hub.matchId != null -> MatchPage(f)
                    hub.ownId != null -> OwnPage(f)
                    else -> CustomList(f)
                }
                else -> if (hub.setId != null) SetPage(f) else VirtualList(f)
            }
            dialogs.undo?.let { spec ->
                key(spec) {
                    UndoNotice(
                        spec.text,
                        onUndo = { spec.onUndo(); dialogs.undo = null },
                        onGone = { if (dialogs.undo === spec) dialogs.undo = null },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                    )
                }
            }
        }
    }
    FolderDialogsHost(f)
}

/** The tabs: click to switch, hold (or right click) to rename, hide or bring back a tab. One tab always stays. */
@Composable
private fun FolderTabsRow(f: Folders) {
    val prefs = f.prefs
    var menuFor by remember { mutableStateOf<Int?>(null) }
    Row(Modifier.skyPanel(RoundedCornerShape(50)).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        prefs.tabs.forEach { tab ->
            Box {
                SkyChip(
                    prefs.tabName(tab), f.hub.tab == tab,
                    onClick = { if (f.hub.tab != tab) { f.hub.tab = tab; f.hub.query = "" } },
                    onLongClick = { menuFor = tab },
                )
                SkyMenu(
                    menuFor == tab, { menuFor = null },
                    listOf(
                        MenuAction("Rename", SkyIcon.Edit) {
                            f.dialogs.naming = NameSpec(
                                "Rename tab", prefs.tabName(tab),
                                "Shown on the tab. Leave it as \"${FolderTabs.DEFAULTS[tab]}\" to keep the usual name.", "Rename",
                            ) { name -> f.organizer.updatePrefs { it.copy(tabNames = it.tabNames + (tab to name)) } }
                        },
                        MenuAction("Hide this tab", SkyIcon.Close, enabled = prefs.tabs.size > 1) {
                            f.organizer.updatePrefs { it.copy(tabs = it.tabs - tab) }
                        },
                    ) + FolderTabs.ALL.filterNot { it in prefs.tabs }.map { hidden ->
                        MenuAction("Show ${prefs.tabName(hidden)}", SkyIcon.Add) { f.organizer.updatePrefs { it.copy(tabs = it.tabs + hidden) } }
                    },
                )
            }
        }
        SkyIconButton(SkyIcon.Edit, "Rename ${prefs.tabName(f.hub.tab)} section", {
            val tab = f.hub.tab
            f.dialogs.naming = NameSpec(
                "Rename section", prefs.tabName(tab),
                "The usual name is ${FolderTabs.DEFAULTS[tab]}. Folders inside keep their own names.", "Rename",
            ) { name -> f.organizer.updatePrefs { it.copy(tabNames = it.tabNames + (tab to name)) } }
        }, box = 36.dp, glyph = 17.dp)
    }
}

/** A button with a word that opens a menu under it (the Add button). */
@Composable
private fun MenuTextButton(text: String, icon: SkyIcon, actions: List<MenuAction>, primary: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    Box {
        SkyButton(text, { open = true }, primary = primary, icon = icon)
        SkyMenu(open, { open = false }, actions)
    }
}

/** Grid or list, the same menu entry on every page; it changes every page, as the Settings switch does. */
private fun layoutActions(f: Folders): List<MenuAction> {
    val list = f.views.data.last.list
    return listOf(
        MenuAction(if (list) "Show as grid" else "Show as list", if (list) SkyIcon.Grid else SkyIcon.Queue) {
            f.views.setListEverywhere(!list)
            f.organizer.updatePrefs { it.copy(listMode = !list) }
        },
    )
}

private fun sortActions(f: Folders): List<MenuAction> = ACTOR_SORTS.map { (sort, label) ->
    val mark = if (f.prefs.sort == sort) (if (f.prefs.descending) "  ↓" else "  ↑") else ""
    MenuAction(label + mark, SkyIcon.Sort) { f.organizer.selectSort(sort) }
}

/** The row under the tabs on a list page: the page's buttons on the right, or the selection bar while selecting. */
@Composable
private fun ListToolbar(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.weight(1f))
        content()
    }
}

/* ---------- Custom folders ---------- */

@Composable
private fun CustomList(f: Folders) {
    val page = "custom"
    val own = f.data.own.filter { it.parentId == null && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val match = f.data.match.filter { !it.hidden && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val hiddenCount = f.data.match.count { it.hidden }
    val allKeys = own.map { "own:" + it.id } + match.map { "match:" + it.id }

    fun videosOf(keys: Set<String>): List<WindowsMediaEntry> = keys.flatMap { key ->
        when {
            key.startsWith("own:") -> f.members?.own?.get(key.removePrefix("own:")).orEmpty()
            key.startsWith("match:") -> f.members?.match?.get(key.removePrefix("match:")).orEmpty()
            else -> emptyList()
        }
    }.distinctBy { it.path.lowercase(Locale.ROOT) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            val ownChosen = keys.any { it.startsWith("own:") }
            FolderSelectionBar(
                count = keys.size,
                total = allKeys.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                actions = listOf(
                    MenuAction("Play", SkyIcon.Play) { f.play(videosOf(keys)); f.hub.endFolderSelection() },
                    // Folders you made yourself are removed rather than hidden.
                    MenuAction("Hide", SkyIcon.Close, enabled = !ownChosen) {
                        f.organizer.hideMatch(keys.filter { it.startsWith("match:") }.map { it.removePrefix("match:") })
                        f.hub.endFolderSelection()
                    },
                ),
                confirm = MenuAction("Remove", SkyIcon.Delete) {
                    keys.forEach { key ->
                        if (key.startsWith("own:")) f.organizer.deleteOwn(key.removePrefix("own:"))
                        if (key.startsWith("match:")) f.organizer.deleteMatch(key.removePrefix("match:"))
                    }
                    f.hub.endFolderSelection()
                },
            )
        } else {
            ListToolbar {
                MenuTextButton(
                    "Add", SkyIcon.Add,
                    listOf(
                        MenuAction("New folder", SkyIcon.Folder) {
                            f.dialogs.naming = NameSpec(
                                "New folder", "", "Give it any name, then choose the folders and videos it shows. Nothing on your disk changes.", "Create",
                            ) { name -> f.organizer.createOwn(name)?.let { id -> f.data.own.firstOrNull { it.id == id }?.let(f::chooseOwn) } }
                        },
                        MenuAction("Add drive folders", SkyIcon.Computer) {
                            f.dialogs.picker = PickerSpec(
                                "Add drive folders",
                                "Tick the folders to keep in Custom folders. Each one shows the videos inside it; switch off Include sub folders to show only the videos directly in it.",
                                withFiles = false, folders = emptyList(), files = emptyList(), subfolderChoice = true,
                            ) { dirs, _, sub -> f.organizer.addDriveFolders(dirs, sub) }
                        },
                        MenuAction("Create filename collection", SkyIcon.Search) {
                            f.dialogs.naming = NameSpec(
                                "Create automatic custom folder", "",
                                "The folder name is matched anywhere in video file names, ignoring case. New matches appear after the library is scanned again.",
                                "Create",
                            ) { f.organizer.createCollection(it) }
                        },
                    ),
                )
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = allKeys.isNotEmpty())
                ViewButton(page, actors = true, videos = false)
                SkyMenuButton(
                    SkyIcon.MoreVert, "Folder options",
                    layoutActions(f) + listOfNotNull(
                        if (hiddenCount > 0) MenuAction("Show hidden folders ($hiddenCount)", SkyIcon.Refresh) { f.organizer.showAllMatch() } else null,
                    ),
                )
            }
        }
        val tiles = own.map { f.ownTile(it, page) } + match.map { matchTile(f, it, page) }
        TileGrid(
            tiles = tiles,
            state = f.hub.grid(page),
            organizer = f.organizer,
            page = page,
            history = f.history,
            onPlay = f.onPlay,
            modifier = Modifier.weight(1f),
            emptyText = if (f.query.isNotEmpty()) "No custom folders found."
            else "Keep your favourite folders together. Use Add to make a folder with your own name, add folders from your drive, or collect videos by filename.",
            footer = plural(tiles.size, "folder"),
            focusKey = f.hub.focusTile,
            onFocused = f::focusTaken,
        )
    }
}

private fun matchTile(f: Folders, folder: MatchFolder, page: String): FolderTile {
    val videos = f.members?.match?.get(folder.id).orEmpty()
    val key = "match:" + folder.id
    return FolderTile(
        key = key,
        title = folder.name,
        detail = plural(videos.size, "video"),
        coverKey = ArtworkKeys.matchFolder(folder.id),
        coverVideos = videos,
        onOpen = { f.go { matchId = folder.id } },
        menu = { listOfNotNull(
            MenuAction("Open", SkyIcon.Folder) { f.go { matchId = folder.id } },
            MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
            MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
            f.coverAction(ArtworkKeys.matchFolder(folder.id), folder.name, videos),
            MenuAction("Rename", SkyIcon.Edit) {
                f.dialogs.naming = NameSpec(
                    "Rename ${folder.name}", folder.name,
                    if (folder.sourcePath == null) "Renaming also changes the automatic filename match." else "Change the display name. Your folder on disk keeps its name.",
                    "Rename",
                ) { f.organizer.renameMatch(folder.id, it) }
            },
            MenuAction("Merge into", SkyIcon.Grid) {
                f.dialogs.naming = NameSpec(
                    "Merge ${folder.name}", "",
                    "Files the matching videos under this name in Virtual folders. This folder stays available.", "Merge",
                ) { name -> f.organizer.assign(videos.map { it.path }, name) }
            },
            MenuAction("Details", SkyIcon.Info) {
                f.dialogs.info = folder.name to (
                    folder.sourcePath?.let { "${plural(videos.size, "indexed video")} in $it. " + if (folder.includeSubfolders) "Includes sub folders." else "This folder only." }
                        ?: "${plural(videos.size, "video")} with '${folder.term}' anywhere in the file name, ignoring case. The list updates by itself."
                    )
            },
            MenuAction("Hide", SkyIcon.Close) { f.organizer.hideMatch(listOf(folder.id)) },
            MenuAction("Remove from Custom folders…", SkyIcon.Delete) {
                f.dialogs.confirm = ConfirmSpec(
                    "Remove ${folder.name}?",
                    if (folder.sourcePath == null) "Remove this automatic collection? Your files and the videos filed by hand are kept."
                    else "Remove this folder from Custom folders? Files on your disk are kept.",
                    "Remove",
                ) { f.organizer.deleteMatch(folder.id) }
            },
        ) },
        selected = f.hub.ticked(page, key),
        onSelect = { f.hub.toggleFolder(page, key) },
    )
}

/** A self-filling Custom folder opened: its videos. */
@Composable
private fun MatchPage(f: Folders) {
    val folder = f.data.match.firstOrNull { it.id == f.hub.matchId }
    if (folder == null) {
        LaunchedEffect(Unit) { f.hub.matchId = null }
        return
    }
    val page = "match:" + folder.id
    val all = f.members?.match?.get(folder.id).orEmpty()
    val videos = all.filter { f.matches(it.file.name) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            VideoSelectionBar(f, page, videos)
        } else {
            FolderHeader(folder.name, plural(all.size, "video"), { f.go { matchId = null } }) {
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = videos.isNotEmpty())
                FolderSearchField(f.hub, "Search here", Modifier.width(220.dp))
                ViewButton(page, actors = false, videos = true)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
                SkyButton("Play all", { f.play(videos) }, enabled = videos.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        TileGrid(
            f.videoTiles(videos, page), f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
            emptyText = if (f.members == null) "Loading…" else "No videos match yet.",
            footer = plural(videos.size, "video"),
            focusKey = f.hub.focusTile,
            onFocused = f::focusTaken,
        )
    }
}

/** The selection bar of a page that lists videos. */
@Composable
internal fun VideoSelectionBar(f: Folders, page: String, videos: List<WindowsMediaEntry>, takeOut: MenuAction? = null) {
    val keys = f.hub.folderKeys
    FolderSelectionBar(
        count = keys.size,
        total = videos.size,
        onClose = { f.hub.endFolderSelection() },
        onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = videos.map { "file:" + it.path }.toSet() },
        actions = f.videoActions { f.byPaths(keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }) },
        confirm = takeOut,
    )
}

/** A Custom folder you filled yourself, opened: folders made inside it, its chosen folders, then its single videos. */
@Composable
private fun OwnPage(f: Folders) {
    val folder = f.data.own.firstOrNull { it.id == f.hub.ownId }
    if (folder == null) {
        LaunchedEffect(Unit) { f.hub.ownId = null; f.hub.ownGroup = null; f.hub.drivePath = null }
        return
    }
    val drive = f.hub.drivePath
    if (drive != null) {
        PhysicalFolderPage(
            f, drive,
            onBack = {
                val parent = File(drive).parent
                f.go { drivePath = if (folder.folders.any { it.equals(drive, ignoreCase = true) } || parent == null) null else parent }
            },
            onOpen = { path -> f.go { drivePath = path } },
        )
        return
    }
    val children = f.data.own.filter { it.parentId == folder.id }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val rules = f.prefs.customRules
    val assignments = f.data.assignments
    val content = computed(f.hub, "own:" + folder.id, folder, f.library.all, rules, assignments) {
        val singles = FolderLogic.members(emptyList(), folder.files, f.library.all)
        val dirCounts = folder.folders.associateWith { FolderLogic.members(listOf(it), emptyList(), f.library.all) }
        Triple(singles, FolderLogic.groupByNames(singles, rules, assignments), dirCounts)
    }
    val singles = content?.first.orEmpty()
    val groups = content?.second.orEmpty()
    val dirVideos = content?.third.orEmpty()
    val group = f.hub.ownGroup
    val page = "own:" + folder.id
    val everything = f.members?.own?.get(folder.id).orEmpty()
    // The name groups as actors, sorted like the Virtual folder's (a group is a VirtualGroup once it has a title).
    val sort = f.prefs.sort
    val descending = f.prefs.descending
    val actors = f.actorEntries("actors:own:" + folder.id, "own:" + folder.id, groups, sort, descending) {
        FolderLogic.sorted(groups.map { (identity, members) -> VirtualGroup(identity, FolderLogic.groupTitle(identity), members) }, sort, descending)
    }
    // A name group that disappears (its videos were taken out) returns to the folder.
    LaunchedEffect(content != null, group, groups.containsKey(group ?: "")) {
        if (content != null && group != null && !groups.containsKey(group)) f.hub.ownGroup = null
    }
    // The grid, or an actor's page with the photo moving between them (spec 5.4).
    ActorSwitch(group) { shown, entering ->
        if (shown != null) {
            // A name group is an actor's own page (spec 5.3); it waits until the groups are worked out.
            val container = "own:" + folder.id
            val refs = remember(groups, folder.id) {
                groups.map { (identity, members) -> ActorRef(identity, FolderLogic.groupTitle(identity), ArtworkKeys.virtualFolder(identity), actorKey(container, identity), members) }
            }
            val actor = refs.firstOrNull { it.identity == shown }
            if (actor == null) {
                if (content == null) EmptyNote("Loading…")
                return@ActorSwitch
            }
            ActorPage(
                actor, refs, f,
                onOpenActor = { identity -> f.go { ownGroup = identity } },
                onBack = { f.hub.focusTile = actorFocusKey(actor.identity); f.go { ownGroup = null } },
                menuOf = { ref, here, playList ->
                    f.actorMenuOf(
                        ref.title, ref.videos, ref.photoKey, folder.name, ActorRemoval.TAKE_OUT, here,
                        onOpen = { f.go { ownGroup = ref.identity } },
                        rename = null,
                        remove = { f.takeActorsOut(folder, ref.title, ref.videos.map { it.path }) },
                        playList = playList,
                    ) { f.tickOnGrid(container, "group:" + ref.identity) { ownGroup = null } }
                },
                add = MenuAction("Add more files/folders", SkyIcon.Add) { f.chooseOwn(folder) },
                focusKey = if (entering) f.hub.focusTile else null,
            )
            return@ActorSwitch
        }
        val searchFocus = remember { FocusRequester() }

        val childTiles = children.filter { f.matches(it.name) }.map { f.ownTile(it, null) }
        val dirTiles = folder.folders.filter { f.matches(leafOf(it)) }.map { path ->
            val videos = dirVideos[path].orEmpty()
            val key = "dir:$path"
            FolderTile(
                key, FolderLogic.originalLabel(leafOf(path)), plural(videos.size, "video"), driveCoverKey(path), videos,
                onOpen = { f.go { drivePath = path } },
                menu = { listOfNotNull(
                    MenuAction("Open", SkyIcon.Folder) { f.go { drivePath = path } },
                    MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
                    MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
                    MenuAction("Details", SkyIcon.Info) { f.dialogs.info = leafOf(path) to "$path\n${plural(videos.size, "video")}" },
                    f.coverAction(driveCoverKey(path), FolderLogic.originalLabel(leafOf(path)), videos),
                    MenuAction("Take out of this folder", SkyIcon.Close) { f.organizer.takeOutOfOwn(folder.id, listOf(path), emptyList()) },
                ) },
                selected = f.hub.ticked(page, key),
                onSelect = { f.hub.toggleFolder(page, key) },
            )
        }
        val rest: List<Tile> = if (folder.grouped) {
            actors.filter { f.matches(it.group.title) }.map { entry ->
                val identity = entry.group.identity
                f.actorTile(
                    entry, page, ArtworkKeys.virtualFolder(identity), folder.name, ActorRemoval.TAKE_OUT,
                    onOpen = { f.go { ownGroup = identity } },
                    rename = null,
                    remove = { f.takeActorsOut(folder, entry.group.title, entry.group.videos.map { it.path }) },
                )
            }
        } else {
            f.videoTiles(singles.filter { f.matches(it.file.name) }, page)
        }
        val tiles: List<Tile> = childTiles + dirTiles + rest

        fun pathsOf(keys: Set<String>): List<String> = keys.flatMap { key ->
            when {
                key.startsWith("group:") -> groups[key.removePrefix("group:")].orEmpty().map { it.path }
                key.startsWith("file:") -> listOf(key.removePrefix("file:"))
                else -> emptyList()
            }
        }
        fun videosOf(keys: Set<String>): List<WindowsMediaEntry> =
            (keys.filter { it.startsWith("dir:") }.flatMap { dirVideos[it.removePrefix("dir:")].orEmpty() } + f.byPaths(pathsOf(keys)))
                .distinctBy { it.path.lowercase(Locale.ROOT) }

        Column(Modifier.fillMaxSize().searchKey(searchFocus), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (f.hub.folderPage == page) {
                val keys = f.hub.folderKeys
                val selectable = tiles.filter { (it as? FolderTile)?.onSelect != null || it is VideoTile }.map { if (it is VideoTile) "file:" + it.entry.path else it.key }
                // Only actors: Play all, Shuffle and Take out… (a Custom folder has nothing to hide, they leave it).
                val names = selectionActionsFor(keys.map(::tileKindOf).toSet(), custom = true)
                FolderSelectionBar(
                    count = keys.size,
                    total = selectable.size,
                    onClose = { f.hub.endFolderSelection() },
                    onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = selectable.toSet() },
                    actions = f.selectionButtons(names, { videosOf(keys) }) {},
                    confirm = names.firstOrNull { it.startsWith("Take out") }?.let { label ->
                        MenuAction(label, SkyIcon.Close) {
                            f.organizer.takeOutOfOwn(folder.id, keys.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }, pathsOf(keys))
                            f.hub.endFolderSelection()
                        }
                    },
                    confirmText = f.takeOutText(folder),
                )
            } else {
                val folderCount = children.size + folder.folders.size + (if (folder.grouped) groups.size else 0)
                val subtitle = "${plural(folderCount, "folder")} · ${plural(everything.size, "video")}"
                FolderHeader(folder.name, subtitle, { f.go { ownId = folder.parentId; ownGroup = null } }) {
                    SkyIconButton(SkyIcon.Edit, "Rename ${folder.name}", {
                        f.dialogs.naming = NameSpec("Rename folder", folder.name, "Only the name in this app changes.", "Rename") { f.organizer.renameOwn(folder.id, it) }
                    })
                    SkyIconButton(SkyIcon.Add, "New folder inside ${folder.name}", {
                        f.dialogs.naming = NameSpec(
                            "New folder inside ${folder.name}", "",
                            "Give it any name, then choose the folders and videos it shows. Nothing on your disk changes.", "Create",
                        ) { name -> f.organizer.createOwn(name, parentId = folder.id)?.let { id -> f.data.own.firstOrNull { it.id == id }?.let(f::chooseOwn) } }
                    })
                    SkyButton("Add or remove files/folders", { f.chooseOwn(folder) }, primary = false, icon = SkyIcon.Edit)
                    if (singles.isNotEmpty()) {
                        SkyIconButton(
                            if (folder.grouped) SkyIcon.Queue else SkyIcon.Grid,
                            if (folder.grouped) "Show single videos one by one" else "Group single videos into folders by name",
                            { f.organizer.setOwnGrouped(folder.id, !folder.grouped) },
                        )
                    }
                    SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                    FolderSearchField(f.hub, "Search here", Modifier.width(200.dp).focusRequester(searchFocus))
                    ViewButton(page, actors = true, videos = true)
                    SkyMenuButton(SkyIcon.MoreVert, "Folder options", (if (folder.grouped) sortActions(f) else emptyList()) + layoutActions(f))
                    SkyButton("Play all", { f.play(everything) }, enabled = everything.isNotEmpty(), icon = SkyIcon.Play)
                }
            }
            if (folder.isEmpty && children.isEmpty()) {
                EmptyAction("Nothing here yet", "Choose folders and videos for this folder.", "Choose folders and files") { f.chooseOwn(folder) }
            } else {
                TileGrid(
                    tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                    emptyText = if (content == null) "Loading…" else "Nothing matches.",
                    footer = "${plural(tiles.count { it is FolderTile }, "folder")} · ${plural(tiles.count { it is VideoTile } + (if (folder.grouped) singles.size else 0), "video")}",
                    letters = !folder.grouped || sort == "NAME",
                    lettersOnlyForActors = folder.grouped,
                    focusKey = if (entering) f.hub.focusTile else null,
                    onFocused = f::focusTaken,
                )
            }
        }
    }
}

/** A message with one button, for an empty folder. */
@Composable
private fun EmptyAction(title: String, text: String, button: String, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text(text, color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        SkyButton(button, onClick, icon = SkyIcon.Edit)
    }
}

/* ---------- Virtual folders ---------- */

@Composable
private fun VirtualList(f: Folders) {
    val page = "virtual"
    val sets = f.data.sets.filter { it.parentId == null && f.matches(it.name) }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val allKeys = sets.map { "set:" + it.id }
    fun newSet() {
        f.dialogs.naming = NameSpec(
            "New virtual folder", "",
            "Give it any name, such as Hollywood Movies, then choose the folders it reads. Its subfolders are made from the names in your file names. Nothing on your disk changes.",
            "Create",
        ) { name -> f.organizer.createSet(name)?.let { id -> f.data.sets.firstOrNull { it.id == id }?.let(f::chooseSet) } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            FolderSelectionBar(
                count = keys.size,
                total = allKeys.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                actions = listOf(
                    MenuAction("Play", SkyIcon.Play) {
                        f.play(keys.flatMap { f.members?.sets?.get(it.removePrefix("set:")).orEmpty() }.distinctBy { it.path.lowercase(Locale.ROOT) })
                        f.hub.endFolderSelection()
                    },
                ),
                confirm = MenuAction("Delete", SkyIcon.Delete) {
                    keys.forEach { f.organizer.deleteSet(it.removePrefix("set:")) }
                    f.hub.endFolderSelection()
                },
                confirmText = "The virtual folders and their subfolders are removed from this app. Your videos and folders on disk are not touched.",
            )
        } else {
            ListToolbar {
                SkyButton("New virtual folder", { newSet() }, icon = SkyIcon.Add)
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = sets.isNotEmpty())
                ViewButton(page, actors = true, videos = false)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
            }
        }
        if (sets.isEmpty() && f.query.isEmpty()) {
            EmptyAction(
                "Make your own virtual folders",
                "Make a folder, such as Hollywood Movies. Choose the folders it reads and its subfolders are made from the names in your file names.",
                "New virtual folder",
            ) { newSet() }
        } else {
            TileGrid(
                sets.map { f.setTile(it, page) }, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                emptyText = "No matching folders.",
                footer = plural(sets.size, "folder"),
                focusKey = f.hub.focusTile,
                onFocused = f::focusTaken,
            )
        }
    }
}

/**
 * The inside of a Virtual folder: folders made inside it, then one subfolder per name found in the file names of
 * what it reads. A video with several names before the first hyphen is listed in each, never copied.
 */
@Composable
private fun SetPage(f: Folders) {
    val set = f.data.sets.firstOrNull { it.id == f.hub.setId }
    if (set == null) {
        LaunchedEffect(Unit) { f.hub.setId = null; f.hub.setGroup = null }
        return
    }
    val drive = f.hub.drivePath
    if (drive != null) {
        PhysicalFolderPage(f, drive,
            onBack = {
                val parent = File(drive).parent
                f.go { drivePath = if (parent == null || set.directFolders.any { it.equals(drive, true) }) null else parent }
            },
            onOpen = { path -> f.go { drivePath = path } },
        )
        return
    }
    val children = f.data.sets.filter { it.parentId == set.id }.sortedBy { it.name.lowercase(Locale.ROOT) }
    val videos = f.members?.sets?.get(set.id).orEmpty()
    val direct = computed(f.hub, "set-direct:" + set.id, set.directFolders, set.directFiles, f.library.all) {
        val all = f.library.all
        Triple(
            FolderLogic.members(set.directFolders, set.directFiles, all).map { it.path.lowercase(Locale.ROOT) }.toSet(),
            FolderLogic.members(emptyList(), set.directFiles, all),
            set.directFolders.map { path -> path to FolderLogic.members(listOf(path), emptyList(), all) },
        )
    }
    val directPaths = direct?.first.orEmpty()
    val directSingles = direct?.second.orEmpty()
    val directFolders = direct?.third.orEmpty()
    val prefs = f.prefs
    val assignments = f.data.assignments
    val groups = computed(f.hub, "set:" + set.id, set, videos, directPaths, prefs.virtualRules, assignments, prefs.sort, prefs.descending) {
        FolderLogic.sorted(FolderLogic.buildGroups(set, videos.filterNot { it.path.lowercase(Locale.ROOT) in directPaths }, prefs.virtualRules, assignments), prefs.sort, prefs.descending)
    }
    val group = f.hub.setGroup
    val current = groups?.firstOrNull { it.identity == group }
    // A subfolder that disappears (renamed into another, taken out, emptied by a scan) returns to the list. Renamed on its
    // own page, it stays open under the new name once the subfolders show it.
    LaunchedEffect(groups != null, group, current == null) {
        if (groups != null && group != null && current == null) {
            val renamed = f.hub.renamedActor?.takeIf { (from, to) -> from == group && groups.any { it.identity == to } }
            f.hub.renamedActor = null
            f.hub.setGroup = renamed?.second
        }
    }
    val page = "set:" + set.id
    val actors = f.actorEntries("actors:set:" + set.id, "set:" + set.id, groups) { groups.orEmpty() }
    // The grid, or an actor's page with the photo moving between them (spec 5.4).
    ActorSwitch(group) { shown, entering ->
        if (shown != null) {
            // A name subfolder is an actor's own page (spec 5.3); it waits until the subfolders are worked out.
            val container = "set:" + set.id
            val refs = remember(groups, set.id) {
                groups.orEmpty().map { ActorRef(it.identity, it.title, ArtworkKeys.virtualSub(set.id, it.identity), actorKey(container, it.identity), it.videos) }
            }
            val actor = refs.firstOrNull { it.identity == shown }
            if (actor == null) {
                if (groups == null) EmptyNote("Loading…")
                return@ActorSwitch
            }
            ActorPage(
                actor, refs, f,
                onOpenActor = { identity -> f.go { setGroup = identity } },
                onBack = { f.hub.focusTile = actorFocusKey(actor.identity); f.go { setGroup = null } },
                menuOf = { ref, here, playList ->
                    f.actorMenuOf(
                        ref.title, ref.videos, ref.photoKey, set.name, ActorRemoval.HIDE, here,
                        onOpen = { f.go { setGroup = ref.identity } },
                        rename = { name ->
                            val renamed = f.organizer.renameSubfolder(set.id, ref.identity, name)
                            // Renaming this page's actor keeps the page on it, unless the new name joins another actor.
                            if (here) f.hub.renamedActor = followRename(ref.identity, renamed, refs.map { it.identity })?.let { ref.identity to it }
                        },
                        remove = { f.hideActors(set.id, listOf(ref.identity)) },
                        playList = playList,
                    ) { f.tickOnGrid(container, "group:" + ref.identity) { setGroup = null } }
                },
                add = MenuAction("Add to this subfolder", SkyIcon.Add) { f.addToSetGroup(set, actor.title) },
                focusKey = if (entering) f.hub.focusTile else null,
            )
            return@ActorSwitch
        }
        val searchFocus = remember { FocusRequester() }

        val tiles: List<Tile> = sectioned(
            children.filter { f.matches(it.name) }.map { f.setTile(it, null) } +
                directFolders.filter { f.matches(leafOf(it.first)) }.map { (path, members) ->
                    FolderTile(
                        "direct:$path", FolderLogic.originalLabel(leafOf(path)), plural(members.size, "video"), driveCoverKey(path), members,
                        onOpen = { f.go { drivePath = path } },
                        menu = { listOfNotNull(
                            MenuAction("Open", SkyIcon.Folder) { f.go { drivePath = path } },
                            MenuAction("Play all", SkyIcon.Play, enabled = members.isNotEmpty()) { f.play(members) },
                            f.coverAction(driveCoverKey(path), leafOf(path), members),
                        ) },
                        selected = f.hub.ticked(page, "direct:$path"),
                        onSelect = { f.hub.toggleFolder(page, "direct:$path") },
                    )
                },
            actors.filter { f.matches(it.group.title) }.map { entry ->
                val identity = entry.group.identity
                f.actorTile(
                    entry, page, ArtworkKeys.virtualSub(set.id, identity), set.name, ActorRemoval.HIDE,
                    onOpen = { f.go { setGroup = identity } },
                    rename = { f.organizer.renameSubfolder(set.id, identity, it) },
                    remove = { f.hideActors(set.id, listOf(identity)) },
                )
            },
            f.videoTiles(directSingles.filter { f.matches(it.file.name) }, page),
        )

        Column(Modifier.fillMaxSize().searchKey(searchFocus), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (f.hub.folderPage == page) {
                val keys = f.hub.folderKeys
                val allKeys = tiles.mapNotNull { tile ->
                    when (tile) {
                        is VideoTile -> tile.key
                        is FolderTile -> tile.key.takeIf { tile.onSelect != null }
                        else -> null
                    }
                }
                val names = selectionActionsFor(keys.map(::tileKindOf).toSet(), custom = false)
                val selectedVideos = {
                    (keys.flatMap { k -> groups.orEmpty().firstOrNull { "group:" + it.identity == k }?.videos.orEmpty() } +
                        keys.flatMap { k -> directFolders.firstOrNull { "direct:" + it.first == k }?.second.orEmpty() } +
                        f.byPaths(keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }))
                        .distinctBy { it.path.lowercase(Locale.ROOT) }
                }
                FolderSelectionBar(
                    count = keys.size,
                    total = allKeys.size,
                    onClose = { f.hub.endFolderSelection() },
                    onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = allKeys.toSet() },
                    actions = f.selectionButtons(names, selectedVideos) {
                        f.hideActors(set.id, keys.filter { it.startsWith("group:") }.map { it.removePrefix("group:") })
                        f.hub.endFolderSelection()
                    },
                    confirm = if ("Take out" !in names) null else MenuAction("Take out", SkyIcon.Close) {
                        val removeDirs = keys.filter { it.startsWith("direct:") }.map { it.removePrefix("direct:") }.toSet()
                        val removeFiles = keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet()
                        if (removeDirs.isNotEmpty() || removeFiles.isNotEmpty()) f.organizer.setDirectSetMembers(set.id,
                            set.directFolders.filterNot { it in removeDirs }, set.directFiles.filterNot { it in removeFiles })
                        f.organizer.hideSubfolders(set.id, keys.filter { it.startsWith("group:") }.map { it.removePrefix("group:") })
                        f.hub.endFolderSelection()
                    },
                    confirmText = "The selected items are taken out of ${set.name}. Files on your disk are not touched.",
                )
            } else {
                val subtitle = "${plural(children.size + directFolders.size + (groups?.size ?: 0), "folder")} · ${plural(videos.size, "video")}"
                FolderHeader(set.name, subtitle, { f.go { setId = set.parentId; setGroup = null } }) {
                    SkyIconButton(SkyIcon.Edit, "Rename ${set.name}", {
                        f.dialogs.naming = NameSpec("Rename virtual folder", set.name, "Only the name in this app changes.", "Rename") { f.organizer.renameSet(set.id, it) }
                    })
                    SkyIconButton(SkyIcon.Add, "New folder inside ${set.name}", {
                        f.dialogs.naming = NameSpec(
                            "New folder inside ${set.name}", "",
                            "Give it any name, then choose the folders and videos it reads. Nothing on your disk changes.", "Create",
                        ) { name -> f.organizer.createSet(name, parentId = set.id)?.let { id -> f.data.sets.firstOrNull { it.id == id }?.let(f::chooseSet) } }
                    })
                    SkyButton("Add or remove files/folders", { f.chooseSet(set) }, primary = false, icon = SkyIcon.Edit)
                    SkyButton("Add SMB directly", { f.chooseDirectSet(set) }, primary = false, icon = SkyIcon.Add)
                    if (set.hidden.isNotEmpty()) {
                        SkyButton("Show hidden actors (${set.hidden.size})", { f.organizer.restoreSubfolders(set.id) }, primary = false, icon = SkyIcon.Refresh)
                    }
                    SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                    FolderSearchField(f.hub, "Search here", Modifier.width(200.dp).focusRequester(searchFocus))
                    ViewButton(page, actors = true, videos = true)
                    SkyMenuButton(SkyIcon.MoreVert, "Folder options", sortActions(f) + layoutActions(f))
                    SkyButton("Play all", { f.play(videos) }, enabled = videos.isNotEmpty(), icon = SkyIcon.Play)
                }
            }
            when {
                set.isEmpty && children.isEmpty() ->
                    EmptyAction("Nothing here yet", "Choose the folders and videos this virtual folder reads.", "Choose folders and files") { f.chooseSet(set) }
                !set.isEmpty && groups != null && direct != null && groups.isEmpty() && children.isEmpty() && directFolders.isEmpty() && directSingles.isEmpty() && f.members != null ->
                    EmptyAction(
                        "No videos found yet",
                        "Subfolders appear once the chosen folders are in your library. Scan them again in Files if something is missing.",
                        "Choose folders and files",
                    ) { f.chooseSet(set) }
                else -> TileGrid(
                    tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
                    emptyText = if (groups == null) "Loading…" else "Nothing matches.",
                    footer = "${plural(tiles.count { it is FolderTile }, "folder")} · ${plural(videos.size, "video")}",
                    letters = prefs.sort == "NAME",
                    lettersOnlyForActors = true,
                    focusKey = if (entering) f.hub.focusTile else null,
                    onFocused = f::focusTaken,
                )
            }
        }
    }
}

/* ---------- Drive folders (opened from a Custom folder) ---------- */

private fun physicalTile(
    f: Folders,
    page: String,
    path: String,
    title: String,
    detail: String,
    videos: List<WindowsMediaEntry>,
    onOpen: () -> Unit,
): FolderTile {
    val key = "dir:$path"
    return FolderTile(
        key, title, detail, driveCoverKey(path), videos, onOpen,
        menu = { listOfNotNull(
            MenuAction("Open", SkyIcon.Folder, action = onOpen),
            MenuAction("Select", SkyIcon.Check) { f.hub.toggleFolder(page, key) },
            MenuAction("Play all", SkyIcon.Play, enabled = videos.isNotEmpty()) { f.play(videos) },
            MenuAction("Add to Custom folders", SkyIcon.Add) { f.organizer.addDriveFolders(listOf(path), true) },
            MenuAction("Add to folder…", SkyIcon.Folder) { f.dialogs.filing = false to videos.map { it.path } },
            f.coverAction(driveCoverKey(path), title, videos),
            MenuAction("Details", SkyIcon.Info) { f.dialogs.info = leafOf(path) to "$path\n${plural(videos.size, "video")}" },
        ) },
        selected = f.hub.ticked(page, key),
        onSelect = { f.hub.toggleFolder(page, key) },
    )
}

/**
 * A real folder opened: its sub folders first, then its videos, all in one grid that scrolls top to bottom. Used by
 * Original folders and by a drive folder opened from a Custom folder.
 */
@Composable
private fun PhysicalFolderPage(f: Folders, path: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val page = "orig:" + path.lowercase(Locale.ROOT)
    val content = computed(f.hub, "orig:" + path.lowercase(Locale.ROOT), path, f.library.all, f.roots) {
        val all = f.library.all
        val contents = localOriginalFolderContents(f.roots, File(path), all.map { File(it.path) })
        val subs = contents.folders.map { it.absolutePath to FolderLogic.members(listOf(it.absolutePath), emptyList(), all) }
        Triple(subs, contents.videos.mapNotNull { f.library.byPath[it.path.lowercase(Locale.ROOT)] }, FolderLogic.members(listOf(path), emptyList(), all))
    }
    val subs = content?.first.orEmpty().filter { f.matches(leafOf(it.first)) }
    val videos = content?.second.orEmpty().filter { f.matches(it.file.name) }
    val everything = content?.third.orEmpty()
    val tiles: List<Tile> = buildList {
        if (subs.isNotEmpty() && videos.isNotEmpty()) add(LabelTile("label:folders", "Folders"))
        subs.forEach { (sub, members) ->
            add(physicalTile(f, page, sub, FolderLogic.originalLabel(leafOf(sub)), plural(members.size, "video"), members) { onOpen(sub) })
        }
        if (subs.isNotEmpty() && videos.isNotEmpty()) add(LabelTile("label:videos", "Videos"))
        addAll(f.videoTiles(videos, page))
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (f.hub.folderPage == page) {
            val keys = f.hub.folderKeys
            val selectable = tiles.mapNotNull { if (it is VideoTile) "file:" + it.entry.path else (it as? FolderTile)?.key }
            FolderSelectionBar(
                count = keys.size,
                total = selectable.size,
                onClose = { f.hub.endFolderSelection() },
                onSelectAll = { f.hub.folderPage = page; f.hub.folderKeys = selectable.toSet() },
                actions = f.videoActions {
                    (keys.filter { it.startsWith("dir:") }.flatMap { k -> subs.firstOrNull { "dir:" + it.first == k }?.second.orEmpty() } +
                        f.byPaths(keys.filter { it.startsWith("file:") }.map { it.removePrefix("file:") })).distinctBy { it.path.lowercase(Locale.ROOT) }
                } + MenuAction("Add to Custom folders", SkyIcon.Add, enabled = keys.any { it.startsWith("dir:") }) {
                    f.organizer.addDriveFolders(keys.filter { it.startsWith("dir:") }.map { it.removePrefix("dir:") }, true)
                    f.hub.endFolderSelection()
                },
            )
        } else {
            val name = File(path).name.ifBlank { path }
            val subtitle = "${plural(content?.first?.size ?: 0, "folder")} · ${plural(everything.size, "video")}"
            FolderHeader(name, subtitle, onBack) {
                SkyButton("Select", { f.hub.folderPage = page; f.hub.folderKeys = emptySet() }, primary = false, icon = SkyIcon.Check, enabled = tiles.isNotEmpty())
                FolderSearchField(f.hub, "Search here", Modifier.width(220.dp))
                ViewButton(page, actors = true, videos = true)
                SkyMenuButton(SkyIcon.MoreVert, "Folder options", layoutActions(f))
                SkyButton("Play all", { f.play(videos) }, enabled = videos.isNotEmpty(), icon = SkyIcon.Play)
            }
        }
        TileGrid(
            tiles, f.hub.grid(page), f.organizer, page, f.history, f.onPlay, Modifier.weight(1f),
            emptyText = if (content == null) "Loading…" else "No videos in this folder.",
            footer = "${plural(subs.size, "folder")} · ${plural(videos.size, "video")}",
            focusKey = f.hub.focusTile,
            onFocused = f::focusTaken,
        )
    }
}

/* ---------- Dialogs ---------- */

@Composable
private fun FolderDialogsHost(f: Folders) {
    val d = f.dialogs
    d.filing?.let { (virtual, paths) ->
        FileIntoFolderDialog(f.organizer, virtual, paths, onDismiss = { d.filing = null }, onDone = { f.hub.endFolderSelection() })
    }
    d.info?.let { (title, text) -> InfoDialog(title, text) { d.info = null } }
    d.cover?.let { target -> ChangeCoverDialog(target) { d.cover = null } }
    d.confirm?.let { spec -> ConfirmDialog(spec.title, spec.text, spec.label, { d.confirm = null }, spec.action) }
    d.naming?.let { spec -> NameDialog(spec.title, spec.initial, spec.label, { d.naming = null }, spec.action, hint = spec.hint) }
    d.picker?.let { spec ->
        DrivePicker(
            title = spec.title,
            explanation = spec.explanation,
            roots = spec.roots ?: f.roots,
            library = f.library.all,
            withFiles = spec.withFiles,
            initialFolders = spec.folders,
            initialFiles = spec.files,
            onDismiss = { d.picker = null },
            subfolderChoice = spec.subfolderChoice,
            confirmLabel = if (spec.subfolderChoice) "Add" else "Done",
            onDone = spec.onDone,
        )
    }
}
