package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class WindowsMediaIndexScaleTest {
    @Test
    fun persistsTenThousandEntriesAndRefreshReplacesMovedOrMissingFiles() {
        val directory = Files.createTempDirectory("skywing-index-scale")
        try {
            val indexFile = directory.resolve("media-index.bin")
            val initialFiles = (0 until 10_000).map { index ->
                File(directory.resolve("initial").resolve("video-$index.mkv").toString())
            }
            assertTrue(WindowsMediaIndex(indexFile).replaceSource("PC", "PC", "This PC", initialFiles))
            assertFalse(WindowsMediaIndex(indexFile).replaceSource("PC", "PC", "This PC", initialFiles))

            val loaded = WindowsMediaIndex(indexFile).load()
            assertEquals(10_000, loaded.size)
            assertTrue(Files.size(indexFile) > 0)

            val refreshedFiles = initialFiles.drop(1) + File(directory.resolve("moved-video.mkv").toString())
            assertTrue(WindowsMediaIndex(indexFile).replaceSource("PC", "PC", "This PC", refreshedFiles))

            val refreshed = WindowsMediaIndex(indexFile).load()
            assertEquals(10_000, refreshed.size)
            assertTrue(refreshed.none { it.path.endsWith("video-0.mkv") })
            assertTrue(refreshed.any { it.path.endsWith("moved-video.mkv") })
        } finally {
            Files.deleteIfExists(directory.resolve("media-index.bin.tmp"))
            Files.deleteIfExists(directory.resolve("media-index.bin"))
            Files.deleteIfExists(directory)
        }
    }
}
