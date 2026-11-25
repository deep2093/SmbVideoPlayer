package com.skywing.desktop.playback

import com.skywing.desktop.automation.TestMedia
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.AfterClass
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/**
 * B24: thumbnails from the pinned libmpv (SKYWING_TEST_MPV=true). One player is kept per worker and used for many
 * videos, and an HDR video's picture is tone mapped. No window is opened.
 */
class ThumbnailExtractorNativeTest {
    @Before
    fun requireNativePlayer() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
    }

    @Test(timeout = 120_000)
    fun oneWorkerMakesSeveralThumbnailsInARowWithOnePlayer() {
        ThumbnailExtractor.closeIdle()
        val before = ThumbnailExtractor.playersStarted
        val first = File(media, "first.jpg")
        val second = File(media, "second.jpg")
        val third = File(media, "third.jpg")
        val a = ThumbnailExtractor.extractDetailed(sdr, first)
        val b = ThumbnailExtractor.extractDetailed(sdr, second, start = "1", exact = true)
        val c = ThumbnailExtractor.extractDetailed(hdr, third, width = 320)
        assertTrue(a.success, a.reason)
        assertTrue(b.success, b.reason)
        assertTrue(c.success, c.reason)
        assertEquals(1, ThumbnailExtractor.playersStarted - before, "the three pictures came from one player")
        assertEquals(480, ImageIO.read(first).width)
        assertEquals(480, ImageIO.read(second).width)
        assertEquals(320, ImageIO.read(third).width)
    }

    @Test(timeout = 120_000)
    fun pictureReportsLength() {
        val result = ThumbnailExtractor.extractDetailed(sdr, File(media, "length.jpg"))
        assertTrue(result.success, result.reason)
        val seconds = result.duration
        assertTrue(seconds != null && seconds.isFinite() && seconds > 0, "length: $seconds")
    }

    @Test(timeout = 120_000)
    fun aFileThatCannotBeReadDoesNotStopTheNextOne() {
        val broken = File(media, "broken.mp4").apply { writeBytes(ByteArray(8192) { 7 }) }
        val failed = ThumbnailExtractor.extractDetailed(broken, File(media, "broken.jpg"))
        assertFalse(failed.success)
        val after = ThumbnailExtractor.extractDetailed(sdr, File(media, "after-broken.jpg"))
        assertTrue(after.success, after.reason)
    }

    @Test(timeout = 120_000)
    fun anHdrVideosThumbnailIsToneMapped() {
        val target = File(media, "hdr.jpg")
        val result = ThumbnailExtractor.extractDetailed(hdr, target)
        assertTrue(result.success, result.reason)
        assertTrue(result.toneMapped, "a PQ video gets the zscale tone-mapping pass: ${result.reason}")
        assertEquals(480, ImageIO.read(target).width)
        val brightness = meanBrightness(target)
        println("tone-mapped picture brightness $brightness")
        assertTrue(brightness in 0.1..0.9, "the tone-mapped picture is neither black nor blown out: $brightness")
    }

    private fun meanBrightness(picture: File): Double {
        val image = ImageIO.read(picture)
        var sum = 0.0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val rgb = image.getRGB(x, y)
            sum += (0.2126 * (rgb shr 16 and 255) + 0.7152 * (rgb shr 8 and 255) + 0.0722 * (rgb and 255)) / 255
        }
        return sum / (image.width * image.height)
    }

    @Test(timeout = 120_000)
    fun anSdrVideosThumbnailIsNotToneMapped() {
        val result = ThumbnailExtractor.extractDetailed(sdr, File(media, "sdr.jpg"))
        assertTrue(result.success, result.reason)
        assertFalse(result.toneMapped)
    }

    companion object {
        private lateinit var media: File
        private lateinit var sdr: File
        private lateinit var hdr: File

        @BeforeClass @JvmStatic
        fun generateMedia() {
            if (System.getenv("SKYWING_TEST_MPV") != "true") return
            media = Files.createTempDirectory("skywing-thumbnails-").toFile()
            sdr = TestMedia.encode(media, TestMedia.Spec("clip-sdr.mp4", "libx264", null, seconds = 3))
            // testsrc2 tagged as HDR10: 10-bit, BT.2020 colours and the PQ curve, which mpv reports as gamma "pq".
            // mpv's encoder passes the BT.2020 primaries to x265 but not the PQ curve, so x265 is told directly.
            hdr = TestMedia.encode(media, TestMedia.Spec("clip-hdr.mkv", "libx265", null, seconds = 3,
                vf = "format=fmt=yuv420p10:colormatrix=bt.2020-ncl:primaries=bt.2020:gamma=pq",
                ovcopts = "x265-params=[transfer=smpte2084:colorprim=bt2020:colormatrix=bt2020nc]"))
        }

        @AfterClass @JvmStatic
        fun deleteMedia() {
            ThumbnailExtractor.closeIdle()
            if (::media.isInitialized) media.deleteRecursively()
        }
    }
}
