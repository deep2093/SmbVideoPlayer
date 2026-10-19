package com.deepak.videolibrary.thumbs

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class ArtworkFilesTest {
    @Test fun publicationFailurePreservesPreviousArtworkAndCleansTemporaryFile() {
        val dir = Files.createTempDirectory("artwork-test").toFile()
        try {
            val target = File(dir,"cover.jpg").apply { writeText("old artwork") }
            try {
                ArtworkFiles.publish(target) { output -> output.write("partial".toByteArray()); throw IOException("Disk full") }
                fail("Failed encoding must not publish")
            } catch(_: ArtworkStorageException) { }
            assertEquals("old artwork",target.readText())
            assertEquals(listOf("cover.jpg"),dir.listFiles()!!.map { it.name })
            ArtworkFiles.publish(target) { it.write("new artwork".toByteArray()) }
            assertEquals("new artwork",target.readText())
        } finally { dir.deleteRecursively() }
    }
}
