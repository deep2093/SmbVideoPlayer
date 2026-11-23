package com.skywing.desktop

import com.skywing.desktop.automation.M1Library
import com.skywing.desktop.automation.PerfLog
import com.skywing.desktop.automation.UiSummary
import com.skywing.desktop.automation.m1Table
import com.skywing.desktop.automation.runnerFolderRefusal
import com.skywing.desktop.automation.wheelMessage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the M1 before/after runner that need no SkyWing: reading its log, summing it up and the table. */
class M1MeasurementTest {
    @Test
    fun theLogReaderKeepsSecondsAndMarksAndSkipsAHalfWrittenLine() {
        val log = PerfLog.parse(
            "1200\tmark\tfirst frame\n" +
                row(2000, frames = 58, p95 = 17.1, max = 12.25, over8 = 2, busy = 120.5, made = 7) +
                "2500\tmark\tscan finished\tseconds=3.25\tproblems=0\n" +
                "3000\tsecond\tui_tasks=3",
        )
        assertEquals(listOf("first frame", "scan finished"), log.marks.map { it.name })
        assertEquals(3.25, log.mark("scan finished")?.values?.get("seconds"))
        assertEquals(listOf(2000L), log.seconds.map { it.atMs })
        assertEquals(7L, log.seconds.single().gauge("thumbnails_made"))
        assertNull(log.mark("first frame", afterMs = 1200))
    }

    @Test
    fun aScrollSummaryTakesTheMedianP95TheWorstSecondAndTheTotals() {
        val rows = PerfLog.parse(
            row(1000, frames = 60, p95 = 17.0, max = 5.0, over8 = 0, busy = 300.0, recompositions = 30) +
                row(2000, frames = 50, p95 = 25.0, max = 14.0, over8 = 3, busy = 500.0, recompositions = 50) +
                row(3000, frames = 58, p95 = 18.0, max = 9.0, over8 = 1, busy = 400.0, recompositions = 40),
        ).seconds
        val summary = UiSummary.of(rows)
        assertEquals(56.0, summary.framesPerSecond)
        assertEquals(18.0, summary.frameP95Ms)
        assertEquals(25.0, summary.worstFrameP95Ms)
        assertEquals(14.0, summary.longestUiTaskMs)
        assertEquals(4L, summary.uiTasksOver8)
        assertEquals(40.0, summary.uiBusyPercent)
        assertEquals(40.0, summary.recompositionsPerSecond)
    }

    @Test
    fun theLibraryIsMadeInNameOrderAndItsFirstNamesUseEveryClip() {
        val names = (0 until M1Library.VIDEOS).map(M1Library::linkName)
        assertEquals(names.sorted(), names)
        assertEquals(M1Library.VIDEOS, names.toSet().size)
        assertEquals((0 until 50).toList(), (0 until 50).map { M1Library.clipIndex(it, clips = 50) })
    }

    @Test
    fun aWheelTurnIsPostedWithItsSignedDeltaAndScreenPoint() {
        assertEquals(0xFF88_0000L to 0x0014_FFF6L, wheelMessage(x = -10, y = 20, delta = -120))
        assertEquals(0x0078_0000L to 0x02D0_0500L, wheelMessage(x = 1280, y = 720, delta = 120))
    }

    @Test
    fun theTableShowsBothImagesAndADashForWhatWasntMeasured() {
        val table = m1Table(
            "Test PC",
            "1920 × 1080 at 120 Hz",
            mapOf("before" to mapOf("start_first_s" to "3.10", "scroll_fps" to "48.0"), "after" to mapOf("start_first_s" to "2.20")),
        )
        assertTrue("| Start to first page, first start (s) | 3.10 | 2.20 |" in table, table)
        assertTrue("| Scrolling Videos: frames per second | 48.0 | – |" in table, table)
    }

    @Test
    fun aRunnerOnAUsbStickIsToldToMoveRunM1() {
        val folder = Path.of("D:/SkyWing-M1")
        val refusal = runnerFolderRefusal(folder, emptyMap(), removable = { true }, runner = "M1").orEmpty()
        assertContains(refusal, "Run M1.cmd is on a USB stick")
        assertContains(refusal, "SkyWing-M1")
    }

    private fun row(at: Long, frames: Int, p95: Double, max: Double, over8: Int, busy: Double, made: Int = 0, recompositions: Int = 0) =
        "$at\tsecond\tui_tasks=10\tui_busy_ms=$busy\tui_p95_ms=1.00\tui_max_ms=$max\tui_over8=$over8\tframes=$frames" +
            "\tframe_p95_ms=$p95\tframe_max_ms=40.00\tstate_changes=0\trecompositions=$recompositions\theap_mb=100\theap_max_mb=1024\tgauge.thumbnails_made=$made\n"
}
