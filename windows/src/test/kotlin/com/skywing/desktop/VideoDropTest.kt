package com.skywing.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** B37: what a drop from Explorer plays, whichever part of the window takes it. */
class VideoDropTest {
    private val folder = Files.createTempDirectory("skywing-drop-").toFile()

    @AfterTest
    fun deleteFolder() {
        folder.deleteRecursively()
    }

    private fun file(name: String) = File(folder, name).apply { writeText("x") }

    private fun files(vararg items: Any) = object : Transferable {
        override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.javaFileListFlavor
        override fun getTransferData(flavor: DataFlavor): Any {
            if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
            return items.toList()
        }
    }

    @Test
    fun theFirstVideoOfSeveralFilesPlays() {
        val subfolder = File(folder, "Season 1").apply { mkdirs() }
        val first = file("Episode 2.MKV")
        file("Episode 3.mp4")
        assertEquals(first, droppedVideo(files(subfolder, file("notes.txt"), first, File(folder, "Episode 3.mp4"))))
    }

    @Test
    fun aVideoThatIsNoLongerThereIsPassedOver() {
        val there = file("there.mp4")
        assertEquals(there, droppedVideo(files(File(folder, "gone.mp4"), there)))
    }

    @Test
    fun withoutAVideoNothingPlays() {
        assertNull(droppedVideo(files(file("notes.txt"), File(folder, "empty folder").apply { mkdirs() })))
        assertNull(droppedVideo(files()))
    }

    @Test
    fun textAndUnreadableDropsPlayNothing() {
        assertNull(droppedVideo(StringSelection(file("clip.mp4").absolutePath)))
        val broken = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = true
            override fun getTransferData(flavor: DataFlavor): Any = throw java.io.IOException("the source went away")
        }
        assertNull(droppedVideo(broken))
    }
}
