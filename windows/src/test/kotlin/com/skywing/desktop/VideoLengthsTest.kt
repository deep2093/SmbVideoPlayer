package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class VideoLengthsTest {
    private val folder = Files.createTempDirectory("skywing-lengths").toFile().apply { deleteOnExit() }
    private val path = folder.toPath().resolve("lengths.json")

    private fun key(name: String, stamp: Long = 1L) = ThumbnailStore.keyOf("C:/clips/$name.mp4", stamp)

    @Test
    fun recordedLengthIsReadBackAfterFlush() {
        val store = VideoLengthStore(path)
        store.record(key("a"), 12.5)
        val before = store.revision.value
        store.flush()
        assertTrue(store.revision.value > before)
        assertEquals(12.5, VideoLengthStore(path).get(key("a")))
    }

    @Test
    fun changedFileIsUnknownAgain() {
        val store = VideoLengthStore(path)
        store.record(key("a", 1), 12.5)
        assertNull(store.get(key("a", 2)))
    }

    @Test
    fun writesAtMostEveryTenSeconds() {
        var clock = 100_000L
        val store = VideoLengthStore(path) { clock }
        store.record(key("a"), 1.0)
        store.flushIfDue()
        val first = store.revision.value
        clock += 1_000
        store.record(key("b"), 2.0)
        store.flushIfDue()
        assertEquals(first, store.revision.value, "a second record 1 s later is held back")
        clock += 10_000
        store.flushIfDue()
        assertEquals(first + 1, store.revision.value)
        assertEquals(2.0, VideoLengthStore(path).get(key("b")))
    }

    @Test
    fun unreadableFileIsNeverOverwritten() {
        for (text in listOf("""{"version":2,"entries":[]}""", """{"version":1}""", """{"version":1,"entries":5}""")) {
            Files.writeString(path, text)
            val store = VideoLengthStore(path)
            store.record(key("a"), 3.0)
            store.flush()
            assertNull(store.get(key("a")))
            assertEquals(text, Files.readString(path))
        }
    }

    @Test
    fun catchUpSkipsKnownVideos() = runBlocking {
        val store = VideoLengthStore(path)
        val known = File("C:/clips/known.mp4")
        val unknown = File("C:/clips/unknown.mp4")
        store.record(ThumbnailStore.keyOf(known.path, 5), 9.0)
        val read = ArrayList<File>()
        LengthCatchUp({ read += it; 20.0 }, store).run(listOf(known to 5L, unknown to 5L)) { }
        assertEquals(listOf(unknown), read)
        assertEquals(20.0, store.get(ThumbnailStore.keyOf(unknown.path, 5)))
    }

    @Test
    fun catchUpStopsAfterThreeFailuresInARow() = runBlocking {
        val store = VideoLengthStore(path)
        val videos = (1..4).map { File("C:/clips/v$it.mp4") to 1L }
        var reads = 0
        LengthCatchUp({ reads++; null }, store).run(videos) { }
        assertEquals(3, reads)
    }

    @Test
    fun catchUpReadsNothingWhileNotIdle() = runBlocking {
        val store = VideoLengthStore(path)
        var reads = 0
        val finished = withTimeoutOrNull(300) {
            LengthCatchUp({ reads++; 1.0 }, store).run(listOf(File("C:/clips/a.mp4") to 1L)) { awaitCancellation() }
        }
        assertNull(finished, "the run is still waiting for idle")
        assertEquals(0, reads)
    }

    @Test
    fun formatLengthText() {
        assertEquals("0:59", formatLength(59.4))
        assertEquals("12:34", formatLength(754.0))
        assertEquals("1:02:03", formatLength(3723.0))
    }
}
