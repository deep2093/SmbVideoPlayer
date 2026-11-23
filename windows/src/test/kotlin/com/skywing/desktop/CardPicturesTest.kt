package com.skywing.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import com.skywing.desktop.artwork.Pictures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** B10: card pictures are decoded at the size they are shown, and kept within a memory budget. */
class CardPicturesTest {
    @Test
    fun aPictureShrinksByTheLargestPowerOfTwoThatStillCoversTheCard() {
        // A 4K poster on a 200 × 300 card: a quarter (960 × 540) still covers the card, an eighth would not.
        assertEquals(4, shrinkStep(IntSize(3840, 2160), IntSize(200, 300)))
        assertEquals(2, shrinkStep(IntSize(3840, 2160), IntSize(1920, 1080)))
        assertEquals(1, shrinkStep(IntSize(480, 270), IntSize(300, 169)))
        // Never made larger than the file, and nothing is guessed before the card has a size.
        assertEquals(1, shrinkStep(IntSize(100, 100), IntSize(300, 300)))
        assertEquals(1, shrinkStep(IntSize(3840, 2160), IntSize.Zero))
    }

    @Test
    fun aShrunkPictureIsDecodedAgainOnlyForACardItNoLongerCovers() {
        val picture = CardPicture(ImageBitmap(960, 540), source = IntSize(3840, 2160), step = 4)
        assertTrue(picture.fits(IntSize(200, 300)))
        assertTrue(picture.fits(IntSize(150, 100)))
        assertFalse(picture.fits(IntSize(800, 600)))
    }

    @Test
    fun theLeastRecentlyShownPictureGoesFirstWhenTheBudgetIsFull() {
        // Each 100 × 100 picture takes 40,000 bytes; the budget holds three.
        val memory = PictureMemory(maxBytes = 120_000)
        val pictures = List(4) { CardPicture(ImageBitmap(100, 100), IntSize(100, 100), 1) }
        memory.put("a", pictures[0], "covers")
        memory.put("b", pictures[1], "covers")
        memory.put("c", pictures[2], "thumbnails")
        assertSame(pictures[0], memory.get("a"), "a was shown again, so b is now the oldest")
        memory.put("d", pictures[3], "thumbnails")
        assertNull(memory.get("b"))
        assertNotNull(memory.get("a"))
        assertEquals(40_000, memory.bytesOf("covers"))
        assertEquals(80_000, memory.bytesOf("thumbnails"))
        memory.clear("thumbnails")
        assertEquals(0, memory.bytesOf("thumbnails"))
        assertNotNull(memory.get("a"))
    }

    @Test
    fun aLargePosterIsKeptAtTheCardsSizeNotItsOwn() {
        val poster = Pictures.draw(3840, 2160) { }
        val picture = assertNotNull(CardPictures.decode(poster, IntSize(200, 300)))
        assertEquals(960, picture.bitmap.width)
        assertEquals(540, picture.bitmap.height)
        assertEquals(IntSize(3840, 2160), picture.source)
        // About 2 MB instead of 33 MB.
        assertEquals(960L * 540 * 4, picture.bytes)
    }

    @Test
    fun aFlipBookStripShrinksByItsFrameSoEveryFrameStaysTheSameHeight() {
        // Eight 480 × 270 frames stacked; a 120 × 67 card takes a quarter.
        val strip = Pictures.draw(480, 270 * 8) { }
        val picture = assertNotNull(CardPictures.decode(strip, IntSize(120, 67), frames = 8))
        assertEquals(4, picture.step)
        assertEquals(120, picture.bitmap.width)
        assertEquals(67 * 8, picture.bitmap.height)
    }

    @Test
    fun bytesThatAreNotAPictureGiveNothing() {
        assertNull(CardPictures.decode(ByteArray(64) { it.toByte() }, IntSize(200, 300)))
    }
}
