package com.deepak.videolibrary

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.player.LibraryPlaybackQueue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FolderCollectionsTest {
    @Test fun folderMembershipIsPathBoundedAndIncludesFutureScans() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            suspend fun add(path: String) = db.dao().insertVideo(VideoEntity(relativePath=path,name=path.substringAfterLast('/'),parentDir=path.substringBeforeLast('/'),size=1,modified=1,groupKey="A",lastSeenSession=1,addedAt=1))
            add("Movies/A.mp4"); add("Movies/Sub/B.mp4"); add("MoviesExtra/C.mp4")
            val dao = db.libraryState()
            dao.createMatch(MatchFolderEntity(connectionKey="nas/share",name="My movies",term="",sourcePath="Movies"))
            assertEquals(2,dao.matchingFolders(LibraryQueries.matchingFolders("nas/share","")).first().single().count)
            val scope = LibraryScope("nas/share",sourcePath="Movies",includeSubfolders=true)
            assertEquals(2,dao.videoList(LibraryQueries.videos(scope)).size)
            assertEquals(1,dao.videoList(LibraryQueries.videos(scope.copy(includeSubfolders=false))).size)
            add("Movies/New/D.mp4")
            assertEquals(3,dao.matchingFolders(LibraryQueries.matchingFolders("nas/share","")).first().single().count)
            dao.renameMatch(dao.sourceFolder("nas/share","Movies",true)!!.id,"Films")
            assertEquals("Movies",dao.sourceFolder("nas/share","Movies",true)!!.sourcePath)
            assertTrue(dao.matchingFolders(LibraryQueries.matchingFolders("other/share","")).first().isEmpty())
        } finally { db.close() }
    }

    @Test fun playbackBatchesTraverseLargeSortedLibraryWithoutDuplicates() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context,LibraryDatabase::class.java).build()
        try {
            repeat(253) { i -> db.dao().insertVideo(VideoEntity(relativePath="M/$i.mp4",name="Same name",parentDir="M",size=(i%5).toLong(),modified=1,groupKey="A",lastSeenSession=1,addedAt=1)) }
            for (sort in listOf("NAME","SIZE","NEWEST","MODIFIED")) for (descending in listOf(false,true)) {
                val scope = LibraryScope("nas/share",sort=sort,descending=descending)
                val expected = db.libraryState().videoList(LibraryQueries.videos(scope)).map { it.id }
                val queue = LibraryPlaybackQueue(db.libraryState())
                val actual = queue.begin(scope,null).toMutableList()
                while(queue.hasMore) { val next = queue.next(); assertTrue(next.size <= 100); actual.addAll(next) }
                assertEquals(expected,actual.map { it.id })
                val chosen = actual[151]
                assertEquals(expected.drop(151).take(100),queue.begin(scope,chosen).map { it.id })
            }
        } finally { db.close() }
    }
}
