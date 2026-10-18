package com.deepak.videolibrary.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.View
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.text.style.TextOverflow
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.Offset
import kotlin.math.abs
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.awaitPointerEvent
import androidx.compose.ui.input.pointer.consume
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.player.SmbPlaybackDataSource
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(video: VideoEntity, settings: SmbSettings, playlist: List<VideoEntity> = listOf(video), onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val prefs = remember { context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    val profile = remember(settings) { SmbProfile(settings.host, settings.share, settings.rootPath, settings.username, settings.password) }
    // A virtual folder is a real ExoPlayer playlist: video N advances to N+1 automatically.
    val queue = remember(video.id, playlist) {
        val entries = playlist.ifEmpty { listOf(video) }
        entries.distinctBy { it.id }.ifEmpty { listOf(video) }
    }
    val initialIndex = remember(video.id, queue) { queue.indexOfFirst { it.id == video.id }.coerceAtLeast(0) }
    val player = remember(queue, profile) {
        ExoPlayer.Builder(context).build().apply {
            val factory = ProgressiveMediaSource.Factory(SmbPlaybackDataSource.Factory(profile))
            setMediaSources(queue.map { item ->
                val uri = Uri.Builder().scheme("smbvideo").authority("library")
                    .appendQueryParameter("path", item.relativePath)
                    .appendQueryParameter("size", item.size.toString()).build()
                factory.createMediaSource(MediaItem.fromUri(uri))
            })
            seekTo(initialIndex, prefs.getLong("resume_path_${video.relativePath}", prefs.getLong("resume_${video.id}", 0L)).coerceAtLeast(0))
            prepare()
            playWhenReady = true
        }
    }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val activeVideo = queue.getOrElse(currentIndex) { video }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    fun revealControls() { controlsVisible = true; controlsInteraction++ }
    // Hide Android status/navigation bars while the fullscreen player is on screen.
    DisposableEffect(activity) {
        val window = activity?.window
        val decor = window?.decorView
        val before = decor?.systemUiVisibility
        decor?.systemUiVisibility = (before ?: 0) or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        onDispose { if (before != null) decor?.systemUiVisibility = before }
    }
    LaunchedEffect(controlsVisible, controlsInteraction, currentIndex) {
        if (controlsVisible) { delay(2_000); controlsVisible = false }
    }
    val aspectModes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT, AspectRatioFrameLayout.RESIZE_MODE_ZOOM, AspectRatioFrameLayout.RESIZE_MODE_FILL, AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH, AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT)
    val aspectLabels = listOf("Fit", "Crop", "Stretch", "Width", "Height")
    var aspectIndex by remember { mutableIntStateOf(0) }
    val aspect = aspectModes[aspectIndex]
    var zoom by remember { mutableFloatStateOf(1f) }
    var holdSpeed by remember { mutableFloatStateOf(prefs.getFloat("hold_speed", 1.5f)) }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", false)) }
    var showSettings by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var retry by remember { mutableIntStateOf(0) }
    var retryPending by remember { mutableStateOf(false) }
    var boost by remember { mutableStateOf(false) }
    var gestureMessage by remember { mutableStateOf("") }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var lastTapSide by remember { mutableIntStateOf(-1) }
    var seekingGesture by remember { mutableStateOf(false) }
    var seekingTarget by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            if (position > 2_000) {
                val item = queue.getOrElse(currentIndex) { video }
                prefs.edit().putLong("resume_path_${item.relativePath}", position).putLong("duration_path_${item.relativePath}", duration).apply()
            }
            delay(1_000)
        }
    }
    LaunchedEffect(retryPending) {
        if (retryPending) {
            if (retry < 4) {
                retry++
                message = "Reconnecting to SMB… attempt $retry of 4"
                delay(1_000L * retry)
                val last = player.currentPosition.coerceAtLeast(0)
                player.prepare()
                player.seekTo(last)
                player.play()
                retryPending = false
            } else {
                message = "Connection unavailable. Check Wi-Fi or SMB server, then tap Retry."
                retryPending = false
            }
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                message = "Playback interruption: ${error.errorCodeName}"
                retryPending = true
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) { retry = 0; message = null }
                if (playbackState == Player.STATE_ENDED) revealControls()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                revealControls()
            }
        }
        player.addListener(listener)
        onDispose {
            prefs.edit().putLong("resume_path_${queue.getOrElse(player.currentMediaItemIndex.coerceAtLeast(0)) { video }.relativePath}", player.currentPosition.coerceAtLeast(0)).apply()
            player.removeListener(listener)
            player.release()
        }
    }
    // Opt-in PiP when user presses Home or switches away; disabled by default.
    DisposableEffect(lifecycleOwner, activity, pipEnabled) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && pipEnabled && Build.VERSION.SDK_INT >= 26 && activity != null && !activity.isFinishing && player.isPlaying) {
                runCatching { activity.enterPictureInPictureMode(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler { if (showSettings) showSettings = false else onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply {
            this.player = player
            useController = false
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        } }, update = { view ->
            view.resizeMode = aspect
            view.videoSurfaceView?.apply { scaleX = zoom; scaleY = zoom }
        }, modifier = Modifier.fillMaxSize())
        // A single pointer handler arbitrates taps, drags and long-press.
        // Separate stacked detectors previously competed and swallowed gestures.
        Row(Modifier.fillMaxSize()) {
            repeat(2) { side ->
                Box(Modifier.weight(1f).fillMaxHeight().pointerInput(player, side, holdSpeed, activity) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        var previous = start
                        var last = start
                        var moved = false
                        var mode = 0 // 0=tap/hold, 1=seek, 2=volume/brightness
                        var startPosition = player.currentPosition.coerceAtLeast(0L)
                        val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                        val startBrightness = activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
                        val startTime = System.currentTimeMillis()
                        var previousSpeed = player.playbackParameters.speed
                        var holding = false
                        var released = false
                        while (!released) {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed) {
                                released = true
                                break
                            }
                            val current = pointer.position
                            last = current
                            val dx = current.x - start.x
                            val dy = current.y - start.y
                            if (!moved && (abs(dx) > 18f || abs(dy) > 18f)) {
                                moved = true
                                mode = if (abs(dx) > abs(dy) * 1.2f) 1 else 2
                                if (holding) {
                                    player.setPlaybackSpeed(previousSpeed)
                                    holding = false
                                    boost = false
                                }
                            }
                            if (!moved && !holding && System.currentTimeMillis() - startTime >= 450L) {
                                previousSpeed = player.playbackParameters.speed
                                player.setPlaybackSpeed(holdSpeed)
                                holding = true
                                boost = true
                            }
                            if (moved) {
                                pointer.consume()
                                if (mode == 1) {
                                    val total = player.duration.takeIf { it > 0L } ?: duration
                                    seekingTarget = (startPosition + (dx / size.width.coerceAtLeast(1) * 90_000f).toLong())
                                        .coerceIn(0L, total.takeIf { it > 0L } ?: Long.MAX_VALUE)
                                    seekingGesture = true
                                    gestureMessage = "Seek ${formatPlaybackTime(seekingTarget)}"
                                } else {
                                    val fraction = -dy / size.height.coerceAtLeast(1).toFloat()
                                    if (side == 0) {
                                        val target = (startVolume + fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                        gestureMessage = "Volume ${(100f * target / maxVolume).roundToInt()}%"
                                    } else {
                                        activity?.window?.let { window ->
                                            val attrs = window.attributes
                                            attrs.screenBrightness = (startBrightness + fraction).coerceIn(0.02f, 1f)
                                            window.attributes = attrs
                                            gestureMessage = "Brightness ${(attrs.screenBrightness * 100).roundToInt()}%"
                                        }
                                    }
                                }
                            }
                            previous = current
                        }
                        if (holding) {
                            player.setPlaybackSpeed(previousSpeed)
                            boost = false
                        }
                        if (mode == 1 && seekingGesture) player.seekTo(seekingTarget)
                        seekingGesture = false
                        if (!moved && !holding) {
                            val now = System.currentTimeMillis()
                            if (now - lastTapTime < 350L && lastTapSide == side) {
                                val delta = if (side == 0) -5_000L else 10_000L
                                player.seekTo((player.currentPosition + delta).coerceIn(0L,
                                    player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
                                gestureMessage = if (side == 0) "−5 sec" else "+10 sec"
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapSide = side
                                if (controlsVisible) controlsVisible = false else revealControls()
                            }
                        }
                    }
                })
            }
        }
        if (seekingGesture) Text("Release to seek: ${formatPlaybackTime(seekingTarget)}", color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xCC101010), RoundedCornerShape(12.dp)).padding(12.dp))
        if (boost) Text("${holdSpeed}×", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
        LaunchedEffect(gestureMessage) {
            if (gestureMessage.isNotEmpty()) { delay(1_100); gestureMessage = "" }
        }
        if (gestureMessage.isNotBlank()) Text(gestureMessage, color = Color.White, modifier = Modifier.align(Alignment.Center).background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(12.dp))
        if (controlsVisible) {
            // Overlay is transient; controls never permanently consume video height.
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth().background(Color(0xA6000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("‹ Back", color = Color.White) }
                    Text(activeVideo.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White)
                    TextButton(onClick = { showSettings = !showSettings; revealControls() }) { Text("⚙", color = Color.White) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { player.seekTo((player.currentPosition - 5_000).coerceAtLeast(0)); revealControls() }) { Text("↶ 5", color = Color.White) }
                    TextButton(onClick = { if (player.isPlaying) player.pause() else player.play(); revealControls() }) { Text(if (isPlaying) "❚❚" else "▶", color = Color.White, style = MaterialTheme.typography.headlineLarge) }
                    TextButton(onClick = { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration.coerceAtLeast(0))); revealControls() }) { Text("10 ↷", color = Color.White) }
                }
                Column(Modifier.fillMaxWidth().background(Color(0xB8000000)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPlaybackTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(formatPlaybackTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    var scrubbing by remember { mutableStateOf(false) }
                    var seekPosition by remember { mutableFloatStateOf(0f) }
                    Slider(value = if (scrubbing) seekPosition else position.coerceIn(0L, duration.coerceAtLeast(1L)).toFloat(),
                        onValueChange = { scrubbing = true; seekPosition = it; revealControls() },
                        onValueChangeFinished = { player.seekTo(seekPosition.toLong()); scrubbing = false; revealControls() },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), modifier = Modifier.fillMaxWidth().height(22.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${currentIndex + 1}/${queue.size}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = { aspectIndex = (aspectIndex + 1) % aspectModes.size; zoom = 1f; revealControls() }) { Text("Aspect: ${aspectLabels[aspectIndex]} ↻", color = Color.White) }
                        if (queue.size > 1) TextButton(onClick = { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); revealControls() }) { Text("Next ›") }
                    }
                }
            }
        }
        if (showSettings && controlsVisible) {
            Column(Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 10.dp).background(Color(0xEE202020), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Hold to fast-forward", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.5f, 1.75f, 2f).forEach { speed ->
                        FilterChip(selected = holdSpeed == speed, onClick = { holdSpeed = speed; prefs.edit().putFloat("hold_speed", speed).apply(); revealControls() }, label = { Text("${speed}×") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Picture in Picture", color = Color.White)
                    Switch(checked = pipEnabled, onCheckedChange = { pipEnabled = it; prefs.edit().putBoolean("pip_enabled", it).apply(); revealControls() })
                }
                TextButton(onClick = { zoom = 1f; revealControls() }) { Text("Reset zoom") }
            }
        }
        message?.let { warning ->
            Row(Modifier.align(Alignment.BottomCenter).background(Color(0xDD202020)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(warning, color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { retry = 0; retryPending = true }) { Text("Retry") }
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
    else "%d:%02d".format(minutes, seconds % 60)
}
