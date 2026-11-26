package com.skywing.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actor pages: playing, finishing and renaming keep the watched and new marks right. */
class PlaybackRecordsTest {
    private val folder: Path = Files.createTempDirectory("skywing-records")
    private var clock = 1_000_000L

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun records() = PlaybackRecords(
        WindowsPlaybackHistoryStore(folder.resolve("history.json")) { null },
        WindowsWatchedStore(folder.resolve("watched.json")),
        NewVideoStore(folder.resolve("new-videos.json")) { clock },
    )

    private fun video(name: String) = folder.resolve(name).toFile()

    private fun key(name: String) = ShareHosts.canonical(video(name).absolutePath).lowercase()

    @Test fun finishingMarksWatchedAndDropsResume() {
        val records = records()
        records.save(video("Clip.mkv"), 980.0, 1000.0)

        assertTrue(key("Clip.mkv") in records.watched.load())
        assertTrue(records.history.load().isEmpty())
    }

    @Test fun stoppingMidwayKeepsResumeOnly() {
        val records = records()
        records.save(video("Clip.mkv"), 300.0, 1000.0)

        assertFalse(key("Clip.mkv") in records.watched.load())
        assertEquals(300.0, records.history.load().single().position)
    }

    @Test fun unwatchClearsResumeToo() {
        val records = records()
        records.save(video("Clip.mkv"), 980.0, 1000.0)
        records.setWatched(video("Clip.mkv"), true)
        records.save(video("Clip.mkv"), 300.0, 1000.0)
        records.setWatched(video("Clip.mkv"), false)

        assertTrue(records.watched.load().isEmpty())
        assertTrue(records.history.load().isEmpty())
    }

    @Test fun rewatchKeepsTickAndProgress() {
        val records = records()
        records.save(video("Clip.mkv"), 980.0, 1000.0)
        records.save(video("Clip.mkv"), 300.0, 1000.0)

        assertTrue(key("Clip.mkv") in records.watched.load())
        assertEquals(300.0, records.history.load().single().position)

        records.save(video("Clip.mkv"), 990.0, 1000.0)

        assertTrue(key("Clip.mkv") in records.watched.load())
        assertTrue(records.history.load().isEmpty())
    }

    @Test fun startedVideoIsNoLongerNew() {
        val records = records()
        records.arrived(listOf(video("Clip.mkv").absolutePath))
        assertTrue(records.newVideos.snapshot().isNew(key("Clip.mkv"), clock))

        records.started(video("Clip.mkv"))

        assertFalse(records.newVideos.snapshot().isNew(key("Clip.mkv"), clock))
    }

    @Test fun renameMovesEverything() {
        val records = records()
        records.arrived(listOf(video("Old.mkv").absolutePath))
        records.save(video("Old.mkv"), 300.0, 1000.0)
        records.setWatched(video("Old.mkv"), true)

        records.rename(
            ShareHosts.canonical(video("Old.mkv").absolutePath),
            ShareHosts.canonical(video("New.mkv").absolutePath),
        )

        assertEquals(setOf(key("New.mkv")), records.watched.load())
        assertTrue(records.history.load().single().path.endsWith("New.mkv"))
        assertTrue(records.newVideos.snapshot().isNew(key("New.mkv"), clock))
    }

    @Test fun everyWriteRaisesTheRevision() {
        val records = records()
        val before = records.revision.value
        records.save(video("Clip.mkv"), 300.0, 1000.0)
        records.setWatched(video("Clip.mkv"), true)
        assertTrue(records.revision.value >= before + 2)
    }

    /**
     * Makes the next write of the store file [name] land in a file kept aside. A write puts a new temporary file in
     * place each time, so the store is still that file only if it was written once.
     */
    private fun firstWriteOf(name: String): Path {
        val kept = Files.createFile(folder.resolve("$name.first"))
        Files.createLink(folder.resolve("$name.tmp"), kept)
        return kept
    }

    private fun writtenOnce(kept: Path, name: String) = Files.isSameFile(kept, folder.resolve(name))

    @Test fun markingManyVideosWritesTheStoreOnceAndRaisesTheRevisionOnce() {
        val records = records()
        val videos = (1..5).map { video("Clip $it.mkv") }
        val watched = firstWriteOf("watched.json")
        val before = records.revision.value

        records.setWatched(videos, true)

        assertEquals(videos.map { key(it.name) }.toSet(), records.watched.load())
        assertTrue(writtenOnce(watched, "watched.json"), "watched.json is written once")
        assertEquals(before + 1, records.revision.value)
    }

    @Test fun unmarkingManyVideosWritesEachStoreOnceAndRaisesTheRevisionOnce() {
        val records = records()
        val videos = (1..5).map { video("Clip $it.mkv") }
        videos.forEach { records.save(it, 300.0, 1000.0) }
        records.setWatched(videos, true)
        val watched = firstWriteOf("watched.json")
        val history = firstWriteOf("history.json")
        val before = records.revision.value

        records.setWatched(videos, false)

        assertTrue(records.watched.load().isEmpty())
        assertTrue(records.history.load().isEmpty())
        assertTrue(writtenOnce(watched, "watched.json"), "watched.json is written once")
        assertTrue(writtenOnce(history, "history.json"), "history.json is written once")
        assertEquals(before + 1, records.revision.value)
    }

    @Test fun libraryMarksUseTheStoreKeys() {
        val records = records()
        records.setWatched(video("Clip.mkv"), true)
        val entry = WindowsMediaEntry(video("Clip.mkv").absolutePath, "s", "local", "S", 1, 1)
        val marks = LibraryMarks(records.watched.load(), records.newVideos.snapshot())

        assertTrue(marks.isWatched(entry))
        assertFalse(marks.isNew(entry, clock))
    }
}
