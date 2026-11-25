package com.skywing.desktop

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
 * The selection after a click on [clicked] among [keys] (in the order shown): Ctrl toggles it, Shift adds everything
 * from [anchor] (the last card clicked) to it. A plain click returns null: it opens or plays, as before.
 */
internal fun clickSelection(
    keys: List<String>,
    selected: Set<String>,
    anchor: String?,
    clicked: String,
    ctrl: Boolean,
    shift: Boolean,
): Set<String>? = when {
    shift -> {
        val from = keys.indexOf(anchor)
        val to = keys.indexOf(clicked)
        selected + if (from < 0 || to < 0) listOf(clicked) else keys.subList(minOf(from, to), maxOf(from, to) + 1)
    }
    ctrl -> if (clicked in selected) selected - clicked else selected + clicked
    else -> null
}

/** The text of a video's Details: the lines given, then its length when known and "Watched" when marked. */
internal fun videoDetails(lines: List<String>, length: Double?, watched: Boolean): String =
    (lines + listOfNotNull(length?.takeIf { it > 0 }?.let { "Length: " + formatClock(it) }, if (watched) "Watched" else null))
        .joinToString("\n")
