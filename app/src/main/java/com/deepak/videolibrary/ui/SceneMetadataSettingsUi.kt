package com.deepak.videolibrary.ui

import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.metadata.SceneMetadataClient
import com.deepak.videolibrary.metadata.SceneMetadataSettings
import com.deepak.videolibrary.metadata.MissingArtworkLookup
import com.deepak.videolibrary.metadata.SceneMetadataWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import androidx.compose.foundation.Image
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.produceState
import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.ImageUrlPolicy
import com.deepak.videolibrary.metadata.SceneMetadataRecord
import com.deepak.videolibrary.thumbs.ThumbStore
import org.json.JSONObject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.DisposableEffect
import com.deepak.videolibrary.metadata.SceneDurationLookup
import com.deepak.videolibrary.metadata.sceneDurationLabel
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.metadata.PosterMode
import com.deepak.videolibrary.metadata.ScenePosterWorker
import com.deepak.videolibrary.thumbs.CustomThumbs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Settings > Artwork, one sub-page at a time ([part]): "online" (matching on/off, provider, keys), "artwork" (the posters
 * and portraits switches), "advanced" (methods, recheck, clearing) and "review". Dialogs are shared by all parts.
 */
@Composable
internal fun SceneMetadataSettingsSection(vm: LibraryViewModel, onPreviewVideo: (VideoEntity) -> Unit = {}, part: String = "online") {
    val context = LocalContext.current
    val service = remember(vm, context) { ServiceLocator.get(context) }
    val prefs = service.sceneMetadataPreferences
    val options by prefs.settings.collectAsStateWithLifecycle()
    val keyRevision by vm.providerKeyRevision.collectAsStateWithLifecycle()
    var consentOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var clearPrompt by remember { mutableStateOf<String?>(null) }
    val actionScope = rememberCoroutineScope()

    val artwork by vm.artworkSettings.collectAsStateWithLifecycle()
    var portraitConsent by remember { mutableStateOf(false) }
    var pendingPortraitSection by remember { mutableStateOf<String?>(null) }
    MetadataPanel(part == "online", "Matching") {
        SwitchRow(
            "Match online",
            "Names and fingerprints only. Never paths or passwords.",
            options.enabled && options.allowedOnlineLookup,
        ) { on ->
            if (on) consentOpen = true
            else {
                prefs.update { it.copy(enabled = false, allowedOnlineLookup = false) }
                SceneMetadataWorker.cancel(context, service.libraryId)
            }
        }
        SettingsDivider()
        SettingsLabel("Try first for portraits")
        SettingsChoice(listOf("ThePornDB", "StashDB"),
            if (options.primary == SceneMetadataSettings.TPDB) 0 else 1) { index ->
            prefs.update { it.copy(primary = if (index == 0) SceneMetadataSettings.TPDB else SceneMetadataSettings.STASHDB) }
        }
        SettingsDivider()
        SettingsLabel("Provider keys")
        SceneKeyEntry(vm, SceneMetadataSettings.STASHDB, "StashDB", keyRevision)
        SceneKeyEntry(vm, SceneMetadataSettings.TPDB, "ThePornDB", keyRevision)
        Text("Stored encrypted on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    // One place for every automatic picture, each with a one-line note (details live in the consent pop-ups).
    MetadataPanel(part == "artwork", "Automatic artwork") {
        val online = options.enabled && options.allowedOnlineLookup
        // All three need Match online, so the note sits at the top and the switches grey out while it's off.
        if (!online) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Turn on Match online first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.ui.settingsSection = "Artwork/Online" }) { Text("Online matching") }
            }
            SettingsDivider()
        }
        SwitchRow("Scene posters", "On videos", options.videoPosters, enabled = online) { on ->
            prefs.update { it.copy(videoPosters = on) }; if (on) ScenePosterWorker.enqueue(context, service.libraryId)
        }
        SettingsDivider()
        // Movie posters and portraits go on folders: each opens a page to choose the folder sections (4 Nov 2026).
        val portraitsOn = artwork.enabled && artwork.adultConsent && artwork.portraitsOnFolders
        SectionsRow("Movie posters", sectionSummary(options.movieSections), online) {
            vm.ui.settingsSection = "Artwork/Automatic/Movies"
        }
        SettingsDivider()
        SectionsRow("Performer portraits", sectionSummary(if (portraitsOn) options.portraitSections else emptySet()), online) {
            vm.ui.settingsSection = "Artwork/Automatic/Portraits"
        }
    }

    MetadataPanel(part == "movies", "Show movie posters in") {
        val online = options.enabled && options.allowedOnlineLookup
        if (!online) Text("Turn on Match online first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FOLDER_SECTIONS.forEachIndexed { index, (section, label, note) ->
            if (index > 0) SettingsDivider()
            SwitchRow(label, note, section in options.movieSections, enabled = online) { on ->
                prefs.update { it.copy(movieSections = if (on) it.movieSections + section else it.movieSections - section) }
                if (on) com.deepak.videolibrary.artwork.MoviePosterWorker.enqueue(context, service.libraryId)
            }
        }
        Text("Found by the folder's name, then its videos' names.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    MetadataPanel(part == "portraits", "Show performer portraits in") {
        val online = options.enabled && options.allowedOnlineLookup
        val portraitsOn = artwork.enabled && artwork.adultConsent && artwork.portraitsOnFolders
        if (!online) Text("Turn on Match online first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FOLDER_SECTIONS.forEachIndexed { index, (section, label, note) ->
            if (index > 0) SettingsDivider()
            SwitchRow(label, note, portraitsOn && section in options.portraitSections, enabled = online) { on ->
                if (on) {
                    if (!artwork.adultConsent) {
                        // Asked once; the section is turned on when you allow it.
                        pendingPortraitSection = section
                        portraitConsent = true
                    } else {
                        prefs.update { it.copy(portraitSections = (if (portraitsOn) it.portraitSections else emptySet()) + section) }
                        vm.updateArtwork { it.copy(enabled = true, portraitsOnFolders = true, portraitsOnVideos = false) }
                    }
                } else {
                    val left = options.portraitSections - section
                    prefs.update { it.copy(portraitSections = left) }
                    if (left.isEmpty()) vm.updateArtwork { it.copy(portraitsOnFolders = false) }
                }
            }
        }
        Text("Found by the folder's name, for folders named after a person.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    MetadataPanel(part == "advanced", "Matching methods") {
        SwitchRow("File name only", "Lets the drive sleep. Less exact.", options.nameOnly) { enabled ->
            prefs.update { old -> old.copy(nameOnly = enabled) }
        }
        SwitchRow("Video pHash", "Experimental, slower. Always reviewed.", options.phash) { enabled ->
            prefs.update { old -> old.copy(phash = enabled) }
        }
        OutlinedButton(
            onClick = {
                val count = service.sceneMetadataStore.retryUnresolved()
                SceneMetadataWorker.enqueue(context, service.libraryId, manual = true)
                status = "Rechecking " + count + " unresolved entries."
            },
            enabled = options.allowedOnlineLookup,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Recheck unconfirmed") }
    }
    MetadataPanel(part == "advanced", "Clear saved results") {
        Text("Only SkyWing's own data. Your videos and chosen covers stay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { clearPrompt = "scene" }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear scene matches…")
        }
        OutlinedButton(onClick = { clearPrompt = "poster" }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear downloaded scene posters…")
        }
        OutlinedButton(onClick = { clearPrompt = "portrait" }, modifier = Modifier.fillMaxWidth()) {
            Text("Clear fetched performer portraits…")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (part == "review") MetadataPanel(true, "Scenes & movie posters") {
        Text("Matches that weren't clear enough to apply.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { vm.ui.reviewRequest++ }, modifier = Modifier.fillMaxWidth()) { Text("Review matches") }
    }

    if (clearPrompt != null) {
        val selected = clearPrompt!!
        val title = when (selected) {
            "scene" -> "Clear saved scene matches?"
            "poster" -> "Clear downloaded scene posters?"
            else -> "Clear downloaded performer portraits?"
        }
        AlertDialog(
            onDismissRequest = { clearPrompt = null },
            title = { Text(title) },
            text = { Text(
                "This removes only this library's saved " + when (selected) {
                    "scene" -> "scene identification results and suggestions. They may be looked up again."
                    "poster" -> "automatic scene posters and approval flags. Confirmed scene metadata stays saved."
                    else -> "automatically fetched performer portraits. Hand-picked covers remain saved."
                } + " Nothing on your SMB server is deleted."
            ) },
            confirmButton = {
                TextButton(onClick = {
                    clearPrompt = null
                    actionScope.launch {
                        val amount = when (selected) {
                            "scene" -> {
                                SceneMetadataWorker.cancel(context, service.libraryId)
                                withContext(Dispatchers.IO) { service.sceneMetadataStore.clearMatches() }
                            }
                            "poster" -> {
                                androidx.work.WorkManager.getInstance(context).cancelUniqueWork(
                                    ScenePosterWorker.workName(service.libraryId)
                                )
                                withContext(Dispatchers.IO) { service.scenePosters.clearDownloaded() }
                            }
                            else -> {
                                vm.clearFetchedPortraits()
                                -1
                            }
                        }
                        status = if (amount >= 0) "Removed $amount saved items." else "Portrait cleanup requested."
                    }
                }) { Text("Clear saved data") }
            },
            dismissButton = { TextButton(onClick = { clearPrompt = null }) { Text("Cancel") } },
        )
    }

    if (portraitConsent) AlertDialog(
        onDismissRequest = { portraitConsent = false; pendingPortraitSection = null },
        title = { Text("Allow online performer portraits?") },
        text = { Text("Only cleaned folder and performer names will be sent to the enabled providers to find pictures. SMB server details and your passwords are not shared.") },
        confirmButton = { TextButton(onClick = {
            portraitConsent = false
            val section = pendingPortraitSection ?: com.deepak.videolibrary.metadata.SceneMetadataSettings.SECTION_VIRTUAL
            pendingPortraitSection = null
            prefs.update { it.copy(portraitSections = it.portraitSections + section) }
            vm.updateArtwork {
                it.copy(enabled = true, adultConsent = true, portraitsOnFolders = true, portraitsOnVideos = false)
            }
        }) { Text("Allow portraits") } },
        dismissButton = { TextButton(onClick = { portraitConsent = false }) { Text("Cancel") } },
    )

    if (consentOpen) AlertDialog(
        onDismissRequest = { consentOpen = false },
        title = { Text("Match scenes and posters online?") },
        text = { Text("This sends cleaned video and folder names, and file fingerprints, to StashDB and ThePornDB to find scenes and movie posters. A fingerprint can uniquely identify a file. Your SMB server address, credentials and full file paths are not sent.") },
        confirmButton = { TextButton(onClick = {
            consentOpen = false
            prefs.update { it.copy(enabled = true, allowedOnlineLookup = true) }
            MissingArtworkLookup.start(context, service, recheck = false, manual = true)
        }) { Text("Allow lookups") } },
        dismissButton = { TextButton(onClick = { consentOpen = false }) { Text("Cancel") } },
    )
    // The review window itself is shown by MatchReviewPrompt at the top of the app, so the pop-up and this button
    // open the same one.
}

/** The folder sections movie posters and portraits can go in: saved id, name, one-line note. */
private val FOLDER_SECTIONS = listOf(
    Triple(com.deepak.videolibrary.metadata.SceneMetadataSettings.SECTION_ORIGINAL, "Original folders", "Movie folders inside the folders you added"),
    Triple(com.deepak.videolibrary.metadata.SceneMetadataSettings.SECTION_VIRTUAL, "Virtual folders", "Folders made from file names"),
    Triple(com.deepak.videolibrary.metadata.SceneMetadataSettings.SECTION_CUSTOM, "Custom folders", "Your folders and their name groups"),
)

/** "Off", or the chosen sections ("Original, Virtual"). */
private fun sectionSummary(sections: Set<String>): String =
    FOLDER_SECTIONS.filter { it.first in sections }.joinToString(", ") { it.second.substringBefore(' ') }.ifEmpty { "Off" }

/** A row that opens its own page, with what is chosen there underneath. */
@Composable
private fun SectionsRow(title: String, summary: String, enabled: Boolean, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Choose folders for $title", onClick = onOpen),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One flat section of an Artwork sub-page; shown only on its own page ([visible]). */
@Composable
private fun MetadataPanel(visible: Boolean, title: String, body: @Composable () -> Unit) {
    if (visible) SettingsStaticCard(title) { body() }
}

@Composable
private fun SceneKeyEntry(vm: LibraryViewModel, provider: String, label: String, revision: Int) {
    val context = LocalContext.current
    val service = remember(context) { ServiceLocator.get(context) }
    var key by remember(provider) { mutableStateOf("") }
    var result by remember(provider) { mutableStateOf<String?>(null) }
    var testing by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = remember(provider, revision) { vm.providerHasKey(provider) }
    var expanded by remember(provider) { mutableStateOf(false) }
    androidx.compose.material3.Surface(shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer, border = settingsCardBorder()) {
        Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { expanded = !expanded }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (saved) "Key saved" else "API key", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.material3.Icon(
                if (expanded) androidx.compose.material.icons.Icons.Filled.ExpandMore else androidx.compose.material.icons.Icons.Filled.ChevronRight,
                contentDescription = if (expanded) "Close $label" else "Open $label")
        }
    }
    if (expanded) {
        OutlinedTextField(
            value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            label = { Text(if (saved) "Key saved. Type to replace." else "API key") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                if (vm.saveProviderKey(provider, key)) { key = ""; result = "Key saved securely." }
                else result = "Couldn't save key. Check its format and device Keystore."
            }, enabled = key.isNotBlank()) { Text("Save key") }
            TextButton(onClick = {
                testing = true
                scope.launch {
                    result = try {
                        val ok = withContext(Dispatchers.IO) { SceneMetadataClient(service.providerKeys).test(provider) }
                        if (ok) "Connected successfully." else "The provider did not confirm this key."
                    } catch (e: CancellationException) { throw e
                    } catch (e: com.deepak.videolibrary.artwork.online.ProviderException) { "Provider error: " + e.failure.message }
                    catch (_: Exception) { "Connection failed. Check the key, network, and GraphQL provider settings." }
                    testing = false
                }
            }, enabled = saved && !testing) { Text(if (testing) "Testing…" else "Test") }
            if (saved) TextButton(onClick = { vm.clearProviderKey(provider); result = "Key removed." }) { Text("Remove") }
        }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * One candidate shown beside the local thumb. Nothing in this UI modifies the SMB file,
 * artwork preference, virtual-folder assignment or provider API.
 */
@Composable
private fun SceneMatchCandidate(
    service: ServiceLocator,
    record: SceneMetadataRecord,
    suggestion: JSONObject,
    onConfirm: () -> Unit,
    onReject: () -> Unit,
    vm: LibraryViewModel,
    onPreviewVideo: (VideoEntity) -> Unit,
) {
    val scene = suggestion.optJSONObject("scene") ?: return
    val method = suggestion.optString("method")
    val provider = when (suggestion.optString("provider")) {
        SceneMetadataSettings.STASHDB -> "StashDB"
        SceneMetadataSettings.TPDB -> "ThePornDB"
        else -> "Metadata provider"
    }
    val images = scene.optJSONArray("images")
    val url = (0 until (images?.length() ?: 0))
        .mapNotNull { images?.optJSONObject(it)?.optString("url") }
        .firstOrNull { ImageUrlPolicy.isAllowed(it) }
    val performers = scene.optJSONArray("performers")
    val names = (0 until (performers?.length() ?: 0))
        .mapNotNull { performers?.optJSONObject(it)?.optJSONObject("performer")?.optString("name") }
        .filter { it.isNotBlank() }.distinct()
    val studio = scene.optJSONObject("studio")?.optString("name").orEmpty()
    val date = scene.optString("release_date").ifBlank { scene.optString("date") }
    val durationSeconds = scene.optInt("duration", 0)
    val durationMs = durationSeconds.takeIf { it > 0 }?.toLong()?.times(1000L)
    val duration = if (durationMs != null) sceneDurationLabel(durationMs) else null
    val methodLabel = when (method) {
        "OSHASH" -> "File fingerprint: exact OSHash"
        "PHASH" -> "Video similarity: pHash (experimental)"
        else -> "Filename search: verify manually"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your video", style = MaterialTheme.typography.labelMedium)
                LocalSceneThumbnail(service, vm, record.path, onPreviewVideo)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$provider cover", style = MaterialTheme.typography.labelMedium)
                ProviderSceneThumbnail(url, durationMs)
            }
        }
        // Keep the actual indexed SMB file name visible for EVERY candidate, not only at
        // the top of the scrolling review list. User-supplied renamed names are preserved.
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Your SMB filename", style = MaterialTheme.typography.labelMedium)
            SelectionContainer {
                Text(
                    record.path.substringAfterLast('/').ifBlank { record.path },
                    style = MaterialTheme.typography.bodyMedium,
                    softWrap = true,
                )
            }
            val folder = record.path.substringBeforeLast('/', "")
            if (folder.isNotBlank()) {
                Text(
                    "Folder: $folder",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    softWrap = true,
                )
            }
        }
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Suggested scene title ($provider)", style = MaterialTheme.typography.labelMedium)
            Text(
                scene.optString("title").ifBlank { scene.optString("code", "Untitled") },
                style = MaterialTheme.typography.bodyMedium,
                softWrap = true,
            )
        }
        Text(methodLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (studio.isNotBlank()) Text("Studio: $studio", style = MaterialTheme.typography.bodySmall)
        if (names.isNotEmpty()) Text("Performers: " + names.take(6).joinToString(", "), style = MaterialTheme.typography.bodySmall)
        if (date.isNotBlank()) Text("Release date: $date", style = MaterialTheme.typography.bodySmall)
        if (duration != null) Text("Provider duration: $duration", style = MaterialTheme.typography.bodySmall)
        if (url == null) Text(
            "This provider returned no usable cover. Verify the other details before confirming.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, modifier = Modifier.weight(1f)) { Text("Confirm") }
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Reject match") }
        }
    }
}

/** Existing Skywing thumbnail opens the same full-screen SMB player as Videos. */
@Composable
private fun LocalSceneThumbnail(
    service: ServiceLocator,
    vm: LibraryViewModel,
    path: String,
    onPreviewVideo: (VideoEntity) -> Unit,
) {
    val video by produceState<VideoEntity?>(null, service.libraryId, path) {
        value = withContext(Dispatchers.IO) { service.db.dao().findVideo(path) }
    }
    // Reading video duration is on-demand for visible review items; playback history
    // is reused when possible, and expensive SMB probes are cached and concurrency-limited.
    val progress by remember(vm, path) { vm.progressFlow(path) }.collectAsStateWithLifecycle(initialValue = null)
    val length by produceState<Long?>(progress?.durationMs?.takeIf { it > 0L }, video, progress?.durationMs) {
        value = progress?.durationMs?.takeIf { it > 0L }
            ?: video?.let { SceneDurationLookup.durationMs(service, it) }
    }
    val source = video?.let { ThumbStore.displayFile(service.artworkContext, it.id, it.thumbStatus) }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = video != null, onClickLabel = "Play video to verify metadata") {
                video?.let(onPreviewVideo)
            },
    ) {
        FileImage(source, Modifier.fillMaxSize())
        SceneLengthBadge(length, Modifier.align(Alignment.BottomEnd))
    }
    TextButton(
        onClick = { video?.let(onPreviewVideo) },
        enabled = video != null,
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Play video") }
    if (video == null) Text(
        "Video unavailable in library index",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SceneLengthBadge(milliseconds: Long?, modifier: Modifier = Modifier) {
    Text(
        text = if (milliseconds != null) sceneDurationLabel(milliseconds) else "Length unknown",
        color = androidx.compose.ui.graphics.Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(4.dp)
            .background(androidx.compose.ui.graphics.Color(0xC9000000), RoundedCornerShape(5.dp))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

/** Untrusted scene images are fetched with the app's existing HTTPS and image-size safeguards. */
@Composable
internal fun ProviderSceneThumbnail(url: String?, durationMs: Long?) {
    val preview by produceState<ImageBitmap?>(null, url) {
        value = if (ImageUrlPolicy.isAllowed(url)) {
            try {
                withContext(Dispatchers.IO) { ArtworkHttp.downloadImage(url!!, 480).asImageBitmap() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        } else null
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (preview == null) {
            Text("No image available", style = MaterialTheme.typography.labelSmall)
        } else {
            Image(bitmap = preview!!, contentDescription = "Matched scene cover",
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        SceneLengthBadge(durationMs, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
internal fun SceneMetadataReviewDialog(
    service: ServiceLocator,
    vm: LibraryViewModel,
    onPreviewVideo: (VideoEntity) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var manualPath by remember { mutableStateOf<String?>(null) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val path = manualPath
        if (uri != null && path != null) {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val video = service.db.dao().findVideo(path) ?: error("File isn't in this library")
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            val result = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(result.size() + count <= 8 * 1024 * 1024) { "Choose an image under 8 MB" }
                                result.write(buffer, 0, count)
                            }
                            result.toByteArray()
                        } ?: error("Unable to open image")
                        val bitmap = ArtworkHttp.decode(bytes, 720) ?: error("Invalid image")
                        try { CustomThumbs.save(service.artworkContext, video.id, bitmap) } finally { bitmap.recycle() }
                        service.sceneMetadataStore.dismiss(path)
                    }
                    refresh++
                    message = "Custom thumbnail saved. Your SMB file was not changed."
                } catch (e: Exception) { message = e.message ?: "Could not save image" }
            }
        }
    }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val records = remember(refresh) { service.sceneMetadataStore.review(100) }
    val reviewListState = rememberLazyListState(
        initialFirstVisibleItemIndex = vm.ui.metadataReviewIndex,
        initialFirstVisibleItemScrollOffset = vm.ui.metadataReviewOffset,
    )
    // The player temporarily removes Settings from composition. Retain the review
    // dialog, the same scroll position, and the same candidate on return.
    val saveReviewPosition = {
        vm.ui.metadataReviewIndex = reviewListState.firstVisibleItemIndex
        vm.ui.metadataReviewOffset = reviewListState.firstVisibleItemScrollOffset
    }
    DisposableEffect(reviewListState) {
        onDispose { saveReviewPosition() }
    }
    val playAndReturn: (VideoEntity) -> Unit = { video ->
        saveReviewPosition()
        onPreviewVideo(video)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review scene matches") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                state = reviewListState,
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (message != null) item { Text(message!!, color = MaterialTheme.colorScheme.primary) }
                if (records.isEmpty()) item { Text("No uncertain matches are waiting.") }
                // Stable keys: the list keeps its place when a file is decided, instead of jumping.
                items(records.size, key = { records[it].path }) { i ->
                    val record = records[i]
                    var searchText by remember(record.path) { mutableStateOf(record.path.substringAfterLast('/').substringBeforeLast('.')) }
                    var searchNote by remember(record.path) { mutableStateOf<String?>(null) }
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(record.path.substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                        val suggestions = remember(record.suggestions) {
                            runCatching { JSONArray(record.suggestions ?: "[]") }.getOrDefault(JSONArray())
                        }
                        if (suggestions.length() == 0) Text("No suggestions available.")
                        for (index in 0 until suggestions.length()) {
                            val suggestion = suggestions.optJSONObject(index) ?: continue
                            SceneMatchCandidate(
                                service, record, suggestion,
                                vm = vm,
                                onPreviewVideo = playAndReturn,
                                onConfirm = {
                                    service.sceneMetadataStore.approve(record.path, suggestion.toString())
                                    service.scenePosters.markApproved(record.path)
                                    ScenePosterWorker.enqueue(context, service.libraryId)
                                    refresh++
                                },
                                onReject = {
                                    service.sceneMetadataStore.reject(
                                        record.path,
                                        suggestion.optString("provider"),
                                        suggestion.optJSONObject("scene")?.optString("id").orEmpty(),
                                    )
                                    refresh++
                                },
                            )
                            HorizontalDivider()
                        }
                        OutlinedButton(onClick = {
                            manualPath = record.path
                            chooser.launch("image/*")
                        }, modifier = Modifier.fillMaxWidth()) { Text("Choose cover image from my phone") }
                        Text("Search ThePornDB with another title. The name is tidied and shortened for you if the first try finds nothing.", style = MaterialTheme.typography.bodySmall)
                        val runSearch: () -> Unit = {
                            focus.clearFocus()
                            keyboard?.hide()
                            val requested = searchText
                            searchNote = "Searching ThePornDB for \"" + requested.trim() + "\"…"
                            scope.launch {
                                try {
                                    val found = withContext(Dispatchers.IO) {
                                        SceneMetadataClient(service.providerKeys).searchTyped(SceneMetadataSettings.TPDB, requested)
                                    }
                                    if (found.isEmpty()) {
                                        message = "ThePornDB: no scenes found. Try fewer words or the scene's title."
                                        searchNote = message
                                    } else {
                                        val proposed = JSONArray()
                                        found.forEach { proposed.put(it.encoded()) }
                                        val offered = service.sceneMetadataStore.offerAlternatives(record.path, proposed.toString())
                                        refresh++
                                        message = if (offered == 0) "ThePornDB: only scenes you already rejected for this file. Try another title."
                                            else if (offered < found.size) "ThePornDB returned $offered suggestions (${found.size - offered} you rejected before are hidden)."
                                            else "ThePornDB returned " + found.size + " suggestions."
                                        searchNote = message
                                    }
                                } catch (e: com.deepak.videolibrary.artwork.online.ProviderException) {
                                    message = "ThePornDB: " + e.failure.message
                                    searchNote = message
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) {
                                    message = "ThePornDB search failed. Check the API key under Scene metadata."
                                    searchNote = message
                                }
                            }
                        }
                        OutlinedTextField(
                            value = searchText, onValueChange = { searchText = it },
                            label = { Text("Search another title") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { runSearch() }),
                        )
                        OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = searchText.isNotBlank(), onClick = runSearch) { Text("Search ThePornDB") }
                        searchNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        // Easy to see: an outlined button in the warning colour, full width.
                        OutlinedButton(
                            onClick = {
                                service.sceneMetadataStore.dismiss(record.path)
                                refresh++
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.error),
                        ) { Text("Reject all suggestions for this file") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
