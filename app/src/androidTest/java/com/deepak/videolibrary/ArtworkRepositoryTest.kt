package com.deepak.videolibrary

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.artwork.ArtworkDatabase
import com.deepak.videolibrary.artwork.ArtworkKeys
import com.deepak.videolibrary.artwork.ArtworkMode
import com.deepak.videolibrary.artwork.ArtworkOverride
import com.deepak.videolibrary.artwork.ArtworkPreferences
import com.deepak.videolibrary.artwork.ArtworkRepository
import com.deepak.videolibrary.artwork.ArtworkSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ArtworkRepositoryTest {
    private val target: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "artwork-test-${System.nanoTime()}"
    private lateinit var home: File
    private val opened = ArrayList<ArtworkDatabase>()

    @Before fun setUp() {
        home = File(target.cacheDir, id).apply { mkdirs() }
    }

    @After fun tearDown() {
        opened.forEach { it.close() }
        target.deleteDatabase(id)
        target.getSharedPreferences("artwork-settings-$id", Context.MODE_PRIVATE).edit().clear().commit()
        home.deleteRecursively()
    }

    /** A fresh repository over the same files and database, like the app after a restart. */
    private fun open(): ArtworkRepository {
        val files = object : ContextWrapper(target) {
            override fun getFilesDir(): File = home
        }
        val db = Room.databaseBuilder(target, ArtworkDatabase::class.java, id).build().also { opened += it }
        return ArtworkRepository(files, db, ArtworkPreferences(target, id))
    }

    private fun picture(color: Int = Color.RED) = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun manualCoverSurvivesARestart() = runBlocking {
        val first = open()
        first.ensureLoaded()
        first.saveImage("original:Shows/Ann", ArtworkMode.CUSTOM, picture(), manual = true)
        val second = open()
        second.ensureLoaded()
        val row = second.row("original:Shows/Ann")
        assertNotNull(row)
        assertEquals(ArtworkMode.CUSTOM, row!!.mode)
        assertTrue(row.manual)
        assertTrue(second.imageFile(row.imageName)!!.isFile)
    }

    @Test fun savingAgainReplacesThePreviousPicture() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        repo.saveImage("virtual:Ann", ArtworkMode.CUSTOM, picture(), manual = true)
        val firstName = repo.row("virtual:Ann")!!.imageName
        repo.saveImage("virtual:Ann", ArtworkMode.CUSTOM, picture(Color.BLUE), manual = true)
        val secondName = repo.row("virtual:Ann")!!.imageName
        assertFalse(firstName == secondName)
        assertNull(repo.imageFile(firstName))
        assertNotNull(repo.imageFile(secondName))
    }

    @Test fun clearOnlyAffectsTheNamedItems() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        repo.saveImage("video:1", ArtworkMode.CUSTOM, picture(), manual = true)
        repo.saveImage("video:2", ArtworkMode.CUSTOM, picture(), manual = true)
        val removedFile = repo.row("video:1")!!.imageName
        assertEquals(1, repo.clear(listOf("video:1")))
        assertNull(repo.row("video:1"))
        assertNull(repo.imageFile(removedFile))
        assertNotNull(repo.row("video:2"))
        // Gone after a restart too.
        val again = open()
        again.ensureLoaded()
        assertNull(again.row("video:1"))
        assertNotNull(again.row("video:2"))
    }

    @Test fun fetchedPortraitsCanBeClearedButManualChoicesStay() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        repo.saveImage("virtual:Fetched", ArtworkMode.PORTRAIT, picture(), manual = false, providerId = "stashdb", performerId = "1", performerName = "Fetched")
        repo.saveImage("virtual:Mine", ArtworkMode.PORTRAIT, picture(), manual = true, providerId = "stashdb", performerId = "2", performerName = "Mine")
        assertEquals(1, repo.clearFetchedPortraits())
        assertNull(repo.row("virtual:Fetched"))
        assertNotNull(repo.row("virtual:Mine"))
    }

    @Test fun lookupResultsNeverReplaceAManualChoice() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        repo.saveImage("virtual:Ann", ArtworkMode.CUSTOM, picture(), manual = true)
        repo.recordLookup("virtual:Ann", ArtworkOverride.STATUS_NO_MATCH)
        assertEquals(ArtworkOverride.STATUS_OK, repo.row("virtual:Ann")!!.lookupStatus)
        repo.recordLookup("virtual:Bob", ArtworkOverride.STATUS_AMBIGUOUS)
        assertEquals(ArtworkOverride.STATUS_AMBIGUOUS, repo.row("virtual:Bob")!!.lookupStatus)
        assertFalse(repo.needsLookup("virtual:Bob"))
        assertTrue(repo.needsLookup("virtual:Never"))
    }

    @Test fun foldersFollowTheSettingsAndFallBackWhenAPictureIsMissing() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        val key = ArtworkKeys.originalFolder("Shows/Ann")
        repo.saveImage(key, ArtworkMode.CUSTOM, picture(), manual = true)
        // Master switch off: nothing changes.
        assertEquals(ArtworkSource.LEGACY, repo.decideFolder(key).source)
        repo.preferences.update { it.copy(enabled = true) }
        assertEquals(ArtworkSource.IMAGE, repo.decideFolder(key).source)
        // The file disappears (storage cleared): the existing cover is used instead of an empty tile.
        repo.imageFile(repo.row(key)!!.imageName)!!.delete()
        assertEquals(ArtworkSource.LEGACY, repo.decideFolder(key).source)
        // A default applies to folders without an override.
        repo.preferences.update { it.withMode(com.deepak.videolibrary.artwork.ArtworkScope.SMB_FOLDER, ArtworkMode.COLLAGE) }
        assertEquals(ArtworkSource.COLLAGE, repo.decideFolder("original:Other").source)
    }

    @Test fun savedPicturesAreCardShaped() = runBlocking {
        val repo = open()
        repo.ensureLoaded()
        repo.saveImage("virtual:Ann", ArtworkMode.CUSTOM, picture(), manual = true, focusY = 0f)
        val file = repo.imageFile(repo.row("virtual:Ann")!!.imageName)!!
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
        // 16:9 window of a 320 x 480 picture.
        assertEquals(320, bounds.outWidth)
        assertEquals(180, bounds.outHeight)
    }
}
