package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.deepak.videolibrary.metadata.PosterMode
import com.deepak.videolibrary.metadata.ScenePosterWorker
import com.deepak.videolibrary.thumbs.CustomThumbs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

/** Separate Settings page. Artwork defaults, manually chosen covers and virtual folders are untouched. */
@Composable
internal fun SceneMetadataSettingsSection(vm: LibraryViewModel) {
    val context = LocalContext.current
    val service = remember(vm, context) { ServiceLocator.get(context) }
    val prefs = service.sceneMetadataPreferences
    val options by prefs.settings.collectAsStateWithLifecycle()
    val keyRevision by vm.providerKeyRevision.collectAsStateWithLifecycle()
    var consentOpen by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Text("Scene metadata", style = MaterialTheme.typography.titleMedium)
    Text(
        "Fetch scene titles, studios, performers, dates and available artwork. Metadata is stored separately and never renames SMB files, changes virtual folders or replaces your existing covers.",
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow("Enable online metadata", null, options.enabled) { enabled ->
        prefs.update { it.copy(enabled = enabled) }
        if (!enabled) SceneMetadataWorker.cancel(context, service.libraryId)
    }

    if (options.enabled) {
        SwitchRow(
            "Allow sending search terms and fingerprints",
            "Cleaned filenames, OSHash and optionally pHash can identify scenes. No SMB path or account credentials are sent.",
            options.allowedOnlineLookup,
        ) { allow ->
            if (allow) consentOpen = true
            else {
                prefs.update { it.copy(allowedOnlineLookup = false) }
                SceneMetadataWorker.cancel(context, service.libraryId)
            }
        }

        SettingsLabel("Search order")
        listOf(SceneMetadataSettings.STASHDB, SceneMetadataSettings.TPDB).forEach { id ->
            val name = if (id == SceneMetadataSettings.STASHDB) "StashDB" else "ThePornDB"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.RadioButton(
                    selected = options.primary == id,
                    onClick = { prefs.update { it.copy(primary = id) } },
                )
                TextButton(onClick = { prefs.update { it.copy(primary = id) } }) { Text("Search $name first") }
            }
        }
        Text("The second provider is searched if the first returns no match.", style = MaterialTheme.typography.bodySmall)

        SettingsDivider()
        SettingsLabel("Your API keys")
        SceneKeyEntry(vm, SceneMetadataSettings.STASHDB, "StashDB", keyRevision)
        SceneKeyEntry(vm, SceneMetadataSettings.TPDB, "ThePornDB", keyRevision)
        Text("Each key is encrypted using Android Keystore and is never included in a build or sent to the other provider.", style = MaterialTheme.typography.bodySmall)

        SettingsDivider()
        SwitchRow("Fetch automatically after scans", "Runs as a separate background worker on unmetered connections with battery safeguards.", options.automatic) {
            prefs.update { old -> old.copy(automatic = it) }
        }
        SwitchRow("Use video pHash matching", "Slower on SMB. Experimental Android compatibility; proposed pHash matches always require review.", options.phash) {
            prefs.update { old -> old.copy(phash = it) }
        }
        SwitchRow("Review uncertain matches", "Filename matches and multiple results wait for your confirmation.", options.reviewUncertain) {
            prefs.update { old -> old.copy(reviewUncertain = it) }
        }

        SettingsDivider()
        SettingsLabel("Scene poster artwork")
        Text("Scene posters are separate from existing artwork. Custom covers and hand-picked frames always win.", style = MaterialTheme.typography.bodySmall)
        PosterMode.entries.forEach { mode ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                androidx.compose.material3.RadioButton(selected = options.posterMode == mode, onClick = {
                    prefs.update { it.copy(posterMode = mode) }
                    if (mode != PosterMode.DISABLED) ScenePosterWorker.enqueue(context, service.libraryId)
                })
                TextButton(onClick = {
                    prefs.update { it.copy(posterMode = mode) }
                    if (mode != PosterMode.DISABLED) ScenePosterWorker.enqueue(context, service.libraryId)
                }) { Text(mode.label) }
            }
        }
        SwitchRow("Use posters on video cards", "Keeps manually selected covers.", options.videoPosters) {
            prefs.update { old -> old.copy(videoPosters = it) }
            if (it) ScenePosterWorker.enqueue(context, service.libraryId)
        }
        SwitchRow("Posters for one-video folders", "Multi-video folders retain their existing collages and portraits.", options.folderPosters) {
            prefs.update { old -> old.copy(folderPosters = it) }
            if (it) ScenePosterWorker.enqueue(context, service.libraryId)
        }
        OutlinedButton(onClick = {
            ScenePosterWorker.enqueue(context, service.libraryId)
            status = "Poster downloads queued for confirmed metadata."
        }, enabled = options.posterMode != PosterMode.DISABLED, modifier = Modifier.fillMaxWidth()) { Text("Apply posters from confirmed matches") }

        SettingsDivider()
        OutlinedButton(onClick = {
            val count = service.sceneMetadataStore.retryUnresolved()
            SceneMetadataWorker.enqueue(context, service.libraryId, manual = true)
            status = "Rechecking " + count + " unresolved matches using the selected provider first."
        }, enabled = options.allowedOnlineLookup, modifier = Modifier.fillMaxWidth()) { Text("Recheck unconfirmed using preferred provider") }
        Text("Confirmed matches and rejected files are kept. Review individual files to search TPDB or StashDB directly.", style = MaterialTheme.typography.bodySmall)

        SettingsDivider()
        Button(onClick = {
            if (!options.allowedOnlineLookup) status = "Allow online lookups before starting."
            else {
                SceneMetadataWorker.enqueue(context, service.libraryId, manual = true)
                status = "Metadata lookup queued. Results are cached locally."
            }
        }, enabled = options.allowedOnlineLookup && (
            vm.providerHasKey(SceneMetadataSettings.STASHDB) || vm.providerHasKey(SceneMetadataSettings.TPDB)
        ), modifier = Modifier.fillMaxWidth()) { Text("Fetch metadata now") }

        OutlinedButton(onClick = { reviewOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Review scene matches")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (consentOpen) AlertDialog(
        onDismissRequest = { consentOpen = false },
        title = { Text("Enable online scene identification?") },
        text = { Text("This will send cleaned video titles and optional file fingerprints to StashDB and ThePornDB to find metadata. Hashes can uniquely identify content. Your SMB server address, credentials and full file paths will not be sent.") },
        confirmButton = { TextButton(onClick = {
            consentOpen = false
            prefs.update { it.copy(allowedOnlineLookup = true) }
        }) { Text("Allow lookups") } },
        dismissButton = { TextButton(onClick = { consentOpen = false }) { Text("Cancel") } },
    )
    if (reviewOpen) SceneMetadataReviewDialog(service, onDismiss = { reviewOpen = false })
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
    Text(label, style = MaterialTheme.typography.titleSmall)
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
    val duration = if (durationSeconds > 0) {
        val hours = durationSeconds / 3600
        if (hours > 0) "%d:%02d:%02d".format(hours, (durationSeconds / 60) % 60, durationSeconds % 60)
        else "%d:%02d".format(durationSeconds / 60, durationSeconds % 60)
    } else null
    val methodLabel = when (method) {
        "OSHASH" -> "File fingerprint: exact OSHash"
        "PHASH" -> "Video similarity: pHash (experimental)"
        else -> "Filename search: verify manually"
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your video", style = MaterialTheme.typography.labelMedium)
                LocalSceneThumbnail(service, record.path)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("$provider cover", style = MaterialTheme.typography.labelMedium)
                ProviderSceneThumbnail(url)
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

/** Use the same manually chosen or generated thumbnail that the library already displays. */
@Composable
private fun LocalSceneThumbnail(service: ServiceLocator, path: String) {
    val video by produceState<com.deepak.videolibrary.data.VideoEntity?>(null, service.libraryId, path) {
        value = withContext(Dispatchers.IO) { service.db.dao().findVideo(path) }
    }
    val source = video?.let { ThumbStore.displayFile(service.artworkContext, it.id, it.thumbStatus) }
    FileImage(
        source,
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)),
    )
    if (source == null) {
        Text("No local thumbnail", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Untrusted scene images are fetched with the app's existing HTTPS and image-size safeguards. */
@Composable
private fun ProviderSceneThumbnail(url: String?) {
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
    }
}

@Composable
private fun SceneMetadataReviewDialog(service: ServiceLocator, onDismiss: () -> Unit) {
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
    val records = remember(refresh) { service.sceneMetadataStore.review(100) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review scene matches") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
            ) {
                if (message != null) item { Text(message!!, color = MaterialTheme.colorScheme.primary) }
                if (records.isEmpty()) item { Text("No uncertain matches are waiting.") }
                items(records.size) { i ->
                    val record = records[i]
                    var searchText by remember(record.path) { mutableStateOf(record.path.substringAfterLast('/').substringBeforeLast('.')) }
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
                        Text("Try a different search, including ThePornDB when StashDB already suggested a scene.", style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(
                            value = searchText, onValueChange = { searchText = it },
                            label = { Text("Search another title") },
                            singleLine = true, modifier = Modifier.fillMaxWidth(),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(SceneMetadataSettings.TPDB, SceneMetadataSettings.STASHDB).forEach { provider ->
                                val label = if (provider == SceneMetadataSettings.TPDB) "Search TPDB" else "Search StashDB"
                                OutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val requested = searchText
                                        scope.launch {
                                            try {
                                                val found = withContext(Dispatchers.IO) {
                                                    SceneMetadataClient(service.providerKeys).search(provider, requested)
                                                }
                                                if (found.isEmpty()) {
                                                    message = label + ": no scenes found. Try another search term."
                                                } else {
                                                    val proposed = JSONArray()
                                                    found.forEach { proposed.put(it.encoded()) }
                                                    service.sceneMetadataStore.offerAlternatives(record.path, proposed.toString())
                                                    refresh++
                                                    message = label + " returned " + found.size + " suggestions."
                                                }
                                            } catch (e: com.deepak.videolibrary.artwork.online.ProviderException) {
                                                message = label + ": " + e.failure.message
                                            } catch (e: CancellationException) { throw e
                                            } catch (_: Exception) {
                                                message = label + " failed. Check the API key and GraphQL provider settings."
                                            }
                                        }
                                    },
                                ) { Text(label) }
                            }
                        }
                        TextButton(onClick = {
                            service.sceneMetadataStore.dismiss(record.path)
                            refresh++
                        }) { Text("Keep existing thumbnail / skip this file") }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
