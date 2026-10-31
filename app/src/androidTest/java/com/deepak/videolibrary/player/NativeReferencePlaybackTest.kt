package com.deepak.videolibrary.player

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Plays an explicitly prepared local comparison. No interpolation is performed by this test. */
class NativeReferencePlaybackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun playPreparedNativeComparison() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeReferencePlayback") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "native-reference-60fps.mp4")
        check(file.isFile) { "Prepare the native comparison first" }
        lateinit var player: ExoPlayer
        val frames = AtomicInteger()
        rule.setContent {
            AndroidView(factory = { context ->
                val activity = context as android.app.Activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                PlayerView(context).apply {
                    useController = false
                    display?.supportedModes?.firstOrNull { kotlin.math.abs(it.refreshRate - 60f) < 1f }?.let {
                        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = it.modeId }
                    }
                    player = ExoPlayer.Builder(context).build().apply {
                        volume = 0f
                        repeatMode = Player.REPEAT_MODE_ONE
                        setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
                        setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                        prepare()
                        playWhenReady = true
                    }.also { this.player = it }
                }
            })
        }
        try {
            SystemClock.sleep(2500)
            frames.set(0)
            val start = SystemClock.elapsedRealtime()
            val duration = (InstrumentationRegistry.getArguments().getString("comparisonSeconds")
                ?.toLong() ?: 20L).coerceIn(10, 180) * 1000
            SystemClock.sleep(duration)
            val fps = frames.get() * 1000.0 / (SystemClock.elapsedRealtime() - start)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nPrepared native comparison scheduled fps=$fps\n") })
            assertTrue("Prepared comparison did not sustain playback: $fps", fps > 54)
        } finally { rule.runOnIdle { player.release() } }
    }
}
