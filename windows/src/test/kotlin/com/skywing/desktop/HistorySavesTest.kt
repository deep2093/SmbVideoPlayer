package com.skywing.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * B20: while a video plays, its place is written at most every 30 s, and only when it moved by more than 5 s; a pause or
 * a seek writes it at the next check.
 */
class HistorySavesTest {
    /** Checks every 5 s (as the player does) and returns the positions that were written. */
    private fun run(vararg checks: Triple<Long, Double, Boolean>): List<Double> {
        val saves = HistorySaves(startedAt = 0)
        return checks.mapNotNull { (at, position, paused) ->
            if (saves.due(at, position, paused)) position.also { saves.saved(at, it) } else null
        }
    }

    private fun playing(seconds: Long, position: Double = seconds.toDouble()) = Triple(seconds * 1_000, position, false)
    private fun paused(seconds: Long, position: Double) = Triple(seconds * 1_000, position, true)

    @Test fun playingSteadilyWritesEvery30Seconds() {
        val checks = (1..13L).map { playing(it * 5) }.toTypedArray()
        assertEquals(listOf(30.0, 60.0), run(*checks))
    }

    @Test fun aPauseIsWrittenOnceAtTheNextCheck() {
        assertEquals(listOf(12.0), run(playing(5), paused(10, 12.0), paused(15, 12.0), paused(20, 12.0)))
    }

    @Test fun aSeekIsWrittenAtTheNextCheck() {
        assertEquals(listOf(300.0), run(playing(5), playing(10), playing(15, 300.0), playing(20, 305.0)))
    }

    @Test fun nothingIsWrittenWhenThePlaceMovedFiveSecondsOrLess() {
        // Stuck while the network catches up: the place written at 30 s is still right at 60 s.
        assertEquals(listOf(8.0), run(playing(30, 8.0), playing(60, 10.0), paused(65, 12.0)))
    }
}
