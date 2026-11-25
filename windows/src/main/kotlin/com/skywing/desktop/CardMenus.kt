package com.skywing.desktop

import java.io.File

/** What the video menu needs to know about the video. */
internal data class VideoMenuState(
    /** Where the video stopped, when it is half watched. */
    val resumeAt: Double?,
    val watched: Boolean,
    val artworkOn: Boolean,
    /** "Automatic" or "Chosen": where the picture comes from (step 2 names the exact source). */
    val pictureHint: String,
)

/** What each item of the video menu does. */
internal class VideoMenuActions(
    val play: () -> Unit,
    val playFromStart: () -> Unit,
    val setWatched: (Boolean) -> Unit,
    val changePicture: () -> Unit,
    val addToFolder: () -> Unit,
    val addToVirtual: () -> Unit,
    val rename: () -> Unit,
    val showInExplorer: () -> Unit,
    val details: () -> Unit,
    val select: () -> Unit,
)

/** The menu of a video card, made for a PC: dividers, keys on the right, and Resume first when it is half watched. */
internal fun videoMenu(state: VideoMenuState, actions: VideoMenuActions): List<MenuAction> = listOfNotNull(
    MenuAction(
        state.resumeAt?.let { "Resume (${formatClock(it)})" } ?: "Play", SkyIcon.Play,
        shortcut = "Enter", action = actions.play,
    ),
    MenuAction("Play from start", SkyIcon.Play, action = actions.playFromStart),
    if (state.watched) {
        MenuAction("Mark as unwatched", SkyIcon.Check, dividerBefore = true) { actions.setWatched(false) }
    } else {
        MenuAction("Mark as watched", SkyIcon.Check, dividerBefore = true) { actions.setWatched(true) }
    },
    if (state.artworkOn) MenuAction("Change picture…", SkyIcon.Image, hint = state.pictureHint, dividerBefore = true, action = actions.changePicture) else null,
    MenuAction("Add to folder…", SkyIcon.Folder, dividerBefore = !state.artworkOn, action = actions.addToFolder),
    MenuAction("Add to virtual folder…", SkyIcon.Grid, action = actions.addToVirtual),
    MenuAction("Rename file…", SkyIcon.Edit, shortcut = "F2", action = actions.rename),
    MenuAction("Show in File Explorer", SkyIcon.Folder, dividerBefore = true, action = actions.showInExplorer),
    MenuAction("Details", SkyIcon.Info, shortcut = "Alt+Enter", action = actions.details),
    MenuAction("Select", SkyIcon.Check, action = actions.select),
)

/** The enabled item of [menu] shown with [shortcut] on its right, for the key that was pressed. */
internal fun shortcutAction(menu: List<MenuAction>, shortcut: String): MenuAction? =
    menu.firstOrNull { it.enabled && it.shortcut == shortcut }

/**
 * The selection after a click on [clicked] among [keys] (in the order shown), as in Windows: Shift with an [anchor] (the
 * last card clicked) selects exactly the range from it (Ctrl+Shift adds the range to [selected]); Ctrl, or Shift with
 * no anchor, toggles the card. A plain click returns null: it opens or plays.
 */
internal fun clickSelection(
    keys: List<String>,
    selected: Set<String>,
    anchor: String?,
    clicked: String,
    ctrl: Boolean,
    shift: Boolean,
): Set<String>? {
    val from = keys.indexOf(anchor)
    val to = keys.indexOf(clicked)
    return when {
        shift && from >= 0 && to >= 0 -> {
            val range = keys.subList(minOf(from, to), maxOf(from, to) + 1).toSet()
            if (ctrl) selected + range else range
        }
        ctrl || shift -> if (clicked in selected) selected - clicked else selected + clicked
        else -> null
    }
}

/** The text of a video's Details: the lines given, then its length when known and "Watched" when marked. */
internal fun videoDetails(lines: List<String>, length: Double?, watched: Boolean): String =
    (lines + listOfNotNull(length?.takeIf { it > 0 }?.let { "Length: " + formatClock(it) }, if (watched) "Watched" else null))
        .joinToString("\n")

/** Where a half-watched video stopped, or null. [history] is keyed by lower-cased path. */
internal fun resumePosition(history: Map<String, WindowsWatchRecord>, entry: WindowsMediaEntry): Double? =
    history[entry.path.lowercase(java.util.Locale.ROOT)]?.takeIf { it.duration > 0 && it.position > 0 && it.position < it.duration }?.position

/**
 * The menu of a video card on any library page, so it is the same everywhere. A step 1 call: the picture hint only
 * says whether a picture was chosen; step 2's dialog names the exact source. [select] null leaves Select out.
 */
internal fun libraryVideoMenu(
    entry: WindowsMediaEntry,
    queue: List<WindowsMediaEntry>,
    history: Map<String, WindowsWatchRecord>,
    marks: LibraryMarks,
    artwork: com.skywing.desktop.artwork.ArtworkRepository?,
    artworkOn: Boolean,
    onPlay: (File, List<File>) -> Unit,
    onResume: (file: File, position: Double, queue: List<File>, autoNext: Boolean) -> Unit,
    onSetWatched: (List<File>, Boolean) -> Unit,
    onRename: (File) -> Unit,
    changePicture: () -> Unit,
    addToFolder: () -> Unit,
    addToVirtual: () -> Unit,
    details: () -> Unit,
    select: (() -> Unit)?,
): List<MenuAction> {
    val files = queue.map { it.file }
    val at = resumePosition(history, entry)
    return videoMenu(
        VideoMenuState(
            resumeAt = at,
            watched = marks.isWatched(entry),
            artworkOn = artworkOn,
            pictureHint = if (artwork?.row(com.skywing.desktop.artwork.ArtworkKeys.video(entry.path)) == null) "Automatic" else "Chosen",
        ),
        VideoMenuActions(
            play = { if (at != null) onResume(entry.file, at, files, false) else onPlay(entry.file, files) },
            playFromStart = { onPlay(entry.file, files) },
            setWatched = { onSetWatched(listOf(entry.file), it) },
            changePicture = changePicture,
            addToFolder = addToFolder,
            addToVirtual = addToVirtual,
            rename = { onRename(entry.file) },
            showInExplorer = { showInExplorer(entry.file, select = true) },
            details = details,
            select = select ?: {},
        ),
    ).filter { select != null || it.label != "Select" }
}

/** The text of a video's Details; its length is read off the screen thread (the store loads its file on first use). */
internal suspend fun videoDetailsText(entry: WindowsMediaEntry, watched: Boolean): String {
    val length = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { ThumbnailStore.lengths.get(ThumbnailStore.keyOf(entry.path, entry.modified)) }.getOrNull()
    }
    return videoDetails(listOf(entry.path, formatSize(entry.size), entry.sourceName).filter { it.isNotBlank() }, length, watched)
}
