package com.deepak.videolibrary

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.paging.PagingSource
import com.deepak.videolibrary.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryStorageTest {
    private lateinit var db:LibraryDatabase
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,LibraryDatabase::class.java).build()}
    @After fun close(){db.close()}
    private suspend fun add(path:String,group:String="Cruise",custom:String?=null,size:Long=1):Long=db.dao().insertVideo(VideoEntity(relativePath=path,name=path.substringAfterLast('/'),parentDir=path.substringBeforeLast('/',""),size=size,modified=1,groupKey=group,customGroup=custom,thumbStatus="DONE",lastSeenSession=0,addedAt=1))
    @Test fun hiddenRulesAreScopedAndPathBounded()=runBlocking {
        add("A/Cruise.mp4");add("A/Sub/Cruise.mp4");add("AB/Cruise.mp4",group="Other")
        val dao=db.libraryState()
        dao.hide(HiddenItem("nas/share","DIRECTORY","A","A"))
        assertEquals(listOf("AB/Cruise.mp4"),dao.videoList(LibraryQueries.videos(LibraryScope("nas/share"))).map{it.relativePath})
        assertEquals(3,dao.videoList(LibraryQueries.videos(LibraryScope("another/share"))).size)
        dao.unhideAll("nas/share")
        dao.hide(HiddenItem("nas/share","GROUP","Cruise","Cruise"))
        assertEquals(1,dao.videoList(LibraryQueries.videos(LibraryScope("nas/share"))).size)
        dao.unhideAll("nas/share")
        assertEquals(3,dao.videoList(LibraryQueries.videos(LibraryScope("nas/share"))).size)
    }
    @Test fun sqlSortingCountsDirectoriesAndLiteralSearch()=runBlocking {
        add("Movies/Action/2024 Tom Cruise.mp4",size=20)
        add("Movies/Drama/100%_fun.mp4",size=30)
        add("MoviesExtra/No.mp4",group="Other",size=10)
        val dao=db.libraryState()
        val matches=dao.videoList(LibraryQueries.videos(LibraryScope("nas","CRUISE")))
        assertEquals(1,matches.size)
        assertEquals(1,dao.videoList(LibraryQueries.videos(LibraryScope("nas","%_"))).size)
        val dirs=dao.directories(LibraryQueries.directories(LibraryScope("nas",directory="Movies"))).first()
        assertEquals(listOf("Movies/Action","Movies/Drama"),dirs.map{it.path})
        val groups=dao.folders(LibraryQueries.groups(LibraryScope("nas"),"SIZE",true)).first()
        assertEquals("Cruise",groups.first().groupKey)
        assertEquals(50L,groups.first().totalSize)
        assertEquals(3,dao.count(LibraryQueries.count(LibraryScope("nas"))).first())
        assertEquals(listOf("Movies/Action","Movies/Drama","MoviesExtra"),dao.directories(LibraryQueries.allDirectories(LibraryScope("nas",directory=""))).first().map{it.path})
    }
    @Test fun movePreservesIdsArtworkAssignmentsAndPlaybackState()=runBlocking {
        val id=add("Old/Cruise.mp4",custom="Favourites")
        val dao=db.libraryState()
        dao.saveProgress("nas","Old/Cruise.mp4",60000,120000)
        dao.hide(HiddenItem("nas","FILE","Old/Cruise.mp4","Cruise"))
        db.withTransaction {
            dao.moveVideo(id,"New/Cruise.mp4","New","Cruise.mp4","Cruise")
            dao.moveProgress("nas","Old/Cruise.mp4","New/Cruise.mp4")
            dao.moveHidden("nas","Old/Cruise.mp4","New/Cruise.mp4")
        }
        val row=db.dao().findVideo("New/Cruise.mp4")!!
        assertEquals(id,row.id);assertEquals("DONE",row.thumbStatus);assertEquals("Favourites",row.customGroup)
        assertEquals(60000L,dao.progress("nas","New/Cruise.mp4")!!.positionMs)
        assertEquals("New/Cruise.mp4",dao.hiddenItems("nas").first().single().target)
    }
    @Test fun progressWritesPreserveContinuePreferenceAndDuration()=runBlocking {
        add("Movie.mp4")
        val dao=db.libraryState()
        dao.saveProgress("nas","Movie.mp4",60000,120000)
        assertEquals(1,dao.continuing(LibraryQueries.continuing("nas")).first().size)
        dao.hideContinue("nas","Movie.mp4",true)
        dao.saveProgress("nas","Movie.mp4",65000,0)
        val state=dao.progress("nas","Movie.mp4")!!
        assertTrue(state.hidden);assertEquals(120000L,state.durationMs)
        assertTrue(dao.continuing(LibraryQueries.continuing("nas")).first().isEmpty())
    }
    @Test fun pagingLoadsOnlyRequestedWindowInSqlOrder()=runBlocking {
        repeat(180){add("Movies/Clip-$it.mp4",size=it.toLong())}
        val source=db.libraryState().pages(LibraryQueries.videos(LibraryScope("nas",sort="SIZE",descending=true)))
        val result=source.load(PagingSource.LoadParams.Refresh<Int>(key=null,loadSize=30,placeholdersEnabled=false))
        assertTrue(result is PagingSource.LoadResult.Page)
        val rows=(result as PagingSource.LoadResult.Page<Int,VideoEntity>).data
        assertEquals(30,rows.size);assertEquals(179L,rows.first().size)
    }
    @Test fun failedThumbnailDetailsPersistAndSelectedAssignmentsAreTargeted()=runBlocking {
        val one=add("Movies/One.mp4");val two=add("Movies/Two.mp4")
        db.dao().setThumbStatus(one,"FAILED","Unsupported codec")
        assertEquals("Unsupported codec",db.dao().observeFailedThumbs().first().single().thumbError)
        db.dao().createFolder(CustomFolderEntity("Merged"))
        db.dao().assignPaths(listOf("Movies/One.mp4"),"Merged")
        assertEquals("Merged",db.dao().findVideo("Movies/One.mp4")!!.customGroup)
        assertNull(db.dao().findVideo("Movies/Two.mp4")!!.customGroup)
        db.dao().setThumbStatus(one,"DONE")
        assertTrue(db.dao().observeFailedThumbs().first().isEmpty())
        assertEquals(two,db.dao().findVideo("Movies/Two.mp4")!!.id)
    }
}
