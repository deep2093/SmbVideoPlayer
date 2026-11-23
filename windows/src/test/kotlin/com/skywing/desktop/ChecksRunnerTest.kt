package com.skywing.desktop

import com.skywing.desktop.automation.Check
import com.skywing.desktop.automation.CheckFindings
import com.skywing.desktop.automation.CheckResult
import com.skywing.desktop.automation.ChecksRequest
import com.skywing.desktop.automation.ScanRun
import com.skywing.desktop.automation.SoakSample
import com.skywing.desktop.automation.Verdict
import com.skywing.desktop.automation.checksTable
import com.skywing.desktop.automation.dropsBetween
import com.skywing.desktop.automation.dropsRising
import com.skywing.desktop.automation.keyMessage
import com.skywing.desktop.automation.msSummary
import com.skywing.desktop.automation.scanResult
import com.skywing.desktop.automation.soakGrowth
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The parts of the checks runner that need no SkyWing: what to run, the verdicts and the table. */
class ChecksRunnerTest {
    @Test
    fun withNoCheckNamedTheRunnerDoesFullScreenAndStress() {
        assertEquals(ChecksRequest(listOf(Check.FULLSCREEN, Check.STRESS), trial = false, scanFolder = null), ChecksRequest.parse(emptyList()))
        assertEquals(ChecksRequest(listOf(Check.FULLSCREEN, Check.STRESS), trial = true, scanFolder = null), ChecksRequest.parse(listOf("TRIAL")))
    }

    @Test
    fun namedChecksRunInTheirOrderOnceEach() {
        val request = ChecksRequest.parse(listOf("soak", "Fullscreen", "soak"))
        assertEquals(listOf(Check.SOAK, Check.FULLSCREEN), request.checks)
    }

    @Test
    fun aScanNeedsTheFolderAfterIt() {
        assertEquals("D:\\Videos", ChecksRequest.parse(listOf("scan", "D:\\Videos", "trial")).scanFolder)
        assertEquals(listOf(Check.SCAN), ChecksRequest.parse(listOf("scan", "D:\\Videos")).checks)
        val missing = assertFailsWith<IllegalArgumentException> { ChecksRequest.parse(listOf("scan")) }
        assertContains(missing.message.orEmpty(), "scan \"D:\\Videos\"")
        assertFailsWith<IllegalArgumentException> { ChecksRequest.parse(listOf("scan", "trial")) }
    }

    @Test
    fun anUnknownWordIsRefusedWithTheWordsThatWork() {
        val refused = assertFailsWith<IllegalArgumentException> { ChecksRequest.parse(listOf("fulscreen")) }
        assertContains(refused.message.orEmpty(), "fulscreen")
        assertContains(refused.message.orEmpty(), "stress")
    }

    @Test
    fun aDisturbanceMeansNotMeasuredEvenWhenSomethingFailed() {
        assertEquals(Verdict.PASS, CheckFindings().verdict())
        assertEquals(Verdict.LOOK, CheckFindings().apply { look("the fade, by eye") }.verdict())
        assertEquals(Verdict.FAIL, CheckFindings().apply { look("x"); problem("SkyWing didn't leave full screen in round 3") }.verdict())
        // Posted keys only reach SkyWing while it is the active window, so what failed may be the disturbance.
        assertEquals(Verdict.NOT_MEASURED, CheckFindings().apply { problem("y"); disturbed("another program was active") }.verdict())
    }

    @Test
    fun inputSpoilsACheckOnlyWhenSomethingAlsoWentWrong() {
        // Windows sometimes reports input nobody made, so input alone is a note, not a reason to run the check again.
        assertEquals(Verdict.PASS, CheckFindings().apply { input("Someone used the keyboard") }.verdict())
        assertEquals(Verdict.NOT_MEASURED, CheckFindings().apply { input("Someone used the keyboard"); problem("z") }.verdict())
    }

    @Test
    fun timesAreSummedUpAsTheMedianAndTheLongest() {
        assertEquals("median 300 ms, longest 900 ms", msSummary(listOf(900, 100, 300, 200, 400)))
        assertEquals("median 250 ms, longest 300 ms", msSummary(listOf(300, 200)))
        assertEquals("none", msSummary(emptyList()))
    }

    @Test
    fun anArrowKeyIsPostedAsTheExtendedKeyAKeyboardSends() {
        // Bit 24 marks the arrow keys of the arrow block; without it Windows means the number pad's arrows.
        assertEquals(1L or (0x4BL shl 16) or (1L shl 24), keyMessage(scanCode = 0x4B, extended = true))
        assertEquals(1L or (0x21L shl 16), keyMessage(scanCode = 0x21, extended = false))
    }

    @Test
    fun soakGrowthComparesTheFirstAndLastMinutesAfterTheSettleTime() {
        val flat = (0..120).map { sample(it * 60L, privateMb = 900.0 + it % 3, handles = 1_500L + it % 5) }
        val growth = soakGrowth(flat, settleSeconds = 1_200)!!
        assertFalse(growth.any { it.over }, growth.toString())

        val leaking = (0..120).map { sample(it * 60L, privateMb = 900.0 + it * 2.0, handles = 1_500L + it * 3) }
        val leaks = soakGrowth(leaking, settleSeconds = 1_200)!!.filter { it.over }.map { it.name }
        assertEquals(listOf("Private memory (MB)", "Handles"), leaks)
    }

    @Test
    fun aSoakTooShortForTheComparisonGivesNoGrowth() {
        assertNull(soakGrowth((0..25).map { sample(it * 60L) }, settleSeconds = 1_200))
    }

    @Test
    fun dropsRiseWhenTheSecondHalfDropsMoreThanTheFirst() {
        assertFalse(dropsRising(listOf(0.0, 0.2, 0.0, 0.1, 0.0, 0.2)))
        assertTrue(dropsRising(listOf(0.0, 0.1, 0.0, 2.0, 3.0, 4.0)))
        assertFalse(dropsRising(listOf(5.0)))
    }

    @Test
    fun aDropCounterThatStartedAgainCountsFromZero() {
        // A seek rebuilds the motion graph, and mpv's drop counters can start again at 0.
        assertEquals(15L, dropsBetween(10, 25))
        assertEquals(53L, dropsBetween(76, 53))
        assertNull(dropsBetween(null, 5))
    }

    @Test
    fun aScanOfAFastFolderShowsHundredthsOfASecond() {
        val fast = scanResult(listOf(ScanRun(0.23, 5_000, 77)), listOf(ScanRun(0.01, 5_000, 77)))
        assertContains(fast.summary, "before B19 0.23 s, after 0.01 s")
    }

    @Test
    fun aScanPassesWhenBothWalksFindTheSameVideosAndSaysHowMuchFaster() {
        val same = scanResult(listOf(ScanRun(12.0, 5_000, 77), ScanRun(10.0, 5_000, 77)), listOf(ScanRun(4.0, 5_000, 77), ScanRun(5.0, 5_000, 77)))
        assertEquals(Verdict.PASS, same.verdict)
        assertContains(same.summary, "5,000 videos")
        assertContains(same.summary, "before B19 11.0 s, after 4.5 s")
        assertContains(same.summary, "2.4× as fast")

        val different = scanResult(listOf(ScanRun(12.0, 5_000, 77)), listOf(ScanRun(4.0, 4_999, 76)))
        assertEquals(Verdict.FAIL, different.verdict)
        assertContains(different.summary, "4,999")
    }

    @Test
    fun theTableHasOneRowPerCheck() {
        val table = checksTable(
            "Test PC", "1920 × 1080 at 60 Hz", "abc1234",
            listOf(
                CheckResult(Check.FULLSCREEN, Verdict.PASS, "20 of 20 rounds"),
                CheckResult(Check.STRESS, Verdict.FAIL, "SkyWing didn't exit by itself in round 4"),
            ),
        )
        assertContains(table, "# SkyWing checks on Test PC")
        assertContains(table, "abc1234")
        assertContains(table, "| ${Check.FULLSCREEN.title} | Pass | 20 of 20 rounds |")
        assertContains(table, "| ${Check.STRESS.title} | Fail | SkyWing didn't exit by itself in round 4 |")
    }

    private fun sample(at: Long, privateMb: Double = 900.0, handles: Long = 1_500) =
        SoakSample(atSeconds = at, privateMb = privateMb, handles = handles, threads = 80, gdiObjects = 60, userObjects = 40)
}
