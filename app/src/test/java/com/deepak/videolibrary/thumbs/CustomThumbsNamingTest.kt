package com.deepak.videolibrary.thumbs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CustomThumbsNamingTest {
    @Test fun parsesVersionedNamesOnly() {
        assertEquals(42L to 1700000000000L, CustomThumbs.parse("42_1700000000000.jpg"))
        assertNull(CustomThumbs.parse("42.jpg"))
        assertNull(CustomThumbs.parse("art-123.tmp"))
        assertNull(CustomThumbs.parse("x_1.jpg"))
        assertNull(CustomThumbs.parse("1_2_3.jpg"))
    }

    @Test fun frameSizeKeepsAspectRatioAndNeverUpscales() {
        assertEquals(960 to 540, FrameGrabber.scaledSize(FrameGrabber.Info(1000, 3840, 2160), 960))
        assertEquals(640 to 1137, FrameGrabber.scaledSize(FrameGrabber.Info(1000, 1080, 1920), 640))
        assertEquals(320 to 240, FrameGrabber.scaledSize(FrameGrabber.Info(1000, 320, 240), 960))
        assertEquals(960 to 540, FrameGrabber.scaledSize(FrameGrabber.Info(0, 0, 0), 960))
    }
}
