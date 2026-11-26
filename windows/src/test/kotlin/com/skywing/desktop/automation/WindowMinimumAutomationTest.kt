package com.skywing.desktop.automation

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan 6.8 rule 5 in the packaged SkyWing: made as small as Windows allows, the main window stays at least
 * 640 × 480 dp, measured on the visible frame. Java's own minimum is in raw pixels (JDK-8221452) and counts the
 * invisible resize borders, so it falls short at every scaling, most at 125 % and above.
 */
class WindowMinimumAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun theWindowStaysAtLeast640By480DpWhenMadeAsSmallAsWindowsAllows() =
        SkyWingTestApp.launch("window-minimum").use { app ->
            val hwnd = app.mainWindow()
            val work = WindowShots.workArea(hwnd)
            WindowShots.move(hwnd, work.left + 40, work.top + 40, 200, 150)
            Thread.sleep(500)
            val placement = WindowShots.placement(hwnd)
            assertNull(WindowRule.minimum(placement))
            // Held at the minimum, not above it: a scale or border counted twice would still pass the line above.
            assertTrue(placement.width <= 644 && placement.height <= 484,
                "Made as small as Windows allows, the window should be about 640 × 480 dp; it is ${placement.width} × ${placement.height} dp")
        }
}
