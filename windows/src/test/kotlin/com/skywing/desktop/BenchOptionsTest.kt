package com.skywing.desktop

import com.skywing.desktop.playback.MotionExperiment
import com.skywing.desktop.playback.MotionQuality
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BenchOptionsTest {
    private fun error(vararg args: String) = BenchOptions.parse(args.toList())!!.exceptionOrNull()?.message

    @Test fun onlyAStartWithBenchIsABenchRun() {
        assertNull(BenchOptions.parse(emptyList()))
        assertNull(BenchOptions.parse(listOf("C:/videos/clip.mkv")))
    }

    @Test fun readsThePlansExampleAndFillsTheRestWithComparableDefaults() {
        assertEquals(BenchOptions(File("C:/clip.mkv"), minutes = 10.0, quality = MotionQuality.BALANCED, stop = 0),
            BenchOptions.parse(listOf("--bench", "C:/clip.mkv", "--minutes", "10", "--quality", "Balanced", "--stop", "2x"))!!.getOrThrow())
        assertEquals(BenchOptions(File("C:/clip.mkv"), minutes = 10.0, quality = MotionQuality.DEFAULT, stop = 0, startSeconds = 0.0, out = null),
            BenchOptions.parse(listOf("--bench", "C:/clip.mkv"))!!.getOrThrow())
        assertEquals(BenchOptions(File("C:/clip.mkv"), minutes = 1.5, quality = MotionQuality.BEST, stop = 1, startSeconds = 30.0, out = File("C:/out")),
            BenchOptions.parse(listOf("--bench", "C:/clip.mkv", "--quality", "best", "--stop", "2.5×", "--minutes", "1.5",
                "--start", "30", "--out", "C:/out"))!!.getOrThrow())
        assertEquals(3, BenchOptions.parse(listOf("--bench", "C:/clip.mkv", "--stop", "4x"))!!.getOrThrow().stop)
        assertNull(BenchOptions.parse(listOf("--bench", "C:/clip.mkv"))!!.getOrThrow().experiment, "no experiment unless asked")
        assertEquals(false, BenchOptions.parse(listOf("--bench", "C:/clip.mkv"))!!.getOrThrow().screenSize, "Settings' switch is ignored")
        assertEquals(true, BenchOptions.parse(listOf("--bench", "C:/clip.mkv", "--screen-size", "on"))!!.getOrThrow().screenSize)
        assertEquals(MotionExperiment.POTPLAYER_LIKE,
            BenchOptions.parse(listOf("--bench", "C:/clip.mkv", "--experiment", "potplayer-like"))!!.getOrThrow().experiment)
    }

    @Test fun mistakesAreReportedNotGuessed() {
        assertEquals("--bench needs a video file", error("--bench"))
        assertEquals("--bench needs a video file", error("--bench", "--minutes", "2"))
        assertEquals("Unknown quality 'ultra': use Fastest, Fast, Balanced, Better or Best", error("--bench", "a.mkv", "--quality", "ultra"))
        assertEquals("Unknown stop '5x': use 2x, 2.5x, 3x or 4x", error("--bench", "a.mkv", "--stop", "5x"))
        assertEquals("--minutes must be a number from 0.1 to 120", error("--bench", "a.mkv", "--minutes", "0"))
        assertEquals("--minutes must be a number from 0.1 to 120", error("--bench", "a.mkv", "--minutes"))
        assertEquals("--start must be 0 or more seconds", error("--bench", "a.mkv", "--start", "-1"))
        assertEquals("Unknown bench option '--fast'", error("--bench", "a.mkv", "--fast"))
        assertEquals("Unknown experiment 'mvtools': use PotPlayer-like", error("--bench", "a.mkv", "--experiment", "mvtools"))
        assertEquals("--screen-size must be on or off", error("--bench", "a.mkv", "--screen-size", "yes"))
    }

    /** PresentMon writes NA for a displayed frame with no earlier one to measure from; the summary skips it (owner's Claw run). */
    @Test fun benchScriptSummarySkipsAFrameWithoutATimeSinceTheLastOne() {
        val folder = kotlin.io.path.createTempDirectory("skywing-presentmon").toFile()
        try {
            val csv = File(folder, "presentmon.csv")
            csv.writeText(buildString {
                appendLine("Application,ProcessID,SwapChainAddress,Runtime,SyncInterval,PresentFlags,Dropped,TimeInSeconds,msInPresentAPI," +
                    "msBetweenPresents,AllowsTearing,PresentMode,msUntilRenderComplete,msUntilDisplayed,msBetweenDisplayChange")
                for (i in 0..90) {
                    val between = if (i == 0) "NA" else "11.111"
                    appendLine("SkyWing.exe,1,0x1,DXGI,1,0,0,${String.format(java.util.Locale.ROOT, "%.5f", i / 90.0)},0.1,11.111,0," +
                        "Hardware: Independent Flip,1.0,5.0,$between")
                }
            })
            val script = listOf(File("tools/bench.ps1"), File("windows/tools/bench.ps1")).first { it.isFile }
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                script.absolutePath, "-PresentMonCsv", csv.path, "-Minutes", "1").redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            kotlin.test.assertTrue(process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), output)
            assertEquals(0, process.exitValue(), output)
            kotlin.test.assertTrue("91 presents, 91 displayed, 0 not displayed" in output, output)
            kotlin.test.assertTrue("median 11.11 ms" in output, output)
            kotlin.test.assertTrue("Present mode: Hardware: Independent Flip 100.0%" in output, output)
        } finally { folder.deleteRecursively() }
    }
}
