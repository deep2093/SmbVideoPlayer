package com.skywing.desktop

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** B20: the watch history and the This PC folders live in small JSON files, moved once from the registry. */
class JsonStoresTest {
    private val folder: Path = Files.createTempDirectory("skywing-json-stores")
    private val legacyParent: Preferences = Preferences.userRoot().node("skywing-tests")
    private val legacyName = "b20-" + UUID.randomUUID()

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
        legacy()?.removeNode()
    }

    private fun legacy(): Preferences? = if (legacyParent.nodeExists(legacyName)) legacyParent.node(legacyName) else null

    private fun history() = WindowsPlaybackHistoryStore(folder.resolve("history.json")) { legacy() }

    private fun recordsInFile() = JsonParser.parseString(Files.readString(folder.resolve("history.json"))).asJsonObject.getAsJsonArray("records")

    @Test fun historyIsKeptInAJsonFileThatANewStoreReadsBack() {
        val video = folder.resolve("Clip.mkv").toFile()
        history().saveProgress(video, 120.0, 1_000.0)

        val records = history().load()
        assertEquals(listOf(video.absolutePath), records.map { it.path })
        assertEquals(120.0, records.single().position)
        assertEquals(1, recordsInFile().size())
        assertFalse(Files.exists(folder.resolve("history.json.tmp")), "the temporary file is moved into place")
    }

    @Test fun registryHistoryMovesOnceKeepingEveryRecordThenTheRegistryValuesGo() {
        legacyParent.node(legacyName).apply {
            // The third video was watched to the end, so it isn't shown, but it moves too.
            listOf(Triple("C:\\v\\a.mkv", 100.0, 1L), Triple("C:\\v\\b.mkv", 200.0, 2L), Triple("C:\\v\\c.mkv", 990.0, 3L))
                .forEachIndexed { index, (path, position, updated) ->
                    put("path.$index", path)
                    putDouble("position.$index", position)
                    putDouble("duration.$index", 1_000.0)
                    putLong("updated.$index", updated)
                }
            putInt("count", 3)
            flush()
        }

        assertEquals(listOf("C:\\v\\b.mkv", "C:\\v\\a.mkv"), history().load().map { it.path })
        assertEquals(3, recordsInFile().size())
        assertNull(legacy(), "the registry values are removed once the file holds them")

        // Values an older SkyWing writes to the registry again aren't read: the move runs once.
        legacyParent.node(legacyName).apply {
            put("path.0", "C:\\v\\d.mkv")
            putDouble("position.0", 50.0)
            putDouble("duration.0", 1_000.0)
            putInt("count", 1)
            flush()
        }
        assertEquals(listOf("C:\\v\\b.mkv", "C:\\v\\a.mkv"), history().load().map { it.path })
    }

    @Test fun aDamagedHistoryFileIsSetAsideNotWrittenOver() {
        val file = folder.resolve("history.json")
        val cut = "{\"version\":1,\"records\":[{\"path\":\"C:\\\\v\\\\a.mkv\",\"position\":1"
        Files.writeString(file, cut)

        assertEquals(emptyList(), history().load())
        assertEquals(cut, Files.readString(folder.resolve("history.json.damaged")))
        val video = folder.resolve("Clip.mkv").toFile()
        history().saveProgress(video, 120.0, 1_000.0)
        assertEquals(listOf(video.absolutePath), history().load().map { it.path })
    }

    @Test fun thisPcFoldersMoveFromTheRegistryAndAreSavedAsJson() {
        val first = folder.resolve("first").toString()
        val second = folder.resolve("second").toString()
        legacyParent.node(legacyName).apply {
            put("folder.0", first)
            put("folder.1", second)
            putInt("count", 2)
            flush()
        }
        fun store() = LocalFolderStore(folder.resolve("local-folders.json")) { legacy() }

        assertEquals(listOf(first, second), store().load())
        assertNull(legacy())
        val third = folder.resolve("third").toString()
        assertEquals(listOf(first, second, third), store().add(third))
        assertEquals(listOf(first, third), store().remove(second))
        assertEquals(listOf(first, third), store().load())
    }

    @Test fun theOldCustomFoldersAreRemovedFromTheRegistry() {
        // Custom folders moved to folders.bin earlier; the registry still holds one value per video.
        legacyParent.node(legacyName).apply {
            put("name.0", "Films")
            putInt("members.0", 1)
            put("path.0.0", "C:\\v\\a.mkv")
            putInt("count", 1)
            flush()
        }
        val store = WindowsCustomFolderStore { legacy() }
        assertEquals(1, store.load().size)

        store.clear()
        assertNull(legacy())
        assertTrue(store.load().isEmpty())
    }
}
