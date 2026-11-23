package com.skywing.desktop.playback

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B24: the thumbnail players kept between pictures, with numbers standing in for players. */
class KeptPlayersTest {
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val made = AtomicInteger()
    private val closed = Closed()

    @AfterTest fun stopScheduler() {
        scheduler.shutdownNow()
    }

    private fun pool(idleCloseMs: Long = 60_000, stop: (Int) -> Unit = { closed += it }) =
        KeptPlayers(maxIdle = 3, idleCloseMs = idleCloseMs, scheduler = scheduler, start = { made.incrementAndGet() }, stop = stop)

    @Test fun aPlayerIsKeptForTheNextPictureAndClosedAfterTheWait() {
        val players = pool(idleCloseMs = 100)
        val first = players.borrow()
        players.giveBack(first, healthy = true)
        assertEquals(first, players.borrow(), "the next picture uses the same player")
        players.giveBack(first, healthy = true)
        assertTrue(closed.await(first), "an idle player is closed after the wait")
        assertEquals(0, players.count)
        assertEquals(1, made.get())
    }

    @Test fun aPlayerThatFailedIsClosedAndNotUsedAgain() {
        val players = pool()
        val broken = players.borrow()
        players.giveBack(broken, healthy = false)
        assertEquals(listOf(broken), closed.all())
        assertEquals(2, players.borrow())
    }

    @Test fun aPlayerThatCannotStartIsNotCountedAsOpen() {
        val players = KeptPlayers<Int>(maxIdle = 3, idleCloseMs = 60_000, scheduler = scheduler, start = { error("mpv_create failed") }, stop = { })
        assertEquals("mpv_create failed", runCatching { players.borrow() }.exceptionOrNull()?.message)
        assertEquals(0, players.count)
    }

    @Test(timeout = 20_000)
    fun aPlayerThatTakesLongToCloseDoesNotHoldUpTheOtherPictures() {
        // A player whose video hangs on a network read can take long to close (mpv waits for its reads to stop).
        val closing = CountDownLatch(1)
        val letItClose = CountDownLatch(1)
        val players = pool(stop = { closing.countDown(); letItClose.await(15, TimeUnit.SECONDS) })
        val stuck = players.borrow()
        val healthy = players.borrow()
        val closer = thread { players.giveBack(stuck, healthy = false) }
        try {
            assertTrue(closing.await(5, TimeUnit.SECONDS), "the broken player starts closing")
            val next = CompletableFuture.supplyAsync { players.giveBack(healthy, healthy = true); players.borrow() }
            assertEquals(healthy, next.get(2, TimeUnit.SECONDS), "another worker gets a player while the broken one closes")
            assertEquals(1, CompletableFuture.supplyAsync { players.count }.get(2, TimeUnit.SECONDS), "the count doesn't wait either")
        } finally {
            letItClose.countDown()
            closer.join()
        }
    }

    /** The players closed so far, in order. */
    private class Closed {
        private val list = CopyOnWriteArrayList<Int>()
        operator fun plusAssign(player: Int) { list += player }
        fun all(): List<Int> = list.toList()
        fun await(player: Int): Boolean {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (System.nanoTime() < deadline) {
                if (player in list) return true
                Thread.sleep(10)
            }
            return false
        }
    }
}
