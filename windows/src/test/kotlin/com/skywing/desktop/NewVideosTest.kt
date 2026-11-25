package com.skywing.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actor pages: videos that arrive in folders SkyWing already knows are marked new, on this PC only. */
class NewVideosTest {
    private val folder: Path = Files.createTempDirectory("skywing-new-videos")
    private var clock = 1_000_000L

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun store(path: Path = folder.resolve("new-videos.json")) = NewVideoStore(path) { clock }

    private fun entry(path: String, source: String = "src1") =
        WindowsMediaEntry(path, source, "folder", "Folder", 1L, 1L)

    private fun key(path: String) = ShareHosts.canonical(path).lowercase()

    private val a = "C:\\Videos\\A.mkv"
    private val b = "C:\\Videos\\B.mkv"

    @Test fun firstLoadMarksNothing() {
        assertTrue(NewVideos.arrivals(emptyList(), listOf(entry(a), entry(b))).isEmpty())
    }

    @Test fun newSourceMarksNothing() {
        val arrivals = NewVideos.arrivals(listOf(entry(a, "src1")), listOf(entry(a, "src1"), entry("D:\\Other\\X.mkv", "src2")))

        assertTrue(arrivals.isEmpty())
    }

    @Test fun newFileInKnownSourceIsNew() {
        val arrivals = NewVideos.arrivals(listOf(entry(a)), listOf(entry(a), entry(b)))
        assertEquals(listOf(key(b)), arrivals)

        store().add(arrivals)
        val marks = store().snapshot()
        assertTrue(marks.isNew(key(b), clock))
        assertFalse(marks.isNew(key(a), clock))
        assertEquals(1, marks.newCount(listOf(key(a), key(b)), clock))
    }

    @Test fun renamedVideoIsNotNew() {
        val videos = store()
        videos.renamePath(a, b)
        videos.add(NewVideos.arrivals(listOf(entry(a)), listOf(entry(b))))

        assertFalse(videos.snapshot().isNew(key(b), clock))
        // The memory is used once: a later arrival under that name counts.
        videos.add(listOf(b))
        assertTrue(videos.snapshot().isNew(key(b), clock))
    }

    @Test fun renameMovesAnExistingEntry() {
        val videos = store()
        videos.add(listOf(a))
        videos.renamePath(a, b)

        val marks = store().snapshot()
        assertFalse(marks.isNew(key(a), clock))
        assertTrue(marks.isNew(key(b), clock))
    }

    @Test fun newExpiresAfterSevenDays() {
        store().add(listOf(a))
        val seen = clock
        val marks = store().snapshot()

        assertTrue(marks.isNew(key(a), seen + NEW_FOR_MS))
        assertFalse(marks.isNew(key(a), seen + NEW_FOR_MS + 1))

        // Any write after that drops the old entry.
        clock = seen + NEW_FOR_MS + 1
        store().add(listOf(b))
        assertEquals(setOf(key(b)), store().snapshot().firstSeen.keys)
    }

    @Test fun playedVideoIsNotNew() {
        val videos = store()
        videos.add(listOf(a))
        videos.played(a.uppercase())

        assertFalse(store().snapshot().isNew(key(a), clock))
    }

    @Test fun dotGoesAfterOpeningTheActor() {
        val videos = store()
        videos.add(listOf(a))
        assertTrue(videos.snapshot().dot("actor", listOf(key(a)), clock))

        clock += 10
        videos.openedActor("actor")

        assertFalse(videos.snapshot().dot("actor", listOf(key(a)), clock))
    }

    @Test fun dotComesBackForALaterArrival() {
        val videos = store()
        videos.add(listOf(a))
        clock += 10
        videos.openedActor("actor")
        clock += 10
        videos.add(listOf(b))

        assertTrue(videos.snapshot().dot("actor", listOf(key(a), key(b)), clock))
        assertFalse(videos.snapshot().dot("actor", listOf(key(a)), clock))
    }

    @Test fun unreadableFileIsNeverOverwritten() {
        val blocked = folder.resolve("new-videos.json")
        Files.createDirectory(blocked)
        val videos = store(blocked)

        videos.add(listOf(a))
        videos.played(a)
        videos.openedActor("actor")
        videos.renamePath(a, b)

        assertTrue(Files.isDirectory(blocked))
        assertEquals(0, Files.list(blocked).use { it.count() })
        assertTrue(videos.snapshot().firstSeen.isEmpty())
    }

    @Test fun fileOfAnotherVersionOrShapeIsNeverOverwritten() {
        val path = folder.resolve("new-videos.json")
        val text = """{"version":2,"seen":"x"}"""
        Files.writeString(path, text)
        val videos = store(path)

        videos.add(listOf(a))
        videos.openedActor("actor")

        assertEquals(text, Files.readString(path))
        assertTrue(videos.snapshot().firstSeen.isEmpty())
    }
}
