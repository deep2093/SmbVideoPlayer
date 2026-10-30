package com.deepak.videolibrary.player

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Collections
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt in with motionPlayback=true; generate fixtures with tools/generate_motion_fixtures.py first. */
class MotionPlaybackTest {
    @get:Rule val rule = createComposeRule()


    /** Explicitly opt in with referenceName; reads only that library item and does not save playback history. */
    @Test fun inspectRequestedReferenceSegment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val name = args.getString("referenceName")
        assumeTrue(!name.isNullOrBlank())
        val start = args.getString("referenceMs")?.toLong() ?: 260_000L
        val app = instrumentation.targetContext
        val services = com.deepak.videolibrary.ServiceLocator.get(app)
        val settings = services.settings.load()
        val profile = com.deepak.videolibrary.smb.SmbProfile(
            settings.host, settings.share, settings.rootPath, settings.username, settings.password,
            settings.domain, settings.requireSigning, settings.requireEncryption,
            alternateHosts = com.deepak.videolibrary.smb.HostFallback.parse(settings.alternateHosts),
        )
        val item = services.db.openHelper.readableDatabase.query(
            "SELECT relativePath, size FROM videos WHERE name = ? LIMIT 2", arrayOf(name),
        ).use { cursor ->
            check(cursor.count == 1) { "Reference must match exactly one indexed video" }
            cursor.moveToFirst()
            cursor.getString(0) to cursor.getLong(1)
        }
        val uri = Uri.Builder().scheme("smbvideo").authority("library")
            .appendPath("reference.mp4").appendQueryParameter("path", item.first)
            .appendQueryParameter("size", item.second.toString()).build()
        val stats = Collections.synchronizedList(mutableListOf<FrameBlendRenderer.Stats>())
        lateinit var player: ExoPlayer
        lateinit var view: SmoothVideoView
        var failed = false
        rule.setContent {
            AndroidView(factory = { context ->
                player = ExoPlayer.Builder(context).build().apply {
                    volume = 0f
                    setMediaSource(androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
                        SmbPlaybackDataSource.Factory(profile),
                    ).createMediaSource(MediaItem.fromUri(uri)))
                    prepare()
                }
                SmoothVideoView(context).also {
                    view = it
                    it.onFailure = { failed = true }
                    it.onStats = { sample -> stats.add(sample) }
                    it.attach(player)
                    val activity = context as android.app.Activity
                    view.display?.supportedModes?.firstOrNull { mode -> kotlin.math.abs(mode.refreshRate - 60f) < 1f }?.let { mode ->
                        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = mode.modeId }
                    }
                }
            })
        }
        try {
            for (level in 1..2) {
                rule.runOnIdle { view.strength = level; player.seekTo(start); player.playWhenReady = true }
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var playing = false
                while (!playing && SystemClock.elapsedRealtime() < deadline) {
                    rule.runOnIdle { playing = player.isPlaying && player.currentPosition >= start + 500 }
                    if (!playing) SystemClock.sleep(200)
                }
                assertTrue("Reference did not start", playing)
                val captured = java.util.concurrent.CountDownLatch(2)
                rule.runOnIdle {
                    assertTrue(view.captureFrames(2) { index, _, label, bitmap ->
                        File(app.cacheDir, "motion-reference-$level-$index.txt").writeText(label)
                        File(app.cacheDir, "motion-reference-$level-$index.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                        captured.countDown()
                    })
                }
                assertTrue("Capture timed out", captured.await(15, java.util.concurrent.TimeUnit.SECONDS))
                SystemClock.sleep(1800) // Exclude synchronous PNG capture/readback stalls from the throughput sample.
                stats.clear()
                SystemClock.sleep(3200)
                val samples = synchronized(stats) { stats.toList() }
                assertFalse("GL setup failed", failed)
                assertTrue("No reference statistics", samples.isNotEmpty())
                instrumentation.sendStatus(0, Bundle().apply {
                    putString("stream", "\nReference strength=$level: decoded=${samples.map { it.sourceFps }.average()} drawn=${samples.map { it.outputFps }.average()} wholeFrameFallback=${samples.map { it.fallbackPercent }.average()}% precise=${samples.last().highPrecisionVectors}\n")
                })
            }
        } finally {
            rule.runOnIdle { view.release(); player.release() }
        }
    }

    @Test fun decodedSyntheticClipsReachSelectedTargets() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("motionPlayback") == "true")
        val app = instrumentation.targetContext
        val stats = Collections.synchronizedList(mutableListOf<FrameBlendRenderer.Stats>())
        var failed = false
        lateinit var player: ExoPlayer
        lateinit var view: SmoothVideoView
        rule.setContent {
            AndroidView(factory = { context ->
                player = ExoPlayer.Builder(context).build()
                SmoothVideoView(context).also {
                    view = it
                    it.onFailure = { failed = true }
                    it.onStats = { sample -> stats.add(sample) }
                    it.attach(player)
                }
            })
        }
        val failures = mutableListOf<String>()
        try {
            for (target in listOf(60)) {
                for (source in listOf(24, 28, 30)) {
                    val fixture = File(app.cacheDir, "motion-test-$source.mp4")
                    instrumentation.context.assets.open("motion-test/$source.mp4").use { input ->
                        fixture.outputStream().use { input.copyTo(it) }
                    }
                    rule.runOnIdle {
                        val activity = view.context as android.app.Activity
                        val display = view.display
                        val mode = display.supportedModes.filter {
                            it.physicalWidth == display.mode.physicalWidth &&
                                it.physicalHeight == display.mode.physicalHeight && it.refreshRate >= target - 1
                        }.minByOrNull { it.refreshRate }
                        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        mode?.let { activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = it.modeId } }
                        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                        player.prepare()
                        player.playWhenReady = true
                    }
                    SystemClock.sleep(2500)
                    stats.clear()
                    SystemClock.sleep(3200)
                    val samples = synchronized(stats) { stats.toList() }
                    assertFalse("Renderer setup failed", failed)
                    assertTrue("No output statistics", samples.size >= 2)
                    val input = samples.map { it.sourceFps }.average()
                    val output = samples.map { it.outputFps }.average()
                    val report = "$source->$target: decoded=$input drawn=$output fallback=${samples.map { it.fallbackPercent }.average()}% interpolation=${samples.last().interpolating} warping=${samples.last().warping} precise=${samples.last().highPrecisionVectors} bad=${samples.last().untrustedPercent}% cut=${samples.last().cutDetected}"
                    instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n$report\n") })
                    if (input < source * 0.85 || output < target * 0.85 || samples.map { it.fallbackPercent }.average() >= 95) failures.add(report)
                }
            }
            assertTrue("Slow playback: ${failures.joinToString()}", failures.isEmpty())
        } finally {
            rule.runOnIdle { view.release(); player.release() }
            for (source in listOf(24, 28, 30)) File(app.cacheDir, "motion-test-$source.mp4").delete()
        }
    }
}
