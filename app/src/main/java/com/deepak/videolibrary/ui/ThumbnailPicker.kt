package com.deepak.videolibrary.ui

import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.thumbs.CustomThumbs
import com.deepak.videolibrary.thumbs.FrameGrabber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Preview frames are this wide; saved thumbnails use [CustomThumbs.WIDTH]. */
private const val PREVIEW_WIDTH = 960

/**
 * Lets the user pick any frame of a video as its permanent thumbnail.
 *
 * Moving the slider shows the nearest key frame quickly (debounced, older requests cancelled); once the
 * slider rests, the exact frame is shown. Save grabs the exact frame again at thumbnail size.
 */
@Composable
internal fun ThumbnailPicker(vm: LibraryViewModel, video: VideoEntity, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val grabber = remember(video.relativePath) { FrameGrabber(vm.smbProfile(), video.relativePath, video.size) }
    DisposableEffect(grabber) { onDispose { grabber.closeAsync() } }

    var info by remember { mutableStateOf<FrameGrabber.Info?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var dragging by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val hasCustom = remember(video.id) { vm.hasCustomThumbnail(video) }

    fun describe(e: Throwable): String = when (e) {
        is FrameGrabber.FrameException -> e.message ?: "Could not read this video."
        else -> SmbErrors.describe(e)
    }

    // Open the video once; start where automatic thumbnails are taken (about 10% in).
    LaunchedEffect(grabber) {
        try {
            val opened = grabber.open()
            info = opened
            position = if (opened.durationMs > 0) opened.durationMs / 10 else 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describe(e)
            loading = false
        }
    }

    // Fetch a preview for the latest position only: wait briefly so tiny slider moves are skipped, and
    // cancel a request that is still running when a newer position arrives.
    LaunchedEffect(info) {
        if (info == null) return@LaunchedEffect
        snapshotFlow { position to dragging }.distinctUntilChanged().collectLatest { (time, moving) ->
            delay(if (moving) 300 else 60)
            loading = true
            try {
                val frame = grabber.frame(time, exact = !moving, maxWidth = PREVIEW_WIDTH)
                if (frame != null) {
                    preview = frame.asImageBitmap()
                    error = null
                } else {
                    error = "No picture at this moment. Try a slightly different time."
                }
                loading = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = describe(e)
                loading = false
            }
        }
    }

    val duration = info?.durationMs ?: 0L
    fun step(deltaMs: Long) {
        position = (position + deltaMs).coerceIn(0L, duration.coerceAtLeast(0L))
    }

    Dialog(onDismissRequest = { if (!saving) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 720.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Change thumbnail", style = MaterialTheme.typography.titleLarge)
                Text(video.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)

                // Preview of the selected frame.
                val ratio = info?.let { if (it.width > 0 && it.height > 0) it.width.toFloat() / it.height else null } ?: (16f / 9f)
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(ratio.coerceIn(0.4f, 3f), matchHeightConstraintsFirst = false)
                        .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    preview?.let {
                        Image(it, contentDescription = "Selected frame", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                    }
                    if (loading || saving) CircularProgressIndicator(Modifier.size(36.dp), color = Color.White)
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(DateUtils.formatElapsedTime(position / 1000), style = MaterialTheme.typography.labelLarge)
                    Text(if (duration > 0) DateUtils.formatElapsedTime(duration / 1000) else "--:--", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Slider(
                    value = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f,
                    onValueChange = {
                        dragging = true
                        position = (it.toDouble() * duration).toLong()
                    },
                    onValueChangeFinished = { dragging = false },
                    enabled = duration > 0 && !saving,
                    colors = SliderDefaults.colors(activeTrackColor = MaterialTheme.colorScheme.tertiary, thumbColor = MaterialTheme.colorScheme.tertiary),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Thumbnail position" },
                )
                if (info != null && duration <= 0) {
                    Text(
                        "This video doesn't report its length, so only its first frame can be chosen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Fine tuning.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(-5_000L to "−5 s", -1_000L to "−1 s", 1_000L to "+1 s", 5_000L to "+5 s").forEach { (delta, label) ->
                        OutlinedButton(
                            onClick = { step(delta) },
                            enabled = duration > 0 && !saving,
                            modifier = Modifier.weight(1f),
                        ) { Text(label, maxLines = 1) }
                    }
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            vm.resetCustomThumbnail(video)
                            onDismiss()
                        },
                        enabled = hasCustom && !saving,
                    ) { Text("Reset to automatic") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Button(
                        onClick = {
                            saving = true
                            error = null
                            scope.launch {
                                try {
                                    val frame = grabber.frame(position, exact = true, maxWidth = CustomThumbs.WIDTH)
                                    if (frame == null) {
                                        error = "Couldn't capture this frame. Try a slightly different time."
                                    } else {
                                        try {
                                            vm.saveCustomThumbnail(video, frame)
                                        } finally {
                                            frame.recycle()
                                        }
                                        onDismiss()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = if (e is com.deepak.videolibrary.thumbs.ArtworkStorageException) {
                                        "Couldn't save the thumbnail. Check free space and try again."
                                    } else {
                                        describe(e)
                                    }
                                } finally {
                                    saving = false
                                }
                            }
                        },
                        enabled = info != null && !saving,
                    ) { Text("Save thumbnail") }
                }
            }
        }
    }
}
