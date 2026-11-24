package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** B24: how many thumbnails are made at once, and that they wait while a video plays. */
@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailWorkTest {
    @Test
    fun onBatteryOneThumbnailIsMadeAtATime() {
        assertEquals(1, thumbnailWorkers(onBattery = true, dedicatedGpu = true, playing = false))
        assertEquals(1, thumbnailWorkers(onBattery = true, dedicatedGpu = false, playing = false))
    }

    @Test
    fun pluggedInADedicatedGraphicsCardMakesThreeAndIntegratedGraphicsTwo() {
        assertEquals(3, thumbnailWorkers(onBattery = false, dedicatedGpu = true, playing = false))
        assertEquals(2, thumbnailWorkers(onBattery = false, dedicatedGpu = false, playing = false))
    }

    @Test
    fun whileAVideoPlaysOneThumbnailIsMadeAtATime() {
        assertEquals(1, thumbnailWorkers(onBattery = false, dedicatedGpu = true, playing = true))
        assertEquals(1, thumbnailWorkers(onBattery = false, dedicatedGpu = false, playing = true))
    }

    @Test
    fun aWorkLimitRunsOnlyItsLimitAndFollowsANewLimit() = runTest {
        var limit = 2
        val work = WorkLimit { limit }
        val release = CompletableDeferred<Unit>()
        val jobs = List(5) { launch { work.withPermit { release.await() } } }
        runCurrent()
        assertEquals(2, work.active)

        // Plugging the charger in raises the limit; a waiting block notices within a second.
        limit = 3
        advanceTimeBy(1_100)
        runCurrent()
        assertEquals(3, work.active)

        release.complete(Unit)
        jobs.joinAll()
        assertEquals(0, work.active)
    }

    @Test
    fun aWaitingBlockStartsAsSoonAsARunningOneEnds() = runTest {
        val work = WorkLimit { 1 }
        val first = CompletableDeferred<Unit>()
        launch { work.withPermit { first.await() } }
        var second = false
        launch { work.withPermit { second = true } }
        runCurrent()
        assertFalse(second)
        first.complete(Unit)
        runCurrent()
        assertTrue(second, "the second block started without waiting for the once-a-second check")
    }

    @Test
    fun aLimitBelowOneStillLetsOneBlockRun() = runTest {
        val work = WorkLimit { 0 }
        var ran = false
        work.withPermit { ran = true }
        assertTrue(ran)
    }

    @Test
    fun theBackgroundRunWaitsWhileAVideoPlays() {
        val folder = Files.createTempDirectory("skywing-thumb-pause").toFile()
        val video = File(folder, "not-a-video.mp4").apply { writeBytes(ByteArray(4096) { 7 }) }
        val videos = listOf(video to video.lastModified())
        try {
            ThumbnailStore.playing = true
            ThumbnailStore.prefetch(videos)
            Thread.sleep(1_500)
            val paused = ThumbnailStore.stats(videos)
            assertEquals(1, paused.waiting, "nothing was tried while the video played")
            assertTrue(ThumbnailStore.working)

            ThumbnailStore.playing = false
            val deadline = System.nanoTime() + 30_000_000_000L
            while (ThumbnailStore.stats(videos).waiting > 0 && System.nanoTime() < deadline) Thread.sleep(100)
            assertEquals(0, ThumbnailStore.stats(videos).waiting, "the run went on once the video stopped")
        } finally {
            ThumbnailStore.playing = false
            ThumbnailStore.stop()
            ThumbnailStore.retryFailed()
            folder.deleteRecursively()
        }
    }
}
