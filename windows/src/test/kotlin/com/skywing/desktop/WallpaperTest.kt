package com.skywing.desktop

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule

/** B11: the wallpaper is painted once into a picture, and an adaptive one changes only after the covers settle. */
class WallpaperTest {
    @get:Rule val compose = createComposeRule()

    private val style = skyThemes.first { it.adaptive?.layout == AdaptiveLayout.GLOW }.adaptive!!

    @Test
    fun theWallpaperIsPaintedOnceIntoAPictureHalfTheWindowsSize() {
        val picture = renderWallpaper(Size(2560f, 1600f), Density(1f), LayoutDirection.Ltr) { drawRect(Color.Red) }
        assertEquals(1280, picture.width)
        assertEquals(800, picture.height)
        assertEquals(Color.Red, picture.toPixelMap()[640, 400])
        // An unchanging picture is uploaded to the graphics card once, not on every frame.
        assertTrue(picture.asSkiaBitmap().isImmutable)
        assertEquals(IntSize(1, 1), wallpaperPictureSize(Size.Zero))
    }

    @Test
    fun theHalfSizePictureLooksTheSameAsPaintingTheWholeWindowForEveryTheme() {
        val window = Size(1280f, 800f)
        val density = Density(1f)
        for (theme in skyThemes) {
            val direct = ImageBitmap(1280, 800)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(direct), window) { drawSkyWallpaper(theme) }
            val picture = renderWallpaper(window, density, LayoutDirection.Ltr) { drawSkyWallpaper(theme) }
            val shown = ImageBitmap(1280, 800)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(shown), window) {
                drawImage(picture, dstSize = IntSize(1280, 800), filterQuality = FilterQuality.Low)
            }
            val a = direct.toPixelMap()
            val b = shown.toPixelMap()
            var worst = 0f
            for (y in 0 until 800 step 7) for (x in 0 until 1280 step 7) {
                val p = a[x, y]
                val q = b[x, y]
                worst = maxOf(worst, abs(p.red - q.red), abs(p.green - q.green), abs(p.blue - q.blue))
            }
            assertTrue(worst <= 3f / 255f, "${theme.id}: a pixel differs by ${(worst * 255).toInt()} of 255")
        }
    }

    @Test
    fun anAdaptiveWallpaperChangesTwoSecondsAfterTheCoversOnScreenStopChangingThenFadesIn() {
        compose.mainClock.autoAdvance = false
        lateinit var wallpaper: AdaptiveWallpaper
        compose.setContent { wallpaper = rememberAdaptiveWallpaper(style) }
        compose.mainClock.advanceTimeByFrame()
        val before = wallpaper.to
        val red = Color(0xFFD03030)
        val token = ScreenColors.add(red)
        try {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(1_000)
            assertEquals(before, wallpaper.to, "the covers may still be changing, so the wallpaper waits")
            assertNull(wallpaper.from)

            compose.mainClock.advanceTimeBy(1_300)
            val after = adaptivePalette(listOf(red), style)
            assertNotEquals(before, after)
            assertEquals(after, wallpaper.to)
            assertNotNull(wallpaper.from, "the old wallpaper stays under the new one while it fades in")
            assertTrue(wallpaper.fade in 0f..0.99f, "fading in, not switched at once: ${wallpaper.fade}")

            compose.mainClock.advanceTimeBy(700)
            assertEquals(1f, wallpaper.fade)
            assertNull(wallpaper.from)
        } finally {
            ScreenColors.remove(token)
        }
    }
}
