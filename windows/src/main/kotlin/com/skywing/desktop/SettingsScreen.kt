package com.skywing.desktop

import com.skywing.desktop.artwork.PortraitLookup

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.dp

private class SettingsPage(val title: String, val subtitle: String, val icon: SkyIcon)

private val PAGES = listOf(
    SettingsPage("Appearance", "Glass theme", SkyIcon.Palette),
    SettingsPage("Library", "Sources and what is indexed", SkyIcon.VideoLibrary),
    SettingsPage("Sync & scanning", "Automatic rescan and library scan", SkyIcon.Refresh),
    SettingsPage("Auto grouping", "How file names become folders", SkyIcon.Grid),
    SettingsPage("App layout", "Folder tabs, columns, list or grid", SkyIcon.Folder),
    SettingsPage("Artwork", "Covers, portraits and thumbnails", SkyIcon.Image),
    SettingsPage("Playback", "Keyboard shortcuts", SkyIcon.Keyboard),
    SettingsPage("About", "SkyWing for Windows", SkyIcon.Info),
)

/**
 * Settings as a short list of categories; each opens as its own page that slides in with a back arrow,
 * the same as Settings on Android.
 */
@Composable
internal fun SettingsScreen(
    current: SkyTheme,
    onTheme: (SkyTheme) -> Unit,
    library: LibraryModel,
    organizer: Organizer,
    portraits: PortraitLookup,
    sync: LibrarySync,
    onOpenFiles: () -> Unit,
) {
    var open by remember { mutableStateOf<String?>(null) }
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
                val scroll = rememberScrollState()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        when (page) {
                            "Appearance" -> AppearancePage(current, onTheme)
                            "Library" -> LibraryPage(library, organizer, onOpenFiles)
                            "Auto grouping" -> GroupingPage(organizer)
                            "App layout" -> LayoutPage(organizer)
                            "Artwork" -> ArtworkPage(library, organizer, portraits)
                            "Sync & scanning" -> SyncPage(library, sync)
                            "Playback" -> PlaybackPage()
                            else -> AboutPage()
                        }
                    }
                    ColumnScrollbar(scroll, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: SkyIcon, title: String, subtitle: String, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    Row(
        Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).skyClickable(onClick).padding(horizontal = 18.dp, vertical = 16.dp),
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
private fun AppearancePage(current: SkyTheme, onTheme: (SkyTheme) -> Unit) {
    val theme = LocalSkyTheme.current
    Text("Theme", color = theme.ink, style = MaterialTheme.typography.titleMedium)
    Text(
        "The same themes as the Android app. Aurora is vivid, Crystal is calm, AMOLED black and Midnight blue are solid, " +
            "and the Adaptive themes take their colours from the covers on screen.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        skyThemes.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                pair.forEach { option -> ThemeChoice(option, option.id == current.id) { onTheme(option) } }
            }
        }
    }
}

@Composable
private fun ThemeChoice(option: SkyTheme, selected: Boolean, onClick: () -> Unit) {
    val theme = LocalSkyTheme.current
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(250.dp).skyClickable(onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.fillMaxWidth().height(140.dp).clip(shape)
                .drawBehind { drawSkyWallpaper(option) }
                .border(if (selected) 3.dp else 1.dp, if (selected) option.accent else option.ink.copy(alpha = 0.2f), shape)
                .padding(12.dp),
        ) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.width(44.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(option.panelTint()))
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

@Composable
private fun LibraryPage(library: LibraryModel, organizer: Organizer, onOpenFiles: () -> Unit) {
    val theme = LocalSkyTheme.current
    val pc = library.all.count { it.sourceType == "PC" }
    val network = library.all.count { it.sourceType == "SMB" }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${library.all.size} videos in your library", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("$pc on this PC  ·  $network on network shares", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        val data = organizer.data
        Text(
            "${data.own.size + data.match.size} custom folders  ·  ${data.sets.size} virtual folders",
            color = theme.muted, style = MaterialTheme.typography.bodyMedium,
        )
    }
    Row { SkyButton("Manage folders and shares", onOpenFiles, icon = SkyIcon.Folder) }
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
        Switch(
            checked = checked,
            onCheckedChange = onChange,
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
        "How names in your file names become folders. With all three on, \"Tom Cruise, Nicole Kidman - Movie\" goes into Tom Cruise and Nicole Kidman, " +
            "and \"Tom Cruise movie\" into Tom Cruise. Videos you filed by hand keep their folder.",
        color = theme.muted, style = MaterialTheme.typography.bodySmall,
    )
    listOf(
        Triple("Virtual folders", prefs.virtualRules) { rules: GroupingSwitches -> organizer.updatePrefs { it.copy(virtualRules = rules) } },
        Triple("Custom folders", prefs.customRules) { rules: GroupingSwitches -> organizer.updatePrefs { it.copy(customRules = rules) } },
    ).forEach { (title, rules, save) ->
        Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
            SwitchRow("Stop at the first hyphen", "Only the text before the first \"-\" names the folder.", rules.stopAtHyphen) { save(rules.copy(stopAtHyphen = it)) }
            SwitchRow("Split at commas", "Each name before the hyphen gets its own folder; the video is listed in each.", rules.splitAtCommas) { save(rules.copy(splitAtCommas = it)) }
            SwitchRow("First two words", "A name with no hyphen or comma uses its first two words.", rules.firstTwoWords) { save(rules.copy(firstTwoWords = it)) }
        }
    }
}

/** Settings › App layout: which Folders tabs show and their order, the number of columns, and list or grid. */
@Composable
private fun LayoutPage(organizer: Organizer) {
    val theme = LocalSkyTheme.current
    val prefs = organizer.data.prefs
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Folders tabs", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("Choose which tabs Folders shows and their order. At least one stays on. Hold a tab to rename it.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        FolderTabs.arranged(prefs.tabs).forEach { tab ->
            val on = tab in prefs.tabs
            val at = prefs.tabs.indexOf(tab)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(
                    checked = on,
                    enabled = !on || prefs.tabs.size > 1,
                    onCheckedChange = { tick -> organizer.updatePrefs { it.copy(tabs = if (tick) it.tabs + tab else it.tabs - tab) } },
                    colors = CheckboxDefaults.colors(checkedColor = theme.accent, checkmarkColor = theme.onAccent, uncheckedColor = theme.muted),
                )
                Text(prefs.tabName(tab), color = if (on) theme.ink else theme.muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                SkyButton("Earlier", {
                    organizer.updatePrefs { it.copy(tabs = it.tabs.toMutableList().apply { add(at - 1, removeAt(at)) }) }
                }, enabled = on && at > 0, primary = false)
                SkyButton("Later", {
                    organizer.updatePrefs { it.copy(tabs = it.tabs.toMutableList().apply { add(at + 1, removeAt(at)) }) }
                }, enabled = on && at in 0 until prefs.tabs.lastIndex, primary = false)
            }
        }
    }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Grid columns", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("Auto fits as many cards as the window allows. Cards keep a 16:9 shape.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        SkyChipBar(listOf("Auto", "2", "3", "4", "5"), if (prefs.columns in 2..5) prefs.columns - 1 else 0, { index ->
            organizer.updatePrefs { it.copy(columns = if (index == 0) 0 else index + 1) }
        })
        SwitchRow("Show as list", "One folder or video per line, with a small picture.", prefs.listMode) { list ->
            organizer.updatePrefs { it.copy(listMode = list) }
        }
    }
}

@Composable
private fun PlaybackPage() {
    val theme = LocalSkyTheme.current
    val shortcuts = listOf(
        "Space or K" to "Play / pause",
        "Left / Right" to "Back 5 s / forward 10 s",
        "Up / Down" to "Volume up / down",
        "M" to "Mute",
        "F" to "Full screen",
        "Esc" to "Leave full screen",
    )
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Automatic sync", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        SwitchRow(
            "Rescan automatically",
            if (sync.auto) "While SkyWing is open: when it starts if the last scan is over 12 hours old, then every 12 hours. Your names and covers are kept."
            else "Off. Use Refresh library to scan.",
            sync.auto,
        ) { sync.updateAuto(it) }
        Text(
            (if (sync.running) "Syncing…" else sync.status) + "  ·  last finished scan " + LibrarySync.ago(sync.lastSuccess),
            color = theme.muted, style = MaterialTheme.typography.bodySmall,
        )
    }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
