package com.deepak.videolibrary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LibraryBackupTest {
    @Test fun backupRestoresOrganizationAndHistoryAndRejectsDifferentLibrary() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        val target=Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        try {
            val video=VideoEntity(relativePath="Movies/A.mp4",name="A.mp4",parentDir="Movies",size=1,modified=1,groupKey="A",customGroup="Favourites",lastSeenSession=1,addedAt=1)
            source.dao().insertVideo(video); target.dao().insertVideo(video.copy(customGroup=null))
            source.dao().createFolder(CustomFolderEntity("Favourites"))
            val state=source.libraryState()
            val collection=state.createMatch(MatchFolderEntity(connectionKey="nas/share",name="Films",term="",sourcePath="Movies"))
            state.hide(HiddenItem("nas/share","MATCH",collection.toString(),"Films"))
            state.putProgress(PlaybackStateEntity("nas/share",video.relativePath,30000,120000))
            target.libraryState().createMatch(MatchFolderEntity(connectionKey="nas/share",name="Existing",term="Existing"))
            val text=LibraryBackup(source,"library-one","nas/share").export()
            assertFalse(text.contains("password")); assertFalse(text.contains("username"))
            assertEquals(0,LibraryBackup(target,"library-one","nas/share").restore(text))
            assertEquals("Favourites",target.dao().findVideo(video.relativePath)!!.customGroup)
            assertEquals(30000L,target.libraryState().progress("nas/share",video.relativePath)!!.positionMs)
            val restored=target.libraryState().sourceFolder("nas/share","Movies",true)!!
            assertEquals(restored.id.toString(),target.libraryState().savedHidden("nas/share").single().target)
            try { LibraryBackup(target,"different-library","nas/share").restore(text); fail("Different library must be rejected") } catch(_:IllegalArgumentException) { }
            assertEquals("Favourites",target.dao().findVideo(video.relativePath)!!.customGroup)
        } finally { source.close(); target.close() }
    }
}
