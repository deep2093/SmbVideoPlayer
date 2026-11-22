package com.skywing.desktop

import com.skywing.desktop.automation.BatteryDrain
import com.skywing.desktop.automation.ClipSize
import com.skywing.desktop.automation.Disturbances
import com.skywing.desktop.automation.FinishedRun
import com.skywing.desktop.automation.HandheldPlan
import com.skywing.desktop.automation.HandheldSystem
import com.skywing.desktop.automation.M0Outcome
import com.skywing.desktop.automation.M0Settings
import com.skywing.desktop.automation.PlaybackReport
import com.skywing.desktop.automation.PowerStatus
import com.skywing.desktop.automation.batteryRefusal
import com.skywing.desktop.automation.finishedRun
import com.skywing.desktop.automation.handheldTable
import com.skywing.desktop.automation.logOutput
import com.skywing.desktop.automation.machineFolder
import com.skywing.desktop.automation.registryString
import com.skywing.desktop.automation.registryValue
import com.skywing.desktop.automation.reportMismatch
import com.skywing.desktop.automation.runnerFolderRefusal
import com.skywing.desktop.automation.tableRow
import com.skywing.desktop.playback.MotionQuality

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The handheld M0 runner (`M0Handheld.kt`) works out its runs, clips, resume points, checks and table rows from files
 * and text, so these tests need no SkyWing, battery or window. The files are temporary. One test makes the runner's
 * Windows calls on this machine; they only read its state.
 */
class M0HandheldTest {
    private val temp: Path = Files.createTempDirectory("skywing m0 handheld ")
    private val runs = HandheldPlan.runs(trial = false)

    @AfterTest
    fun deleteTemporaryFiles() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun theRunsFollowTheSection4TableWithThePluggedInHalfFirst() {
        assertEquals(
            listOf(
                "1080p-fast-ac", "1080p-balanced-ac", "4k-fast-ac", "4k-balanced-ac",
                "1080p-fast-battery", "1080p-balanced-battery", "4k-fast-battery", "4k-balanced-battery",
            ),
            runs.map { it.label },
        )
        assertEquals(listOf(true, true, true, true, false, false, false, false), runs.map { it.onAc })
        assertEquals(ClipSize.UHD_4K, runs[6].clip)
        assertEquals(MotionQuality.FAST, runs[6].quality)
        assertEquals("4K Fast, on battery", runs[6].title)
    }

    @Test
    fun aTrialDoesThePluggedInHalfUnderItsOwnLabels() {
        assertEquals(
            listOf("trial-1080p-fast-ac", "trial-1080p-balanced-ac", "trial-4k-fast-ac", "trial-4k-balanced-ac"),
            HandheldPlan.runs(trial = true).map { it.label },
        )
    }

    @Test
    fun eachRunBecomesM0SettingsWithItsQualityPowerAndTimes() {
        val image = temp.resolve("SkyWing").createDirectories().also { it.resolve("SkyWing.exe").createFile() }
        val clip = temp.resolve("clip.mp4").createFile()
        fun settings(run: Int, trial: Boolean = false, vrr: Boolean? = null) = M0Settings.fromEnvironment(
            mapOf("SKYWING_TEST_IMAGE" to image.toString()) +
                HandheldPlan.runs(trial)[run].environment(clip, temp.resolve("results"), vrr),
        )

        val batteryFast4k = settings(6)
        assertEquals("4k-fast-battery", batteryFast4k.label)
        assertEquals(MotionQuality.FAST, batteryFast4k.quality)
        assertFalse(batteryFast4k.onAc)
        assertNull(batteryFast4k.vrr)
        assertEquals(60, batteryFast4k.warmupSeconds)
        assertEquals(300, batteryFast4k.windowSeconds)
        assertEquals(true, settings(1, vrr = true).vrr)
        val trial = settings(0, trial = true)
        assertEquals(10, trial.warmupSeconds)
        assertEquals(30, trial.windowSeconds)
    }

    @Test
    fun eachClipFolderMustHoldExactlyOneVideo() {
        fun refusal(block: () -> Any) = assertFailsWith<IllegalArgumentException> { block() }.message.orEmpty()
        assertContains(refusal { ClipSize.HD_1080P.clip(temp, emptyMap()) }, "clips\\1080p")
        val folder = temp.resolve("clips").resolve("1080p").createDirectories()
        folder.resolve("Put one 1080p video here.txt").createFile()
        assertContains(refusal { ClipSize.HD_1080P.clip(temp, emptyMap()) }, "clips\\1080p")
        val video = folder.resolve("a.mkv").createFile()
        assertEquals(video, ClipSize.HD_1080P.clip(temp, emptyMap()))
        folder.resolve("b.MP4").createFile()
        assertContains(refusal { ClipSize.HD_1080P.clip(temp, emptyMap()) }, "2 videos")
    }

    @Test
    fun aClipCanBeNamedInTheEnvironmentInstead() {
        val named = temp.resolve("elsewhere.mp4")
        assertEquals(named, ClipSize.UHD_4K.clip(temp, mapOf("SKYWING_M0_CLIP_4K" to named.toString())))
    }

    @Test
    fun theRunnerRefusesAFolderOnAShareAUsbStickOrInOneDrive() {
        val local = temp.resolve("SkyWing-M0")
        assertNull(runnerFolderRefusal(local, emptyMap(), removable = { false }))
        val share = runnerFolderRefusal(Path.of("""\\nas.invalid\tools\SkyWing-M0"""), emptyMap(), removable = { error("a share is never queried") })
        assertContains(share.orEmpty(), "network")
        val stick = runnerFolderRefusal(local, emptyMap(), removable = { it == local }).orEmpty()
        assertContains(stick, "USB stick")
        assertContains(stick, """C:\SkyWing-M0""")
        assertContains(runnerFolderRefusal(local, mapOf("OneDrive" to temp.toString()), removable = { false }).orEmpty(), "OneDrive")
        val business = temp.resolve("OneDrive - Example").resolve("SkyWing-M0")
        assertContains(runnerFolderRefusal(business, emptyMap(), removable = { false }).orEmpty(), "OneDrive")
    }

    @Test
    fun theWindowsCallsTheRunnerMakesWorkOnThisMachine() {
        val percent = HandheldSystem.powerStatus().percent
        assertTrue(percent == null || percent in 0..100, "battery at $percent %")
        assertTrue(HandheldSystem.foregroundLockTimeoutMs() >= 0)
        assertTrue(HandheldSystem.idleMs() >= 0)
        assertFalse(HandheldSystem.removable(Path.of(System.getenv("SystemRoot") ?: """C:\Windows""")))
        assertContains(registryValue("""HKLM\SOFTWARE\Microsoft\Windows NT\CurrentVersion""", "ProductName").orEmpty(), "Windows")
        assertNull(registryValue("""HKCU\Software\SkyWing M0 test key that does not exist""", "x"))
    }

    @Test
    fun aRunIsFinishedOnlyWhenItsFolderHasATableRow() {
        val results = temp.resolve("results").createDirectories()
        assertNull(finishedRun(results, "1080p-fast-ac"))
        results.resolve("20261123-101500-1080p-fast-ac").createDirectories().resolve("summary.txt").createFile()
        results.resolve("20261123-102000-trial-1080p-fast-ac").createDirectories().resolve("row.md").createFile()
        assertNull(finishedRun(results, "1080p-fast-ac"), "a stopped run and a trial run don't count")
        val done = results.resolve("20261123-103000-1080p-fast-ac").createDirectories().also { it.resolve("row.md").createFile() }
        assertEquals(done, finishedRun(results, "1080p-fast-ac"))
    }

    @Test
    fun registryValuesAreReadFromRegQueryOutput() {
        val vrr = "\r\nHKEY_CURRENT_USER\\Software\\JavaSoft\\Prefs\\com\\skywing\\desktop\\playback\r\n    motion.vrr    REG_SZ    true\r\n\r\n"
        assertEquals("true", registryString(vrr, "motion.vrr"))
        assertEquals("Claw 8 AI+ A2VM", registryString("    SystemProductName    REG_SZ    Claw 8 AI+ A2VM\r\n", "SystemProductName"))
        assertNull(registryString("ERROR: The system was unable to find the specified registry key or value.\r\n", "motion.vrr"))
    }

    @Test
    fun resultsGoToAFolderNamedAfterTheDevice() {
        assertEquals("Claw-8-AI-A2VM", machineFolder(product = "Claw 8 AI+ A2VM", computerName = "PC-1"))
        assertEquals("PC-1", machineFolder(product = "Default string", computerName = "PC-1"))
        assertEquals("PC-1", machineFolder(product = null, computerName = "PC-1"))
        assertEquals("this-device", machineFolder(product = " ", computerName = null))
    }

    @Test
    fun batteryRunsNeedFortyPercentAndBatterySaverOff() {
        assertNull(batteryRefusal(PowerStatus(onAc = false, percent = 85, batterySaver = false)))
        assertNull(batteryRefusal(PowerStatus(onAc = false, percent = 40, batterySaver = false)))
        assertContains(batteryRefusal(PowerStatus(onAc = false, percent = 39, batterySaver = false)).orEmpty(), "39 %")
        assertContains(batteryRefusal(PowerStatus(onAc = false, percent = 85, batterySaver = true)).orEmpty(), "Battery saver")
        assertContains(batteryRefusal(PowerStatus(onAc = true, percent = 85, batterySaver = false)).orEmpty(), "charger")
        assertContains(batteryRefusal(PowerStatus(onAc = null, percent = null, batterySaver = false)).orEmpty(), "battery state")
    }

    @Test
    fun aReportThatDoesNotMatchTheRunStopsIt() {
        val hd = runs[0]
        val uhd = runs[2]
        assertNull(reportMismatch(hd, PlaybackReport(report())))
        assertNull(reportMismatch(uhd, PlaybackReport(report(file = UHD_FILE))))
        assertContains(reportMismatch(hd, PlaybackReport(report(file = UHD_FILE))).orEmpty(), "3840 × 2160")
        assertContains(reportMismatch(uhd, PlaybackReport(report())).orEmpty(), "1920 × 1080")
        assertContains(reportMismatch(hd, PlaybackReport(report(transfer = "pq"))).orEmpty(), "HDR")
        assertContains(reportMismatch(hd, PlaybackReport(report(quality = "Balanced"))).orEmpty(), "Balanced")
        assertContains(reportMismatch(hd, PlaybackReport(report(multiplier = "none"))).orEmpty(), "2×")
        assertContains(reportMismatch(hd, PlaybackReport("SkyWing 0.1.101 · Windows x64 motion preview\n")).orEmpty(), "size")
    }

    @Test
    fun aFinishedRunBecomesASection4Row() {
        assertEquals(
            "| 1080p | Fast | Plugged in | 3 | 0.6 | 0 | 0.85 | 1,234 → 1,260 | — | Windows power mode Balanced; filter throughput 47.95 fps, target 47.952 fps |",
            tableRow(runs[0], outcome()),
        )
        val battery = outcome(battery = BatteryDrain(watts = 18.25, percentPerHour = 21.04), modes = "Balanced" to "Best power efficiency")
        assertEquals(
            "| 4K | Balanced | Battery | 3 | 0.6 | 0 | 0.85 | 1,234 → 1,260 | 18.3; 21.0 | Windows power mode Balanced at the start, " +
                "Best power efficiency at the end; filter throughput 47.95 fps, target 47.952 fps |",
            tableRow(runs[7], battery),
        )
        val noisy = tableRow(runs[0], outcome(generations = 1L to 2L, input = 4, battery = null))
        assertContains(noisy, "graph generations 1 → 2")
        assertContains(noisy, "keyboard or mouse input in 4 s")
        assertContains(tableRow(runs[4], outcome(battery = null)), "| Battery | 3 | 0.6 | 0 | 0.85 | 1,234 → 1,260 | unavailable |")
    }

    @Test
    fun theTableHasTheDeviceLinesAndARowForEveryRun() {
        val table = handheldTable(
            device = "Claw 8 AI+ A2VM",
            plan = runs,
            finished = mapOf(runs[0] to FinishedRun("| 1080p | Fast | Plugged in | finished |", PlaybackReport(report()))),
        )
        assertContains(table, "Claw 8 AI+ A2VM")
        assertContains(table, "| `Preferred motion GPU:` line | `Intel(R) Arc(TM) B390 GPU; driver: 32.0.101.9999` |")
        assertContains(table, "| `Renderer:` line | `Intel(R) Arc(TM) B390 GPU; preference: Intel D3D11; output: gpu-next` |")
        assertContains(table, "| `Display:` line | `120.0 Hz; VRR activation: not measured` |")
        assertContains(table, "| 1080p clip frame rate | 23.976 fps |")
        assertContains(table, "| 1080p | Fast | Plugged in | finished |")
        assertContains(table, "| 4K | Balanced | Battery | | | | | | | not run yet |")
    }

    @Test
    fun whatTheRunnerPrintsAlsoGoesToTheEndOfItsLog() {
        val log = temp.resolve("runner-log.txt")
        log.writeText("An earlier start.\r\n")
        val console = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(console, true, Charsets.UTF_8))
            logOutput(log)
            println("Run 1 of 8: 1080p Fast, plugged in. Don't touch the device.")
            println("SkyWing didn't play at 2× (Multiplier: 1.5×).")
        } finally {
            System.setOut(original)
        }
        val shown = console.toString(Charsets.UTF_8)
        val kept = log.readText()
        for (line in listOf("Run 1 of 8: 1080p Fast, plugged in.", "SkyWing didn't play at 2× (Multiplier: 1.5×).")) {
            assertContains(shown, line)
            assertContains(kept, line)
        }
        assertTrue(kept.startsWith("An earlier start."), "the log keeps what earlier starts wrote")
        assertContains(kept, "===", message = "the log marks where this start begins")
        assertFalse("===" in shown, "the start mark goes only into the log")
    }

    private fun outcome(
        battery: BatteryDrain? = null,
        modes: Pair<String, String> = "Balanced" to "Balanced",
        generations: Pair<Long?, Long?> = 1L to 1L,
        input: Int = 0,
    ) = M0Outcome(
        folder = temp,
        windowSeconds = 300.0,
        cores = 0.849,
        memoryStartMb = 1_234.4,
        memoryEndMb = 1_260.0,
        battery = battery,
        drops = 3,
        restarts = 0,
        generations = generations,
        powerModes = modes,
        disturbances = Disturbances(foregroundLostSeconds = 0, fullScreenLostSeconds = 0, inputSeconds = input, description = "quiet"),
        reportB = PlaybackReport(report()),
    )

    private fun report(
        file: String = "File: local file; 1920 × 1080; source 23.976 fps",
        transfer: String = "bt.1886",
        quality: String = "Fast",
        multiplier: String = "2×",
    ) = """
        SkyWing 0.1.101 · Windows x64 motion preview
        Build: commit ea838e1; runtime frame-sharing, sharing on; watchdog fix ed072bf included
        Preferred motion GPU: Intel(R) Arc(TM) B390 GPU; driver: 32.0.101.9999
        Renderer: Intel(R) Arc(TM) B390 GPU; preference: Intel D3D11; output: gpu-next
        Display: 120.0 Hz; VRR activation: not measured
        $file
        Codec: h264; pixel format: yuv420p
        Transfer: $transfer; decoder: d3d11va
        Engine: open-svpflow deadbad2; quality: $quality; target: 47.952 fps
        Filter processing throughput: 47.95 fps (not presented FPS)
        Multiplier: $multiplier; G-SYNC / FreeSync: true; lower automatically: false
    """.trimIndent()

    private companion object {
        const val UHD_FILE = "File: local file; 3840 × 2160; source 29.97 fps"
    }
}
