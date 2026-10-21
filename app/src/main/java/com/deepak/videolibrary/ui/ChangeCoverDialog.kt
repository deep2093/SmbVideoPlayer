package com.deepak.videolibrary.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
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
    COLLAGE("Folder collage"),
    INTERNET("Internet performer"),
    IMAGE("Choose image"),
    RESET("Reset"),
}

/**
 * Change cover: tabbed sources (auto frame, internet performer, folder collage, image from this phone, reset), a search
 * field prefilled with the cleaned name, a candidate list, and a crop-position step before anything is saved.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ChangeCoverDialog(vm: LibraryViewModel, target: CoverTarget, onPickFrame: (() -> Unit)?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.artworkSettings.collectAsStateWithLifecycle()
    val tabs = remember(target.key) { CoverTab.entries.filter { it != CoverTab.COLLAGE || target.isFolder } }
    var tab by remember { mutableStateOf(CoverTab.AUTO) }

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember(target.key) { mutableStateOf(vm.artworkSearchTerm(target.key, target.label)) }
    var oneOff by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<PerformerCandidate>?>(null) }
    var pending by remember { mutableStateOf<Bitmap?>(null) }
    var pendingCandidate by remember { mutableStateOf<PerformerCandidate?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        error = null
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodePhoneImage(context, uri) }
            busy = false
            if (bitmap == null) {
                error = "That file could not be read as a picture."
            } else {
                pending = bitmap
                pendingCandidate = null
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Change cover", style = MaterialTheme.typography.titleLarge)
                Text(target.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

                val chosen = pending
                if (chosen != null) {
                    PositionStep(
                        image = chosen,
                        saving = busy,
                        onCancel = { pending?.recycle(); pending = null; pendingCandidate = null },
                        onApply = { fx, fy ->
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
                            FilterChip(selected = tab == t, onClick = { tab = t; error = null }, label = { Text(t.label) })
                        }
                    }
                    when (tab) {
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
                        }
                        CoverTab.COLLAGE -> {
                            Text("A collage of ${settings.collageTiles} frames from this folder (change the number in Settings > Artwork).", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.setCoverMode(target.key, ArtworkMode.COLLAGE); onDismiss() }) { Text("Use a collage") }
                        }
                        CoverTab.INTERNET -> {
                            val allowed = settings.adultConsent || oneOff
                            if (!allowed) {
                                Text(
                                    "Searching sends only the name below to the services you set up in Settings > Artwork. Nothing is sent until you allow it.",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = oneOff, onCheckedChange = { oneOff = it })
                                    Text("Allow this one search")
                                }
                            } else {
                                PillTextField(query, { query = it }, label = "Performer name", modifier = Modifier.fillMaxWidth())
                                Button(enabled = query.isNotBlank() && !busy, onClick = {
                                    busy = true
                                    error = null
                                    results = null
                                    scope.launch {
                                        try {
                                            when (val outcome = vm.searchPerformers(query.trim())) {
                                                is SearchOutcome.Failed -> {
                                                    error = outcome.failure.message
                                                }
                                                is SearchOutcome.Found -> {
                                                    results = outcome.candidates
                                                    if (outcome.candidates.isEmpty()) {
                                                        error = outcome.failures.firstOrNull()?.let { "${it.first}: ${it.second.message}" } ?: "No matches. Try a different spelling."
                                                    }
                                                }
                                            }
                                        } catch (e: CancellationException) {
                                            throw e
                                        } finally {
                                            busy = false
                                        }
                                    }
                                }) { Text("Search") }
                                results?.forEach { candidate ->
                                    CandidateRow(vm, candidate, enabled = !busy) {
                                        val url = candidate.imageUrl
                                        if (url == null) {
                                            error = "This match has no picture."
                                        } else {
                                            busy = true
                                            error = null
                                            scope.launch {
                                                try {
                                                    pending = vm.downloadPortrait(url)
                                                    pendingCandidate = candidate
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: ProviderException) {
                                                    error = e.failure.message
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        CoverTab.IMAGE -> {
                            Text("Use a picture from this phone. Your original file is not changed or moved.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { picker.launch("image/*") }, enabled = !busy) { Text("Choose from this phone") }
                        }
                        CoverTab.RESET -> {
                            Text("Go back to the default cover for this item. Pictures you chose are removed from the app only.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { vm.resetCover(target.key, target.video); onDismiss() }) { Text("Reset to default") }
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

@Composable
private fun CandidateRow(vm: LibraryViewModel, candidate: PerformerCandidate, enabled: Boolean, onChoose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RemoteThumb(vm, candidate.previewUrl, Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(8.dp)))
        Column(Modifier.weight(1f)) {
            Text(candidate.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            candidate.disambiguation?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            Text(candidate.providerName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onChoose, enabled = enabled && candidate.imageUrl != null) { Text("Choose") }
    }
}

/** A small picture loaded from an address (bounded, HTTPS only). Shows a plain box while loading or on failure. */
@Composable
private fun RemoteThumb(vm: LibraryViewModel, url: String?, modifier: Modifier) {
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

/** Shows how the picture will be cropped to a card and lets the user slide the visible part. */
@Composable
private fun PositionStep(image: Bitmap, saving: Boolean, onCancel: () -> Unit, onApply: (Float, Float) -> Unit) {
    var fx by remember { mutableFloatStateOf(0.5f) }
    var fy by remember { mutableFloatStateOf(0.25f) }
    val bitmap = remember(image) { image.asImageBitmap() }
    Text("Position the picture", style = MaterialTheme.typography.titleMedium)
    Box(Modifier.fillMaxWidth().aspectRatio(CoverCrop.CARD_ASPECT).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        Image(
            bitmap,
            contentDescription = "Cover preview",
            contentScale = ContentScale.Crop,
            alignment = BiasAlignment(fx * 2f - 1f, fy * 2f - 1f),
            modifier = Modifier.fillMaxSize(),
        )
    }
    Text("Up / down", style = MaterialTheme.typography.labelMedium)
    Slider(value = fy, onValueChange = { fy = it }, enabled = !saving)
    Text("Left / right", style = MaterialTheme.typography.labelMedium)
    Slider(value = fx, onValueChange = { fx = it }, enabled = !saving)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onCancel, enabled = !saving) { Text("Back") }
        Button(onClick = { onApply(fx, fy) }, enabled = !saving) { Text("Use this cover") }
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
