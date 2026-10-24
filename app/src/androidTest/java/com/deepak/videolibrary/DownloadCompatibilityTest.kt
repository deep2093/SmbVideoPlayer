package com.deepak.videolibrary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.downloads.DownloadDatabase
import com.deepak.videolibrary.downloads.DownloadItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DownloadCompatibilityTest {
    @Test fun completedLookupRetainsLegacyUriAndRespectsLibraryAndDestination() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, DownloadDatabase::class.java).build()
        try {
            val old = DownloadItem(libraryId = "old-server", sourcePath = "A.mp4", name = "A.mp4", targetFolder = "Movies",
                status = DownloadItem.DONE, uri = "content://media/external/downloads/42", size = 7, bytesDone = 7)
            db.dao().insertAll(listOf(old))
            assertEquals(old.uri, db.dao().completedAt("old-server", "A.mp4", "Movies")!!.uri)
            assertNull(db.dao().completedAt("other-server", "A.mp4", "Movies"))
            assertNull(db.dao().completedAt("old-server", "A.mp4", "Other"))
            db.dao().insertAll(listOf(old.copy(sourcePath = "Pending.mp4", status = DownloadItem.RUNNING)))
            assertNull(db.dao().completedAt("old-server", "Pending.mp4", "Movies"))
        } finally { db.close() }
    }
}
