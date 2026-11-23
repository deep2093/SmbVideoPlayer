package com.skywing.desktop.automation

import com.skywing.desktop.FoundVideo
import com.skywing.desktop.VideoFiles
import com.skywing.desktop.playback.MotionQuality
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.GraphicsEnvironment
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.system.exitProcess

/**
 * The checks runner (docs/modernise/CHECKS-RUNNER.md): the later milestones' automatic checks of the current SkyWing
 * build on one machine, with nobody at it. Like the M1 runner it installs nothing: the app image runs from the runner
 * folder on isolated data ([SkyWingTestApp]), with motion smoothing on at 2× in the default quality and "Lower
 * automatically" off. Keys are posted to SkyWing's window ([Win32.postKey]), not typed, so the pointer stays where it
 * is. They reach SkyWing only while it is the active window: a check that finds another program active says the
 * device wasn't left alone instead of failing SkyWing.
 *
 * - fullscreen: 20 times full screen in and out while the video plays (plan section 10, check 5; M6)
 * - stress: 10 times: start, 100 × (seek, full screen in, seek, full screen out), close (plan 5.5; M2)
 * - soak: 2 hours full screen with a seek every 10 minutes, and SkyWing's memory, handles, threads and GDI and USER
 *   objects each minute (plan section 10; M7). Only when named, because it takes the screen for 2 hours.
 * - scan "<folder>": walks the folder the way SkyWing did before B19 and the way it does now, and compares the
 *   times and what they found (plan section 10, check 9). Read only, and SkyWing doesn't start. Only when named.
 *
 * `Run checks.cmd` does fullscreen and stress; `Run checks.cmd soak` or `Run checks.cmd scan "Z:\Videos"` does those.
 * `trial` makes every check short, which tests the runner, not SkyWing. Results go to `results\<device>\<date-time>`:
 * `checks.md`, a text file per check and the playback reports. They hold no file names: the clips are generated.
 * SKYWING_CHECKS_IMAGE names another app image than the runner's `app` folder, SKYWING_CHECKS_MEDIA another clip folder.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "prepare") {
        ChecksMedia.prepare(Path.of(args[1]), soak = args.getOrNull(2) == "soak")
        exitProcess(0)
    }
    // exitProcess: AWT and the Access Bridge client keep threads that would hold the JVM open.
    val code = try {
        val request = ChecksRequest.parse(args.toList())
        HandheldSystem.keepConsoleRunning()
        HandheldSystem.keepAwake()
        ChecksRunner(request, System.getenv(), Path.of("").toAbsolutePath()).runAll()
    } catch (refused: IllegalArgumentException) {
        HandheldSystem.beep(3)
        println()
        println("Stopped: ${refused.message}")
        2
    } catch (error: Throwable) {
        HandheldSystem.beep(3)
        println()
        println("Stopped by an error:")
        error.printStackTrace(System.out)
        3
    }
    exitProcess(code)
}

internal enum class Check(val word: String, val title: String) {
    FULLSCREEN("fullscreen", "Full screen in and out"),
    STRESS("stress", "Seeks and full screen, then close"),
    SOAK("soak", "Full-screen soak"),
    SCAN("scan", "Folder scan before and after B19"),
}

/** What one start of the runner does: [checks] in that order, short when [trial], and the folder a scan walks. */
internal data class ChecksRequest(val checks: List<Check>, val trial: Boolean, val scanFolder: String?) {
    companion object {
        private val DEFAULT = listOf(Check.FULLSCREEN, Check.STRESS)

        /** The runner's words: check names, `trial`, and the folder after `scan`. Throws IllegalArgumentException. */
        fun parse(args: List<String>): ChecksRequest {
            val checks = mutableListOf<Check>()
            var trial = false
            var folder: String? = null
            var index = 0
            while (index < args.size) {
                val word = args[index++].trim()
                if (word.equals("trial", ignoreCase = true)) {
                    trial = true
                    continue
                }
                val check = requireNotNull(Check.entries.firstOrNull { it.word.equals(word, ignoreCase = true) }) {
                    "\"$word\" isn't a check. The checks are ${Check.entries.joinToString { it.word }}; trial makes them short."
                }
                if (check == Check.SCAN) {
                    // cmd.exe passes "D:\" as D:" (the backslash escapes the quote), so a stray quote is dropped.
                    val next = args.getOrNull(index)?.trim()?.trimEnd('"')
                    require(!next.isNullOrEmpty() && !isWord(next)) { "scan needs the folder after it, for example: Run checks.cmd scan \"D:\\Videos\"" }
                    folder = next
                    index++
                }
                if (check !in checks) checks += check
            }
            return ChecksRequest(checks.ifEmpty { DEFAULT }, trial, folder)
        }

        private fun isWord(text: String) = text.equals("trial", ignoreCase = true) || Check.entries.any { it.word.equals(text, ignoreCase = true) }
    }
}

/** How long the checks run. */
internal data class ChecksTiming(
    val fullscreenRounds: Int,
    val stressRounds: Int,
    val stressSteps: Int,
    val soakSeconds: Long,
    val soakSampleSeconds: Long,
    val soakSeekSeconds: Long,
    /** The soak's numbers are compared from here on: SkyWing fills its caches first. */
    val soakSettleSeconds: Long,
) {
    companion object {
        val FULL = ChecksTiming(20, 10, 100, soakSeconds = 7_200, soakSampleSeconds = 60, soakSeekSeconds = 600, soakSettleSeconds = 1_200)
        val TRIAL = ChecksTiming(3, 2, 6, soakSeconds = 240, soakSampleSeconds = 10, soakSeekSeconds = 60, soakSettleSeconds = 60)
    }
}

internal enum class Verdict(val word: String) {
    PASS("Pass"),
    FAIL("Fail"),
    /** Nothing failed, but a number is past the first limit (the soak's are set from its first runs): look at it. */
    LOOK("Look at it"),
    /** The device wasn't left alone, so the check says nothing about SkyWing. Run it again. */
    NOT_MEASURED("Not measured"),
}

internal data class CheckResult(val check: Check, val verdict: Verdict, val summary: String, val details: List<String> = emptyList())

/** What a check found, and the verdict it gives. */
internal class CheckFindings {
    private val problems = mutableListOf<String>()
    private val disturbances = mutableListOf<String>()
    private val looks = mutableListOf<String>()
    private val notes = mutableListOf<String>()
    private var input = false

    val failed: Boolean get() = problems.isNotEmpty() || disturbances.isNotEmpty()

    /** Something SkyWing did wrong. */
    fun problem(text: String) { problems += text }

    /** Something that kept the check from measuring SkyWing, such as another program in front. */
    fun disturbed(text: String) { if (text !in disturbances) disturbances += text }

    /**
     * Someone used the keyboard, mouse or touch screen. Posted keys still reach SkyWing while it stays in front, so this
     * spoils the check only when something also went wrong: a key or a click may have caused it.
     */
    fun input(text: String) {
        input = true
        note(text)
    }

    fun look(text: String) { looks += text }

    fun note(text: String) { if (text !in notes) notes += text }

    fun verdict(): Verdict = when {
        disturbances.isNotEmpty() -> Verdict.NOT_MEASURED
        problems.isNotEmpty() && input -> Verdict.NOT_MEASURED
        problems.isNotEmpty() -> Verdict.FAIL
        looks.isNotEmpty() -> Verdict.LOOK
        else -> Verdict.PASS
    }

    fun result(check: Check, summary: String) = CheckResult(
        check, verdict(), summary,
        problems.map { "Problem: $it" } + disturbances.map { "Not left alone: $it" } + looks.map { "Look at: $it" } + notes,
    )
}

/** The median and the longest of [values] in milliseconds, or "none". */
internal fun msSummary(values: List<Long>): String =
    if (values.isEmpty()) "none" else "median ${median(values.map(Long::toDouble)).roundToLong()} ms, longest ${values.max()} ms"

internal fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
}

/** One reading of the SkyWing process during the soak, [atSeconds] after it started. */
internal data class SoakSample(val atSeconds: Long, val privateMb: Double, val handles: Long, val threads: Long, val gdiObjects: Long, val userObjects: Long)

/** How much one number grew over the soak, and the growth allowed: the plan sets the limits from the first runs. */
internal data class SoakGrowth(val name: String, val start: Double, val end: Double, val allowed: Double) {
    val over: Boolean get() = end - start > allowed
}

private class SoakMeasure(val name: String, val value: (SoakSample) -> Double, val percent: Double, val floor: Double)

/** First limits: 10 % of the settled value, but at least the floor, so small counts don't trip on noise. */
private val SOAK_MEASURES = listOf(
    SoakMeasure("Private memory (MB)", { it.privateMb }, 10.0, 50.0),
    SoakMeasure("Handles", { it.handles.toDouble() }, 10.0, 100.0),
    SoakMeasure("Threads", { it.threads.toDouble() }, 10.0, 10.0),
    SoakMeasure("GDI objects", { it.gdiObjects.toDouble() }, 10.0, 50.0),
    SoakMeasure("USER objects", { it.userObjects.toDouble() }, 10.0, 50.0),
)

/**
 * Each soak number's growth from the first five readings after [settleSeconds] to the last five (medians, so one odd
 * reading doesn't count), or null when fewer than ten readings came after it.
 */
internal fun soakGrowth(samples: List<SoakSample>, settleSeconds: Long): List<SoakGrowth>? {
    val settled = samples.filter { it.atSeconds >= settleSeconds }
    if (settled.size < 10) return null
    val first = settled.take(5)
    val last = settled.takeLast(5)
    return SOAK_MEASURES.map { measure ->
        val start = median(first.map(measure.value))
        SoakGrowth(measure.name, start, median(last.map(measure.value)), allowed = max(start * measure.percent / 100, measure.floor))
    }
}

/**
 * The drops one mpv counter counted between two playback reports, or null when a report lacks it. A seek rebuilds the
 * motion graph, which can start mpv's counters again at 0: a counter that fell counts from that new start.
 */
internal fun dropsBetween(before: Long?, after: Long?): Long? = when {
    before == null || after == null -> null
    after >= before -> after - before
    else -> after
}

/** True when the drops per minute of the second half of [perMinute] (one value per seek interval) clearly exceed the first's. */
internal fun dropsRising(perMinute: List<Double>): Boolean {
    if (perMinute.size < 2) return false
    val half = perMinute.size / 2
    return perMinute.drop(half).average() > perMinute.take(half).average() * 1.5 + 1.0
}

/** One walk of the scanned folder: how long it took, how many videos it found, and a hash of their paths, sizes and times. */
internal data class ScanRun(val seconds: Double, val videos: Int, val signature: Int)

/** The scan check's verdict: both walks must find the same videos with the same sizes and times. */
internal fun scanResult(before: List<ScanRun>, after: List<ScanRun>): CheckResult {
    val findings = CheckFindings()
    val oldSeconds = median(before.map { it.seconds })
    val newSeconds = median(after.map { it.seconds })
    val times = "before B19 ${seconds(oldSeconds)}, after ${seconds(newSeconds)}" +
        if (newSeconds > 0) String.format(Locale.ROOT, ", %.1f× as fast", oldSeconds / newSeconds) else ""
    val same = (before + after).map { it.videos to it.signature }.distinct().size == 1
    val summary = if (same) {
        String.format(Locale.ROOT, "%,d videos; %s (median of %d walks each)", before.first().videos, times, before.size)
    } else {
        fun counts(runs: List<ScanRun>) = runs.joinToString(" and ") { String.format(Locale.ROOT, "%,d", it.videos) }
        findings.problem("The walks found different videos or sizes and times")
        "The walks differ: before B19 found ${counts(before)}, after found ${counts(after)}; $times"
    }
    (before.map { "Before B19: %.2f s".format(Locale.ROOT, it.seconds) } + after.map { "After: %.2f s".format(Locale.ROOT, it.seconds) }).forEach(findings::note)
    return findings.result(Check.SCAN, summary)
}

/** A time in seconds, to the hundredth under a second, as a folder on a fast drive walks. */
private fun seconds(value: Double): String = String.format(Locale.ROOT, if (value < 1.0) "%.2f s" else "%.1f s", value)

/** checks.md: one row per check, then each check's details. */
internal fun checksTable(device: String, display: String, build: String, results: List<CheckResult>): String = buildString {
    appendLine("# SkyWing checks on $device")
    appendLine()
    appendLine("SkyWing build: $build. Display: $display. CHECKS-RUNNER.md says what each check does.")
    appendLine()
    appendLine("| Check | Result | What it found |")
    appendLine("|---|---|---|")
    for (result in results) appendLine("| ${result.check.title} | ${result.verdict.word} | ${result.summary.replace("|", "/")} |")
    for (result in results.filter { it.details.isNotEmpty() }) {
        appendLine()
        appendLine("## ${result.check.title}")
        appendLine()
        result.details.forEach { appendLine("- $it") }
    }
}

/** The seeks of one stress step, before and after full screen: +10 s, −5 s, and every third step −5 s twice, so the video stays near its start. */
internal fun stressSeeks(step: Int): Pair<Int, Int> = if (step % 3 == 0) Win32.VK_LEFT to Win32.VK_LEFT else Win32.VK_RIGHT to Win32.VK_LEFT

/** The runner's test clips, made with ffmpeg on the laptop: 1080p at 23.976 fps with a tone, as most films are. */
internal object ChecksMedia {
    private const val CLIP_MINUTES = 15
    /** Long enough for the 2-hour soak, its seeks and the start. */
    private const val SOAK_MINUTES = 135

    fun clip(folder: Path): Path = folder.resolve("checks-1080p.mp4")

    fun soakClip(folder: Path): Path = folder.resolve("soak-1080p.mp4")

    /** Makes the clip, or with [soak] the soak clip, unless it is there. Needs ffmpeg with libx264 on PATH. */
    fun prepare(folder: Path, soak: Boolean) {
        folder.createDirectories()
        if (soak) make(soakClip(folder), SOAK_MINUTES) else make(clip(folder), CLIP_MINUTES)
    }

    private fun make(clip: Path, minutes: Int) {
        if (clip.isRegularFile() && clip.fileSize() > 0L) return
        val seconds = minutes * 60
        val partial = clip.resolveSibling(clip.name + ".part.mp4")
        runFfmpeg(
            listOf(
                "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=24000/1001:duration=$seconds",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$seconds",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "32", "-g", "240", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", "96k", "-shortest", "-movflags", "+faststart", partial.toString(),
            ),
            // libx264 at veryfast makes this pattern several times faster than real time on a laptop.
            timeoutMinutes = minutes + 10L,
        )
        Files.move(partial, clip, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** The GDI and USER objects a process holds (GetGuiResources): what leaks when windows or brushes aren't freed. */
private object GuiResources {
    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun CloseHandle(handle: Pointer): Boolean
    }

    @Suppress("FunctionName")
    private interface User32 : StdCallLibrary {
        fun GetGuiResources(process: Pointer, flags: Int): Int
    }

    private val kernel32: Kernel32 = Native.load("kernel32", Kernel32::class.java)
    private val user32: User32 = Native.load("user32", User32::class.java)
    private const val PROCESS_QUERY_LIMITED_INFORMATION = 0x1000

    /** (GDI, USER) objects of process [pid], or null when it can't be opened. Read only. */
    fun of(pid: Long): Pair<Long, Long>? {
        val process = kernel32.OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid.toInt()) ?: return null
        return try {
            user32.GetGuiResources(process, 0).toLong() to user32.GetGuiResources(process, 1).toLong()
        } finally {
            kernel32.CloseHandle(process)
        }
    }
}

/** One reading of every SkyWing process with PowerShell's Get-Process, summed: what the soak follows. Read only. */
private class ProcessCounts(val ids: Set<Long>, val privateBytes: Long, val handles: Long, val threads: Long, val cpuSeconds: Double) {
    companion object {
        private val SCRIPT = """
            ${'$'}c = [Globalization.CultureInfo]::InvariantCulture
            ${'$'}p = @(Get-Process -Name SkyWing -ErrorAction SilentlyContinue)
            ${'$'}memory = [long]0; ${'$'}handles = [long]0; ${'$'}threads = [long]0; ${'$'}cpu = 0.0
            foreach (${'$'}x in ${'$'}p) { ${'$'}memory += ${'$'}x.PrivateMemorySize64; ${'$'}handles += ${'$'}x.HandleCount; ${'$'}threads += ${'$'}x.Threads.Count; ${'$'}cpu += ${'$'}x.TotalProcessorTime.TotalSeconds }
            'ids=' + ((${'$'}p | ForEach-Object { ${'$'}_.Id }) -join ',')
            'memory=' + ${'$'}memory.ToString(${'$'}c)
            'handles=' + ${'$'}handles.ToString(${'$'}c)
            'threads=' + ${'$'}threads.ToString(${'$'}c)
            'cpu=' + ${'$'}cpu.ToString('R', ${'$'}c)
        """.trimIndent()

        fun take(): ProcessCounts {
            val values = runPowerShell(SCRIPT, "The process reading").lineSequence().mapNotNull { text ->
                text.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.trim() to value.trim() }
            }.toMap()
            return ProcessCounts(
                ids = values["ids"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet(),
                privateBytes = values.getValue("memory").toLong(),
                handles = values.getValue("handles").toLong(),
                threads = values.getValue("threads").toLong(),
                cpuSeconds = values.getValue("cpu").toDouble(),
            )
        }
    }
}

/** One start of the checks runner. */
private class ChecksRunner(private val request: ChecksRequest, private val environment: Map<String, String>, private val runner: Path) {
    private val timing = if (request.trial) ChecksTiming.TRIAL else ChecksTiming.FULL
    private val product = registryValue(BIOS, "SystemProductName")
    private val device = listOfNotNull(registryValue(BIOS, "SystemManufacturer"), product).joinToString(" ").ifBlank { "this device" }
    private val image = path("SKYWING_CHECKS_IMAGE") ?: runner.resolve("app")
    private val media = path("SKYWING_CHECKS_MEDIA") ?: runner.resolve("media")
    private val results = runner.resolve("results").resolve(machineFolder(product, environment["COMPUTERNAME"]))
        .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + if (request.trial) "-trial" else "")
    private val quietWait = quietWaitMs(HandheldSystem.foregroundLockTimeoutMs())
    private val outcomes = mutableListOf<CheckResult>()

    private fun path(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    fun runAll(): Int {
        runnerFolderRefusal(runner, environment, HandheldSystem::removable, runner = "checks")?.let { throw IllegalArgumentException(it) }
        val playing = request.checks.any { it != Check.SCAN }
        if (playing) {
            require(image.resolve("SkyWing.exe").isRegularFile()) { "The app isn't in the runner folder (app\\SkyWing.exe)." }
            require(skyWingProcesses().isEmpty()) { "Close SkyWing first: the checks count every SkyWing process." }
        }
        if (Check.FULLSCREEN in request.checks || Check.STRESS in request.checks) {
            require(ChecksMedia.clip(media).isRegularFile()) { "The test clip isn't in the runner folder (media\\${ChecksMedia.clip(media).name})." }
        }
        if (Check.SOAK in request.checks) {
            require(ChecksMedia.soakClip(media).isRegularFile()) {
                "The soak clip isn't in the runner folder (media\\${ChecksMedia.soakClip(media).name}). Build the runner with " +
                    "gradlew -p windows checksSoakMedia checksRunner."
            }
        }
        results.createDirectories()
        logOutput(results.resolve("runner-log.txt"))
        System.setProperty(SkyWingTestApp.REPORTS_PROPERTY, results.resolve("diagnostics").toString())
        println("SkyWing checks${if (request.trial) " (trial)" else ""} on $device")
        println("SkyWing build: ${build()}")
        println("Display: ${display()}")
        println("Power: ${power()}")
        println("Checks: ${request.checks.joinToString { it.title }}, about ${minutes()} minutes.")
        if (playing) println("Don't touch the device until it beeps. You can watch: the picture is the one thing the runner can't check.")

        val clipboard = Clipboard.text()
        try {
            for (check in request.checks) {
                println()
                println("${check.title}...")
                val result = when (check) {
                    Check.FULLSCREEN -> fullScreenCheck()
                    Check.STRESS -> stressCheck()
                    Check.SOAK -> soakCheck()
                    Check.SCAN -> scanCheck(requireNotNull(request.scanFolder))
                }
                outcomes += result
                results.resolve("${check.word}.txt").writeText((listOf("${result.verdict.word}: ${result.summary}") + result.details).joinToString("\n", postfix = "\n"))
                writeTable()
                println("${result.verdict.word}: ${result.summary}")
                result.details.forEach { println("  $it") }
            }
        } finally {
            if (clipboard != null) Clipboard.write(clipboard)
        }
        HandheldSystem.beep(3)
        println()
        println("Done. The results are in ${results.resolve("checks.md")}. Bring back the whole results folder.")
        return if (outcomes.all { it.verdict == Verdict.PASS }) 0 else 1
    }

    private fun writeTable() = results.resolve("checks.md").writeText(checksTable(device, display(), build(), outcomes))

    /** Check 5 and M6's test: full screen in and out while the video plays. */
    private fun fullScreenCheck(): CheckResult {
        val findings = CheckFindings()
        val inputAtStart = Win32.lastInputTick()
        val enter = mutableListOf<Long>()
        val exit = mutableListOf<Long>()
        var done = 0
        var reports: Pair<PlaybackReport, PlaybackReport?>? = null
        val app = launch("fullscreen", ChecksMedia.clip(media))
        try {
            val smoothing = startPlaying(app, findings)
            val player = Player(app)
            val before = if (smoothing) player.report("fullscreen-a") else null
            for (round in 1..timing.fullscreenRounds) {
                if (!inFront(app, findings)) break
                val inMs = player.toggle(entering = true, findings, "round $round") ?: break
                enter += inMs
                Thread.sleep(SETTLE_MS)
                if (!player.playing()) findings.problem("The video wasn't playing in full screen (round $round)")
                val outMs = player.toggle(entering = false, findings, "round $round") ?: break
                exit += outMs
                Thread.sleep(SETTLE_MS)
                if (!player.playing()) findings.problem("The video wasn't playing after full screen (round $round)")
                val mains = app.windows().count { Win32.title(it) == SkyWingTestApp.MAIN_TITLE }
                if (mains != 1) findings.problem("$mains main windows after round $round")
                done = round
                if (findings.failed) break
            }
            if (before != null) {
                val after = player.report("fullscreen-b")
                if (after == null) findings.problem("Smoothing was off after the rounds")
                reports = before to after
            }
        } catch (error: Throwable) {
            findings.problem("The check stopped: ${firstLine(error)}")
        } finally {
            close(app, findings, "fullscreen")
        }
        inputSince(inputAtStart, findings)
        val counters = reports?.let { (a, b) -> counters(a, b, findings) } ?: "no playback report"
        findings.note("The fade and flicker aren't checked: the runner can't see the picture.")
        return findings.result(
            Check.FULLSCREEN,
            "$done of ${timing.fullscreenRounds} rounds. Full screen in ${msSummary(enter)}; back in the window in ${msSummary(exit)}. $counters.",
        )
    }

    /** Plan 5.5 and M2's teardown stress: each round starts SkyWing, seeks and changes full screen, then closes it. */
    private fun stressCheck(): CheckResult {
        val findings = CheckFindings()
        val inputAtStart = Win32.lastInputTick()
        val changes = mutableListOf<Long>()
        val closes = mutableListOf<Long>()
        var rounds = 0
        var restarts = 0L
        for (round in 1..timing.stressRounds) {
            val app = launch("stress-$round", ChecksMedia.clip(media))
            try {
                val smoothing = startPlaying(app, findings, quiet = round > 1)
                val player = Player(app)
                if (!inFront(app, findings)) break
                for (step in 1..timing.stressSteps) {
                    val (first, second) = stressSeeks(step)
                    player.seek(first)
                    changes += player.toggle(entering = true, findings, "round $round, step $step") ?: break
                    player.seek(second)
                    changes += player.toggle(entering = false, findings, "round $round, step $step") ?: break
                    if (!app.isRunning) break
                }
                if (!findings.failed && app.isRunning) {
                    if (!player.playing()) findings.problem("The video wasn't playing after round $round")
                    if (smoothing) {
                        val report = player.report("stress-$round")
                        if (report == null) findings.problem("Smoothing was off after round $round") else restarts += report.restarts ?: 0
                    }
                }
            } catch (error: Throwable) {
                findings.problem("Round $round stopped: ${firstLine(error)}")
            } finally {
                closes += close(app, findings, "stress-$round")
            }
            rounds = round
            if (findings.failed) break
        }
        inputSince(inputAtStart, findings)
        if (restarts > 0) findings.problem("The motion watchdog restarted smoothing $restarts times")
        return findings.result(
            Check.STRESS,
            "$rounds of ${timing.stressRounds} rounds of ${timing.stressSteps} × (seek, full screen, seek, window). Changes " +
                "${msSummary(changes)}. Closed in ${msSummary(closes)}. Watchdog restarts: $restarts.",
        )
    }

    /** Plan section 10 and M7: two hours full screen, a seek every 10 minutes, the process's resources each minute. */
    private fun soakCheck(): CheckResult {
        val findings = CheckFindings()
        val folder = results.resolve("soak").createDirectories()
        val csv = folder.resolve("minutes.csv")
        csv.writeText("seconds,private_mb,handles,threads,gdi_objects,user_objects,cpu_cores\n")
        val samples = mutableListOf<SoakSample>()
        val dropsPerMinute = mutableListOf<Double>()
        var restarts: Long? = null
        var foregroundLost = 0
        var fullScreenLost = 0
        val app = launch("soak", ChecksMedia.soakClip(media))
        var started = false
        try {
            // No return in here: closing SkyWing in finally can still find a crash or a hang.
            val (player, firstReport) = soakStart(app, findings) ?: throw SoakNotStarted()
            started = true
            var report = firstReport
            var reportAt = System.currentTimeMillis()
            val start = reportAt
            var lastCounts = ProcessCounts.take() to start
            var nextSample = start + timing.soakSampleSeconds * 1000
            var nextSeek = start + timing.soakSeekSeconds * 1000
            var lastInput = Win32.lastInputTick()
            var seeks = 0
            while (System.currentTimeMillis() < start + timing.soakSeconds * 1000) {
                Thread.sleep(1_000)
                val now = System.currentTimeMillis()
                if (!app.isRunning) {
                    findings.problem("SkyWing exited after ${(now - start) / 60_000} minutes")
                    break
                }
                if (Win32.foregroundProcess() !in app.processIds()) foregroundLost++
                if (!player.screen.held()) fullScreenLost++
                val input = Win32.lastInputTick()
                if (input != lastInput) {
                    findings.input("Someone used the keyboard, mouse or touch screen at ${clock()}")
                    lastInput = input
                }
                if (now >= nextSample) {
                    val counts = ProcessCounts.take()
                    val gui = counts.ids.mapNotNull(GuiResources::of)
                    val sample = SoakSample((now - start) / 1000, counts.privateBytes / MIB, counts.handles, counts.threads, gui.sumOf { it.first }, gui.sumOf { it.second })
                    samples += sample
                    val cores = (counts.cpuSeconds - lastCounts.first.cpuSeconds) / ((now - lastCounts.second) / 1000.0)
                    lastCounts = counts to now
                    csv.appendText(String.format(Locale.ROOT, "%d,%.1f,%d,%d,%d,%d,%.2f\n", sample.atSeconds, sample.privateMb, sample.handles, sample.threads, sample.gdiObjects, sample.userObjects, cores))
                    folder.resolve("progress.txt").writeText("At ${clock()}: ${sample.atSeconds / 60} of ${timing.soakSeconds / 60} minutes\n")
                    nextSample += timing.soakSampleSeconds * 1000
                }
                if (now >= nextSeek) {
                    val next = player.report("soak-${++seeks}")
                    if (next == null) {
                        findings.problem("Smoothing was off after ${(now - start) / 60_000} minutes")
                        break
                    }
                    if (player.screen.enterAgainIfLeft()) findings.note("Full screen was left after a playback report; the runner entered it again")
                    drops(report, next)?.let { dropsPerMinute += it / ((System.currentTimeMillis() - reportAt) / 60_000.0) }
                    report = next
                    reportAt = System.currentTimeMillis()
                    player.seek(Win32.VK_RIGHT)
                    nextSeek += timing.soakSeekSeconds * 1000
                }
            }
            restarts = report.restarts
        } catch (notStarted: SoakNotStarted) {
            // soakStart said why.
        } catch (error: Throwable) {
            findings.problem("The soak stopped: ${firstLine(error)}")
        } finally {
            close(app, findings, "soak")
        }
        if (!started) return findings.result(Check.SOAK, "The soak didn't start.")
        if (foregroundLost > 0) findings.disturbed("another program was the active window for $foregroundLost s")
        if (fullScreenLost > 0) findings.problem("SkyWing wasn't full screen for $fullScreenLost s")
        if ((restarts ?: 0) > 0) findings.problem("The motion watchdog restarted smoothing $restarts times")
        val growth = soakGrowth(samples, timing.soakSettleSeconds)
        if (growth == null) findings.note("Too short to compare the numbers after the first ${timing.soakSettleSeconds / 60} minutes")
        growth?.filter { it.over }?.forEach {
            findings.look(String.format(Locale.ROOT, "%s grew from %.0f to %.0f after the first %d minutes (first limit: +%.0f)", it.name, it.start, it.end, timing.soakSettleSeconds / 60, it.allowed))
        }
        if (dropsRising(dropsPerMinute)) findings.look("Drops per minute rose: ${dropsPerMinute.joinToString { String.format(Locale.ROOT, "%.1f", it) }}")
        val minutes = samples.lastOrNull()?.atSeconds?.div(60) ?: 0
        val grew = growth?.joinToString("; ") { String.format(Locale.ROOT, "%s %.0f to %.0f", it.name, it.start, it.end) } ?: "not compared"
        val drops = if (dropsPerMinute.isEmpty()) "none measured" else dropsPerMinute.joinToString(", ") { String.format(Locale.ROOT, "%.1f", it) }
        return findings.result(Check.SOAK, "$minutes of ${timing.soakSeconds / 60} minutes. After the first ${timing.soakSettleSeconds / 60}: $grew. Drops per minute: $drops. Watchdog restarts: ${restarts ?: "unknown"}.")
    }

    /**
     * The soak's start: playing, in front, full screen and the first playback report. Null, with the reason in
     * [findings], when it can't start.
     */
    private fun soakStart(app: SkyWingTestApp, findings: CheckFindings): Pair<Player, PlaybackReport>? {
        if (!startPlaying(app, findings)) return null
        val player = Player(app)
        if (!inFront(app, findings)) return null
        if (player.toggle(entering = true, findings, "at the start") == null) return null
        Thread.sleep(SETTLE_MS)
        val report = player.report("soak-0")
        if (report == null) findings.problem("Smoothing was off in full screen")
        return report?.let { player to it }
    }

    private class SoakNotStarted : Exception()

    /** Check 9: the folder walk before B19 against today's, read only. The first walk only fills the caches. */
    private fun scanCheck(folderText: String): CheckResult {
        val root = Path.of(folderText).toAbsolutePath().normalize()
        if (!root.isDirectory()) return CheckResult(Check.SCAN, Verdict.NOT_MEASURED, "The folder can't be opened. Name a folder, for example scan \"D:\\Videos\".")
        return try {
            newWalk(root)
            val before = mutableListOf<ScanRun>()
            val after = mutableListOf<ScanRun>()
            repeat(2) {
                before += timed { oldWalk(root) }
                after += timed { newWalk(root) }
            }
            scanResult(before, after)
        } catch (error: Exception) {
            CheckResult(Check.SCAN, Verdict.NOT_MEASURED, "A folder in it can't be read (${firstLine(error)}). Name a folder of videos, not a drive.")
        }
    }

    private fun timed(walk: () -> List<Triple<String, Long, Long>>): ScanRun {
        val start = System.nanoTime()
        val found = walk()
        return ScanRun((System.nanoTime() - start) / 1e9, found.size, found.sortedBy { it.first }.hashCode())
    }

    /**
     * The walk SkyWing did before B19 (LocalVideoScanner before fa52238): Files.walk, a link and a file check for each
     * path, then each video's size and time from WindowsMediaIndex.replaceSource.
     */
    private fun oldWalk(root: Path): List<Triple<String, Long, Long>> {
        val files = LinkedHashMap<String, File>()
        Files.walk(root).use { paths ->
            for (path in paths) {
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)) continue
                if (!VideoFiles.isVideo(path.fileName.toString())) continue
                files.putIfAbsent(path.toAbsolutePath().normalize().toString().lowercase(), path.toFile())
            }
        }
        return files.map { (key, file) -> Triple(key, file.length(), file.lastModified()) }
    }

    private fun newWalk(root: Path): List<Triple<String, Long, Long>> {
        val found = LinkedHashMap<String, FoundVideo>()
        VideoFiles.walk(root, found, Int.MAX_VALUE)
        return found.map { (key, video) -> Triple(key, video.size, video.modified) }
    }

    private fun launch(name: String, clip: Path): SkyWingTestApp {
        waitUntilQuiet(quietWait)
        return SkyWingTestApp.launch("checks-$name", args = listOf(clip.toString()), image = image) { root, _ ->
            seedMotion(root, MotionQuality.DEFAULT, vrr = null)
        }
    }

    /** Waits for the video to play and smoothing to hold a rate. False, with the problem noted, when smoothing didn't start. */
    private fun startPlaying(app: SkyWingTestApp, findings: CheckFindings, quiet: Boolean = false): Boolean {
        PlayerControls(app).waitAdvancing("the test clip", minStepPercent = 0.0)
        return try {
            val rate = app.waitForHolding()
            if (!quiet) findings.note(String.format(Locale.ROOT, "Smoothing held %.2f fps", rate))
            true
        } catch (failed: AssertionError) {
            findings.problem("Smoothing didn't start: ${firstLine(failed)}")
            false
        }
    }

    private fun inFront(app: SkyWingTestApp, findings: CheckFindings): Boolean {
        if (app.awaitForeground(timeoutMs = 10_000)) return true
        findings.disturbed("${programName(Win32.foregroundProcess())} was the active window, so SkyWing didn't get the keys")
        return false
    }

    private fun inputSince(tick: Int, findings: CheckFindings) {
        if (Win32.lastInputTick() != tick) findings.input("Someone used the keyboard, mouse or touch screen during the check")
    }

    /** Closes SkyWing as a user would and notes a crash, a hang on close or a process left behind. Returns how long closing took. */
    private fun close(app: SkyWingTestApp, findings: CheckFindings, name: String): Long {
        val crashed = !app.isRunning
        val start = System.nanoTime()
        app.close(keepData = true)
        val ms = (System.nanoTime() - start) / 1_000_000
        when {
            crashed -> findings.problem("SkyWing exited by itself during $name")
            app.exitedGracefully != true -> findings.problem("SkyWing didn't exit within 10 s of being closed ($name); the runner stopped it")
        }
        if (skyWingProcesses().isNotEmpty()) findings.problem("A SkyWing process was still running after $name")
        if (crashed || app.exitedGracefully != true) keepCrashFiles(app.dataDir, name)
        app.close(keepData = false)
        return ms
    }

    /** SkyWing's output and any Java crash log, for a check that ended badly. They stay on the device. */
    private fun keepCrashFiles(dataDir: Path, name: String) {
        val folder = results.resolve("crash-$name").createDirectories()
        runCatching { Files.copy(dataDir.resolve("app-output.txt"), folder.resolve("app-output.txt")) }
        runCatching { dataDir.listDirectoryEntries("hs_err_pid*.log").forEach { Files.copy(it, folder.resolve(it.name)) } }
    }

    /** Drops and restarts between two playback reports, as the summary line says them. */
    private fun counters(a: PlaybackReport, b: PlaybackReport?, findings: CheckFindings): String {
        if (b == null) return "smoothing was off at the end"
        val restarts = b.restarts?.minus(a.restarts ?: 0)
        if ((restarts ?: 0) > 0) findings.problem("The motion watchdog restarted smoothing $restarts times")
        b.line("Last heartbeat age: ")?.let { findings.note("Motion heartbeat age at the end: $it") }
        return "Drops: ${drops(a, b) ?: "unknown"}. Watchdog restarts: ${restarts ?: "unknown"}"
    }

    private fun drops(a: PlaybackReport, b: PlaybackReport): Long? {
        val video = dropsBetween(a.frameDrops, b.frameDrops) ?: return null
        return dropsBetween(a.decoderDrops, b.decoderDrops)?.plus(video)
    }

    /** SkyWing's player in one start: full screen, seeks and playback reports through posted keys and its own buttons. */
    private inner class Player(private val app: SkyWingTestApp) {
        private val main = app.mainWindow()
        private val windowed = Win32.bounds(main)
        val screen = FullScreen(app, Win32.monitorBounds(main))

        /**
         * Posts F and waits until SkyWing is in full screen, or back at its window size. Milliseconds, or null after
         * 5 s, with the problem in [findings] (at [where]).
         */
        fun toggle(entering: Boolean, findings: CheckFindings, where: String): Long? {
            val start = System.nanoTime()
            Win32.postKey(screen.keyTarget(), Win32.VK_F, Win32.SCAN_F)
            while (System.nanoTime() - start < 5_000_000_000L) {
                val done = if (entering) screen.held() else !screen.held() && Win32.bounds(main) == windowed
                if (done) return (System.nanoTime() - start) / 1_000_000
                Thread.sleep(10)
            }
            findings.problem("SkyWing didn't ${if (entering) "go full screen" else "leave full screen"} within 5 s ($where)")
            return null
        }

        /** Posts an arrow key: the player seeks 5 s back or 10 s forward. */
        fun seek(key: Int) = Win32.postKey(screen.keyTarget(), key, if (key == Win32.VK_LEFT) Win32.SCAN_LEFT else Win32.SCAN_RIGHT, extended = true)

        /** True when the position moves forward. Shift brings back full screen's hidden controls (and does nothing else). */
        fun playing(): Boolean {
            Win32.postKey(screen.keyTarget(), Win32.VK_SHIFT, Win32.SCAN_SHIFT)
            return runCatching { PlayerControls(app).waitAdvancing("the video", minStepPercent = 0.0) }.isSuccess
        }

        /** Copies a playback report and keeps it as report-[name].txt; null when smoothing is off. */
        fun report(name: String): PlaybackReport? {
            val text = copyPlaybackReport(app, screen) ?: return null
            results.resolve("reports").createDirectories().resolve("report-$name.txt").writeText(text)
            return PlaybackReport(text)
        }
    }

    private fun build(): String =
        if (path("SKYWING_CHECKS_IMAGE") != null) "the image SKYWING_CHECKS_IMAGE names"
        else runCatching { runner.resolve("app-build.txt").readText().trim() }.getOrDefault("unknown")

    private fun display(): String {
        val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        return "${mode.width} × ${mode.height} at ${mode.refreshRate} Hz"
    }

    private fun power(): String {
        val probe = Probe.take()
        return when (probe.onAc) {
            true -> "plugged in, " + probe.powerMode(true)
            false -> "on battery, " + probe.powerMode(false)
            null -> "no battery reading"
        }
    }

    private fun minutes(): Int = request.checks.sumOf { check ->
        when (check) {
            Check.FULLSCREEN -> if (request.trial) 1 else 5
            Check.STRESS -> if (request.trial) 3 else 30
            Check.SOAK -> (timing.soakSeconds / 60 + 3).toInt()
            Check.SCAN -> 2
        }
    }

    private companion object {
        const val BIOS = "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS"
        const val SETTLE_MS = 1_500L
        const val MIB = 1024.0 * 1024.0

        fun firstLine(error: Throwable): String = error.message?.lineSequence()?.firstOrNull() ?: error.javaClass.simpleName
    }
}
