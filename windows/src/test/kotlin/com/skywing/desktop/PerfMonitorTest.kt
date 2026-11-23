package com.skywing.desktop

import org.junit.Test
import kotlin.test.*

class PerfMonitorTest {
    private fun ms(value: Double) = (value * 1_000_000).toLong()

    private fun fields(line: String): Map<String, String> =
        line.split('\t').drop(2).associate { it.substringBefore('=') to it.substringAfter('=', "") }

    @Test fun aSecondGivesTheLongestUiTaskItsP95AndHowManyWentOver8Ms() {
        val window = PerfWindow()
        repeat(94) { window.uiTask(ms(1.0)) }
        repeat(5) { window.uiTask(ms(9.0)) }
        window.uiTask(ms(30.0))
        val second = window.roll(atMs = 2_000, heapUsedMb = 300, heapMaxMb = 2_048, gauges = emptyMap())
        assertEquals(100, second.uiTasks)
        assertEquals(169.0, second.uiBusyMs, 0.001)
        assertEquals(9.0, second.uiP95Ms, 0.001)
        assertEquals(30.0, second.uiMaxMs, 0.001)
        assertEquals(6, second.uiOver8)

        val next = window.roll(atMs = 3_000, heapUsedMb = 300, heapMaxMb = 2_048, gauges = emptyMap())
        assertEquals(0, next.uiTasks)
        assertEquals(0.0, next.uiMaxMs)
    }

    @Test fun frameIntervalsStartAgainWhenTheFrameLoopRestarts() {
        val window = PerfWindow()
        window.frameLoopStarted()
        listOf(0.0, 4.2, 8.4, 25.0).forEach { window.frame(ms(it)) }
        val scrolling = window.roll(atMs = 1_000, heapUsedMb = 1, heapMaxMb = 2, gauges = emptyMap())
        assertEquals(4, scrolling.frames)
        assertEquals(16.6, scrolling.frameMaxMs!!, 0.001)
        assertEquals(16.6, scrolling.frameP95Ms!!, 0.001)

        // Nothing scrolled for a while: the loop stopped, and the pause before it started again is not a frame time.
        window.frameLoopStarted()
        window.frame(ms(5_000.0))
        val idle = window.roll(atMs = 6_000, heapUsedMb = 1, heapMaxMb = 2, gauges = emptyMap())
        assertEquals(1, idle.frames)
        assertNull(idle.frameMaxMs)
        assertNull(idle.frameP95Ms)
    }

    @Test fun theLogHasTimesAndCountsOnly() {
        val window = PerfWindow()
        window.uiTask(ms(2.5))
        window.stateChanges(40)
        window.stateChanges(2)
        val line = window.roll(atMs = 1_500, heapUsedMb = 310, heapMaxMb = 2_048, gauges = mapOf("Thumbnails in memory" to 12L)).logLine()
        assertEquals(listOf("1500", "second"), line.split('\t').take(2))
        val values = fields(line)
        assertEquals("1", values["ui_tasks"])
        assertEquals("2.50", values["ui_max_ms"])
        assertEquals("42", values["state_changes"])
        assertEquals("310", values["heap_mb"])
        assertEquals("", values["frame_p95_ms"])
        assertEquals("12", values["gauge.thumbnails_in_memory"])

        val mark = perfMarkLine(800, "scan\tfinished\n", mapOf("videos" to 5_000, "seconds" to 12.25))
        assertEquals("800\tmark\tscan finished\tvideos=5000\tseconds=12.25", mark)
    }
}
