package com.skywing.desktop.automation

import com.google.gson.JsonParser
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Plan 6.8 rule 5 in the packaged SkyWing: the main window stays at least 640 × 480 dp, measured on the visible frame,
 * when made as small as Windows allows and when it opens at a size saved below that. Java's own minimum is in raw
 * pixels (JDK-8221452) and counts the invisible resize borders, so it falls short at every scaling, most at 125 % and
 * above.
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
            val placement = settled(hwnd) { WindowShots.move(hwnd, work.left + 40, work.top + 40, 200, 150) }
            assertNull(WindowRule.minimum(placement))
            // Held at the minimum, not above it: a scale or border counted twice would still pass the line above.
            assertTrue(placement.width <= 644 && placement.height <= 484,
                "Made as small as Windows allows, the window should be about 640 × 480 dp; it is ${placement.width} × ${placement.height} dp")
        }

    @Test
    fun aWindowSavedBelowTheMinimumOpensAtLeast640By480Dp() {
        val first = SkyWingTestApp.launch("window-minimum-save")
        try {
            try {
                val hwnd = first.mainWindow()
                val work = WindowShots.workArea(hwnd)
                WindowShots.restore(hwnd)
                WindowShots.move(hwnd, work.left + 40, work.top + 40, 1000, 750)
                Thread.sleep(1500) // the placement is saved 500 ms after a move
            } finally {
                first.close(keepData = true)
            }
            // Saved at Java's old minimum: 640 × 480 dp outer, about 625 × 473 dp of visible window at 150 %.
            val file = first.dataDir.resolve("LocalAppData/SkyWing/library/window.json")
            val root = JsonParser.parseString(Files.readString(file)).asJsonObject
            root.getAsJsonObject("screens").entrySet().forEach { (_, entry) ->
                entry.asJsonObject.apply { addProperty("maximised", false); addProperty("width", 640.0); addProperty("height", 480.0) }
            }
            Files.writeString(file, root.toString())
            SkyWingTestApp.launch("window-minimum-reopen", dataDir = first.dataDir).use { app ->
                assertNull(WindowRule.minimum(settled(app.mainWindow())))
            }
        } finally {
            first.dataDir.toFile().deleteRecursively()
        }
    }

    /**
     * The window's placement once it meets the minimum, or after 3 s. SkyWing hooks the minimum only once its window
     * is up, so [action] is repeated until it is.
     */
    private fun settled(hwnd: Long, action: () -> Unit = {}): Placement {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (true) {
            action()
            Thread.sleep(250)
            val placement = WindowShots.placement(hwnd)
            if (WindowRule.minimum(placement) == null || System.nanoTime() > deadline) return placement
        }
    }
}
