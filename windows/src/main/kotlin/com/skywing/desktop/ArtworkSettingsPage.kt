package com.skywing.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.ArtworkScope
import com.skywing.desktop.artwork.EndpointPolicy
import com.skywing.desktop.artwork.PortraitLookup
import com.skywing.desktop.artwork.PortraitSelection
import java.io.File
import kotlinx.coroutines.launch

/**
 * Settings › Artwork, as on Android: default covers per kind of item, collage size, internet portraits (only after
 * consent, with your own StashDB key), and the tools: look up now, review suggestions, clear looked-up portraits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtworkPage(library: LibraryModel, organizer: Organizer, portraits: PortraitLookup) {
    val art = LocalCovers.current?.artwork ?: return
    val theme = LocalSkyTheme.current
    val scope = rememberCoroutineScope()
    val s = art.settings
    var consentAsk by remember { mutableStateOf(false) }
    var reviewing by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Card(title: String, content: @Composable () -> Unit) {
        Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = theme.ink, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }

    @Composable
    fun Note(text: String) = Text(text, color = theme.muted, style = MaterialTheme.typography.bodySmall)

    Card("Covers") {
        SwitchRow("Artwork", "Use covers you choose, moving covers, collages and portraits. Off shows plain video thumbnails.", s.enabled) { on ->
            art.updateSettings { it.copy(enabled = on) }
        }
        if (s.enabled) {
            ArtworkScope.entries.forEach { kind ->
                Text("Default for " + kind.label.lowercase(), color = theme.ink, style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    kind.defaultModes.forEach { mode ->
                        SkyChip(mode.label, s.modeFor(kind) == mode, { art.updateSettings { it.withMode(kind, mode) } })
                    }
                }
            }
            Note("A cover you choose for one item (Change cover in its menu) always wins over these defaults.")
            Text("Video collage tiles", color = theme.ink, style = MaterialTheme.typography.bodyMedium)
            SkyChipBar((2..6).map { it.toString() }, s.collageTiles - 2, { i -> art.updateSettings { it.copy(collageTiles = i + 2) } })
        }
    }

    if (s.enabled) {
        Card("Internet portraits") {
            Note("Performer portraits from StashDB, using your own API key. Only the names you search are sent. Nothing is sent until you allow it here.")
            SwitchRow("Allow internet portraits", "Names may be sent to StashDB, which lists adult performers.", s.adultConsent) { on ->
                if (on) consentAsk = true else art.updateSettings { it.copy(adultConsent = false) }
            }
            if (s.adultConsent) {
                SwitchRow("Portraits on folders", "Folders whose default is the internet portrait get one.", s.portraitsOnFolders) { on ->
                    art.updateSettings { it.copy(portraitsOnFolders = on) }
                }
                SwitchRow("Portraits on videos", "Videos whose default is the portrait use their name's portrait.", s.portraitsOnVideos) { on ->
                    art.updateSettings { it.copy(portraitsOnVideos = on) }
                }
                SwitchRow("Look up after scans", "Find portraits in the background after the library changes.", s.autoFetchOnScan) { on ->
                    art.updateSettings { it.copy(autoFetchOnScan = on) }
                }
                SwitchRow("Videos: first name only", "Search with the part of the file name before the first comma or hyphen.", s.videoUseFirstName) { on ->
                    art.updateSettings { it.copy(videoUseFirstName = on) }
                }
                SwitchRow("Folders: whole name", "Search with the whole folder name.", s.folderUseWholeName) { on ->
                    art.updateSettings { it.copy(folderUseWholeName = on) }
                }
                listOf(ArtworkScope.SMB_FOLDER, ArtworkScope.VIRTUAL_FOLDER).forEach { kind ->
                    Text("How portraits are chosen: " + kind.label.lowercase(), color = theme.ink, style = MaterialTheme.typography.bodyMedium)
                    SkyChipBar(PortraitSelection.entries.map { it.label }, s.selectionFor(kind).ordinal, { i ->
                        art.updateSettings { it.withSelection(kind, PortraitSelection.entries[i]) }
                    })
                    Note(s.selectionFor(kind).detail)
                }
            }
        }

        Card("StashDB key") {
            var hasKey by remember { mutableStateOf(art.keys.has()) }
            var typed by remember { mutableStateOf("") }
            Note(if (hasKey) "A key is saved, encrypted for your Windows account." else "No key saved. Get one from your StashDB account page.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkyField(typed, { typed = it }, "API key", Modifier.weight(1f), secret = true)
                SkyButton("Save", {
                    hasKey = art.keys.save(typed)
                    message = if (hasKey) "Key saved." else "The key couldn't be saved."
                    typed = ""
                }, enabled = typed.isNotBlank())
                SkyButton("Remove", { art.keys.clear(); hasKey = false; message = "Key removed." }, enabled = hasKey, primary = false)
            }
            var endpoint by remember { mutableStateOf(s.customEndpoint) }
            Note("Optional: your own StashDB-compatible address (https). Empty uses stashdb.org.")
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SkyField(endpoint, { endpoint = it }, "https://…/graphql", Modifier.weight(1f))
                SkyButton("Use", {
                    val text = endpoint.trim()
                    if (text.isEmpty() || EndpointPolicy.isAllowed(text)) {
                        art.updateSettings { it.copy(customEndpoint = text) }
                        message = if (text.isEmpty()) "Using stashdb.org." else "Address saved."
                    } else {
                        message = "That address isn't allowed. Use an https address."
                    }
                }, primary = false)
            }
        }

        Card("Tools") {
            val progress = portraits.progress
            val note = remember(progress, art.revision) { art.preferences.lastNote }
            val waiting = remember(art.revision) { art.reviewQueue().size }
            if (progress != null) {
                Text("Looking up ${progress.first} of ${progress.second}…", color = theme.ink, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (progress.second == 0) 0f else progress.first.toFloat() / progress.second },
                    modifier = Modifier.fillMaxWidth(),
                    color = theme.accent,
                )
            }
            note?.let { Note(it) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (progress == null) {
                    SkyButton("Look up portraits now", {
                        portraits.start(forced = true) { lookupTargets(library.all, organizer.data) }
                        message = if (s.adultConsent) null else "Allow internet portraits first."
                    }, enabled = s.adultConsent, icon = SkyIcon.Search)
                } else {
                    SkyButton("Stop", { portraits.cancel() }, primary = false, icon = SkyIcon.Stop)
                }
                SkyButton("Review suggestions ($waiting)", { reviewing = true }, enabled = waiting > 0, primary = false)
                SkyButton("Clear looked-up portraits", { confirm = "fetched" }, primary = false)
                SkyButton("Reset all artwork", { confirm = "all" }, primary = false, icon = SkyIcon.Delete)
            }
            Note("Portraits that were found, and covers you chose, are never replaced by a later lookup. Clear them here, or change one in its Change cover.")
        }
    }
    message?.let { Text(it, color = theme.accent, style = MaterialTheme.typography.bodyMedium) }

    if (consentAsk) {
        ConfirmDialog(
            "Allow internet portraits?",
            "SkyWing will send folder and video names to StashDB (or your own address) to find performer portraits. " +
                "StashDB lists adult performers, so results can be adult content. Your key and file paths are never sent.",
            "Allow",
            onDismiss = { consentAsk = false },
        ) { art.updateSettings { it.copy(adultConsent = true) } }
    }
    confirm?.let { what ->
        ConfirmDialog(
            if (what == "all") "Reset all artwork?" else "Clear looked-up portraits?",
            if (what == "all") "Every cover you chose, every portrait and every moving cover goes back to automatic. Your video files are not touched."
            else "Portraits found by lookups are removed. Covers you chose by hand stay.",
            if (what == "all") "Reset" else "Clear",
            onDismiss = { confirm = null },
        ) {
            scope.launch {
                val count = if (what == "all") art.clearAll() else art.clearFetchedPortraits()
                message = "$count cleared."
            }
        }
    }
    if (reviewing) ReviewSuggestionsDialog(art, portraits, organizer) { reviewing = false }
}

/** A readable name for a cover key, for Review suggestions. */
private fun keyLabel(key: String, organizer: Organizer): String {
    val rest = key.substringAfter(':')
    return when (key.substringBefore(':')) {
        "virtual" -> FolderLogic.groupTitle(rest)
        "match" -> organizer.data.match.firstOrNull { it.id == rest }?.name ?: rest
        "original" -> File(rest).name.ifEmpty { rest }
        else -> rest
    }
}

/** One folder at a time: pick one of the portraits found for it, or skip it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewSuggestionsDialog(art: ArtworkRepository, portraits: PortraitLookup, organizer: Organizer, onDismiss: () -> Unit) {
    val theme = LocalSkyTheme.current
    val scope = rememberCoroutineScope()
    val queue = remember(art.revision) { art.reviewQueue() }
    val row = queue.firstOrNull()
    var busy by remember { mutableStateOf(false) }
    val trusted = remember(art.settings.customEndpoint) { EndpointPolicy.hostOf(art.settings.customEndpoint) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = theme.menu,
        titleContentColor = theme.ink,
        textContentColor = theme.ink,
        title = { Text(if (row == null) "No suggestions left" else keyLabel(row.key, organizer) + " (${queue.size} waiting)") },
        text = {
            if (row != null) {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pick the right person, or skip this folder.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        art.suggestions(row.key).forEach { candidate ->
                            Column(
                                Modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).skyClickable({
                                    busy = true
                                    scope.launch {
                                        try {
                                            portraits.apply(row.key, candidate)
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }, enabled = !busy).padding(4.dp),
                            ) {
                                RemoteThumb(candidate.previewUrl, trusted, Modifier.fillMaxWidth().aspectRatio(3f / 4f))
                                Text(candidate.name, color = theme.ink, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                candidate.disambiguation?.let { Text(it, color = theme.muted, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (row != null) SkyButton("Skip", { scope.launch { art.skipSuggestions(row.key) } }, enabled = !busy, primary = false)
        },
        dismissButton = { SkyButton("Close", onDismiss, primary = false) },
    )
}
