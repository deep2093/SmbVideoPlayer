package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.ui.SkyWingQueueDialog
import com.deepak.videolibrary.R

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.deepak.videolibrary.player.SmoothVideoView
import com.deepak.videolibrary.player.NativeSvpEffect
import com.deepak.videolibrary.player.NativeSvpScale
import com.deepak.videolibrary.player.NativeSvpStream
import com.deepak.videolibrary.player.NativeSvpController
import com.deepak.videolibrary.player.NativeSvpRenderersFactory
import com.deepak.videolibrary.player.NativeSvpSpeedCheck
import com.deepak.videolibrary.player.NativeSvpTuning
import com.deepak.videolibrary.player.GpuPlaybackReport
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
    // While this screen streams, the WireGuard tunnel (if any) must not be taken down automatically.
    DisposableEffect(Unit) {
        val vpnHold = com.deepak.videolibrary.vpn.WireGuardManager.get(context).beginPlayback()
        onDispose { vpnHold.release() }
    }
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
    // Playback gestures stay enabled without a global Playback settings page.
    val volumeGestureEnabled = true
    val brightnessGestureEnabled = true
    var smoothPref by remember {
        mutableStateOf(prefs.getBoolean("experimental_smooth_60fps", false))
    }
    // HDR10, HDR10+, HLG and Dolby Vision videos play straight to the screen: smoothing switches itself off for
    // them (the saved choice stays, so the next normal video is smoothed again).
    var hdrVideo by remember { mutableStateOf(false) }
    // Videos above 60 fps (90, 120, ...) already move smoothly and cannot go through the 60 fps smoothing engine, so
    // smoothing steps aside for them too and the screen is asked for its fastest refresh rate.
    var highFpsVideo by remember { mutableStateOf(false) }
    // A video the smoothing engine failed on plays normally; your smoothing setting itself stays on.
    var smoothFailedPath by remember { mutableStateOf<String?>(null) }
    var smoothRetry by remember { mutableStateOf("" to 0) }
    var playingPath by remember { mutableStateOf(video.relativePath) }
    val smooth60 = smoothPref && !hdrVideo && !highFpsVideo && smoothFailedPath != playingPath
    val nativeAvailable = remember { NativeSvpStream.available }
    val nativeController = remember { NativeSvpController() }
    // CPU smoothing (Adreno phones) runs at 1080p only after this device passed the 1080p speed check for this app
    // version, and drops back to 720p for the session (and later ones) if live playback cannot keep up.
    val versionCode = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L) }
    var showGpuLab by remember { mutableStateOf(false) }
    // The CPU engine is only the automatic fallback for phones without a working GPU path; it always runs at 720p.
    // Run the engine on the GPU on Snapdragon (Adreno) phones too, with the driver fix found by the GPU lab.
    val gpuFailedKey = "native_gpu_failed_$versionCode"
    var adrenoGpu by remember { mutableStateOf(!prefs.getBoolean(gpuFailedKey, false)) }
    remember { GpuPlaybackReport.installCrashCapture(context); true }
    NativeSvpTuning.adrenoGpu = adrenoGpu
    // Smoothing quality: 0 = Performance 720p, 1 = Balanced 900p, 2 = Quality 1080p, 3 = Max 1440p. The GPU may use up
    // to the highest level its quality check passed (1080p until checked; 1440p only after passing). CPU smoothing on
    // Snapdragon stays at 720p unless the CPU 1080p check passed. During playback the watchdog lowers [sessionCap] when
    // the device falls behind and raises it again after a calm stretch.
    var quality by remember { mutableStateOf(prefs.getInt("native_quality", 2).coerceIn(0, 3)) }
    var gpuCheck by remember { mutableStateOf(GpuQualityStore.read(context)) } // "<max level>|summary"
    LaunchedEffect(Unit) {
        // The first-run check (run from the main screen) may finish while this screen is closed; pick it up on open.
        gpuCheck = GpuQualityStore.read(context)
    }
    var gpuChecking by remember { mutableStateOf(false) }
    var gpuCheckStatus by remember { mutableStateOf("") }
    val gpuAllowed = (gpuCheck?.substringBefore('|')?.toIntOrNull() ?: 2).coerceIn(0, 3)
    var sessionCap by remember { mutableIntStateOf(3) }
    // Power mode: 0 = Battery (at most 900p, lowered sooner when the phone warms), 1 = Balanced (default; also at most 900p
    // while Android's battery saver is on), 2 = Quality (lowered only when the phone is getting seriously hot).
    var powerMode by remember { mutableIntStateOf(prefs.getInt("native_power_mode", 1).coerceIn(0, 2)) }
    var batterySaver by remember { mutableStateOf(false) }
    var heatCap by remember { mutableIntStateOf(3) }
    val modeCap = if (powerMode == 0 || (powerMode == 1 && batterySaver)) 1 else 3
    // Highest level the device passed in its GPU check; the top stop (1440p) is also offered when only 1080p passed,
    // as 1080p smoothing enlarged and sharpened to the screen.
    val maxStop = if (gpuAllowed >= 2) 3 else gpuAllowed
    val gpuLevel = minOf(quality, gpuAllowed, sessionCap, heatCap, modeCap).coerceAtLeast(0)
    val cpuLevel = minOf(quality, 0, sessionCap).coerceAtLeast(0)
    // The top stop: smoothed frames smaller than the screen are enlarged (up to 1440p) with a sharp filter.
    val displayOrientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    val sharpTarget = remember(displayOrientation) {
        val metrics = context.resources.displayMetrics
        intArrayOf(minOf(metrics.widthPixels, 2560), minOf(metrics.heightPixels, 1440))
    }
    val sharp = smooth60 && nativeAvailable && quality == 3 && gpuLevel >= 2
    NativeSvpTuning.upscaleTarget = if (sharp) sharpTarget else null
    val nativeEffects = remember(gpuLevel, cpuLevel, adrenoGpu, sharp, sharpTarget[0], sharpTarget[1]) {
        listOf(NativeSvpScale(NativeSvpTuning.levelSize(gpuLevel), NativeSvpTuning.levelSize(cpuLevel)), NativeSvpEffect(nativeController))
    }
    val nativeInUse by rememberUpdatedState(smooth60 && nativeAvailable)
    val nativeFrames = remember { java.util.concurrent.atomic.AtomicInteger() }
    var nativeShownFps by remember { mutableStateOf(0f) }
    // 0 = clean, 1 = balanced, 2 = maximum smoothing (legacy backend only).
    var smoothDebug by remember { mutableStateOf(prefs.getBoolean("smooth_60fps_debug", false)) }
    val smoothTargetFps = 60
    var smoothStrength by remember { mutableStateOf(prefs.getInt("smooth_60fps_strength", 1).coerceIn(0, 2)) }
    // Frame-rate indicator: what the video really plays at, and what the screen shows.
    var showFps by remember { mutableStateOf(prefs.getBoolean("show_fps_indicator", true)) }
    var smoothViewRef by remember { mutableStateOf<SmoothVideoView?>(null) }
    var smoothStats by remember { mutableStateOf<com.deepak.videolibrary.player.FrameBlendRenderer.Stats?>(null) }
    var fileFps by remember { mutableStateOf(0f) }
    var decodedFps by remember { mutableStateOf(0f) }
    var displayHz by remember { mutableStateOf(0f) }
    // Prefer a 60 Hz display mode for even output cadence. SmoothVideoView performs
    // motion estimation separately; the display-mode request alone adds no frames.
    DisposableEffect(activity, highFpsVideo) {
        val window = activity?.window
        val beforeId = window?.attributes?.preferredDisplayModeId ?: 0
        if (highFpsVideo && window != null) {
            val display = window.decorView.display
            val current = display?.mode
            val fastest = display?.supportedModes
                ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
                ?.maxByOrNull { it.refreshRate }
            if (fastest != null && fastest.refreshRate >= 90f) {
                val params = window.attributes
                params.preferredDisplayModeId = fastest.modeId
                window.attributes = params
            }
        }
        onDispose {
            if (highFpsVideo && window != null) {
                val params = window.attributes
                params.preferredDisplayModeId = beforeId
                window.attributes = params
            }
        }
    }
    DisposableEffect(activity, hdrVideo) {
        val window = activity?.window
        if (hdrVideo && window != null && Build.VERSION.SDK_INT >= 26) {
            window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
            NativeSvpTuning.lastVideoInfo += " | window colour mode set to HDR (now ${window.colorMode})"
        }
        onDispose { if (window != null && Build.VERSION.SDK_INT >= 26) window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT }
    }
    DisposableEffect(activity, smooth60, smoothTargetFps) {
        val window = activity?.window
        val beforeId = window?.attributes?.preferredDisplayModeId ?: 0
        if (smooth60 && window != null) {
            val requestedHz = if (smooth60) smoothTargetFps.toFloat() else 60f
            val display = window.decorView.display
            val current = display?.mode
            val mode = display?.supportedModes
                ?.filter { current == null || (it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight) }
                ?.minByOrNull { kotlin.math.abs(it.refreshRate - requestedHz) }
                ?.takeIf { kotlin.math.abs(it.refreshRate - requestedHz) < 2f }
            if (mode != null) {
                val params = window.attributes
                params.preferredDisplayModeId = mode.modeId
                window.attributes = params
            }
        }
        onDispose {
            if (window != null) {
                val params = window.attributes
                params.preferredDisplayModeId = beforeId
                window.attributes = params
            }
        }
    }
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
                alternateHosts = com.deepak.videolibrary.smb.HostFallback.parse(settings.alternateHosts),
            )
        }
    // A virtual folder is a real ExoPlayer playlist: video N advances to N+1 automatically.
    val queue = remember(video.relativePath, playlist) {
        val entries = playlist.ifEmpty { listOf(video) }
        entries.distinctBy { it.relativePath }.ifEmpty { listOf(video) }
    }
    val initialIndex = remember(video.relativePath, queue) { queue.indexOfFirst { it.relativePath == video.relativePath }.coerceAtLeast(0) }
    // Once a player has had video effects set (even an empty list), Media3 keeps sending every frame through its
    // GPU effects pipeline, which costs speed (a 120 fps video stalled at ~100). Without smoothing the player must
    // never touch effects, so switching smoothing off rebuilds the player at the same spot instead.
    var playerGeneration by remember { mutableIntStateOf(0) }
    var restartAt by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    val effectsUsed = remember { booleanArrayOf(false) }
    val player = remember(queue, profile, playerGeneration) {
        AppDiagnostics.record(context, "player", "open")
        effectsUsed[0] = false
        val renderers = NativeSvpRenderersFactory(context, nativeController).setEnableDecoderFallback(true)
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
            // When a video has several audio languages, start with English (a video with no English track plays its default).
            trackSelectionParameters = trackSelectionParameters.buildUpon().setPreferredAudioLanguage("en").build()
            if (smooth60 && nativeAvailable) {
                setVideoEffects(nativeEffects)
                effectsUsed[0] = true
            }
            setVideoFrameMetadataListener { _, _, _, _ -> nativeFrames.incrementAndGet() }
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
            val phoneFactory = ProgressiveMediaSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(context))
            setMediaSources(
                queue.map { item ->
                    if (item.relativePath.startsWith(PHONE_URI_PREFIX)) {
                        // A video stored on this phone: plain content:// playback, no SMB involved.
                        return@map phoneFactory.createMediaSource(MediaItem.fromUri(Uri.parse(item.relativePath)))
                    }
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
    /** Recreates the player at the same video and moment; it gets smoothing only if smoothing is on at that point. */
    fun rebuildPlayer() {
        restartAt = player.currentMediaItemIndex.coerceAtLeast(0) to player.currentPosition.coerceAtLeast(0L)
        playerGeneration++
    }
    LaunchedEffect(player, smooth60, nativeAvailable, nativeEffects) {
        if (smooth60 && nativeAvailable) {
            // Effects only take hold on a player that had them from the start: a player built without smoothing
            // ignores them (smoothing switched back on stayed at the plain frame rate). Rebuild it with smoothing.
            if (effectsUsed[0]) player.setVideoEffects(nativeEffects) else rebuildPlayer()
        } else if (effectsUsed[0]) {
            rebuildPlayer()
        }
    }
    // Ask the system for steady (non-throttling-spike) performance while smoothing, where it supports that.
    DisposableEffect(activity, smooth60, nativeAvailable) {
        val window = activity?.window
        val power = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        val sustained = smooth60 && nativeAvailable && window != null && power?.isSustainedPerformanceModeSupported == true
        if (sustained) runCatching { window?.setSustainedPerformanceMode(true) }
        onDispose { if (sustained) runCatching { window?.setSustainedPerformanceMode(false) } }
    }
    // Quality watchdog for every native mode. After an 8 s grace period, three seconds in a row under 50 shown fps while
    // playing lowers the level by one (on the same engine); at the lowest level a Snapdragon GPU falls back to the CPU
    // engine. A stall report from the OpenCL wrapper falls back to the CPU straight away. After two calm minutes (and at
    // least three minutes after the last step down) it steps back up one level towards the chosen quality.
    val currentGpuLevel by rememberUpdatedState(gpuLevel)
    val currentCpuLevel by rememberUpdatedState(cpuLevel)
    val gpuTarget by rememberUpdatedState(minOf(quality, gpuAllowed, heatCap, modeCap))
    val cpuTarget by rememberUpdatedState(minOf(quality, 0))
    // Heat: lower the smoothing level when Android reports the phone is warming up, raise it again one step at a time
    // after 90 calm seconds. How early depends on the power mode.
    LaunchedEffect(smooth60, nativeAvailable, powerMode) {
        if (!(smooth60 && nativeAvailable)) return@LaunchedEffect
        val power = context.getSystemService(android.os.PowerManager::class.java) ?: return@LaunchedEffect
        var calmSince = 0L
        var lastStatus = -1
        while (true) {
            batterySaver = power.isPowerSaveMode
            val status = power.currentThermalStatus
            if (status != lastStatus) {
                lastStatus = status
                GpuPlaybackReport.log(context, "thermal status=$status powerMode=$powerMode batterySaver=$batterySaver")
            }
            val allowed = when (powerMode) {
                2 -> when { status >= 4 -> 0; status >= 3 -> 1; else -> 3 }
                1 -> when { status >= 3 -> 0; status >= 2 -> 1; else -> 3 }
                else -> when { status >= 2 -> 0; else -> 1 }
            }
            val now = SystemClock.elapsedRealtime()
            if (allowed < heatCap) {
                heatCap = allowed
                calmSince = 0L
                AppDiagnostics.record(context, "native-quality", "heat: capped at ${NativeSvpTuning.LEVEL_NAMES[allowed]}")
                android.widget.Toast.makeText(context, "Phone is warm; smoothing lowered to ${NativeSvpTuning.LEVEL_NAMES[allowed]}", android.widget.Toast.LENGTH_LONG).show()
            } else if (allowed > heatCap) {
                if (calmSince == 0L) calmSince = now
                else if (now - calmSince > 90_000L) {
                    heatCap = minOf(allowed, heatCap + 1)
                    calmSince = 0L
                    AppDiagnostics.record(context, "native-quality", "heat: raised to ${NativeSvpTuning.LEVEL_NAMES[heatCap]}")
                }
            } else calmSince = 0L
            delay(2000)
        }
    }
    LaunchedEffect(player, smooth60, nativeAvailable) {
        if (!(smooth60 && nativeAvailable)) return@LaunchedEffect
        val stallLog = NativeSvpTuning.stallLog(context)
        fun stallCount() = runCatching { stallLog.readText() }.getOrDefault("").split("=== OpenCL stall").size
        var stallsBefore = stallCount()
        var lastOutputs = NativeSvpTuning.outputs.get()
        var lastPictures = NativeSvpTuning.newPictures.get()
        var lastInputs = NativeSvpTuning.inputs.get()
        GpuPlaybackReport.log(context, "--- playback session ${java.util.Date()} ---")
        var lastCount = nativeFrames.get()
        var lastAt = SystemClock.elapsedRealtime()
        var playingSince = 0L
        var slow = 0
        var calm = 0
        var lastStepDown = 0L
        var lastPos = -1L
        fun toast(text: String) = android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
        while (true) {
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val count = nativeFrames.get()
            val fps = (count - lastCount) * 1000f / (now - lastAt).coerceAtLeast(1L)
            lastCount = count
            lastAt = now
            val stream = NativeSvpTuning.lastStream
            val onGpu = stream?.contains("OpenCL") == true
            val onAdrenoGpu = NativeSvpTuning.lastAdreno && onGpu
            val outputsNow = NativeSvpTuning.outputs.get()
            val picturesNow = NativeSvpTuning.newPictures.get()
            val inputsNow = NativeSvpTuning.inputs.get()
            GpuPlaybackReport.log(
                context,
                String.format(
                    java.util.Locale.ROOT,
                    "%s state=%d playing=%b pos=%.1fs shown=%.0ffps engineOut=%d newPictures=%d inputs=%d slowest=%.0fms cap=%d %s",
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                    player.playbackState, player.isPlaying, player.currentPosition / 1000.0, fps,
                    outputsNow - lastOutputs, picturesNow - lastPictures, inputsNow - lastInputs,
                    NativeSvpTuning.takeSlowest() / 1e6, sessionCap, stream ?: "-",
                ),
            )
            val fed = inputsNow - lastInputs > 0
            lastOutputs = outputsNow
            lastPictures = picturesNow
            lastInputs = inputsNow
            if (onAdrenoGpu && stallCount() > stallsBefore) {
                stallsBefore = stallCount()
                // One stall can be a passing driver hiccup: use the CPU engine for now, and give up on the GPU for
                // good only after the third stall.
                val stalls = prefs.getInt("native_gpu_stalls_$versionCode", 0) + 1
                prefs.edit().putInt("native_gpu_stalls_$versionCode", stalls).apply()
                if (stalls >= 3) prefs.edit().putBoolean(gpuFailedKey, true).apply()
                adrenoGpu = false
                playingSince = 0L
                AppDiagnostics.record(context, "native-quality", "GPU stalled, CPU engine")
                toast("GPU smoothing stalled; switched to the CPU engine")
                continue
            }
            if (!player.isPlaying || stream == null) { playingSince = 0L; slow = 0; calm = 0; continue }
            // Hold-to-fast-forward (or any speed other than normal) feeds the engine several times more frames than it
            // is built for, so it always falls behind then. That says nothing about the quality level: don't judge it,
            // and give it the usual settling time once normal speed is back.
            if (player.playbackParameters.speed != 1f) { playingSince = 0L; slow = 0; calm = 0; continue }
            // The engine got no frames at all (it is not in the picture path): a lower level would not help.
            if (!fed) { playingSince = 0L; slow = 0; calm = 0; continue }
            // A jump (seek bar, scrubbing, double-tap skip) restarts the engine, which is briefly slow: same treatment.
            val posNow = player.currentPosition
            val jumped = lastPos >= 0 && kotlin.math.abs(posNow - lastPos - 1000L) > 2500L
            lastPos = posNow
            if (jumped) { playingSince = 0L; slow = 0; calm = 0; continue }
            if (playingSince == 0L) playingSince = now
            if (now - playingSince < 8000L) continue
            if (fps < 50f) { slow++; calm = 0 } else { slow = 0; if (fps >= 57f) calm++ }
            val level = if (onGpu) currentGpuLevel else currentCpuLevel
            val target = if (onGpu) gpuTarget else cpuTarget
            if (slow >= 3) {
                slow = 0
                playingSince = 0L
                if (level > 0) {
                    sessionCap = level - 1
                    lastStepDown = now
                    AppDiagnostics.record(context, "native-quality", "down to ${NativeSvpTuning.LEVEL_NAMES[level - 1]} at ${fps.toInt()} fps")
                    toast("Smoothing kept up only at ${fps.toInt()} fps; lowered to ${NativeSvpTuning.LEVEL_NAMES[level - 1]}")
                } else if (onAdrenoGpu) {
                    // For this playback only (a busy or hot moment); the GPU is tried again next time.
                    adrenoGpu = false
                    AppDiagnostics.record(context, "native-quality", "GPU too slow at lowest level, CPU engine")
                    toast("GPU smoothing could not keep up even at 720p; switched to the CPU engine")
                }
            } else if (calm >= 120 && level < target && now - lastStepDown > 180_000L) {
                calm = 0
                playingSince = 0L
                sessionCap = level + 1
                AppDiagnostics.record(context, "native-quality", "up to ${NativeSvpTuning.LEVEL_NAMES[level + 1]}")
                toast("Smoothing raised to ${NativeSvpTuning.LEVEL_NAMES[level + 1]}")
            }
        }
    }
    // Runs always (not only with the on-screen numbers) so the playback report can show how normal playback went.
    LaunchedEffect(player) {
        var lastCount = -1
        var lastAt = 0L
        var lastNativeCount = nativeFrames.get()
        while (true) {
            delay(1000)
            val counters = player.videoDecoderCounters
            counters?.ensureUpdated()
            val count = counters?.renderedOutputBufferCount ?: 0
            val now = SystemClock.elapsedRealtime()
            if (lastCount in 0..count && now > lastAt && player.isPlaying) decodedFps = (count - lastCount) * 1000f / (now - lastAt)
            val nativeCount = nativeFrames.get()
            if (lastAt > 0 && now > lastAt && player.isPlaying) nativeShownFps = (nativeCount - lastNativeCount) * 1000f / (now - lastAt)
            lastNativeCount = nativeCount
            lastCount = count
            lastAt = now
            fileFps = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f
            if (fileFps > 61f) highFpsVideo = true
            displayHz = activity?.window?.decorView?.display?.refreshRate ?: 0f
            if (!nativeInUse && player.isPlaying) {
                val display = activity?.window?.decorView?.display
                val modes = display?.supportedModes?.map { "%.0f".format(it.refreshRate) }?.distinct()?.joinToString("/")
                NativeSvpTuning.logNormal(
                    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) +
                        " shown=${"%.1f".format(decodedFps)}fps file=${player.videoFormat?.frameRate ?: -1f}fps " +
                        "dropped=${counters?.droppedBufferCount} skipped=${counters?.skippedOutputBufferCount} " +
                        "display=${"%.0f".format(displayHz)}Hz modes=$modes pos=${player.currentPosition / 1000}s " +
                        "${player.videoFormat?.width}x${player.videoFormat?.height} ${player.videoFormat?.sampleMimeType}",
                )
            }
        }
    }
    var transitionSerial by remember { mutableIntStateOf(0) }
    var currentIndex by remember { mutableIntStateOf(initialIndex) }
    val activeVideo = queue.getOrElse(currentIndex) { video }
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableIntStateOf(0) }
    var showTracks by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    fun revealControls() {
        controlsVisible = true
        controlsInteraction++
    }
    val orientation = remember(activity) { com.deepak.videolibrary.ui.PlayerOrientation(activity) }
    DisposableEffect(orientation) { onDispose { orientation.restore() } }
    // Turn the screen to match every video. The player only reports a size change when the size differs from the
    // previous video, so also check the current picture size regularly and when the next video starts.
    LaunchedEffect(player, orientation) {
        while (true) {
            val size = player.videoSize
            val format = player.videoFormat
            val quarterTurn = size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270 ||
                (size.width == 0 && (format?.rotationDegrees == 90 || format?.rotationDegrees == 270))
            val w = if (size.width > 0) (size.width * size.pixelWidthHeightRatio).toInt() else format?.width ?: 0
            val h = if (size.height > 0) size.height else format?.height ?: 0
            if (w > 0 && h > 0) {
                if (quarterTurn) orientation.onVideoShape(h, w) else orientation.onVideoShape(w, h)
            }
            delay(400)
        }
    }
    DisposableEffect(activity) {
        val window = activity?.window
        val beforeBrightness = window?.attributes?.screenBrightness
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val behavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        // Light icons over the black video, even in a light app theme; the theme's choice comes back on close.
        val lightBars = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (lightBars != null) controller?.isAppearanceLightStatusBars = lightBars
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
    // Double-tap jumps add up while you keep tapping (each more tap on the same side is another 10 s), and the player
    // jumps once at the end (4 Nov 2026): every jump restarts smoothing, and many in a row could stall playback.
    var tapJumpTarget by remember { mutableStateOf<Long?>(null) }
    var tapJumpJob by remember { mutableStateOf<Job?>(null) }
    var lastTapSide by remember { mutableIntStateOf(-1) }
    var seekingGesture by remember { mutableStateOf(false) }
    var seekingTarget by remember { mutableLongStateOf(0L) }

    LaunchedEffect(player) {
        val restart = restartAt
        if (restart != null) {
            // Rebuilt without smoothing: carry on from the same video and moment.
            restartAt = null
            currentIndex = restart.first
            playingPath = queue.getOrNull(currentIndex)?.relativePath ?: playingPath
            player.seekTo(restart.first, restart.second)
        } else {
            val saved = vm.resumeFor(video.relativePath)
            val resume = if (saved != null && !(saved.durationMs > 0 && saved.positionMs >= saved.durationMs - 10_000)) saved.positionMs else 0L
            player.seekTo(initialIndex, resume.coerceAtLeast(0))
        }
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
            playingPath = queue.getOrNull(currentIndex)?.relativePath ?: playingPath
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
                NativeSvpTuning.lastVideoInfo += " | decoder=$decoderName"
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
                // Sideways-stored clips (rotation 90/270) are shown the other way round; anamorphic ones are widened.
                val quarterTurn = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
                val shownWidth = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
                if (quarterTurn) orientation.onVideoShape(videoSize.height, shownWidth)
                else orientation.onVideoShape(shownWidth, videoSize.height)
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // The track list carries the file's colour info; player.videoFormat is still empty at this point.
                val format = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                    .firstNotNullOfOrNull { g -> (0 until g.length).firstOrNull { g.isTrackSelected(it) }?.let { g.getTrackFormat(it) } }
                    ?: player.videoFormat
                if (format != null) {
                    val transfer = format.colorInfo?.colorTransfer
                    val codecs = format.codecs.orEmpty()
                    val dolby = format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION ||
                        codecs.startsWith("dvh") || codecs.startsWith("dva") || codecs.startsWith("dav1")
                    hdrVideo = dolby || transfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 ||
                        transfer == androidx.media3.common.C.COLOR_TRANSFER_HLG
                    highFpsVideo = format.frameRate > 61f
                    NativeSvpTuning.sourceFpsHint = if (format.frameRate > 0f) format.frameRate else 0f
                    AppDiagnostics.record(context, "hdr", "hdr=$hdrVideo dolbyVision=$dolby transfer=$transfer codecs=$codecs")
                    val ci = format.colorInfo
                    val screen = activity?.window
                    NativeSvpTuning.lastVideoInfo = "hdr=$hdrVideo dolbyVision=$dolby mime=${format.sampleMimeType} codecs=$codecs " +
                        "fps=${format.frameRate} colorSpace=${ci?.colorSpace} range=${ci?.colorRange} transfer=$transfer ${format.width}x${format.height} " +
                        "displayIsHdr=${if (Build.VERSION.SDK_INT >= 26) screen?.decorView?.display?.isHdr else null}"
                    AppDiagnostics.record(
                        context,
                        "codec",
                        "mime=" + format.sampleMimeType + " width=" + format.width + " height=" + format.height + " fps=" + format.frameRate,
                    )
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                AppDiagnostics.record(context, "error", error.errorCodeName)
                NativeSvpTuning.lastVideoInfo = "PLAYBACK ERROR ${error.errorCodeName}: ${error.cause?.message ?: error.message} | was: ${NativeSvpTuning.lastVideoInfo}"
                NativeSvpTuning.logNormal("ERROR ${error.errorCodeName}: ${error.cause?.message ?: error.message}")
                // Both effect-pipeline failure kinds: set-up (5001) and while running (5002).
                if (nativeInUse && (
                        error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED
                        )
                ) {
                    // Most engine errors are passing: fast scrubbing tripping Media3's frame hand-over ("Textures are all
                    // in use"), a mid-video size change, a timeline jump. Restart the player at the same spot with
                    // smoothing still on, up to three times per video, before giving up on smoothing for that video.
                    val detail = generateSequence(error as Throwable?) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
                    // Errors that will happen again whatever we do: no retry.
                    val permanent = listOf("SDR only", "libraries are unavailable", "Unsupported source frame rate", "requires two decoded frames")
                        .any { detail.contains(it) }
                    val tries = if (smoothRetry.first == playingPath) smoothRetry.second else 0
                    if (!permanent && tries < 3) {
                        smoothRetry = playingPath to tries + 1
                        rebuildPlayer()
                        return
                    }
                    smoothFailedPath = playingPath
                    rebuildPlayer()
                    android.widget.Toast.makeText(context, "Smoothing unavailable for this video; using normal playback", android.widget.Toast.LENGTH_LONG).show()
                    return
                }
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
                orientation.newVideo()
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                playingPath = queue.getOrNull(currentIndex)?.relativePath ?: playingPath
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
        if (smooth60 && !nativeAvailable) {
            AndroidView(
                onReset = null,
                onRelease = { it.release() },
                factory = { SmoothVideoView(it).apply { onFailure = { smoothPref = false }; onStats = { stats -> smoothStats = stats }; smoothViewRef = this } },
                update = { view ->
                    view.attach(player)
                    view.strength = smoothStrength
                    view.debug = smoothDebug
                    view.resizeMode = aspect
                },
                modifier = Modifier.fillMaxSize().clipToBounds().graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
            )
        } else {
            androidx.compose.runtime.key(hdrVideo || highFpsVideo) { AndroidView(
                onReset = null,
                onRelease = {
                    it.player = null
                },
                factory = {
                    android.view.LayoutInflater
                        .from(it)
                        .inflate(if (hdrVideo || highFpsVideo) com.deepak.videolibrary.R.layout.playback_view_hdr else com.deepak.videolibrary.R.layout.playback_view, null) as PlayerView
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
            ) }
        }
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
                    if (!moved && !holding && (showSettings || showTracks)) {
                        // A tap on the video only closes an open menu.
                        showSettings = false
                        showTracks = false
                    } else if (!moved && !holding) {
                        val now = System.currentTimeMillis()
                        val chaining = tapJumpTarget != null && now - lastTapTime < 600L && lastTapSide == side
                        if (chaining || (now - lastTapTime < 350L && lastTapSide == side)) {
                            singleTap?.cancel()
                            val delta = if (side == 0) -10_000L else 10_000L
                            val target = ((tapJumpTarget ?: player.currentPosition) + delta).coerceIn(
                                0L,
                                player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE,
                            )
                            tapJumpTarget = target
                            val total = (target - player.currentPosition) / 1000
                            gestureMessage = if (total < 0) "−${-total} sec" else "+$total sec"
                            lastTapTime = now
                            tapJumpJob?.cancel()
                            tapJumpJob = gestureScope.launch {
                                delay(600)
                                tapJumpTarget?.let { player.seekTo(it) }
                                tapJumpTarget = null
                            }
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
                modifier = Modifier.align(Alignment.Center).offset(y = (-96).dp).background(Color(0xCC101010), RoundedCornerShape(12.dp)).padding(12.dp),
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
                modifier = Modifier.align(Alignment.Center).offset(y = (-96).dp).background(Color(0xA6000000), RoundedCornerShape(14.dp)).padding(12.dp),
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible && (activity as? com.deepak.videolibrary.MainActivity)?.playbackInPip != true,
            modifier = Modifier.fillMaxSize(),
            enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)),
            exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
            LibraryPlayerControls(
                title = activeVideo.name,
                position = position,
                duration = duration,
                isPlaying = isPlaying,
                canPrevious = player.hasPreviousMediaItem(),
                canNext = player.hasNextMediaItem() || vm.playbackHasMore,
                queueLabel = "${currentIndex + 1} / ${queue.size}",
                aspectLabel = aspectLabels[aspectIndex],
                onRotate = { orientation.rotate() },
                onBack = onClose,
                onTogglePlayback = { if (player.playWhenReady) player.pause() else player.play() },
                onSeek = { player.seekTo(it); revealControls() },
                onPrevious = { player.seekToPreviousMediaItem(); revealControls() },
                onNext = {
                    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else onQueueEnded()
                    revealControls()
                },
                onTracks = { showTracks = !showTracks; showSettings = false; revealControls() },
                onOptions = { showSettings = !showSettings; showTracks = false; revealControls() },
                smoothOn = smooth60,
                onSmooth = {
                    smoothPref = !smoothPref
                    prefs.edit().putBoolean("experimental_smooth_60fps", smoothPref).apply()
                    revealControls()
                },
                onAspect = { aspectIndex = (aspectIndex + 1) % aspectModes.size; zoom = 1f; revealControls() },
                onPip = if (activity?.packageManager?.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) == true) ({
                    val size = player.videoSize
                    val ratio = if (size.height > 0) size.width.toFloat() * size.pixelWidthHeightRatio / size.height else 16f / 9f
                    runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder()
                        .setAspectRatio(Rational((ratio.coerceIn(0.42f, 2.39f) * 1000).roundToInt(), 1000)).build()) }
                }) else null,
                onQueue = { showQueue = true; revealControls() },
                onInteraction = ::revealControls,
            )
        }
        if (showFps) {
            val stats = smoothStats
            val hz = if (displayHz > 0f) " · display ${"%.0f".format(displayHz)} Hz" else ""
            val line1 = if (nativeInUse) {
                "Smooth 60 ON · video ${"%.1f".format(fileFps)} fps → screen ${"%.1f".format(nativeShownFps)} fps$hz"
            } else if (smooth60 && stats != null) {
                "Smooth $smoothTargetFps ON · video ${"%.1f".format(stats.sourceFps)} fps → screen ${"%.1f".format(stats.outputFps)} fps$hz"
            } else if (smooth60) {
                "Smooth $smoothTargetFps ON · measuring…$hz"
            } else {
                "Normal playback · ${"%.1f".format(decodedFps)} fps shown (file says ${"%.1f".format(fileFps)})$hz"
            }
            val line2 = if (nativeInUse) {
                "Processing " + (NativeSvpTuning.lastStream ?: "starting…")
            } else if (smooth60 && !nativeAvailable && stats != null) {
                when {
                    !stats.interpolating -> "Source is close to the selected target: shown as it is"
                    stats.warping -> "Adding in-between frames with motion estimate · real frames ${stats.fallbackPercent}%"
                    else -> "Adding in-between frames by blending only"
                }
            } else null
            Column(
                Modifier.align(Alignment.TopStart).padding(top = 56.dp, start = 12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(line1, color = Color.White, style = MaterialTheme.typography.labelSmall)
                if (line2 != null) Text(line2, color = Color(0xFFB0BEC5), style = MaterialTheme.typography.labelSmall)
            }
        }
        if (showGpuLab) GpuLabDialog(onDismiss = { showGpuLab = false })
        if (showQueue) {
            SkyWingQueueDialog(queue.map { it.name }, currentIndex, vm.playbackHasMore,
                onDismiss = { showQueue = false; revealControls() },
                onSelect = { player.seekTo(it, 0L); showQueue = false; revealControls() })
        }
        if (showTracks && controlsVisible) {
            val trackGroups = player.currentTracks.groups
            Column(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 56.dp, end = 12.dp)
                    .widthIn(max = 320.dp)
                    .heightIn(max = 340.dp)
                    .background(Color(0xF21A1A1E), RoundedCornerShape(16.dp))
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(stringResource(R.string.ui_audio_subtitles_e06b742), color = Color.White, style = MaterialTheme.typography.titleMedium)
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
                        }) { Text(stringResource(R.string.ui_off_e3de5ab), color = Color.White) }
                    }
                    val groups = trackGroups.filter { it.type == trackType }
                    if (groups.isEmpty()) Text(stringResource(R.string.ui_no_available_tracks_d613c90), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    groups.forEach { group ->
                        for (index in 0 until group.length) {
                            val supported = group.isTrackSupported(index)
                            val format = group.getTrackFormat(index)
                            val label = trackLabel(format, index)
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
                TextButton(onClick = { showTracks = false }) { Text(stringResource(R.string.ui_close_bbfa773)) }
            }
        }
        if (showSettings && controlsVisible) {
            var showAdvanced by remember { mutableStateOf(false) }
            // Opens just above the bottom-right three-dot button, within thumb reach in every layout.
            BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Column(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 56.dp)
                    .widthIn(max = 320.dp)
                    .fillMaxWidth(0.9f)
                    .heightIn(max = (maxHeight - 64.dp).coerceIn(160.dp, 380.dp))
                    .background(Color(0xEE202020), RoundedCornerShape(12.dp))
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Settings", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.ui_close_bbfa773), color = Color.White) }
                }
                Text(stringResource(R.string.ui_hold_to_fast_forward_5f7f116), color = Color.White, style = MaterialTheme.typography.bodySmall)
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
                    Text(stringResource(R.string.ui_picture_in_picture_7a61e37), color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = pipEnabled, onCheckedChange = {
                        pipEnabled = it
                        prefs.edit().putBoolean("pip_enabled", it).apply()
                        revealControls()
                    })
                }
                if (smooth60 && nativeAvailable) {
                    Text("Smoothing quality: ${NativeSvpTuning.LEVEL_NAMES[quality.coerceAtMost(maxStop)]}${if (quality == 3 && gpuAllowed < 3) " (sharp)" else ""}", color = Color.White, style = MaterialTheme.typography.bodySmall)
                    var sliderPos by remember(quality, maxStop) { mutableFloatStateOf(quality.coerceAtMost(maxStop).toFloat()) }
                    Slider(
                        value = sliderPos,
                        onValueChange = { sliderPos = it.coerceAtMost(maxStop.toFloat()) },
                        onValueChangeFinished = {
                            val level = sliderPos.toInt().coerceIn(0, maxStop)
                            sliderPos = level.toFloat()
                            quality = level
                            sessionCap = 3
                            prefs.edit().putInt("native_quality", level).apply()
                            revealControls()
                        },
                        valueRange = 0f..3f,
                        steps = 2,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NativeSvpTuning.LEVEL_NAMES.forEachIndexed { index, name ->
                            Text(name, color = if (index <= maxStop) Color.White else Color.Gray, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState())) {
                        listOf("Battery", "Balanced", "Quality").forEachIndexed { index, label ->
                            FilterChip(
                                selected = powerMode == index,
                                onClick = {
                                    powerMode = index
                                    prefs.edit().putInt("native_power_mode", index).apply()
                                    revealControls()
                                },
                                label = { Text(label) },
                            )
                        }
                    }
                    val qualityNote = buildString {
                        append("Now: ${NativeSvpTuning.LEVEL_NAMES[gpuLevel]}")
                        if (sharp) append(" smoothed, sharpened to the screen")
                        if (heatCap < 3) append(" · lowered by heat")
                        else if (modeCap < 3 && quality > modeCap) append(" · limited by the power mode")
                        else if (sessionCap < minOf(quality, gpuAllowed)) append(" (lowered for this session)")
                        if (gpuAllowed < 2) append(" · higher levels are too heavy for this device")
                    }
                    Text(qualityNote, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show frame rate", color = Color.White, modifier = Modifier.weight(1f))
                    Switch(checked = showFps, onCheckedChange = { value ->
                        showFps = value
                        prefs.edit().putBoolean("show_fps_indicator", value).apply()
                        revealControls()
                    })
                }
                Row {
                    TextButton(onClick = {
                        zoom = 1f
                        aspectIndex = 0
                        revealControls()
                    }) { Text(stringResource(R.string.ui_reset_zoom_e573797), color = Color.White) }
                    TextButton(onClick = { showAdvanced = !showAdvanced }) {
                        Text(if (showAdvanced) "Hide tests" else "Tests & report", color = Color.White)
                    }
                }
                if (showAdvanced) {
                    if (nativeAvailable) TextButton(enabled = !gpuChecking, onClick = {
                        player.pause()
                        gpuChecking = true
                        gpuCheckStatus = "Starting the GPU quality check (about 1 minute, video paused)…"
                        revealControls()
                        gestureScope.launch {
                            val text = com.deepak.videolibrary.player.GpuLabRunner.runLevels(context) { gpuCheckStatus = "GPU check: $it" }
                            GpuQualityStore.save(context, text)
                            GpuQualityStore.clearFailure(context)
                            adrenoGpu = true
                            AppDiagnostics.record(context, "native-quality", "GPU check max=${text.substringBefore('|')}")
                            gpuCheck = text
                            sessionCap = 3
                            gpuChecking = false
                        }
                    }) { Text("Re-run GPU quality check", color = Color.White) }
                    if (gpuChecking) Text(gpuCheckStatus, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    if (nativeAvailable) TextButton(enabled = !gpuChecking, onClick = {
                        player.pause()
                        showGpuLab = true
                    }) { Text("GPU lab (diagnostics)", color = Color.White) }
                    TextButton(onClick = {
                        gestureScope.launch {
                            val uri = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { GpuPlaybackReport.save(context) }
                            if (uri == null) {
                                android.widget.Toast.makeText(context, "Could not save the GPU report", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                val share = android.content.Intent(android.content.Intent.ACTION_SEND)
                                    .setType("text/plain")
                                    .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(android.content.Intent.createChooser(share, "Share GPU report"))
                            }
                        }
                    }) { Text("Share GPU playback report", color = Color.White) }
                }
            }
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
                }) { Text(stringResource(R.string.ui_retry_9f5cd8a)) }
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

/** "English · AC-3 · 5.1" style name for an audio or subtitle track, so tracks can be told apart. */
private fun trackLabel(format: androidx.media3.common.Format, index: Int): String {
    val language = format.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    val codec = when (format.sampleMimeType) {
        "audio/ac3" -> "AC-3"; "audio/eac3" -> "E-AC-3"; "audio/eac3-joc" -> "Dolby Atmos"
        "audio/true-hd" -> "TrueHD"; "audio/vnd.dts" -> "DTS"; "audio/vnd.dts.hd" -> "DTS-HD"
        "audio/mp4a-latm" -> "AAC"; "audio/mpeg" -> "MP3"; "audio/opus" -> "Opus"; "audio/flac" -> "FLAC"
        else -> null
    }
    val channels = when (format.channelCount) {
        androidx.media3.common.Format.NO_VALUE, 0 -> null
        1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${format.channelCount} ch"
    }
    val parts = listOfNotNull(format.label?.takeIf { it != language }, language, codec, channels.takeIf { codec != null || language != null })
    return parts.joinToString(" · ").ifBlank { "Track ${index + 1}" }
}
