package com.deepak.videolibrary.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.artwork.ArtworkMode
import com.deepak.videolibrary.artwork.CoverCrop
import com.deepak.videolibrary.artwork.online.PerformerCandidate
import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.SearchOutcome
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Change cover dialog edits: a video or a folder, identified by the same key the library uses for covers. */
internal data class CoverTarget(val key: String, val title: String, val label: String, val video: VideoEntity? = null) {
    val isFolder: Boolean get() = video == null
}

private enum class CoverTab(val label: String) {
    AUTO("Auto frame"),
    SCENE("Scene poster"),
    COLLAGE("Folder collage"),
    PHOTOS("Photo collage"),
    INTERNET("StashDB"),
    TPDB("ThePornDB"),
    GOOGLE("Google Images"),
    IMAGE("From this phone"),
    RESET("Reset"),
}

/** Search suggestions from a label: each part before or between "," and "-", then the whole name. */
internal fun nameChoices(label: String): List<String> {
    val base = label.substringAfterLast('/').replace(Regex("\\.[A-Za-z0-9]{2,4}$"), "")
    val parts = base.split(',', '-').map { it.replace('_', ' ').replace('.', ' ').trim().replace(Regex("\\s+"), " ") }
    return (parts + base.trim()).filter { it.length >= 2 }.distinct().take(6)
}

/**
 * Change cover: tabbed sources (auto frame, folder collage, StashDB performer, Google Images, a picture from this
 * phone, reset), a search field prefilled with the cleaned name, a choice of photos, and a crop-position step
 * (showing both card shapes) before anything is saved.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ChangeCoverDialog(vm: LibraryViewModel, target: CoverTarget, onPickFrame: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    val tabs = remember(target.key) {
        CoverTab.entries.filter { tab ->
            (tab != CoverTab.COLLAGE || target.isFolder) &&
                // Videos can use scene posters or custom images, never performer portraits.
                ((tab != CoverTab.INTERNET && tab != CoverTab.TPDB) || target.isFolder)
        }
    }
    var tab by remember { mutableStateOf(CoverTab.AUTO) }
    val sceneOptions by produceState<List<SceneCoverOption>>(emptyList(), target.key, tab) {
        value = if (tab == CoverTab.SCENE) vm.sceneCoverCandidates(target.key, target.video) else emptyList()
    }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember(target.key) { mutableStateOf(vm.artworkSearchTerm(target.key, target.label)) }
    var oneOff by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PerformerCandidate>?>(null) }
    var photosOf by remember { mutableStateOf<PerformerCandidate?>(null) }
    // StashDB answers a few matches at a time: this page of matches, and whether another page may exist.
    var resultPage by remember { mutableIntStateOf(1) }
    var moreResults by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCandidate by remember { mutableStateOf<PerformerCandidate?>(null) }
    val choices = remember(target.label) { nameChoices(target.label) }
    // Choosing videos of this folder: for the photo collage ("photos") or for the folder collage ("collage").
    var choosingFrom by remember { mutableStateOf<String?>(null) }
    fun makeMoving() {
        busy = true
        error = null
        scope.launch {
            try {
                if (vm.makeMovingCover(target.key, target.video)) onDismiss() else error = "No frames could be read from the video. Try again when the server is reachable."
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "Couldn't make the moving cover. Check the connection and try again."
            } finally {
                busy = false
            }
        }
    }
    // Pictures gathered for a photo collage (up to four), and whether the next picture picked goes there.
    val collage = remember { androidx.compose.runtime.mutableStateListOf<Bitmap>() }
    var collecting by remember { mutableStateOf(false) }
    fun takePicture(bitmap: Bitmap, candidate: PerformerCandidate?) {
        if (collecting) {
            if (collage.size < com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS) collage.add(bitmap)
            collecting = false
            tab = CoverTab.PHOTOS
        } else {
            pending = bitmap
            pendingCandidate = candidate
        }
    }

    // Suggestions already found in the background for this item are shown straight away.
    androidx.compose.runtime.LaunchedEffect(target.key) {
        val saved = vm.portraitSuggestions(target.key)
        if (saved.isNotEmpty() && results == null) results = saved
    }

    fun load(candidate: PerformerCandidate?, url: String, fromWeb: Boolean = false) {
        busy = true
        error = null
        scope.launch {
            try {
                if (fromWeb) {
                    // A GIF from the web stays moving; anything else goes to the crop step as before.
                    val bytes = vm.pictureBytesFromWeb(url)
                    if (com.deepak.videolibrary.artwork.online.ArtworkHttp.isGif(bytes) && !collecting) {
                        vm.applyAnimatedCover(target.key, bytes)
                        onDismiss()
                        return@launch
                    }
                    val still = withContext(Dispatchers.Default) { com.deepak.videolibrary.artwork.online.ArtworkHttp.decode(bytes, 1280) }
                        ?: throw ProviderException(com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE)
                    takePicture(still, candidate)
                    return@launch
                }
                takePicture(vm.downloadPortrait(url), candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                error = if (fromWeb) "That picture can't be used. Open it first (tap it in Google), then hold the larger picture." else e.failure.message
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            // A GIF from the phone is kept as it is, so it moves on the card.
            val gif = withContext(Dispatchers.IO) {
                if (context.contentResolver.getType(uri) == "image/gif") {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().takeIf { it.size <= com.deepak.videolibrary.artwork.ArtworkRepository.MAX_ANIMATED_BYTES }
                    }
                } else {
                    null
                }
            }
            if (gif != null && !collecting) {
                try {
                    vm.applyAnimatedCover(target.key, gif)
                    onDismiss()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Couldn't save the moving picture. Check free space and try again."
                } finally {
                    busy = false
                }
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) { decodePhoneImage(context, uri) }
            busy = false
            if (bitmap == null) {
                error = "That file could not be read as a picture."
            } else {
                takePicture(bitmap, null)
            }
        }
    }
    // Several pictures from this phone at once, for a photo collage.
    val multiPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS),
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            val room = com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS - collage.size
            val read = withContext(Dispatchers.IO) { uris.take(room).mapNotNull { decodePhoneImage(context, it) } }
            collage.addAll(read)
            busy = false
            if (read.size < minOf(room, uris.size)) error = "Some pictures could not be read."
        }
    }

    choosingFrom?.let { purpose ->
        val max = if (purpose == "photos") com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS - collage.size else 6
        FolderVideoPicker(
            vm = vm,
            coverKey = target.key,
            max = max.coerceAtLeast(1),
            title = if (purpose == "photos") "Pictures for the collage" else "Videos for the collage",
            onDismiss = { choosingFrom = null },
        ) { videos ->
            choosingFrom = null
            busy = true
            error = null
            scope.launch {
                try {
                    if (purpose == "photos") {
                        val pictures = withContext(Dispatchers.IO) {
                            videos.mapNotNull { video -> vm.stillOf(video)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }
                        }
                        collage.addAll(pictures.take(com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS - collage.size))
                        if (pictures.size < videos.size) error = "Some videos have no thumbnail yet."
                    } else {
                        if (vm.applyChosenCollage(target.key, videos)) onDismiss() else error = "Those videos have no thumbnails yet. Generate thumbnails first."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    error = "Couldn't make the collage. Try again."
                } finally {
                    busy = false
                }
            }
        }
    }
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            // The Google tab holds a web page that scrolls by itself, so the dialog does not scroll around it.
            val scrollable = pending != null || tab != CoverTab.GOOGLE
            Column(
                Modifier.then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Change cover", style = MaterialTheme.typography.titleLarge)
                Text(target.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

                val chosen = pending
                if (chosen != null) {
                    PositionStep(
                        image = chosen,
                        saving = busy,
                        onCancel = { pending?.recycle(); pending = null; pendingCandidate = null },
                        onApply = { fx, fy, zoom ->
                            busy = true
                            scope.launch {
                                try {
                                    val candidate = pendingCandidate
                                    vm.applyCoverImage(
                                        key = target.key,
                                        mode = if (candidate != null) ArtworkMode.PORTRAIT else ArtworkMode.CUSTOM,
                                        image = chosen,
                                        focusY = fy,
                                        candidate = candidate,
                                        focusX = fx,
                                        zoom = zoom,
                                    )
                                    onDismiss()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = "Couldn't save the cover. Check free space and try again."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    )
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEach { t ->
                            FilterChip(selected = tab == t, onClick = { tab = t; error = null; collecting = false }, label = { Text(t.label) })
                        }
                    }
                    if (collecting && (tab == CoverTab.INTERNET || tab == CoverTab.TPDB || tab == CoverTab.GOOGLE)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Choosing photo ${collage.size + 1} of ${com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS} for the collage",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { collecting = false; tab = CoverTab.PHOTOS }) { Text("Back") }
                        }
                    }
                    when (tab) {
                        CoverTab.PHOTOS -> PhotoCollageTab(
                            pictures = collage,
                            enabled = !busy,
                            onRemove = { collage.removeAt(it) },
                            onFromPhone = {
                                multiPicker.launch(
                                    androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onFromStashDb = { collecting = true; tab = CoverTab.INTERNET },
                            onFromThePornDb = { collecting = true; tab = CoverTab.TPDB },
                            onFromGoogle = { collecting = true; tab = CoverTab.GOOGLE },
                            onFromFolder = if (target.isFolder) ({ choosingFrom = "photos" }) else null,
                            onMake = {
                                pending = com.deepak.videolibrary.artwork.PhotoCollage.compose(collage.toList())
                                pendingCandidate = null
                            },
                        )
                        CoverTab.AUTO -> {
                            if (target.isFolder) {
                                Text("Use the frame of the first video in this folder.", style = MaterialTheme.typography.bodyMedium)
                                Button(onClick = { vm.setCoverMode(target.key, ArtworkMode.FIRST_FRAME); onDismiss() }) { Text("Use first video frame") }
                            } else {
                                Text("Pick any moment of this video, or go back to the automatic frame.", style = MaterialTheme.typography.bodyMedium)
                                if (onPickFrame != null) {
                                    Button(onClick = { onDismiss(); onPickFrame() }) { Text("Choose a frame…") }
                                }
                                OutlinedButton(onClick = { vm.setCoverMode(target.key, ArtworkMode.FRAME); onDismiss() }) { Text("Use the automatic frame") }
                            }
                            Text(
                                if (target.isFolder) "Or a moving cover: short clips from the first video, played in turn." else "Or a moving cover: short clips from this video, played in turn.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = ::makeMoving, enabled = !busy) { Text(if (busy) "Making…" else "Make a moving cover") }
                        }
                        CoverTab.SCENE -> {
                            Text("Pick an image from your confirmed scene matches. This becomes your manual cover and won't be replaced by automatic matching.",
                                style = MaterialTheme.typography.bodyMedium)
                            if (sceneOptions.isEmpty()) {
                                Text("No confirmed scene images yet for this item. Review matches under Artwork & scene metadata, or use another tab to choose a picture.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            sceneOptions.forEach { scene ->
                                androidx.compose.material3.Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainer,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                ) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(scene.title, style = MaterialTheme.typography.titleSmall)
                                        Text("${scene.providerName} · ${scene.videoName}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val file = scene.localFile
                                        if (file != null) {
                                            FileImage(file, Modifier.fillMaxWidth().heightIn(max = 148.dp))
                                        } else {
                                            ProviderSceneThumbnail(scene.imageUrl, null)
                                        }
                                        OutlinedButton(
                                            enabled = !busy && (file != null || scene.imageUrl != null),
                                            onClick = {
                                                if (file != null && file.isFile) {
                                                    busy = true
                                                    scope.launch {
                                                        try {
                                                            val chosenImage = withContext(Dispatchers.IO) {
                                                                android.graphics.BitmapFactory.decodeFile(file.path)
                                                            }
                                                            if (chosenImage != null) takePicture(chosenImage, null)
                                                            else error = "The saved scene picture could not be read."
                                                        } finally { busy = false }
                                                    }
                                                } else if (scene.imageUrl != null) load(null, scene.imageUrl)
                                            },
                                        ) { Text("Choose this scene image") }
                                    }
                                }
                            }
                        }
                        CoverTab.COLLAGE -> {
                            Text("A collage of ${settings.collageTiles} frames from this folder (change the number in Settings > Artwork).", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.setCoverMode(target.key, ArtworkMode.COLLAGE); onDismiss() }) { Text("Use a collage") }
                            Text("Or choose which videos it shows.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedButton(onClick = { choosingFrom = "collage" }, enabled = !busy) { Text("Choose videos…") }
                        }
                        CoverTab.INTERNET, CoverTab.TPDB -> {
                            val allowed = settings.adultConsent || oneOff
                            if (!allowed) {
                                Text(
                                    "Searching sends only the name below to ${if (tab == CoverTab.TPDB) "ThePornDB" else "StashDB"}, using the key saved under Scene metadata. Nothing is sent until you allow it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = oneOff, onCheckedChange = { oneOff = it })
                                    Text("Allow this one search")
                                }
                            } else {
                                val photos = photosOf
                                if (photos != null) {
                                    PhotoChoice(vm, photos, enabled = !busy, onBack = { photosOf = null }) { url -> load(photos, url) }
                                } else {
                                    SearchField(query, { query = it }, choices, label = "Performer name")
                                    // Asks for one page of matches. [next] adds the following page below the ones already shown.
                                    fun runSearch(next: Boolean) {
                                        busy = true
                                        error = null
                                        val page = if (next) resultPage + 1 else 1
                                        if (!next) results = null
                                        scope.launch {
                                            try {
                                                var outcome = vm.searchPerformersFrom(if (tab == CoverTab.TPDB) com.deepak.videolibrary.artwork.ProviderIds.TPDB else com.deepak.videolibrary.artwork.ProviderIds.STASH_DB, query.trim(), page)
                                                // A video tries the name before the first comma, then the name before the hyphen.
                                                val terms = if (target.video != null) com.deepak.videolibrary.artwork.online.NameCleaner.videoTerms(target.label) else emptyList()
                                                val fallback = terms.getOrNull(1)
                                                val first = outcome
                                                if (!next && fallback != null && query.trim() == terms.first() &&
                                                    first is SearchOutcome.Found && first.candidates.isEmpty()
                                                ) {
                                                    query = fallback
                                                    outcome = vm.searchPerformersFrom(if (tab == CoverTab.TPDB) com.deepak.videolibrary.artwork.ProviderIds.TPDB else com.deepak.videolibrary.artwork.ProviderIds.STASH_DB, fallback, page)
                                                }
                                                when (outcome) {
                                                    is SearchOutcome.Failed -> {
                                                        error = outcome.failure.message
                                                    }
                                                    is SearchOutcome.Found -> {
                                                        val shown = if (next) results.orEmpty() else emptyList()
                                                        val seen = shown.map { it.providerId + ":" + it.performerId }.toSet()
                                                        results = shown + outcome.candidates.filter { (it.providerId + ":" + it.performerId) !in seen }
                                                        resultPage = page
                                                        moreResults = outcome.candidates.size >= com.deepak.videolibrary.artwork.online.PerformerProvider.PAGE_SIZE
                                                        if (results.isNullOrEmpty()) {
                                                            error = outcome.failures.firstOrNull()?.let { "${it.first}: ${it.second.message}" } ?: "No matches. Try a different spelling, or the Google Images tab."
                                                        }
                                                    }
                                                }
                                            } catch (e: CancellationException) {
                                                throw e
                                            } finally {
                                                busy = false
                                            }
                                        }
                                    }
                                    Button(enabled = query.isNotBlank() && !busy, onClick = { runSearch(next = false) }) { Text("Search") }
                                    results?.forEach { candidate ->
                                        CandidateRow(vm, candidate, enabled = !busy) {
                                            val url = candidate.imageUrl
                                            if (candidate.images.size > 1) {
                                                photosOf = candidate
                                            } else if (url != null) {
                                                load(candidate, url)
                                            } else {
                                                error = "This match has no picture."
                                            }
                                        }
                                    }
                                    if (moreResults && !results.isNullOrEmpty()) {
                                        OutlinedButton(enabled = !busy, onClick = { runSearch(next = true) }, modifier = Modifier.fillMaxWidth()) {
                                            Text("More results (page ${resultPage + 1})")
                                        }
                                    }
                                }
                            }
                        }
                        CoverTab.GOOGLE -> GoogleImagesTab(
                            query = query,
                            onQuery = { query = it },
                            choices = choices,
                            enabled = !busy,
                            onPicture = { address -> load(null, address, fromWeb = true) },
                        )
                        CoverTab.IMAGE -> {
                            Text("Use a picture from this phone. Your original file is not changed or moved.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Choose from this phone") }
                        }
                        CoverTab.RESET -> {
                            Text("Go back to the automatic cover for this item. Pictures you chose are removed from the app only.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.resetCover(target.key, target.video); onDismiss() }) { Text("Reset to automatic") }
                        }
                    }
                    if (busy) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp)); Spacer(Modifier.width(12.dp)); Text("Working…") }
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Row(Modifier.fillMaxWidth()) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                    }
                }
            }
        }
    }
}

/**
 * The name to search: wraps onto up to three lines so a long name is fully visible and the cursor can be placed
 * anywhere, with a clear button and one-tap suggestions taken from the file or folder name.
 */
@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, choices: List<String>, label: String) {
    androidx.compose.material3.OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        minLines = 1,
        maxLines = 3,
        shape = RoundedCornerShape(20.dp),
        trailingIcon = if (value.isNotEmpty()) {
            { androidx.compose.material3.IconButton(onClick = { onChange("") }) { androidx.compose.material3.Icon(Icons.Filled.Close, contentDescription = "Clear") } }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (choices.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                androidx.compose.material3.SuggestionChip(onClick = { onChange(choice) }, label = { Text(choice, maxLines = 1) })
            }
        }
    }
}

/** Photos shown per page of a performer's pictures. */
private const val PHOTOS_PER_PAGE = 12

/** Every photo the service has for one performer, a page at a time; tap one to use it. */
@Composable
private fun PhotoChoice(vm: LibraryViewModel, candidate: PerformerCandidate, enabled: Boolean, onBack: () -> Unit, onChoose: (String) -> Unit) {
    val pages = ((candidate.images.size + PHOTOS_PER_PAGE - 1) / PHOTOS_PER_PAGE).coerceAtLeast(1)
    var page by remember(candidate.performerId) { mutableIntStateOf(0) }
    val current = page.coerceIn(0, pages - 1)
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack) { Text("‹ Results") }
        Text("${candidate.name} · ${candidate.images.size} photos", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    candidate.images.drop(current * PHOTOS_PER_PAGE).take(PHOTOS_PER_PAGE).chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { url ->
                Box(
                    Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(14.dp))
                        .clickable(enabled = enabled, onClickLabel = "Use this photo") { onChoose(url) },
                ) {
                    RemoteThumb(vm, url, Modifier.fillMaxSize())
                }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (pages > 1) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(enabled = current > 0, onClick = { page = current - 1 }) { Text("‹ Previous") }
            Text("Page ${current + 1} of $pages", style = MaterialTheme.typography.labelLarge)
            TextButton(enabled = current < pages - 1, onClick = { page = current + 1 }) { Text("Next ›") }
        }
    }
}

@Composable
private fun CandidateRow(vm: LibraryViewModel, candidate: PerformerCandidate, enabled: Boolean, onChoose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteThumb(vm, candidate.previewUrl, Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(14.dp)))
        Column(Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            candidate.disambiguation?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(
                candidate.providerName + if (candidate.images.size > 1) " · ${candidate.images.size} photos" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onChoose, enabled = enabled && candidate.imageUrl != null) { Text(if (candidate.images.size > 1) "Photos" else "Choose") }
    }
}

/** A small picture loaded from an address (bounded, HTTPS only). Shows a plain box while loading or on failure. */
@Composable
internal fun RemoteThumb(vm: LibraryViewModel, url: String?, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, url) {
        value = if (url == null) {
            null
        } else {
            try {
                vm.downloadPortrait(url, 240).asImageBitmap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Shows how the picture will be cropped (landscape and taller cards, side by side) and lets the user slide the
 * visible part. Both shapes are saved, so a page can switch between them later.
 */
@Composable
private fun PositionStep(image: Bitmap, saving: Boolean, onCancel: () -> Unit, onApply: (Float, Float, Float) -> Unit) {
    var fx by remember { mutableFloatStateOf(0.5f) }
    var fy by remember { mutableFloatStateOf(0.25f) }
    // The zoom slider starts in the middle (1x): left zooms out (blurred borders fill the gaps), right zooms in.
    var zoomPosition by remember { mutableFloatStateOf(0.5f) }
    val zoom = CoverCrop.zoomForSlider(zoomPosition)
    val bitmap = remember(image) { image.asImageBitmap() }
    // A tall photo already fills a card's width, so sideways movement needs a little zoom first.
    val sideRoom = image.width.toFloat() / image.height > CoverCrop.TALL_ASPECT || kotlin.math.abs(zoom - 1f) > 0.01f
    Text("Position the picture", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(2.2f)) {
            CropPreview(bitmap, CoverCrop.CARD_ASPECT, fx, fy, zoom, "Landscape preview")
            Text("Landscape", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f)) {
            CropPreview(bitmap, CoverCrop.TALL_ASPECT, fx, fy, zoom, "Taller preview")
            Text("Taller 3:4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("Up / down", style = MaterialTheme.typography.labelMedium)
    Slider(value = fy, onValueChange = { fy = it }, enabled = !saving)
    Text("Left / right", style = MaterialTheme.typography.labelMedium)
    Slider(value = fx, onValueChange = { fx = it }, enabled = !saving)
    if (!sideRoom) {
        Text("This picture already fills the card's width. Zoom in or out a little to move it left or right.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text("Zoom", style = MaterialTheme.typography.labelMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = zoomPosition, onValueChange = { zoomPosition = it }, enabled = !saving, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
        Text("In", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, enabled = !saving) { Text("Back") }
        Button(onClick = { onApply(fx, fy, zoom) }, enabled = !saving) { Text("Use this cover") }
    }
}

@Composable
private fun CropPreview(bitmap: ImageBitmap, aspect: Float, fx: Float, fy: Float, zoom: Float, label: String) {
    // Drawn from the exact window that will be saved, so the preview always matches the result.
    val window = CoverCrop.rect(bitmap.width, bitmap.height, aspect, fx, fy, zoom)
    val zoomedOut = CoverCrop.overflows(window, bitmap.width, bitmap.height)
    Box(Modifier.fillMaxWidth().aspectRatio(aspect).clip(RoundedCornerShape(14.dp)).background(Color.Black).semantics { contentDescription = label }) {
        if (zoomedOut) {
            // Zoomed out: the saved cover fills the edges with a blurred copy; show the same here.
            Image(
                bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).background(Color.Black.copy(alpha = 0.35f)),
            )
        }
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val scale = size.width / window.width
            drawImage(
                bitmap,
                dstOffset = androidx.compose.ui.unit.IntOffset((-window.left * scale).toInt(), (-window.top * scale).toInt()),
                dstSize = androidx.compose.ui.unit.IntSize((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt()),
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
            )
        }
    }
}

/** Reads a picture the user picked, scaled down so a huge photo cannot exhaust memory. */
private fun decodePhoneImage(context: android.content.Context, uri: Uri): Bitmap? = try {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        null
    } else {
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1600) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
} catch (e: Exception) {
    null
}

/** True when artwork management is on, so "Change cover" is offered in menus. */
@Composable
internal fun rememberArtworkEnabled(vm: LibraryViewModel): Boolean {
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    return settings.enabled
}


/**
 * Photo collage: up to four pictures (from this phone, StashDB or Google Images) joined into one cover. Two or
 * more make a collage; one picture is simply used as it is.
 */
@Composable
private fun PhotoCollageTab(
    pictures: List<Bitmap>,
    enabled: Boolean,
    onRemove: (Int) -> Unit,
    onFromPhone: () -> Unit,
    onFromStashDb: () -> Unit,
    onFromThePornDb: () -> Unit,
    onFromGoogle: () -> Unit,
    onFromFolder: (() -> Unit)?,
    onMake: () -> Unit,
) {
    val max = com.deepak.videolibrary.artwork.PhotoCollage.MAX_PHOTOS
    Text(
        "Pick up to $max pictures to join into one cover: 2 side by side, 3 as one large and two small, 4 as a grid.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(max) { index ->
            val picture = pictures.getOrNull(index)
            Box(
                Modifier.weight(1f).aspectRatio(3f / 4f).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (picture != null) {
                    val image = remember(picture) { picture.asImageBitmap() }
                    Image(image, contentDescription = "Collage picture ${index + 1}", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    androidx.compose.material3.IconButton(
                        onClick = { onRemove(index) },
                        enabled = enabled,
                        modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                    ) {
                        androidx.compose.material3.Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove picture ${index + 1}",
                            tint = Color.White,
                            modifier = Modifier.background(Color.Black.copy(alpha = 0.45f), androidx.compose.foundation.shape.CircleShape).padding(2.dp),
                        )
                    }
                } else {
                    Text("${index + 1}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val full = pictures.size >= max
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onFromFolder != null) OutlinedButton(onClick = onFromFolder, enabled = enabled && !full) { Text("From this folder") }
        OutlinedButton(onClick = onFromPhone, enabled = enabled && !full) { Text("From this phone") }
        OutlinedButton(onClick = onFromStashDb, enabled = enabled && !full) { Text("From StashDB") }
        OutlinedButton(onClick = onFromThePornDb, enabled = enabled && !full) { Text("From ThePornDB") }
        OutlinedButton(onClick = onFromGoogle, enabled = enabled && !full) { Text("From Google Images") }
    }
    Button(onClick = onMake, enabled = enabled && pictures.isNotEmpty()) {
        Text(if (pictures.size >= 2) "Make collage (${pictures.size})" else "Use this picture")
    }
}

/**
 * The videos of the folder a cover belongs to, as a grid of thumbnails: tick up to [max] of them. Used to choose
 * which videos a collage shows.
 */
@Composable
private fun FolderVideoPicker(
    vm: LibraryViewModel,
    coverKey: String,
    max: Int,
    title: String,
    onDismiss: () -> Unit,
    onDone: (List<VideoEntity>) -> Unit,
) {
    val videos by produceState<List<VideoEntity>?>(null, coverKey) { value = vm.videosForCoverKey(coverKey) }
    val chosen = remember { androidx.compose.runtime.mutableStateListOf<VideoEntity>() }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title (${chosen.size} of $max)") },
        text = {
            val list = videos
            when {
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("No videos found in this folder yet.")
                else -> androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(96.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                ) {
                    items(list.size, key = { list[it].relativePath }) { index ->
                        val video = list[index]
                        val ticked = chosen.any { it.relativePath == video.relativePath }
                        Box(
                            Modifier.aspectRatio(16f / 10f).clip(RoundedCornerShape(10.dp)).clickable {
                                if (ticked) chosen.removeAll { it.relativePath == video.relativePath } else if (chosen.size < max) chosen.add(video)
                            },
                        ) {
                            FileImage(remember(video.id, video.thumbStatus) { vm.stillOf(video) }, Modifier.fillMaxSize())
                            SelectionBadge(ticked, Modifier.align(Alignment.TopEnd).padding(4.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = chosen.isNotEmpty(), onClick = { onDone(chosen.toList()) }) { Text("Use ${chosen.size}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
