package com.skywing.desktop.playback

import com.sun.jna.Memory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Plan 5.7 item 4: "Smooth at screen size". */
class SmoothingSizeTest {
    private val uhd = PixelSize(3840, 2160)
    private val hd = PixelSize(1920, 1080)
    private val portrait = PixelSize(1080, 1920)

    @Test fun aVideoLargerThanTheScreenIsFittedInsideItAtItsOwnAspectRoundedDownToEvenSizes() {
        val screens = listOf(PixelSize(1920, 1200), PixelSize(2560, 1600), PixelSize(3840, 2160), PixelSize(1366, 768), PixelSize(3440, 1440))
        val expected = mapOf(
            uhd to listOf(PixelSize(1920, 1080), PixelSize(2560, 1440), null, PixelSize(1364, 768), PixelSize(2560, 1440)),
            hd to listOf(null, null, null, PixelSize(1364, 768), null),
            portrait to listOf(PixelSize(674, 1200), PixelSize(900, 1600), null, PixelSize(432, 768), PixelSize(810, 1440)),
        )
        for ((video, sizes) in expected) screens.zip(sizes).forEach { (screen, size) ->
            assertEquals(size, SmoothingSize.fit(video, video, screen), "$video on $screen")
        }
    }

    @Test fun aVideoThatFitsIsNeverScaledUp() {
        assertNull(SmoothingSize.fit(PixelSize(1280, 720), PixelSize(1280, 720), PixelSize(1920, 1200)))
        assertNull(SmoothingSize.fit(PixelSize(1920, 1200), PixelSize(1920, 1200), PixelSize(1920, 1200)), "the screen's own size")
    }

    @Test fun anAnamorphicVideoIsFittedByItsShownSizeAndScaledEvenly() {
        // 1440 x 1080 stored, shown 1920 x 1080 (4:3 pixels): fitted by the shown width, then both sides shrink alike.
        assertEquals(PixelSize(1024, 768), SmoothingSize.fit(PixelSize(1440, 1080), PixelSize(1920, 1080), PixelSize(1366, 768)))
        assertNull(SmoothingSize.fit(PixelSize(1440, 1080), PixelSize(1920, 1080), PixelSize(1920, 1200)))
    }

    @Test fun theReportSaysWhatSizeSmoothingRunsAt() {
        val screen = PixelSize(1920, 1200)
        assertEquals("Smoothing size: 1920 × 1080 (screen 1920 × 1200)", SmoothingSize.line(true, PixelSize(1920, 1080), uhd, screen))
        assertEquals("Smoothing size: the video's own, 1920 × 1080 (screen 1920 × 1200)", SmoothingSize.line(true, null, hd, screen))
        assertEquals("Smoothing size: the video's own, 3840 × 2160 (screen size not reported)", SmoothingSize.line(true, null, uhd, null))
        assertEquals("Smoothing size: the video's own, 3840 × 2160 (Smooth at screen size is off)", SmoothingSize.line(false, null, uhd, screen))
        assertEquals("Smoothing size: the video's own (Smooth at screen size is off)", SmoothingSize.line(false, null, null, null))
    }

    @Test fun theScreensSizeIsItsSourceModeInWindowsDisplayConfiguration() {
        // Two DISPLAYCONFIG_MODE_INFO entries (64 bytes each): a target mode, then a source mode of 2560 x 1600.
        Memory(128).use { modes ->
            modes.clear()
            modes.setInt(0, 2) // DISPLAYCONFIG_MODE_INFO_TYPE_TARGET
            modes.setInt(64, 1) // DISPLAYCONFIG_MODE_INFO_TYPE_SOURCE
            modes.setInt(64 + 16, 2560)
            modes.setInt(64 + 20, 1600)
            assertEquals(PixelSize(2560, 1600), DisplayPaths.sourceSize(modes, 2, 1))
            assertNull(DisplayPaths.sourceSize(modes, 2, 0), "a target mode is not the desktop's size")
            assertNull(DisplayPaths.sourceSize(modes, 2, -1), "DISPLAYCONFIG_PATH_MODE_IDX_INVALID")
            assertNull(DisplayPaths.sourceSize(modes, 2, 2))
        }
    }
}
