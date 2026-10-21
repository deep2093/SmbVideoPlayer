package com.deepak.videolibrary.downloads

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpeedTest {
    @Test fun speedIsBytesOverTheWindow() {
        val speed = DownloadSpeed(windowMs = 3_000)
        speed.record(1, 0, 0)
        speed.record(1, 10 * 1024 * 1024, 1_000)
        assertEquals(10.0 * 1024 * 1024, speed.bytesPerSecond(1), 1.0)
        assertEquals("10.0 MB/s", DownloadSpeed.format(speed.bytesPerSecond(1)))
    }

    @Test fun oldSamplesDropOutAndAStallShowsZero() {
        val speed = DownloadSpeed(windowMs = 3_000)
        speed.record(1, 0, 0)
        speed.record(1, 5_000_000, 1_000)
        // No progress for a while: the window slides past the fast part.
        speed.record(1, 5_000_000, 5_000)
        speed.record(1, 5_000_000, 9_000)
        assertEquals(0.0, speed.bytesPerSecond(1), 0.001)
    }

    @Test fun aRestartedFileStartsAgainAndUnknownIsZero() {
        val speed = DownloadSpeed()
        speed.record(1, 900, 0)
        speed.record(1, 100, 1_000)
        assertEquals(0.0, speed.bytesPerSecond(1), 0.001)
        assertEquals(0.0, speed.bytesPerSecond(42), 0.001)
        speed.keepOnly(emptySet())
        assertEquals(0.0, speed.bytesPerSecond(1), 0.001)
    }
}
