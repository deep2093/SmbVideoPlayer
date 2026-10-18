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
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
fun PlayerScreen(video: VideoEntity, settings: SmbSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val prefs = remember { context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    val profile = remember(settings) { SmbProfile(settings.host, settings.share, settings.rootPath, settings.username, settings.password) }
    val mediaUri = remember(video.id) {
        Uri.Builder().scheme("smbvideo").authority("library")
            .appendQueryParameter("path", video.relativePath)
            .appendQueryParameter("size", video.size.toString()).build()
    }
    val player = remember(video.id, profile) {
        ExoPlayer.Builder(context).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory(SmbPlaybackDataSource.Factory(profile))
                .createMediaSource(MediaItem.fromUri(mediaUri)))
            prepare()
            seekTo(prefs.getLong("resume_${video.id}", 0L).coerceAtLeast(0))
            playWhenReady = true
        }
    }
    var aspect by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
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

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            isPlaying = player.isPlaying
            if (position > 2_000) prefs.edit().putLong("resume_${video.id}", position).apply()
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
            }
        }
        player.addListener(listener)
        onDispose {
            prefs.edit().putLong("resume_${video.id}", player.currentPosition.coerceAtLeast(0)).apply()
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
    BackHandler { onClose() }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("‹ Library") }
            Text(video.name, maxLines = 1, color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { showSettings = !showSettings }) { Text("Options") }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            AndroidView(factory = { PlayerView(it).apply {
                this.player = player
                useController = false // Custom controls remain clickable beneath gesture regions
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            } }, update = { view ->
                view.resizeMode = aspect
                view.videoSurfaceView?.apply { scaleX = zoom; scaleY = zoom }
            }, modifier = Modifier.fillMaxSize())
            Row(Modifier.fillMaxSize()) {
                repeat(2) { side ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Box(Modifier.fillMaxSize().pointerInput(player, side, holdSpeed) {
                            detectTapGestures(onDoubleTap = {
                                val delta = if (side == 0) -5_000L else 10_000L
                                player.seekTo((player.currentPosition + delta).coerceIn(0L, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
                                gestureMessage = if (side == 0) "−5 sec" else "+10 sec"
                            }, onPress = {
                                val earlyRelease = withTimeoutOrNull(450L) { tryAwaitRelease() }
                                if (earlyRelease == null) {
                                    val previous = player.playbackParameters.speed
                                    boost = true
                                    player.setPlaybackSpeed(holdSpeed)
                                    try { tryAwaitRelease() } finally { player.setPlaybackSpeed(previous); boost = false }
                                }
                            })
                        })
                        // Vertical right-side volume, left-side screen brightness.
                        Box(Modifier.fillMaxSize().pointerInput(side, activity) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                val delta = -drag.y / size.height.coerceAtLeast(1).toFloat()
                                if (side == 0) {
                                    activity?.window?.let { window ->
                                        val lp = window.attributes
                                        val current = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        lp.screenBrightness = (current + delta * 2f).coerceIn(0.02f, 1f)
                                        window.attributes = lp
                                        gestureMessage = "Brightness ${(lp.screenBrightness * 100).roundToInt()}%"
                                    }
                                } else {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                    val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                                    val target = (current + delta * max * 2).roundToInt().coerceIn(0, max)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                    gestureMessage = "Volume ${(100f * target / max.coerceAtLeast(1)).roundToInt()}%"
                                }
                            }
                        })
                    }
                }
            }
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    if (zoomChange != 1f) {
                        zoom = (zoom * zoomChange).coerceIn(1f, 3f)
                        gestureMessage = "Zoom ${(zoom * 100).roundToInt()}%"
                    }
                }
            })
            if (boost) Text("${holdSpeed}×", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(12.dp))
            if (gestureMessage.isNotBlank()) Text(gestureMessage, color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp))
        }
        if (showSettings) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1.5f, 1.75f, 2f).forEach { speed ->
                        FilterChip(selected = holdSpeed == speed, onClick = { holdSpeed = speed; prefs.edit().putFloat("hold_speed", speed).apply() }, label = { Text("Hold ${speed}×") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Picture in Picture (optional)", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = pipEnabled, onCheckedChange = { pipEnabled = it; prefs.edit().putBoolean("pip_enabled", it).apply() })
                }
                Text("Pinch to zoom (1–3×), swipe left for brightness, right for volume", style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { player.seekTo((player.currentPosition - 5_000L).coerceAtLeast(0)) }) { Text("−5s") }
            TextButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) { Text(if (isPlaying) "Pause" else "Play") }
            TextButton(onClick = { player.seekTo(player.currentPosition + 10_000L) }) { Text("+10s") }
            TextButton(onClick = { zoom = 1f }) { Text("Reset zoom") }
        }
        Slider(value = position.coerceAtMost(duration).toFloat(), onValueChange = { player.seekTo(it.toLong()) }, valueRange = 0f..duration.coerceAtLeast(1).toFloat(), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Fit" to AspectRatioFrameLayout.RESIZE_MODE_FIT, "Crop" to AspectRatioFrameLayout.RESIZE_MODE_ZOOM, "Stretch" to AspectRatioFrameLayout.RESIZE_MODE_FILL).forEach { (name, mode) ->
                TextButton(onClick = { aspect = mode; zoom = 1f }) { Text(name) }
            }
        }
        message?.let { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).padding(8.dp))
            TextButton(onClick = { retry = 0; retryPending = true }) { Text("Retry") }
        } }
    }
}
