package com.skywing.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** B9: what the screens look up in the whole library is built once per reload, off the screen thread, with the list. */
class LibraryModelTest {
    private val folder: Path = Files.createTempDirectory("skywing-library-model")

    @AfterTest fun cleanUp() {
        folder.toFile().deleteRecursively()
    }

    @Test fun aReloadIndexesEveryVideoByItsPathAndListsTheSharesOnce() = runBlocking {
        val local = folder.resolve("Clip One.mkv").toString()
        val store = WindowsMediaIndex(folder.resolve("media-index.bin")).apply {
            replaceSource("PC", "PC", "This PC", listOf(FoundVideo(File(local), 1, 2)))
            replaceSource(
                "SMB:films", "SMB", "Films",
                listOf(FoundVideo(File("\\\\server\\films\\a.mkv"), 1, 2), FoundVideo(File("\\\\SERVER\\Films\\b\\c.mkv"), 1, 2)),
            )
        }
        val library = LibraryModel(store)
        library.reload()

        assertEquals(3, library.byPath.size)
        assertSame(library.all.first { it.path == local }, library.byPath[local.lowercase(Locale.ROOT)])
        assertEquals(listOf("\\\\server\\films"), library.shareRoots)
    }
}
