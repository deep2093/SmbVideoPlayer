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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.skywing.desktop.artwork.ArtworkMode
import com.skywing.desktop.artwork.ArtworkRepository
import com.skywing.desktop.artwork.ArtworkSettings
import com.skywing.desktop.artwork.ArtworkScope
import com.skywing.desktop.artwork.EndpointPolicy
import com.skywing.desktop.artwork.PortraitLookup
import com.skywing.desktop.artwork.PortraitSelection
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                SwitchRow("Portraits on folders", "Folders get a performer portrait as their cover (their default becomes the internet portrait).", s.portraitsOnFolders) { on ->
                    art.updateSettings { it.copy(portraitsOnFolders = on).withPortraitDefaults(folders = on, videos = false) }
                }
                SwitchRow("Portraits on videos", "Videos use the portrait of their name (their default becomes the internet portrait).", s.portraitsOnVideos) { on ->
                    art.updateSettings { it.copy(portraitsOnVideos = on).withPortraitDefaults(folders = false, videos = on) }
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
            val note = portraits.note
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
                        message = when {
                            !(s.portraitsOnFolders || s.portraitsOnVideos) -> "Turn on Portraits on folders or Portraits on videos first."
                            !art.keys.has() -> "Save your StashDB API key first (StashDB key, above)."
                            !s.portraitLookupWanted(ArtworkScope.SMB_FOLDER) && !s.portraitLookupWanted(ArtworkScope.VIRTUAL_FOLDER) ->
                                "Nothing to look up: set a default cover to Internet performer portrait, and choose Automatic or Manual instead of Custom."
                            else -> {
                                portraits.start(forced = true) { lookupTargets(library.all, organizer.data) }
                                null
                            }
                        }
                    }, enabled = s.adultConsent, icon = SkyIcon.Search)
                } else {
                    SkyButton("Stop", { portraits.cancel() }, primary = false, icon = SkyIcon.Stop)
                }
                SkyButton("Review suggestions ($waiting)", { reviewing = true }, enabled = waiting > 0, primary = false)
                SkyButton("Clear looked-up portraits", { confirm = "fetched" }, primary = false)
            }
            Note("Portraits that were found, and covers you chose, are never replaced by a later lookup. Clear them here, or change one in its Change cover.")
        }
    }
    ThumbnailsCard(library) { message = it }

    Card("Reset your choices") {
        Note("Covers, portraits and pictures you chose stay saved, even for folders and videos you take out, and come back when they return. Reset them here. Nothing on your drive changes.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SkyButton("Reset artwork", { confirm = "all" }, primary = false, icon = SkyIcon.Delete)
            SkyButton("Reset thumbnails", { confirm = "videos" }, primary = false, icon = SkyIcon.Delete)
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
            when (what) {
                "all" -> "Reset all artwork?"
                "videos" -> "Reset all thumbnails?"
                else -> "Clear looked-up portraits?"
            },
            when (what) {
                "all" -> "Every cover, collage and portrait of your folders, chosen or found, is removed. Folders go back to their automatic covers. Pictures chosen for videos are kept."
                "videos" -> "Every picture, frame and moving cover you chose for a video is removed, and its automatic thumbnail shows again. Folder covers are kept."
                else -> "Portraits found by lookups are removed. Covers you chose by hand stay."
            },
            if (what == "fetched") "Clear" else "Reset",
            onDismiss = { confirm = null },
        ) {
            scope.launch {
                val count = when (what) {
                    "all" -> art.clearFolderArtwork()
                    "videos" -> art.clearVideoArtwork()
                    else -> art.clearFetchedPortraits()
                }
                message = if (count > 0) "$count removed." else "Nothing to remove."
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

/**
 * Turning portraits on makes the internet portrait the default cover of that kind of item (when it still shows the
 * existing cover), so a lookup has something to fill. Other defaults the user picked are kept.
 */
private fun ArtworkSettings.withPortraitDefaults(folders: Boolean, videos: Boolean): ArtworkSettings {
    var next = this
    if (folders) {
        listOf(ArtworkScope.SMB_FOLDER, ArtworkScope.VIRTUAL_FOLDER).forEach { kind ->
            if (next.modeFor(kind) == ArtworkMode.EXISTING) next = next.withMode(kind, ArtworkMode.PORTRAIT)
        }
    }
    if (videos && next.videoMode == ArtworkMode.EXISTING) next = next.withMode(ArtworkScope.VIDEO, ArtworkMode.PORTRAIT)
    return next
}

/** Thumbnails, as on Android: how many are ready, waiting or failed, and Generate, Retry failed and Clear cache. */
@Composable
private fun ThumbnailsCard(library: LibraryModel, onMessage: (String) -> Unit) {
    val theme = LocalSkyTheme.current
    var showFailed by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val videos = remember(library.all) { library.all.map { it.file to it.modified } }
    val stats by produceState<ThumbnailStore.Stats?>(null, videos, tick) {
        while (true) {
            value = withContext(Dispatchers.IO) { ThumbnailStore.stats(videos) }
            delay(3_000)
        }
    }
    Column(Modifier.fillMaxWidth().skyGlass(RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Thumbnails", color = theme.ink, style = MaterialTheme.typography.titleMedium)
        val s = stats
        if (s == null) {
            Text("Counting…", color = theme.muted, style = MaterialTheme.typography.bodyMedium)
        } else {
            Text("${s.ready} ready  ·  ${s.waiting} waiting  ·  ${s.failed.size} failed", color = theme.ink, style = MaterialTheme.typography.bodyMedium)
            val all = (s.ready + s.waiting + s.failed.size).coerceAtLeast(1)
            if (ThumbnailStore.working && s.waiting > 0) {
                LinearProgressIndicator(progress = { s.ready.toFloat() / all }, modifier = Modifier.fillMaxWidth(), color = theme.accent)
            }
        }
        Text("Made in the background, cards on screen first. Each one is a frame about a tenth of the way into the video.", color = theme.muted, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SkyButton("Generate", { ThumbnailStore.prefetch(videos); onMessage("Making thumbnails in the background."); tick++ }, enabled = (s?.waiting ?: 0) > 0, icon = SkyIcon.Image)
            SkyButton("Retry failed", { ThumbnailStore.retryFailed(); ThumbnailStore.prefetch(videos); tick++ }, enabled = (s?.failed?.size ?: 0) > 0, primary = false)
            SkyButton("Clear cache", { onMessage("${ThumbnailStore.clearCache()} thumbnails deleted. They are made again when shown."); tick++ }, primary = false)
            if (ThumbnailStore.working) SkyButton("Stop", { ThumbnailStore.stop(); tick++ }, primary = false, icon = SkyIcon.Stop)
        }
        if (s != null && s.failed.isNotEmpty()) {
            SkyButton(if (showFailed) "Hide failed thumbnails" else "View failed thumbnails", { showFailed = !showFailed }, primary = false)
            if (showFailed) s.failed.take(200).forEach { file ->
                Text(file.path, color = theme.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}
