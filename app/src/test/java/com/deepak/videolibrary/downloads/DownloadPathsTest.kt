package com.deepak.videolibrary.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPathsTest {
    @Test fun filesGoUnderTheAppFolder() {
        assertEquals("Download/SkyWing/", DownloadPaths.relativePath(""))
        assertEquals("Download/SkyWing/Ann/S1/", DownloadPaths.relativePath("Ann/S1"))
    }

    @Test fun wholeFolderKeepsItsNameAndStructure() {
        assertEquals("Ann", DownloadPaths.folderTarget("Shows/Ann", "Shows/Ann/e1.mp4"))
        assertEquals("Ann/S1", DownloadPaths.folderTarget("Shows/Ann", "Shows/Ann/S1/e1.mp4"))
        assertEquals("Ann/S1/Extras", DownloadPaths.folderTarget("/Shows/Ann/", "Shows/Ann/S1/Extras/x.srt"))
    }

    @Test fun namesAreMadeSafe() {
        assertEquals("a_b_c", DownloadPaths.cleanSegment("a:b?c"))
        assertEquals("_", DownloadPaths.cleanSegment("..."))
        assertEquals("x/y", DownloadPaths.cleanFolder("../x/./y/"))
        assertFalse(DownloadPaths.relativePath("../../etc").contains(".."))
    }

    @Test fun mimeTypesForCommonFiles() {
        assertEquals("video/mp4", DownloadPaths.mimeType("A.MP4"))
        assertEquals("video/x-matroska", DownloadPaths.mimeType("a.mkv"))
        assertEquals("application/octet-stream", DownloadPaths.mimeType("readme"))
    }

    @Test fun progressFraction() {
        val item = DownloadItem(libraryId = "l", sourcePath = "a.mp4", name = "a.mp4", targetFolder = "", size = 200, bytesDone = 50, status = DownloadItem.RUNNING)
        assertEquals(0.25f, item.fraction, 0.0001f)
        assertTrue(item.active)
        assertEquals(0f, item.copy(size = 0).fraction, 0f)
        assertTrue(item.copy(status = DownloadItem.FAILED).finished)
    }
}
