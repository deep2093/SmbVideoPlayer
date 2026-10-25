package com.skywing.desktop

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
import androidx.compose.material3.MaterialTheme
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageTitle("Settings", "Make SkyWing yours")
                PAGES.forEach { entry ->
                    SettingsRow(entry.icon, entry.title, entry.subtitle) { open = entry.title }
                }
            }
        } else {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SkyIconButton(SkyIcon.Back, "Back to Settings", { open = null })
                    PageTitle(page)
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    when (page) {
                        "Appearance" -> AppearancePage(current, onTheme)
                        "Library" -> LibraryPage(library, onOpenFiles)
                        "Playback" -> PlaybackPage()
                        else -> AboutPage()
                    }
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
        "The same four glass themes as the Android app. Aurora is vivid; Crystal is calm.",
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
private fun LibraryPage(library: LibraryModel, onOpenFiles: () -> Unit) {
    val theme = LocalSkyTheme.current
    val pc = library.all.count { it.sourceType == "PC" }
    val network = library.all.count { it.sourceType == "SMB" }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${library.all.size} videos in your library", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        Text("$pc on this PC  ·  $network on network shares", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        Text("${library.virtual.size} virtual folders are made from the video names.", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
    }
    Row { SkyButton("Manage folders and shares", onOpenFiles, icon = SkyIcon.Folder) }
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
