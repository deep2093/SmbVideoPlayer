package com.skywing.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * B19: scans take each video's size and time from the folder listing instead of asking the drive once more per file.
 * The values must be the ones File gives, or every thumbnail (kept under path and time) would be made again.
 */
class VideoScanTest {
    private val folder: Path = Files.createTempDirectory("skywing-scan")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun video(relative: String, bytes: Int, modified: Long): Path = folder.resolve(relative).also {
        it.parent.createDirectories()
        it.writeBytes(ByteArray(bytes))
        it.toFile().setLastModified(modified)
    }

    @Test fun aLocalScanFindsVideosInEveryFolderWithTheSizeAndTimeFileGives() = runBlocking {
        val first = video("a.mkv", 10, 1_700_000_000_123L)
        val second = video("deep/er/B.MP4", 20, 1_600_000_000_456L)
        video("notes.txt", 5, 1_600_000_000_000L)

        val result = LocalVideoScanner.scan(listOf(folder.toString()))

        assertEquals(listOf("a.mkv", "B.MP4"), result.files.map { it.file.name })
        for ((found, path) in result.files.zip(listOf(first, second))) {
            assertEquals(path.toFile().length(), found.size)
            assertEquals(path.toFile().lastModified(), found.modified)
        }
        assertEquals(0, result.unavailableFolders)
    }

    @Test fun aShareScanFindsTheSameVideosAndStopsAtTheLimit() = runBlocking {
        (1..5).forEach { video("show/episode $it.mkv", it, 1_650_000_000_000L + it) }

        val all = WindowsShareBrowser().scan(folder, folder)
        assertEquals(5, all.files.size)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), all.files.map { it.size })
        assertEquals(all.files.map { it.file.lastModified() }, all.files.map { it.modified })

        val some = WindowsShareBrowser().scan(folder, folder, limit = 3)
        assertTrue(some.truncated)
        assertEquals(3, some.files.size)
    }

    @Test fun aMissingFolderIsReportedAndTheOthersAreStillScanned() = runBlocking {
        video("here.mkv", 1, 1_650_000_000_000L)
        val missing = folder.resolve("gone").toString()

        val result = LocalVideoScanner.scan(listOf(missing, folder.toString()))

        assertEquals(listOf("here.mkv"), result.files.map { it.file.name })
        assertEquals(1, result.unavailableFolders)
        assertEquals(listOf(missing), result.unavailableRoots)
    }
}
