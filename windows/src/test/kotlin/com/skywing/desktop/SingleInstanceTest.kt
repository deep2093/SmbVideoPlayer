package com.skywing.desktop

import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * B38: one SkyWing per data folder. A second start hands its file to the running one over a named pipe and exits.
 * The data folders here are only names for the pipe; nothing is written to them.
 */
class SingleInstanceTest {
    private val owners = mutableListOf<SingleInstance.Owner>()

    @AfterTest
    fun closeOwners() {
        owners.forEach { it.close() }
    }

    private fun folder() = "C:\\SkyWingTest\\" + UUID.randomUUID() + "\\AppData\\Local"

    private fun own(claim: SingleInstance.Claim): SingleInstance.Owner = assertIs<SingleInstance.Owner>(claim).also { owners += it }

    private fun SingleInstance.Owner.next(): List<String> = runBlocking { withTimeout(5_000) { handoffs.receive() } }

    @Test
    fun aSecondStartHandsItsFileToTheFirst() {
        val data = folder()
        val first = own(SingleInstance.claim(data, emptyList()))
        // A relative path is resolved by the second start, whose working folder the first doesn't know.
        val video = File("clips", "second.mp4")
        assertEquals(SingleInstance.HandedOff, SingleInstance.claim(data, listOf(video.path)))
        assertEquals(listOf(video.absolutePath), first.next())
    }

    @Test
    fun aSecondStartWithoutAFileOnlyAsksForTheWindow() {
        val data = folder()
        val first = own(SingleInstance.claim(data, emptyList()))
        assertEquals(SingleInstance.HandedOff, SingleInstance.claim(data, emptyList()))
        assertEquals(emptyList(), first.next())
    }

    @Test
    fun startsOnAnotherDataFolderRunOnTheirOwn() {
        own(SingleInstance.claim(folder(), emptyList()))
        own(SingleInstance.claim(folder(), emptyList()))
    }

    @Test
    fun afterTheFirstClosesTheNextStartRunsItself() {
        val data = folder()
        own(SingleInstance.claim(data, emptyList())).close()
        own(SingleInstance.claim(data, emptyList()))
    }

    @Test
    fun everyHandOffArrivesInOrderWithItsWholeName() {
        val data = folder()
        val first = own(SingleInstance.claim(data, emptyList()))
        val sent = (1..5).map { listOf(File("C:\\Vidéos\\Holiday 日本 $it.mkv").path, File("C:\\Films\\Part $it.mp4").path) }
        sent.forEach { assertEquals(SingleInstance.HandedOff, SingleInstance.claim(data, it)) }
        assertEquals(sent, sent.map { first.next() })
    }

    @Test
    fun startsAtTheSameTimeLeaveOneRunning() {
        val data = folder()
        val starts = 8
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(starts)
        try {
            val claims = (1..starts).map { n -> pool.submit<SingleInstance.Claim> { go.await(); SingleInstance.claim(data, listOf("C:\\v\\$n.mp4")) } }
            go.countDown()
            val results = claims.map { it.get(30, TimeUnit.SECONDS) }
            val running = results.filterIsInstance<SingleInstance.Owner>().onEach { owners += it }
            assertEquals(1, running.size, "one start keeps running: $results")
            assertEquals(starts - 1, results.count { it == SingleInstance.HandedOff }, "the others hand over: $results")
            val received = (1 until starts).map { running.single().next() }.flatten().toSet()
            assertEquals(starts - 1, received.size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun theNameFollowsTheFolderButDoesNotShowIt() {
        val name = SingleInstance.pipeName("C:\\Users\\Someone\\AppData\\Local")
        assertEquals(name, SingleInstance.pipeName("c:\\users\\someone\\appdata\\local\\"))
        assertNotEquals(name, SingleInstance.pipeName("C:\\Users\\Other\\AppData\\Local"))
        assertTrue(name.startsWith("\\\\.\\pipe\\SkyWing-"), name)
        assertFalse("someone" in name.lowercase(), name)
    }
}
