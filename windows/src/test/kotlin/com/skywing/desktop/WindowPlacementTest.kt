package com.skywing.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.skywing.desktop.automation.Placement
import com.skywing.desktop.automation.WindowRule
import java.awt.Frame
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M3 item 8 (M3 design brief 5.6, plan 6.8): where the window opens, and what it remembers. */
class WindowPlacementTest {
    private val handheld = ScreenInfo("handheld", Area(0.0, 0.0, 1280.0, 752.0))
    private val laptop = ScreenInfo("laptop", Area(0.0, 0.0, 1707.0, 1019.0))
    private val big = ScreenInfo("big", Area(0.0, 0.0, 3840.0, 2112.0))

    private fun placement(start: WindowStart, screen: ScreenInfo) = with(start.normal) {
        Placement(start.maximised, x, y, width, height, screen.work.x, screen.work.y, screen.work.width, screen.work.height)
    }

    @Test fun aSmallScreenStartsMaximisedAndRestoresToThe75PercentWindow() {
        val start = WindowSizing.firstStart(handheld.work)
        assertTrue(start.maximised)
        assertNull(WindowRule.firstStart(placement(start, handheld)))
        assertNull(WindowRule.restoredFromMaximised(placement(start.copy(maximised = false), handheld)))
    }

    @Test fun aBiggerScreenStartsAsACentred75PercentWindowIn16By10() {
        val start = WindowSizing.firstStart(laptop.work)
        assertFalse(start.maximised)
        assertNull(WindowRule.firstStart(placement(start, laptop)))
        assertEquals(1.6, start.normal.width / start.normal.height, 0.01)
    }

    @Test fun aVeryBigScreenIsClampedTo1600By1000() {
        val start = WindowSizing.firstStart(big.work)
        assertEquals(Area(1120.0, 556.0, 1600.0, 1000.0), start.normal)
        assertNull(WindowRule.firstStart(placement(start, big)))
    }

    @Test fun savedBoundsOnTheirScreenComeBack() {
        val saved = WindowStart(false, Area(100.0, 80.0, 1000.0, 700.0))
        assertEquals(saved, WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to saved)), listOf(laptop), laptop))
    }

    @Test fun boundsOffEveryScreenFallBackToTheFirstStart() {
        val saved = WindowStart(false, Area(4000.0, 80.0, 1000.0, 700.0))
        assertEquals(WindowSizing.firstStart(laptop.work), WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to saved)), listOf(laptop), laptop))
    }

    @Test fun boundsBiggerThanTheWorkAreaFallBackToTheFirstStart() {
        val saved = WindowStart(false, Area(0.0, 0.0, 1800.0, 900.0))
        assertEquals(WindowSizing.firstStart(laptop.work), WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to saved)), listOf(laptop), laptop))
    }

    @Test fun atLeast64DpOfTheTitleBarMustBeOnAScreen() {
        val mostlyOff = WindowStart(false, Area(1707.0 - 100.0, 80.0, 1000.0, 700.0))
        assertEquals(mostlyOff, WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to mostlyOff)), listOf(laptop), laptop))
        val tooFar = WindowStart(false, Area(1707.0 - 40.0, 80.0, 1000.0, 700.0))
        assertEquals(WindowSizing.firstStart(laptop.work), WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to tooFar)), listOf(laptop), laptop))
    }

    @Test fun aScreenThatIsGoneStartsAfreshOnThePrimaryScreen() {
        val saved = WindowStart(true, Area(100.0, 80.0, 1000.0, 700.0))
        assertEquals(WindowSizing.firstStart(laptop.work), WindowSizing.choose(SavedWindow("dock", mapOf("dock" to saved)), listOf(laptop), laptop))
    }

    @Test fun savedBoundsKeepTheMinimumSize() {
        val tiny = WindowStart(false, Area(100.0, 80.0, 500.0, 300.0))
        assertEquals(Area(100.0, 80.0, 640.0, 480.0), WindowSizing.choose(SavedWindow("laptop", mapOf("laptop" to tiny)), listOf(laptop), laptop).normal)
    }

    @Test fun windowAtStartOverridesWhatWasRemembered() {
        val saved = SavedWindow("laptop", mapOf("laptop" to WindowStart(false, Area(100.0, 80.0, 1000.0, 700.0))))
        val maximised = WindowSizing.choose(saved, listOf(laptop), laptop, StartMode.MAXIMISED)
        assertTrue(maximised.maximised)
        assertEquals(WindowSizing.firstStart(laptop.work).normal, maximised.normal)
        val large = WindowSizing.choose(saved, listOf(laptop), laptop, StartMode.LARGE)
        assertFalse(large.maximised)
        assertEquals(0.9, maxOf(large.normal.width / laptop.work.width, large.normal.height / laptop.work.height), 0.01)
        val small = WindowSizing.choose(saved, listOf(laptop), laptop, StartMode.SMALL)
        assertEquals(0.6, maxOf(small.normal.width / laptop.work.width, small.normal.height / laptop.work.height), 0.01)
    }

    // From the M3 review: what is saved for a screen must be a normal window on that screen.
    @Test fun aNormalWindowOnTheScreenBeingSavedIsKept() {
        val start = WindowStart(true, Area(100.0, 80.0, 1000.0, 700.0))
        assertEquals(start, WindowSizing.forScreen(start, laptop))
    }

    @Test fun aMaximisedWindowMovedToAnotherScreenGetsThatScreensNormalWindow() {
        val dock = ScreenInfo("dock", Area(1707.0, 0.0, 2560.0, 1400.0))
        val fromLaptop = WindowStart(true, Area(100.0, 80.0, 1000.0, 700.0))
        assertEquals(WindowStart(true, WindowSizing.firstStart(dock.work).normal), WindowSizing.forScreen(fromLaptop, dock))
    }

    @Test fun aMaximisedFrameReadAsTheNormalWindowIsNotSaved() {
        val maximisedFrame = WindowStart(true, Area(-8.0, -8.0, 1723.0, 1035.0))
        assertEquals(WindowStart(true, WindowSizing.firstStart(laptop.work).normal), WindowSizing.forScreen(maximisedFrame, laptop))
    }

    @Test fun boundsReadWhileTheFrameIsMaximisedAreNotTaken() {
        val frame = Frame()
        val folder = Files.createTempDirectory("skywing-window")
        try {
            frame.extendedState = Frame.MAXIMIZED_BOTH
            val saver = WindowPlacementSaver(WindowPlacementStore(JsonFile(folder.resolve("window.json"))), WindowStart(true, Area(100.0, 80.0, 1000.0, 700.0)))
            saver.window = frame
            // Compose can still report Floating for a moment after Windows has maximised the frame.
            val lagging = WindowState(WindowPlacement.Floating, position = WindowPosition(200.dp, 150.dp), size = DpSize(900.dp, 600.dp))
            saver.observe(lagging)
            val saved = saver.pending(lagging)?.second ?: error("nothing to save")
            assertNotEquals(Area(200.0, 150.0, 900.0, 600.0), saved.normal)
        } finally {
            frame.dispose()
            folder.toFile().deleteRecursively()
        }
    }

    @Test fun theStartModeIsKeptAndResetForgetsTheBounds() {
        val folder = Files.createTempDirectory("skywing-window")
        try {
            val store = WindowPlacementStore(JsonFile(folder.resolve("window.json")))
            assertEquals(StartMode.REMEMBER, store.mode())
            store.save("laptop", WindowStart(false, Area(10.0, 20.0, 1200.0, 750.0)))
            store.setMode(StartMode.LARGE)
            assertEquals(StartMode.LARGE, store.mode())
            store.forget()
            assertEquals(SavedWindow(null, emptyMap()), store.load())
            assertEquals(StartMode.LARGE, store.mode())
        } finally {
            folder.toFile().deleteRecursively()
        }
    }

    @Test fun eachScreenKeepsItsOwnPlacementInTheJsonFile() {
        val folder = Files.createTempDirectory("skywing-window")
        try {
            val store = WindowPlacementStore(JsonFile(folder.resolve("window.json")))
            val onLaptop = WindowStart(true, Area(10.0, 20.0, 1200.0, 750.0))
            val onDock = WindowStart(false, Area(30.0, 40.0, 1300.0, 800.0))
            store.save("laptop", onLaptop)
            store.save("dock", onDock)
            assertEquals(SavedWindow("dock", mapOf("laptop" to onLaptop, "dock" to onDock)), store.load())
            assertEquals(SavedWindow(null, emptyMap()), WindowPlacementStore(JsonFile(folder.resolve("none.json"))).load())
        } finally {
            folder.toFile().deleteRecursively()
        }
    }
}
