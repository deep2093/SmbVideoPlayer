package com.deepak.videolibrary

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.thumbs.CustomThumbs
import com.deepak.videolibrary.thumbs.ThumbStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/** Custom thumbnails: priority, persistence, reset, cache clearing and isolation between videos. */
class CustomThumbnailTest {
    private lateinit var root: File
    private lateinit var context: Context

    @Before fun setUp() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(app.cacheDir, "custom-thumb-test-${UUID.randomUUID()}").apply { mkdirs() }
        // Private storage for this test only, so the app's real thumbnails are never touched.
        context = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
        }
        CustomThumbs.dropIndexForTest()
    }

    @After fun tearDown() {
        CustomThumbs.dropIndexForTest()
        root.deleteRecursively()
    }

    private fun frame(color: Int, width: Int = 1280, height: Int = 720) =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test fun customThumbnailWinsAndSurvivesRestartAndCacheClear() {
        // An automatic thumbnail exists for video 7.
        ThumbStore.thumbFile(context, 7).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(ThumbStore.thumbFile(context, 7), ThumbStore.displayFile(context, 7, "DONE"))

        val saved = CustomThumbs.save(context, 7, frame(Color.RED))
        assertEquals(saved, ThumbStore.displayFile(context, 7, "DONE"))
        // Even while the automatic thumbnail is being regenerated or failed, the custom one shows.
        assertEquals(saved, ThumbStore.displayFile(context, 7, "PENDING"))
        assertEquals(saved, ThumbStore.displayFile(context, 7, "FAILED"))

        // Stored scaled to the thumbnail width with the aspect ratio kept.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(saved.path, bounds)
        assertEquals(CustomThumbs.WIDTH, bounds.outWidth)
        assertEquals(CustomThumbs.WIDTH * 720 / 1280, bounds.outHeight)

        // "Clear cache" removes automatic artwork only.
        ThumbStore.clearAll(context)
        assertTrue(saved.isFile)

        // After a restart the index is rebuilt from disk.
        CustomThumbs.dropIndexForTest()
        assertEquals(saved, CustomThumbs.fileFor(context, 7))
    }

    @Test fun savingAgainReplacesTheOldFileAndResetAffectsOnlyThatVideo() {
        val first = CustomThumbs.save(context, 1, frame(Color.RED))
        Thread.sleep(5) // distinct file names
        val second = CustomThumbs.save(context, 1, frame(Color.BLUE))
        val other = CustomThumbs.save(context, 2, frame(Color.GREEN))

        assertFalse("the previous custom file is deleted", first.exists())
        assertEquals(second, CustomThumbs.fileFor(context, 1))
        assertEquals(2, CustomThumbs.dir(context).listFiles()!!.count { it.name.endsWith(".jpg") })

        assertTrue(CustomThumbs.reset(context, 1))
        assertNull(CustomThumbs.fileFor(context, 1))
        assertFalse(second.exists())
        assertEquals(other, CustomThumbs.fileFor(context, 2))
        assertTrue(other.isFile)
        assertFalse("resetting twice is harmless", CustomThumbs.reset(context, 1))
    }

    @Test fun deletedVideosLoseTheirCustomThumbnail() {
        CustomThumbs.save(context, 3, frame(Color.RED))
        val kept = CustomThumbs.save(context, 4, frame(Color.BLUE))
        CustomThumbs.prune(context, setOf(4L))
        assertNull(CustomThumbs.fileFor(context, 3))
        assertNotNull(CustomThumbs.fileFor(context, 4))
        assertTrue(kept.isFile)
    }
}
