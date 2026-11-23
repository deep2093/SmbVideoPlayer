package com.skywing.desktop

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B16–B18: the library index never loses videos because it couldn't be read once, and changes never overwrite each other. */
class WindowsMediaIndexSafetyTest {
    private val folder: Path = Files.createTempDirectory("skywing-index-safety")
    private val index: Path = folder.resolve("media-index.bin")
    private val copy: Path = folder.resolve("media-index.bak")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun videos(prefix: String, count: Int) = (1..count).map { File(folder.resolve("$prefix-$it.mkv").toString()) }

    /** A library of 3 videos on this PC and 2 on a share. */
    private fun fiveVideos(): WindowsMediaIndex = WindowsMediaIndex(index).apply {
        replaceSource("PC", "PC", "This PC", videos("pc", 3))
        replaceSource("SMB:share", "SMB", "Share", videos("share", 2))
    }

    @Test fun aMissingIndexIsAnEmptyLibraryThatCanBeSaved() {
        val store = WindowsMediaIndex(index)
        assertEquals(emptyList(), store.load())
        assertNull(store.notice)
        assertTrue(store.replaceSource("PC", "PC", "This PC", videos("pc", 2)))
        assertEquals(2, WindowsMediaIndex(index).load().size)
        assertTrue(copy.exists(), "A good copy is kept after every save")
    }

    @Test fun aCorruptIndexIsRestoredFromTheGoodCopyAndTheLibraryKeepsEveryVideo() {
        fiveVideos()
        index.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        val store = WindowsMediaIndex(index)
        assertEquals(5, store.load().size)
        assertEquals("Library couldn't be read. Restored the last good copy.", store.notice)

        // The next scan of one source keeps the other.
        assertTrue(store.replaceSource("SMB:share", "SMB", "Share", videos("share", 4)))
        val saved = WindowsMediaIndex(index).load()
        assertEquals(3, saved.count { it.sourceType == "PC" })
        assertEquals(4, saved.count { it.sourceType == "SMB" })
    }

    @Test fun aHalfWrittenIndexIsRestoredToo() {
        fiveVideos()
        val whole = index.readBytes()
        index.writeBytes(whole.copyOf(whole.size / 2))
        assertEquals(5, WindowsMediaIndex(index).load().size)
    }

    @Test fun aLockedIndexWithNoGoodCopyIsNeverWrittenOver() {
        fiveVideos()
        copy.deleteIfExists()
        val saved = index.readBytes()

        // Another program holding the file (an antivirus scan): it may read next time, so nothing is written now.
        FileChannel.open(index, StandardOpenOption.READ, StandardOpenOption.WRITE).use { channel ->
            channel.lock().use {
                val store = WindowsMediaIndex(index)
                assertEquals(emptyList(), store.load())
                assertNotNull(store.notice)
                val refused = assertFailsWith<IllegalStateException> { store.replaceSource("PC", "PC", "This PC", videos("pc", 1)) }
                assertTrue("nothing is saved" in refused.message.orEmpty(), refused.message)
                assertEquals(0, store.removeWhere { true })
            }
        }
        assertContentEquals(saved, index.readBytes(), "The locked index must be left as it is")
        assertTrue(!copy.exists())
        assertEquals(5, WindowsMediaIndex(index).load().size, "Once it can be read, the library is all there")
    }

    @Test fun aDamagedIndexWithNoGoodCopyIsKeptAsideAndTheLibraryStartsAgain() {
        // Damaged for good, and no good copy yet (a library saved by a build before the copy existed): reading again
        // won't help, so the file is kept as it is under another name and the next scan builds the library again.
        fiveVideos()
        copy.deleteIfExists()
        val garbage = ByteArray(64) { (it * 7).toByte() }
        index.writeBytes(garbage)

        val store = WindowsMediaIndex(index)
        assertEquals(emptyList(), store.load())
        assertTrue("media-index.bin.damaged" in store.notice.orEmpty(), store.notice)
        assertContentEquals(garbage, folder.resolve("media-index.bin.damaged").readBytes(), "The damaged file is kept")
        assertTrue(store.replaceSource("PC", "PC", "This PC", videos("pc", 2)))
        assertEquals(2, WindowsMediaIndex(index).load().size)
    }

    @Test fun theIndexIsReadOnceAndKeptInMemory() {
        val store = fiveVideos()
        index.deleteIfExists()
        copy.deleteIfExists()
        assertEquals(5, store.load().size)
        assertEquals(2, store.removeWhere { it.sourceType == "SMB" })
        assertEquals(3, WindowsMediaIndex(index).load().size)
    }

    @Test fun aHideDuringAScanNeverUndoesTheScan() {
        val store = WindowsMediaIndex(index)
        store.replaceSource("PC", "PC", "This PC", videos("pc", 1))
        val scans = thread {
            repeat(60) { round -> store.replaceSource("SMB:a", "SMB", "A", videos("a$round", 2)) }
        }
        val hides = thread {
            repeat(60) { round ->
                store.replaceSource("SMB:junk", "SMB", "Junk", videos("junk$round", 1))
                store.removeWhere { it.sourceId == "SMB:junk" }
            }
        }
        scans.join()
        hides.join()
        val saved = WindowsMediaIndex(index).load()
        assertEquals(setOf("a59-1.mkv", "a59-2.mkv"), saved.filter { it.sourceId == "SMB:a" }.map { File(it.path).name }.toSet())
        assertEquals(1, saved.count { it.sourceId == "PC" })
        assertEquals(0, saved.count { it.sourceId == "SMB:junk" })
    }
}
