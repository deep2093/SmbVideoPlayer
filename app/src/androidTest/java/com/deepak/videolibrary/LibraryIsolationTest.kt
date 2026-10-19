package com.deepak.videolibrary

import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.settings.*
import com.deepak.videolibrary.thumbs.ThumbStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LibraryIsolationTest {
    @Test fun samePathOnTwoLibrariesKeepsIndependentIndexAndArtwork() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val one = ServiceLocator(context,"test-one-$suffix",SettingsStore(context))
        val two = ServiceLocator(context,"test-two-$suffix",SettingsStore(context))
        try {
            one.db.dao().insertVideo(VideoEntity(relativePath="Same.mp4",name="Same.mp4",parentDir="",size=1,modified=1,groupKey="One",customGroup="Keep",lastSeenSession=1,addedAt=1))
            assertNull(two.db.dao().findVideo("Same.mp4"))
            two.db.dao().insertVideo(VideoEntity(relativePath="Same.mp4",name="Same.mp4",parentDir="",size=2,modified=1,groupKey="Two",lastSeenSession=1,addedAt=1))
            assertEquals("Keep",one.db.dao().findVideo("Same.mp4")!!.customGroup)
            assertEquals(2L,two.db.dao().findVideo("Same.mp4")!!.size)
            val art1 = ThumbStore.thumbFile(one.artworkContext,1)
            val art2 = ThumbStore.thumbFile(two.artworkContext,1)
            assertNotEquals(art1.path,art2.path)
        } finally { one.db.close(); two.db.close() }
    }
}
