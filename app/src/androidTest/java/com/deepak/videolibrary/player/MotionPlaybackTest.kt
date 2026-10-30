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
            for (target in listOf(60, 90)) {
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
                        view.targetFps = target
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
                    val report = "$source->$target: decoded=$input drawn=$output fallback=${samples.map { it.fallbackPercent }.average()}% interpolation=${samples.last().interpolating} warping=${samples.last().warping} bad=${samples.last().untrustedPercent}% cut=${samples.last().cutDetected}"
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
