package com.skywing.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Actor pages: which videos were watched to the end is kept on this PC in watched.json. */
class WatchedStoreTest {
    private val folder: Path = Files.createTempDirectory("skywing-watched")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    private fun store() = WindowsWatchedStore(folder.resolve("watched.json"))

    private fun video(name: String) = folder.resolve(name).toFile()

    private fun key(name: String) = ShareHosts.canonical(video(name).absolutePath).lowercase()

    @Test fun markedVideoIsReadBackByANewStore() {
        store().mark(video("Clip.mkv"))

        assertTrue(key("Clip.mkv") in store().load())
        assertEquals(key("Clip.mkv"), key("Clip.mkv").lowercase())
    }

    @Test fun unmarkRemovesTheMark() {
        val watched = store()
        watched.mark(video("Clip.mkv"))
        watched.unmark(video("Clip.mkv"))

        assertTrue(store().load().isEmpty())
    }

    @Test fun renameMovesTheMark() {
        val watched = store()
        watched.mark(video("Old.mkv"))
        watched.renamePath(video("Old.mkv").absolutePath, video("New.mkv").absolutePath)

        val loaded = store().load()
        assertFalse(key("Old.mkv") in loaded)
        assertTrue(key("New.mkv") in loaded)
    }

    @Test fun unreadableFileIsNeverOverwritten() {
        // A folder where the file should be can't be read as a file, like a file another program holds open.
        val blocked = folder.resolve("watched.json")
        Files.createDirectory(blocked)
        val watched = WindowsWatchedStore(blocked)

        watched.mark(video("Clip.mkv"))
        watched.unmark(video("Clip.mkv"))
        watched.renamePath("a", "b")

        assertTrue(Files.isDirectory(blocked))
        assertEquals(0, Files.list(blocked).use { it.count() })
        assertTrue(watched.load().isEmpty())
    }

    @Test fun finishedAtBoundary() {
        assertTrue(finishedAt(970.0, 1000.0))
        assertFalse(finishedAt(969.9, 1000.0))
        assertFalse(finishedAt(10.0, 0.0))
        assertFalse(finishedAt(Double.NaN, 1000.0))
        // The rule is the last 30 seconds, whatever the length.
        assertTrue(finishedAt(7170.0, 7200.0))
        assertFalse(finishedAt(7169.9, 7200.0))
    }

    @Test fun historyForgetRemovesTheResumeRecord() {
        val history = WindowsPlaybackHistoryStore(folder.resolve("history.json")) { null }
        history.saveProgress(video("Clip.mkv"), 120.0, 1_000.0)
        history.forget(video("Clip.mkv"))

        assertTrue(history.load().isEmpty())
    }

    @Test fun fileOfAnotherVersionOrShapeIsNeverOverwritten() {
        val path = folder.resolve("watched.json")
        val text = """{"version":2,"watched":"x"}"""
        Files.writeString(path, text)
        val watched = WindowsWatchedStore(path)

        watched.mark(video("Clip.mkv"))

        assertEquals(text, Files.readString(path))
        assertTrue(watched.load().isEmpty())
    }
}
