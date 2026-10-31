package com.deepak.videolibrary.player

import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeLivePlaybackTest {
    @Test fun decodeInterpolateSeekAndResume() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeLive") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val source = args.getString("nativeSource")?.toInt() ?: 24
        val fixture = File(app.cacheDir, "native-live-$source.mp4")
        val reference = args.getString("nativeReferenceName")
        if (reference == null) instrumentation.context.assets.open("motion-test/$source.mp4").use { input -> fixture.outputStream().use { input.copyTo(it) } }
        var mediaSource: androidx.media3.exoplayer.source.MediaSource? = null
        if (reference != null) {
            val services = com.deepak.videolibrary.ServiceLocator.get(app)
            val settings = services.settings.load()
            val profile = com.deepak.videolibrary.smb.SmbProfile(settings.host, settings.share, settings.rootPath,
                settings.username, settings.password, settings.domain, settings.requireSigning, settings.requireEncryption,
                alternateHosts = com.deepak.videolibrary.smb.HostFallback.parse(settings.alternateHosts))
            val item = services.db.openHelper.readableDatabase.query(
                "SELECT relativePath, size FROM videos WHERE name = ? LIMIT 2", arrayOf(reference),
            ).use { check(it.count == 1); it.moveToFirst(); it.getString(0) to it.getLong(1) }
            val uri = Uri.Builder().scheme("smbvideo").authority("library").appendPath("reference.mp4")
                .appendQueryParameter("path", item.first).appendQueryParameter("size", item.second.toString()).build()
            mediaSource = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(SmbPlaybackDataSource.Factory(profile))
                .createMediaSource(MediaItem.fromUri(uri))
        }
        val controller = NativeSvpController()
        lateinit var player: ExoPlayer
        val frames = AtomicInteger()
        val lastFrame = java.util.concurrent.atomic.AtomicReference(0L to 0L)
        val failure = java.util.concurrent.atomic.AtomicReference<PlaybackException?>()
        val scenario = androidx.test.core.app.ActivityScenario.launch(NativePlaybackTestActivity::class.java)
        fun onMain(action: () -> Unit) { scenario.onActivity { action() } }
        scenario.onActivity { context ->
                val activity = context as android.app.Activity
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                PlayerView(context).apply {
                    useController = false
                    display?.supportedModes?.firstOrNull { kotlin.math.abs(it.refreshRate - 60f) < 1f }?.let {
                        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = it.modeId }
                    }
                    player = ExoPlayer.Builder(context, NativeSvpRenderersFactory(context, controller)).build().apply {
                        volume = 0f
                        setVideoEffects(if (reference != null) listOf(NativeSvpScale(), NativeSvpEffect(controller, args.getString("nativeGpu")?.toBooleanStrictOrNull())) else listOf(NativeSvpEffect(controller, args.getString("nativeGpu")?.toBooleanStrictOrNull())))
                        setVideoFrameMetadataListener { pts, release, _, _ -> frames.incrementAndGet(); lastFrame.set(pts to release) }
                        addListener(object : Player.Listener { override fun onPlayerError(error: PlaybackException) { failure.set(error) } })
                        if (mediaSource != null) { setMediaSource(checkNotNull(mediaSource)); seekTo(265_000) }
                        else if (args.getString("nativeEos") == "true") setMediaItems(listOf(
                            MediaItem.fromUri(Uri.fromFile(fixture)), MediaItem.fromUri(Uri.fromFile(fixture))))
                        else setMediaItem(MediaItem.fromUri(Uri.fromFile(fixture)))
                        prepare(); playWhenReady = true
                    }.also { this.player = it }
                }.also { context.setContentView(it) }
        }
        try {
            if (args.getString("nativeEos") == "true") {
                val deadline = SystemClock.elapsedRealtime() + 20_000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    onMain { ready = player.isPlaying && player.duration > 0 }
                    if (!ready) SystemClock.sleep(100)
                }
                assertTrue("EOS fixture did not start", ready)
                onMain { player.seekTo(player.duration - 1500) }
                SystemClock.sleep(4000)
                onMain { assertEquals("Next item did not start", 1, player.currentMediaItemIndex) }
                frames.set(0)
                SystemClock.sleep(2000)
                assertNull(failure.get()?.stackTraceToString(), failure.get())
                assertTrue("Second item did not interpolate: frames=${frames.get()}", frames.get() >= 108)
                return
            }
            for (seek in listOf(false, true)) {
                if (seek) onMain { player.seekTo(if (reference == null) 1000 else 265_000); player.play() }
                val deadline = SystemClock.elapsedRealtime() + 30_000
                var ready = false
                while (!ready && failure.get() == null && SystemClock.elapsedRealtime() < deadline) {
                    onMain { ready = player.isPlaying && player.currentPosition >= (if (reference == null) 1500 else 265_500) }
                    if (!ready) SystemClock.sleep(100)
                }
                SystemClock.sleep(2000)
                val measureMs = args.getString("nativeMeasureMs")?.toLong()?.coerceIn(2500, 30000) ?: 2500
                onMain {
                    assertTrue("Measurement would outlast the video; use a longer fixture",
                        player.duration - player.currentPosition > measureMs + 500)
                }
                frames.set(0)
                val start = SystemClock.elapsedRealtime()
                SystemClock.sleep(measureMs)
                val fps = frames.get() * 1000.0 / (SystemClock.elapsedRealtime() - start)
                var position = 0L
                onMain { position = player.currentPosition }
                val stamp = lastFrame.get()
                // Media3 1.6.1 adds this renderer offset for the first period (MediaPeriodQueue).
                val clockErrorMs = (stamp.first - 1_000_000_000_000L + (System.nanoTime() - stamp.second) / 1000) / 1000.0 - position
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\nLive native source=$source seek=$seek scheduled=$fps clockErrorMs=$clockErrorMs error=${failure.get()?.stackTraceToString()}\n") })
                assertNull(failure.get()?.stackTraceToString(), failure.get())
                assertTrue("Video drifted from the playback clock: $clockErrorMs ms", kotlin.math.abs(clockErrorMs) < 120)
                assertTrue("Live native output too slow: $fps", fps >= 54)
                if (!seek) {
                    var pausedAt = 0L
                    onMain { player.pause(); pausedAt = player.currentPosition }
                    SystemClock.sleep(400)
                    onMain {
                        assertTrue("Pause advanced the playback clock", kotlin.math.abs(player.currentPosition - pausedAt) < 50)
                        player.play()
                    }
                }
            }
            // Exercise effect replacement through the same API as the normal playback switch.
            onMain { player.setVideoEffects(emptyList()) }
            SystemClock.sleep(300)
            onMain {
                player.setVideoEffects(listOf(NativeSvpScale(), NativeSvpEffect(controller, args.getString("nativeGpu")?.toBooleanStrictOrNull())))
                player.seekTo(if (reference == null) 1000 else 265_000)
            }
            SystemClock.sleep(2500)
            assertNull(failure.get()?.stackTraceToString(), failure.get())
        } finally { onMain { player.release() }; fixture.delete(); scenario.close() }
    }
}
