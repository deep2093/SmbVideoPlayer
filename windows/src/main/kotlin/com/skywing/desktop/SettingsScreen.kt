package com.skywing.desktop

import com.skywing.desktop.artwork.PortraitLookup
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStatus

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import com.skywing.desktop.ui.adaptive.LocalAdaptive
import com.skywing.desktop.ui.theme.SkyRadius
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp

private class SettingsPage(val title: String, val subtitle: String, val icon: SkyIcon)

/** Which Settings page is open, and what Back does to it (M3 design brief 5.6). */
internal class SettingsNavigation {
    var open by mutableStateOf<String?>(null)

    /** Back inside Settings: closes the open page when the list is hidden; true when it did, false to leave it to the shell. */
    fun back(listShown: Boolean): Boolean {
        if (listShown || open == null) return false
        open = null
        return true
    }
}

private val PAGES = listOf(
    SettingsPage("Appearance", "Theme and colours", SkyIcon.Palette),
    SettingsPage("Library", "Sources and what is indexed", SkyIcon.VideoLibrary),
    SettingsPage("Sync & scanning", "Automatic rescan and library scan", SkyIcon.Refresh),
    SettingsPage("Auto grouping", "How file names become folders", SkyIcon.Grid),
    SettingsPage("App layout", "Folder tabs, columns, list or grid", SkyIcon.Folder),
    SettingsPage("Artwork", "Covers, portraits and thumbnails", SkyIcon.Image),
    SettingsPage("Playback", "Smoothing and keyboard shortcuts", SkyIcon.Keyboard),
    SettingsPage("About", "SkyWing for Windows", SkyIcon.Info),
)

/**
 * Settings (M3 design brief 5.6, decision O1). From 900 dp the category list stays at the left and the chosen page
 * shows beside it. Narrower, the list is a page of its own and each category slides in with a back arrow; Back,
 * Esc, the mouse back button and Settings again return to the list through [onBackHandler].
 */
@Composable
internal fun SettingsScreen(
    current: SkyTheme,
    onTheme: (SkyTheme) -> Unit,
    library: LibraryModel,
    organizer: Organizer,
    portraits: PortraitLookup,
    sync: LibrarySync,
    smoothingMode: SmoothingMode,
    smoothingStatus: SmoothingStatus,
    onSmoothingMode: (SmoothingMode) -> Unit,
    /** Receives what Back does here: closes the open page when the list is hidden, and says whether it did. */
    onBackHandler: ((() -> Boolean)?) -> Unit = {},
    /** "Reset window size" in Appearance. */
    onResetWindow: () -> Unit = {},
    onOpenFiles: () -> Unit,
) {
    val adaptive = LocalAdaptive.current
    val twoPanes = adaptive.settingsTwoPanes
    val nav = remember { SettingsNavigation() }
    var open by nav::open
    val listShown by rememberUpdatedState(twoPanes)
    DisposableEffect(nav) {
        onBackHandler { nav.back(listShown) }
        onDispose { onBackHandler(null) }
    }
    val pageContent: @Composable (String) -> Unit = { page ->
        val scroll = rememberScrollState()
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    "Appearance" -> AppearancePage(current, onTheme, onResetWindow)
                    "Library" -> LibraryPage(library, organizer, onOpenFiles)
                    "Auto grouping" -> GroupingPage(organizer)
                    "App layout" -> LayoutPage(organizer)
                    "Artwork" -> ArtworkPage(library, organizer, portraits)
                    "Sync & scanning" -> SyncPage(library, sync)
                    "Playback" -> PlaybackPage(smoothingMode, smoothingStatus, onSmoothingMode)
                    else -> AboutPage()
                }
            }
            ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
        }
    }
    if (twoPanes) {
        val shown = open ?: PAGES.first().title
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(adaptive.pagePadding)) {
            val scroll = rememberScrollState()
            Column(
                Modifier.width(adaptive.settingsListWidth).fillMaxHeight().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                PageTitle("Settings", modifier = Modifier.padding(bottom = 12.dp))
                PAGES.forEach { entry ->
                    SettingsListEntry(entry.icon, entry.title, entry.title == shown) { open = entry.title }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PageTitle(shown)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // A page's own state (its scroll) starts again when another category is chosen.
                    androidx.compose.runtime.key(shown) { pageContent(shown) }
                }
            }
        }
        return
    }
    AnimatedContent(
        targetState = open,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally(initialOffsetX = { it }) togetherWith slideOutHorizontally(targetOffsetX = { -it })
            } else {
                slideInHorizontally(initialOffsetX = { -it }) togetherWith slideOutHorizontally(targetOffsetX = { it })
            }
        },
    ) { page ->
        if (page == null) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    PageTitle("Settings", "Make SkyWing yours")
                    PAGES.forEach { entry ->
                        SettingsRow(entry.icon, entry.title, entry.subtitle) { open = entry.title }
                    }
                }
                ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkyIconButton(SkyIcon.Back, "Back to Settings", { open = null })
                    PageTitle(page)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { pageContent(page) }
            }
        }
    }
}

/** One category in the two-pane list: a tab with its icon and name, the open one selected, with the accent at 16 %. */
@Composable
internal fun SettingsListEntry(icon: SkyIcon, title: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalSkyTheme.current.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = LocalAdaptive.current.rowHeight).clip(RoundedCornerShape(SkyRadius.control))
            .background(if (selected) c.accentSubtle else Color.Transparent)
            .skyClickable(onClick, role = Role.Tab).semantics { this.selected = selected }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SkyIconView(icon, if (selected) c.accent else c.textSecondary, size = 20.dp)
        Text(title, color = c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
    }
}

@Composable
internal fun SettingsRow(icon: SkyIcon, title: String, subtitle: String, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    Row(
        Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).skyClickable(onClick, role = Role.Button)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SkyIconView(icon, theme.accent, size = 26.dp)
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
        SkyIconView(SkyIcon.ChevronRight, theme.muted)
    }
}

@Composable
private fun AppearancePage(current: SkyTheme, onTheme: (SkyTheme) -> Unit, onResetWindow: () -> Unit) {
    val theme = LocalSkyTheme.current
    Text("Theme", color = theme.ink, style = MaterialTheme.typography.titleMedium)
    Text(
        "Dark, Light, Midnight and OLED black are plain colours. Adaptive is Dark with its accent and a soft tint taken " +
            "from the covers on screen.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        skyThemes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { option -> ThemeChoice(option, option.id == current.id) { onTheme(option) } }
            }
        }
    }
    // Plan 6.8 rule 6.
    Text("Window at start", color = theme.ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
    Text(
        "Remember last opens SkyWing as you left it, on the same screen. Small, Medium and Large take 60, 75 and 90 % of the screen.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
    val store = remember { WindowPlacementStore() }
    var mode by remember { mutableStateOf(store.mode()) }
    SkySegmented(StartMode.entries.map { it.label }, StartMode.entries.indexOf(mode), { index ->
        mode = StartMode.entries[index]
        store.setMode(mode)
    })
    SkyButton("Reset window size", onResetWindow, primary = false)
}

@Composable
internal fun ThemeChoice(option: SkyTheme, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier.width(250.dp).skyClickable(onClick, role = Role.RadioButton).semantics { this.selected = selected },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.fillMaxWidth().height(140.dp).clip(shape)
                .drawBehind { drawSkyWallpaper(option) }
                .border(if (selected) 3.dp else 1.dp, if (selected) option.accent else option.ink.copy(alpha = 0.2f), shape)
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(option.surface))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(90.dp).height(12.dp).clip(RoundedCornerShape(50)).background(option.accent))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(option.card))
                        Box(Modifier.weight(1f).height(54.dp).clip(RoundedCornerShape(10.dp)).background(option.card))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (selected) SkyIconView(SkyIcon.Check, theme.accent, size = 18.dp)
            Text(option.label, color = if (selected) theme.accent else theme.ink, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Settings › Library: what the library holds, the actor page layout, and the buttons for shares and view settings. */
@Composable
internal fun LibraryPage(library: LibraryModel, organizer: Organizer, onOpenFiles: () -> Unit) {
    val theme = LocalSkyTheme.current
    val pc = library.all.count { it.sourceType == "PC" }
    val network = library.all.count { it.sourceType == "SMB" }
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${library.all.size} videos in your library", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("$pc on this PC  ·  $network on network shares", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        val data = organizer.data
        Text(
            "${data.own.size + data.match.size} custom folders  ·  ${data.sets.size} virtual folders",
            color = theme.muted, style = MaterialTheme.typography.bodyMedium,
        )
    }
    val views = LocalLibraryViews.current
    val layouts = listOf(ActorLayout.HEADER to "Header", ActorLayout.STUDIO to "Studio")
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Actor page layout", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text(
            "Header folds into a slim bar as the videos scroll. Studio keeps a small header with Continue watching and Up next.",
            color = theme.muted, style = MaterialTheme.typography.bodySmall,
        )
        SkyChipBar(layouts.map { it.second }, layouts.indexOfFirst { it.first == views.data.actorLayout }, { views.setLayout(layouts[it].first) })
    }
    var confirmReset by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SkyButton("Manage folders and shares", onOpenFiles, icon = SkyIcon.Folder)
        SkyButton("Reset view settings", { confirmReset = true }, primary = false)
    }
    if (confirmReset) {
        ConfirmDialog(
            "Reset view settings?",
            "Every folder, actor and page goes back to the standard view: landscape pictures, medium size, as a grid.",
            "Reset",
            { confirmReset = false },
        ) { views.reset() }
    }
}

/** A labelled on/off switch with a line of explanation, as on the Android settings pages. */
@Composable
internal fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val theme = LocalSkyTheme.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.bodyMedium)
            Text(detail, color = theme.muted, style = MaterialTheme.typography.bodySmall)
        }
        SkySwitch(
            checked = checked,
            onCheckedChange = onChange,
            name = title,
            colors = SwitchDefaults.colors(checkedTrackColor = theme.accent, checkedThumbColor = theme.onAccent),
        )
    }
}

/**
 * Settings › Auto grouping: the three switches that turn file names into folder names, set separately for Virtual
 * folders and for the single videos inside Custom folders. Changing them regroups at once; folders filed by hand stay.
 */
@Composable
private fun GroupingPage(organizer: Organizer) {
    val theme = LocalSkyTheme.current
    val prefs = organizer.data.prefs
    Text(
        "How names in your file names become folders. With all three on, \"Ava Stone, Mira Lake - Movie\" goes into Ava Stone and Mira Lake, " +
            "and \"Ava Stone movie\" into Ava Stone. Videos you filed by hand keep their folder.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
    listOf(
        Triple("Virtual folders", prefs.virtualRules) { rules: GroupingSwitches -> organizer.updatePrefs { it.copy(virtualRules = rules) } },
        Triple("Custom folders", prefs.customRules) { rules: GroupingSwitches -> organizer.updatePrefs { it.copy(customRules = rules) } },
    ).forEach { (title, rules, save) ->
        Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
            SwitchRow("Stop at the first hyphen", "Only the text before the first \"-\" names the folder.", rules.stopAtHyphen) { save(rules.copy(stopAtHyphen = it)) }
            SwitchRow("Split at commas", "Each name before the hyphen gets its own folder; the video is listed in each.", rules.splitAtCommas) { save(rules.copy(splitAtCommas = it)) }
            SwitchRow("First two words", "A name with no hyphen or comma uses its first two words.", rules.firstTwoWords) { save(rules.copy(firstTwoWords = it)) }
        }
    }
}

/** One Folders tab on Settings › App layout: shown or not, and moved earlier or later. */
@Composable
internal fun FolderTabRow(
    name: String,
    on: Boolean,
    canTurnOff: Boolean,
    canMoveEarlier: Boolean,
    canMoveLater: Boolean,
    onTick: (Boolean) -> Unit,
    onEarlier: () -> Unit,
    onLater: () -> Unit,
) {
    val theme = LocalSkyTheme.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Checkbox(
            checked = on,
            enabled = !on || canTurnOff,
            onCheckedChange = onTick,
            // Named like the folder picker's boxes, so a screen reader says which tab it shows.
            modifier = Modifier.accessibleDescription(name),
            colors = CheckboxDefaults.colors(checkedColor = theme.accent, checkmarkColor = theme.onAccent, uncheckedColor = theme.muted),
        )
        Text(name, color = if (on) theme.ink else theme.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        SkyButton("Earlier", onEarlier, enabled = canMoveEarlier, primary = false)
        SkyButton("Later", onLater, enabled = canMoveLater, primary = false)
    }
}

/** Settings › App layout: which Folders tabs show and their order, the number of columns, the picture size, and list or grid. */
@Composable
internal fun LayoutPage(organizer: Organizer) {
    val theme = LocalSkyTheme.current
    val prefs = organizer.data.prefs
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Folders tabs", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("Choose which tabs Folders shows and their order. At least one stays on. Hold a tab to rename it.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        FolderTabs.arranged(prefs.tabs).forEach { tab ->
            val on = tab in prefs.tabs
            val at = prefs.tabs.indexOf(tab)
            FolderTabRow(
                name = prefs.tabName(tab),
                on = on,
                canTurnOff = prefs.tabs.size > 1,
                canMoveEarlier = on && at > 0,
                canMoveLater = on && at in 0 until prefs.tabs.lastIndex,
                onTick = { tick -> organizer.updatePrefs { it.copy(tabs = if (tick) it.tabs + tab else it.tabs - tab) } },
                onEarlier = { organizer.updatePrefs { it.copy(tabs = it.tabs.toMutableList().apply { add(at - 1, removeAt(at)) }) } },
                onLater = { organizer.updatePrefs { it.copy(tabs = it.tabs.toMutableList().apply { add(at + 1, removeAt(at)) }) } },
            )
        }
    }
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Grid columns", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text(
            "Auto fits as many cards as the window allows. Each page's View menu sets the card shape.",
            color = theme.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        SkyChipBar(listOf("Auto", "2", "3", "4", "5"), if (prefs.columns in 2..5) prefs.columns - 1 else 0, { index ->
            organizer.updatePrefs { it.copy(columns = if (index == 0) 0 else index + 1) }
        })
        val views = LocalLibraryViews.current
        Text("Picture size", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text(
            "New pages start at this size. Each page's View menu can change its own.",
            color = theme.muted,
            style = MaterialTheme.typography.bodySmall,
        )
        val sizes = listOf(CardSizes.SMALL, CardSizes.NORMAL, CardSizes.LARGE)
        val sizeNames = listOf("Small", "Medium", "Large")
        // Shows the size new pages start at: the last size picked anywhere, so a page's View menu moves it too.
        SkyChipBar(sizeNames, sizeNames.indexOf(CardSizes.named(views.data.last.size)).coerceAtLeast(0), { index ->
            views.setSizeForNewPages(sizes[index])
        })
        // Shows the last choice made on any page's View menu; turning it on or off sets every page.
        SwitchRow("Show as list", "One folder or video per line, with a small picture.", views.data.last.list) { list ->
            views.setListEverywhere(list)
            organizer.updatePrefs { it.copy(listMode = list) }
        }
    }
}

@Composable
private fun PlaybackPage(mode: SmoothingMode, status: SmoothingStatus, onMode: (SmoothingMode) -> Unit) {
    val theme = LocalSkyTheme.current
    SmoothingOptions(mode, status, onMode)
    MotionPlaybackSettings()
    GraphicsForVideoSetting()
    ScreenSetting()
    val shortcuts = listOf(
        "Space or K" to "Play / pause",
        "Left / Right" to "Back 5 s / forward 10 s",
        "Up / Down" to "Volume up / down",
        "M" to "Mute",
        "F" to "Full screen",
        "Esc" to "Leave full screen",
    )
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shortcuts.forEach { (keys, what) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(keys, color = theme.accent, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
                Text(what, color = theme.ink, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    Text(
        "You can also drop a video onto the window, or choose SkyWing from Open with in Explorer.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun AboutPage() {
    val theme = LocalSkyTheme.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SkyMark(64.dp)
        Column {
            Text("SkyWing for Windows", color = theme.ink, style = MaterialTheme.typography.titleLarge)
            Text("Development preview", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Spacer(Modifier.size(4.dp))
    Text(
        "Videos play with the bundled libmpv player. SkyWing is free software under the GPL-3.0-or-later; " +
            "licences and sources are installed in the app's legal folder.",
        color = theme.muted, style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Settings › Sync & scanning, as on Android: automatic rescan, then the library scan with its numbers, Refresh
 * library and the problems of the last scan.
 */
@Composable
private fun SyncPage(library: LibraryModel, sync: LibrarySync) {
    val theme = LocalSkyTheme.current
    var showProblems by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Automatic sync", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            "Rescan automatically",
            if (sync.auto) "SkyWing checks for changes at every startup and every 12 hours while open. Your names and covers are kept."
            else "SkyWing checks for changes at every startup. Use Refresh library to check again while open.",
            sync.auto,
        ) { sync.updateAuto(it) }
        Text(
            (if (sync.running) "Syncing…" else sync.status) + "  ·  last finished scan " + LibrarySync.ago(sync.lastSuccess),
            color = theme.muted, style = MaterialTheme.typography.bodySmall,
        )
    }
    Column(Modifier.fillMaxWidth().skyPanel(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Library scan", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        val pc = library.all.count { it.sourceType == "PC" }
        val shares = library.all.filter { it.sourceType == "SMB" }
        Text(
            "${library.all.size} videos  ·  $pc on this PC  ·  ${shares.size} on ${shares.map { it.sourceId }.distinct().size} network share(s)",
            color = theme.ink, style = MaterialTheme.typography.bodyMedium,
        )
        if (sync.running) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = theme.accent)
            if (sync.current.isNotEmpty()) Text("Scanning: ${sync.current}", color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
        Text(
            "Scans the folders added under This PC and every network share in your library. A share or drive that can't be reached keeps its list.",
            color = theme.muted, style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Refresh library", { sync.refresh() }, enabled = !sync.running, icon = SkyIcon.Refresh)
            if (sync.running) SkyButton("Stop", { sync.stop() }, primary = false, icon = SkyIcon.Stop)
        }
        if (sync.problems.isNotEmpty()) {
            SkyButton(if (showProblems) "Hide scan errors" else "View scan errors (${sync.problems.size})", { showProblems = !showProblems }, primary = false)
            if (showProblems) sync.problems.forEach { problem ->
                Column {
                    Text(problem.place, color = theme.ink, style = MaterialTheme.typography.labelMedium)
                    Text(problem.message, color = theme.accent, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
