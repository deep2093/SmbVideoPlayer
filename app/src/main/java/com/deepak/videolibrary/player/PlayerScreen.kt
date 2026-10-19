package com.deepak.videolibrary.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.player.SmbPlaybackDataSource
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    video: VideoEntity,
    settings: SmbSettings,
    vm: LibraryViewModel,
    playlist: List<VideoEntity> = listOf(video),
    onQueueEnded: () -> Unit = {},
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val gestureScope = rememberCoroutineScope()
    var singleTap by remember { mutableStateOf<Job?>(null) }
    val accessibility = LocalAccessibilityManager.current
    val controlsTimeout =
        accessibility?.calculateRecommendedTimeoutMillis(
            4_000L,
            containsIcons = true,
            containsText = true,
            containsControls = true,
        ) ?: 4_000L
    val prefs = remember { context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE) }
    // Starting playback explicitly makes a previously dismissed item eligible again.
    LaunchedEffect(video.relativePath) {
        vm.hideContinue(video.relativePath, false)
    }
    val volumeGestureEnabled = remember { prefs.getBoolean("volume_gesture_enabled", true) }
    val brightnessGestureEnabled = remember { prefs.getBoolean("brightness_gesture_enabled", true) }
    val profile =
        remember(settings) {
            SmbProfile(
                settings.host,
                settings.share,
                settings.rootPath,
                settings.username,
                settings.password,
                settings.domain,
                settings.requireSigning,
                settings.requireEncryption,
            )
        }
    // A virtual folder is a real ExoPlayer playlist: video N advances to N+1 automatically.
    val queue = remember(video.relativePath, playlist) {
        val entries = playlist.ifEmpty { listOf(video) }
        entries.distinctBy { it.relativePath }.ifEmpty { listOf(video) }
    }
    val initialIndex = remember(video.relativePath, queue) { queue.indexOfFirst { it.relativePath == video.relativePath }.coerceAtLeast(0) }
    val player = remember(queue, profile) {
        AppDiagnostics.record(context, "player", "open")
        val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
        // Size the sample buffer to the device: tablets with plenty of heap keep the full 96 MB
        // for 4K Wi-Fi jitter, smaller devices get less so playback never competes with the UI.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val bufferBytes = when {
            activityManager.isLowRamDevice -> 32 * 1024 * 1024
            activityManager.memoryClass >= 256 -> 96 * 1024 * 1024
            else -> 64 * 1024 * 1024
        }
        // At 28 Mbps, 20 seconds needs about 70 MB. Bound sample memory while
        // allowing a useful buffer for Wi-Fi jitter; do not force software decoding.
        val loadControl = DefaultLoadControl
            .Builder()
            .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
            .setTargetBufferBytes(bufferBytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        ExoPlayer.Builder(context, renderers).setLoadControl(loadControl).build().apply {
            setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_NETWORK)
            val factory = ProgressiveMediaSource.Factory(
                SmbPlaybackDataSource.Factory(profile) { detail -> AppDiagnostics.record(context, "smb_read", detail) },
            )
            setMediaSources(
                queue.map { item ->
                    val uri = Uri
                        .Builder()
                        .scheme("smbvideo")
                        .authority("library")
                        .appendPath(item.name)
                        .appendQueryParameter("path", item.relativePath)
                        .appendQueryParameter("size", item.size.toString())
                        .build()
                    factory.createMediaSource(MediaItem.fromUri(uri))
                },
            )
            seekTo(initialIndex, 0)
            playWhenReady = false
        }
    }
    var transitionSerial by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val activeVideo = queue.getOrElse(currentIndex) { video }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    fun revealControls() {
        controlsVisible = true
        controlsInteraction++
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val beforeBrightness = window?.attributes?.screenBrightness
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val behavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            if (behavior != null)controller?.systemBarsBehavior = behavior
            if (window != null &&
                beforeBrightness != null
            ) {
                val attrs = window.attributes
                attrs.screenBrightness = beforeBrightness
                window.attributes = attrs
            }
        }
    }
    LaunchedEffect(controlsVisible, controlsInteraction, currentIndex, showTracks, showSettings, controlsTimeout) {
        if (controlsVisible && !showTracks && !showSettings) {
            delay(controlsTimeout)
            controlsVisible = false
        }
    }
    val aspectModes =
        listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
            AspectRatioFrameLayout.RESIZE_MODE_FILL,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH,
            AspectRatioFrameLayout.RESIZE_MODE_FIXED_HEIGHT,
        )
    val aspectLabels = listOf("Fit", "Crop", "Stretch", "Width", "Height")
    var aspectIndex by remember { mutableIntStateOf(0) }
    val aspect = aspectModes[aspectIndex]
    var zoom by remember { mutableFloatStateOf(1f) }
    var holdSpeed by remember { mutableFloatStateOf(prefs.getFloat("hold_speed", 1.5f)) }
    var pipEnabled by remember { mutableStateOf(prefs.getBoolean("pip_enabled", false)) }
    var trackRefresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(player.playWhenReady) }
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
        val saved = vm.resumeFor(video.relativePath)
        val resume = if (saved != null && !(saved.durationMs > 0 && saved.positionMs >= saved.durationMs - 10_000)) saved.positionMs else 0L
        player.seekTo(initialIndex, resume.coerceAtLeast(0))
        player.prepare()
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))player.play()
        var saves = 0
        var lastSavedPosition = -1L
        var lastSavedDuration = -1L
        while (true) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.coerceAtLeast(0)
            isPlaying = player.playWhenReady
            currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
            if (++saves >= 5) {
                saves = 0
                if (player.playbackState != Player.STATE_IDLE && (position != lastSavedPosition || duration != lastSavedDuration)) {
                    vm.saveProgress(queue.getOrElse(currentIndex) { video }.relativePath, position, duration)
                    lastSavedPosition = position
                    lastSavedDuration = duration
                }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(transitionSerial) {
        if (transitionSerial > 0) {
            val index = player.currentMediaItemIndex
            val item = queue.getOrNull(index)
            if (item != null) {
                val saved = vm.resumeFor(item.relativePath)
                if (index == player.currentMediaItemIndex && saved != null && player.playbackState != Player.STATE_ENDED) {
                    val resume = com.deepak.videolibrary.player.PlaybackPolicy
                        .resumePosition(saved.positionMs, saved.durationMs)
                    if (resume > 0) player.seekTo(index, resume)
                }
            }
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
                retryPending = false
            } else {
                message = "Connection unavailable. Check Wi-Fi or SMB server, then tap Retry."
                retryPending = false
            }
        }
    }
    DisposableEffect(player) {
        fun updateKeepAwake() {
            val active =
                player.playWhenReady &&
                    player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                    (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)
            if (active) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        var bufferingSince = 0L
        val analytics = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                AppDiagnostics.record(context, "decoder", "name=$decoderName initMs=$initializationDurationMs")
            }
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                AppDiagnostics.record(
                    context,
                    "frames",
                    "dropped=$droppedFrames elapsedMs=$elapsedMs bufferedMs=${player.totalBufferedDuration}",
                )
            }
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = player.playWhenReady
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isPlaying = playWhenReady
                updateKeepAwake()
                if (!playWhenReady &&
                    player.playbackState != Player.STATE_IDLE
                ) {
                    vm.saveProgress(
                        queue.getOrElse(player.currentMediaItemIndex) { video }.relativePath,
                        player.currentPosition,
                        player.duration,
                    )
                }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (oldPosition.mediaItemIndex !=
                    newPosition.mediaItemIndex
                ) {
                    vm.saveProgress(
                        queue.getOrElse(oldPosition.mediaItemIndex) { video }.relativePath,
                        oldPosition.positionMs,
                        duration,
                    )
                }
            }
            override fun onPlaybackSuppressionReasonChanged(reason: Int) {
                isPlaying = player.playWhenReady
                updateKeepAwake()
            }
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                AppDiagnostics.record(context, "video", "width=" + videoSize.width + " height=" + videoSize.height)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val format = player.videoFormat
                if (format != null) {
                    AppDiagnostics.record(
                        context,
                        "codec",
                        "mime=" + format.sampleMimeType + " width=" + format.width + " height=" + format.height + " fps=" + format.frameRate,
                    )
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                AppDiagnostics.record(context, "error", error.errorCodeName)
                message = "Playback interruption: ${error.errorCodeName}"
                retryPending =
                    error.errorCode in
                    setOf(
                        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    )
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                updateKeepAwake()
                if (playbackState == Player.STATE_BUFFERING) bufferingSince = SystemClock.elapsedRealtime()
                if (playbackState == Player.STATE_READY && bufferingSince > 0L) {
                    AppDiagnostics.record(
                        context,
                        "buffer",
                        "waitMs=${SystemClock.elapsedRealtime() - bufferingSince} bufferedMs=${player.totalBufferedDuration}",
                    )
                    bufferingSince = 0L
                }
                if (playbackState == Player.STATE_READY) {
                    retry = 0
                    message = null
                    AppDiagnostics.record(context, "player", "ready")
                }
                if (playbackState == Player.STATE_BUFFERING) AppDiagnostics.record(context, "player", "buffering")
                if (playbackState == Player.STATE_ENDED) { revealControls(); onQueueEnded() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverrides()
                    .build()
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) transitionSerial++
                zoom = 1f
                revealControls()
            }
        }
        player.addListener(listener)
        player.addAnalyticsListener(analytics)
        updateKeepAwake()
        onDispose {
            if (player.playbackState !=
                Player.STATE_IDLE
            ) {
                vm.saveProgress(
                    queue
                        .getOrElse(player.currentMediaItemIndex.coerceAtLeast(0)) {
                            video
                        }.relativePath,
                    player.currentPosition,
                    player.duration,
                )
            }
            player.removeListener(listener)
            player.removeAnalyticsListener(analytics)
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            AppDiagnostics.record(context, "player", "closed")
            singleTap?.cancel()
            player.release()
        }
    }
    DisposableEffect(lifecycleOwner, activity, pipEnabled, player) {
        val main = activity as? com.deepak.videolibrary.MainActivity
        val previous = main?.onLeavePlayback
        if (main != null) {
            main.onLeavePlayback = {
                if (pipEnabled &&
                    Build.VERSION.SDK_INT >= 26 &&
                    main.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
                    player.isPlaying
                ) {
                    val size = player.videoSize
                    val ratio = if (size.height > 0)size.width.toFloat() * size.pixelWidthHeightRatio / size.height else 16f / 9f
                    runCatching {
                        main.enterPictureInPictureMode(
                            PictureInPictureParams
                                .Builder()
                                .setAspectRatio(
                                    Rational(
                                        (
                                            ratio.coerceIn(0.42f, 2.39f) *
                                                1000
                                        ).roundToInt(),
                                        1000,
                                    ),
                                ).build(),
                        )
                    }
                }
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            main?.onLeavePlayback = previous
        }
    }
    BackHandler {
        if (showTracks) {
            showTracks = false
        } else if (showSettings) {
            showSettings = false
        } else {
            onClose()
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            onReset = null,
            onRelease = {
                it.player = null
            },
            factory = {
                android.view.LayoutInflater
                    .from(it)
                    .inflate(com.deepak.videolibrary.R.layout.playback_view, null) as PlayerView
            },
            update = { view ->
                view.player = player
                view.useController = false
                view.resizeMode = aspect
            },
            modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer {
                scaleX = zoom
                scaleY = zoom
            },
        )
        // One full-width handler keeps both pinch pointers in the same coordinate space.
        // Separate stacked detectors previously competed and swallowed gestures.
        Box(
            Modifier.fillMaxSize().pointerInput(player, holdSpeed, activity, volumeGestureEnabled, brightnessGestureEnabled) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val start = down.position
                    val side = if (start.x < size.width / 2f) 0 else 1
                    var pinching = false
                    var moved = false
                    var mode = 0 // 0=tap/hold, 1=seek, 2=volume/brightness
                    var startPosition = player.currentPosition.coerceAtLeast(0L)
                    var appliedVolume = -1
                    var appliedBrightness = -1
                    val startVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    val startBrightness = activity
                        ?.window
                        ?.attributes
                        ?.screenBrightness
                        ?.takeIf { it >= 0f } ?: 0.5f
                    val startTime = System.currentTimeMillis()
                    var previousSpeed = player.playbackParameters.speed
                    var holding = false
                    var released = false
                    try {
                        while (!released) {
                            val remainingHold = if (!moved &&
                                !holding
                            ) {
                                (450L - (System.currentTimeMillis() - startTime)).coerceAtLeast(1L)
                            } else {
                                10_000L
                            }
                            val event = withTimeoutOrNull(remainingHold) { awaitPointerEvent() }
                            if (event == null) {
                                if (!moved && !holding) {
                                    previousSpeed = player.playbackParameters.speed
                                    player.setPlaybackSpeed(holdSpeed)
                                    holding = true
                                    boost = true
                                }
                                continue
                            }
                            if (event.changes.count { it.pressed } >= 2) {
                                pinching = true
                                moved = true
                                mode = 3
                                seekingGesture = false
                                if (holding) {
                                    player.setPlaybackSpeed(previousSpeed)
                                    holding = false
                                    boost = false
                                }
                                zoom = (zoom * event.calculateZoom()).coerceIn(1f, 4f)
                                gestureMessage = "Zoom ${(zoom * 100).roundToInt()}%"
                                event.changes.forEach { it.consume() }
                                continue
                            }
                            if (pinching) {
                                event.changes.forEach { it.consume() }
                                if (event.changes.none { it.pressed }) break
                                continue
                            }
                            val pointer = event.changes.firstOrNull { it.id == down.id }
                            if (pointer == null || !pointer.pressed) {
                                released = true
                                break
                            }
                            val current = pointer.position
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
                                    if (side == 1 && volumeGestureEnabled) {
                                        val target = (startVolume + fraction * maxVolume).roundToInt().coerceIn(0, maxVolume)
                                        if (target !=
                                            appliedVolume
                                        ) {
                                            audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                                            appliedVolume =
                                                target
                                        }
                                        gestureMessage = "Volume ${(100f * target / maxVolume).roundToInt()}%"
                                    } else if (side == 0 && brightnessGestureEnabled) {
                                        activity?.window?.let { window ->
                                            val attrs = window.attributes
                                            val percent = ((startBrightness + fraction).coerceIn(0.02f, 1f) * 100).roundToInt()
                                            attrs.screenBrightness = percent / 100f
                                            if (percent != appliedBrightness) {
                                                window.attributes = attrs
                                                appliedBrightness = percent
                                            }
                                            gestureMessage = "Brightness ${(attrs.screenBrightness * 100).roundToInt()}%"
                                        }
                                    }
                                }
                            }
                        }
                    } finally {
                        if (holding) player.setPlaybackSpeed(previousSpeed)
                        boost = false
                    }
                    if (mode == 1 && seekingGesture) player.seekTo(seekingTarget)
                    seekingGesture = false
                    if (!moved && !holding) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 350L && lastTapSide == side) {
                            singleTap?.cancel()
                            val delta = if (side == 0) -10_000L else 10_000L
                            player.seekTo(
                                (player.currentPosition + delta).coerceIn(
                                    0L,
                                    player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE,
                                ),
                            )
                            gestureMessage = if (side == 0) "−10 sec" else "+10 sec"
                            lastTapTime = 0L
                        } else {
                            lastTapTime = now
                            lastTapSide = side
                            singleTap?.cancel()
                            singleTap = gestureScope.launch {
                                delay(350)
                                if (controlsVisible) controlsVisible = false else revealControls()
                            }
                        }
                    }
                }
            },
        )
        if (seekingGesture) {
            Text(
                "Release to seek: ${formatPlaybackTime(seekingTarget)}",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC101010), RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        if (boost) Text("$holdSpeed×", color = Color.White, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
        LaunchedEffect(gestureMessage) {
            if (gestureMessage.isNotEmpty()) {
                delay(1_100)
                gestureMessage = ""
            }
        }
        if (gestureMessage.isNotBlank()) {
            Text(
                gestureMessage,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(12.dp),
            )
        }
        if (controlsVisible) {
            // Overlay is transient; controls never permanently consume video height.
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth().background(Color(0xA6000000)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.Filled.ArrowBack, "Back to library", tint = Color.White) }
                    Text(
                        activeVideo.name,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White,
                    )
                    IconButton(onClick = {
                        showTracks = !showTracks
                        showSettings = false
                        revealControls()
                    }) {
                        Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White)
                    }
                    IconButton(onClick = {
                        showSettings = !showSettings
                        showTracks = false
                        revealControls()
                    }) {
                        Icon(Icons.Filled.MoreVert, "Playback options", tint = Color.White)
                    }
                }
                Column(Modifier.fillMaxWidth().background(Color(0xEB000000)).padding(horizontal = 12.dp, vertical = 5.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatPlaybackTime(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.weight(1f))
                        Text(formatPlaybackTime(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                    var scrubbing by remember { mutableStateOf(false) }
                    var seekPosition by remember { mutableFloatStateOf(0f) }
                    Slider(
                        value = if (scrubbing) {
                            seekPosition
                        } else {
                            (
                                position.toDouble() / duration.coerceAtLeast(
                                    1L,
                                )
                            ).coerceIn(0.0, 1.0).toFloat()
                        },
                        onValueChange = {
                            scrubbing = true
                            seekPosition = it
                            revealControls()
                        },
                        onValueChangeFinished = {
                            player.seekTo((seekPosition.toDouble() * duration.coerceAtLeast(0L)).toLong())
                            scrubbing =
                                false
                            revealControls()
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFEF5350),
                            activeTrackColor = Color(0xFFD32F2F),
                            inactiveTrackColor = Color.DarkGray,
                        ),
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = {
                            player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0))
                            revealControls()
                        }, modifier = Modifier.size(64.dp)) {
                            Icon(Icons.Filled.Replay10, "Rewind 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        FilledIconButton(
                            onClick = {
                                if (player.playWhenReady) player.pause() else player.play()
                                revealControls()
                            },
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = Color(0xFFD32F2F),
                                contentColor = Color.White,
                            ),
                        ) {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(40.dp),
                            )
                        }
                        IconButton(onClick = {
                            player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration.coerceAtLeast(0)))
                            revealControls()
                        }, modifier = Modifier.size(64.dp)) {
                            Icon(Icons.Filled.Forward10, "Forward 10 seconds", tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${currentIndex + 1}/${queue.size}", color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        TextButton(onClick = {
                            aspectIndex = (aspectIndex + 1) % aspectModes.size
                            zoom = 1f
                            revealControls()
                        }) { Text("Aspect: ${aspectLabels[aspectIndex]} ↻", color = Color.White) }
                        if (queue.size > 1 || vm.playbackHasMore) {
                            TextButton(onClick = {
                                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else onQueueEnded()
                                revealControls()
                            }) { Text("Next ›") }
                        }
                    }
                }
            }
        }
        if (showTracks && controlsVisible) {
            val trackGroups = player.currentTracks.groups
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp)
                    .widthIn(max = 290.dp)
                    .background(Color(0xF21A1A1E), RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text("Audio & subtitles", color = Color.White, style = MaterialTheme.typography.titleMedium)
                listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_TEXT).forEach { trackType ->
                    Text(
                        if (trackType == C.TRACK_TYPE_AUDIO) "Audio tracks" else "Subtitles",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (trackType == C.TRACK_TYPE_TEXT) {
                        TextButton(onClick = {
                            player.trackSelectionParameters = player.trackSelectionParameters
                                .buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                .build()
                            trackRefresh++
                            revealControls()
                        }) { Text("Off", color = Color.White) }
                    }
                    val groups = trackGroups.filter { it.type == trackType }
                    if (groups.isEmpty()) Text("No available tracks", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    groups.forEach { group ->
                        for (index in 0 until group.length) {
                            val supported = group.isTrackSupported(index)
                            val format = group.getTrackFormat(index)
                            val label = format.label ?: format.language ?: "Track ${index + 1}"
                            TextButton(enabled = supported, onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(trackType, false)
                                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                                    .build()
                                trackRefresh++
                                revealControls()
                            }) {
                                Text(
                                    if (supported) label else "$label · unsupported (${format.sampleMimeType ?: "codec"})",
                                    color = if (!supported) {
                                        Color.Gray
                                    } else if (group.isTrackSelected(index)) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color.White
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = { showTracks = false }) { Text("Close") }
            }
        }
        if (showSettings && controlsVisible) {
            Column(
                Modifier
                    .align(
                        Alignment.TopEnd,
                    ).padding(top = 52.dp, end = 10.dp)
                    .background(Color(0xEE202020), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("Hold to fast-forward", color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.5f, 1.75f, 2f).forEach { speed ->
                        FilterChip(selected = holdSpeed == speed, onClick = {
                            holdSpeed = speed
                            prefs.edit().putFloat("hold_speed", speed).apply()
                            revealControls()
                        }, label = { Text("$speed×") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Picture in Picture", color = Color.White)
                    Switch(checked = pipEnabled, onCheckedChange = {
                        pipEnabled = it
                        prefs.edit().putBoolean("pip_enabled", it).apply()
                        revealControls()
                    })
                }
                TextButton(onClick = {
                    zoom = 1f
                    aspectIndex = 0
                    revealControls()
                }) { Text("Reset zoom", color = Color.White) }
            }
        }
        message?.let { warning ->
            Row(
                Modifier.align(Alignment.BottomCenter).background(Color(0xDD202020)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(warning, color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    retry = 0
                    retryPending = true
                }) { Text("Retry") }
            }
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes % 60, seconds % 60)
    } else {
        "%d:%02d".format(minutes, seconds % 60)
    }
}
