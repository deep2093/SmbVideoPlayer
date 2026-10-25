package com.skywing.desktop

import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WindowsShareBrowserTest {
    @Test
    fun acceptsUncShareAndRejectsRelativeOrTraversal() {
        val browser = WindowsShareBrowser()
        assertTrue(browser.shareRoot("\\\\server\\share").toString().startsWith("\\\\server\\share"))
        assertThrows(IllegalArgumentException::class.java) { browser.shareRoot("Z:\\video") }
        assertThrows(IllegalArgumentException::class.java) { browser.shareRoot("\\\\server\\share\\..\\other") }
    }

    @Test
    fun listsOnlyVideosAndFoldersWithinSelectedRoot() {
        runBlocking {
            val root = Files.createTempDirectory("skywing-share-test")
            val sub = Files.createDirectory(root.resolve("Films"))
            Files.createFile(root.resolve("sample.mkv"))
            Files.createFile(root.resolve("notes.txt"))
            try {
                val listing = WindowsShareBrowser().list(root, root)
                assertEquals(listOf("Films", "sample.mkv"), listing.entries.map { it.file.name })
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { WindowsShareBrowser().list(root, root.parent) }
                }
            } finally {
                Files.deleteIfExists(root.resolve("sample.mkv"))
                Files.deleteIfExists(root.resolve("notes.txt"))
                Files.deleteIfExists(sub)
                Files.deleteIfExists(root)
            }
        }
    }
}
