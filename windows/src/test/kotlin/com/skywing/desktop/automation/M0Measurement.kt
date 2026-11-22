package com.skywing.desktop.automation

import com.skywing.desktop.NetworkPaths
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.prefs.Preferences
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * Runs one M0 measurement (`docs/modernise/M0-RESULTS.md`, section 2) with nobody at the keyboard. It launches an
 * installed SkyWing on isolated data ([SkyWingTestApp]), so the owner's settings, library and history are not used
 * or changed. It plays a local clip from the start, full screen, with motion on at 2× Balanced, leaves it alone for
 * the warm-up, and then measures a window:
 * - the SkyWing processes' CPU time and private memory, and the battery, read as the section 2 snippet reads them
 * - playback reports A (start of the window) and B (end), copied with the player's own "Copy playback report" button
 * - whether SkyWing kept the foreground and full screen, and whether anyone used the keyboard or mouse
 *
 * Controls are pressed over Java Access Bridge; the only keys are Shift (it brings back the hidden full-screen
 * controls), posted to SkyWing's own window. Nothing is clicked by coordinates. While the window runs, only cheap
 * Win32 calls are made once a second, so watching costs SkyWing nothing measurable.
 *
 * It takes the desktop for the warm-up plus the window plus about a minute: ask the owner first
 * (`windows/TESTING-POLICY.md`, rule 4). The clipboard is used for the reports; its text is put back at the end.
 * Run `gradlew -p windows m0Run` with:
 * - SKYWING_TEST_IMAGE: the installed SkyWing folder. Required, so a run never measures a development build by mistake.
 * - SKYWING_M0_CLIP: the clip, on a local drive.
 * - SKYWING_M0_OUT: where results go, outside this checkout and outside OneDrive (it would upload them while written).
 * - SKYWING_M0_LABEL: the run's name, used for its results folder (letters, digits, '-' and '_').
 * - SKYWING_M0_VRR: true or false, written as SkyWing's "My screen uses G-SYNC / FreeSync" setting.
 * - SKYWING_M0_POWER: ac or battery, what the machine must be on.
 * - SKYWING_M0_WARMUP and SKYWING_M0_WINDOW: seconds; 60 and 600 when unset.
 * SKYWING_GPU passes through to SkyWing (laptop run B).
 *
 * The results folder gets summary.txt, report-A.txt, report-B.txt, progress.txt and SkyWing's app-output.txt, or
 * failure.txt when the run stopped. The clip's name is not written anywhere.
 */
fun main() {
    val run = try {
        M0Run(M0Settings.fromEnvironment(System.getenv())).also { it.preflight() }
    } catch (refused: IllegalArgumentException) {
        System.err.println("M0 run not started: ${refused.message}")
        exitProcess(2)
    }
    // exitProcess: AWT (the clipboard) and the Access Bridge client keep threads that would hold the JVM open.
    val result = runCatching { run.run() }
    result.onSuccess { println("M0 run finished. Results: $it") }
    result.onFailure { System.err.println("M0 run failed: ${it.message}\nResults: ${run.folder}") }
    exitProcess(if (result.isSuccess) 0 else 1)
}

/** One M0 run's settings, read from the environment and checked before SkyWing starts. */
internal class M0Settings private constructor(
    val clip: Path,
    val out: Path,
    val label: String,
    val vrr: Boolean,
    val onAc: Boolean,
    val warmupSeconds: Long,
    val windowSeconds: Long,
    val gpu: String?,
) {
    companion object {
        private val LABEL = Regex("[A-Za-z0-9_-]{1,40}")

        /** Throws IllegalArgumentException, with what to change, for a setup that breaks the M0 rules. */
        fun fromEnvironment(environment: Map<String, String>, workingDir: Path = Path.of("").toAbsolutePath()): M0Settings {
            fun value(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun required(name: String, what: String) = requireNotNull(value(name)) { "Set $name to $what." }
            fun seconds(name: String, default: Long): Long {
                val text = value(name) ?: return default
                val seconds = text.toLongOrNull()
                require(seconds != null && seconds in 5..7_200) { "$name must be a number of seconds from 5 to 7200." }
                return seconds
            }

            val image = Path.of(required("SKYWING_TEST_IMAGE", "the installed SkyWing folder"))
            require(image.resolve("SkyWing.exe").isRegularFile()) { "SKYWING_TEST_IMAGE has no SkyWing.exe." }
            for (name in listOf("SKYWING_VS_SHARE_FRAMES", "SKYWING_D3D11_COMPAT")) {
                require(value(name) == null) { "Unset $name: M0 runs use frame sharing and the normal swap chain." }
            }
            val clipText = required("SKYWING_M0_CLIP", "a video on a local drive")
            // Checked before the file is touched, so a network drive is never woken.
            require(!NetworkPaths.isNetwork(clipText)) { "SKYWING_M0_CLIP is on a network drive or share. M0 runs play local files." }
            val clip = Path.of(clipText).toAbsolutePath().normalize()
            require(clip.isRegularFile()) { "SKYWING_M0_CLIP is not a file." }

            val outText = required("SKYWING_M0_OUT", "a results folder outside the checkout and outside OneDrive")
            require(!NetworkPaths.isNetwork(outText)) { "SKYWING_M0_OUT is on a network drive or share." }
            val out = Path.of(outText).toAbsolutePath().normalize()
            val checkout = generateSequence(workingDir.toAbsolutePath().normalize()) { it.parent }.firstOrNull { it.resolve(".git").exists() }
            require(checkout == null || !out.isInside(checkout)) { "SKYWING_M0_OUT is inside the checkout. Results stay out of the repository." }
            val oneDrive = listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial").mapNotNull(::value).map(Path::of)
            require(oneDrive.none { out.isInside(it) } && out.none { it.toString().startsWith("OneDrive", ignoreCase = true) }) {
                "SKYWING_M0_OUT is in OneDrive, which would upload the results while they are written."
            }

            val label = required("SKYWING_M0_LABEL", "a short run name such as laptop-run-b")
            require(LABEL.matches(label)) { "SKYWING_M0_LABEL may only hold letters, digits, '-' and '_', at most 40." }
            val vrr = requireNotNull(required("SKYWING_M0_VRR", "true or false").toBooleanStrictOrNull()) { "SKYWING_M0_VRR must be true or false." }
            val power = required("SKYWING_M0_POWER", "ac or battery")
            require(power == "ac" || power == "battery") { "SKYWING_M0_POWER must be ac or battery." }
            return M0Settings(clip, out, label, vrr, power == "ac", seconds("SKYWING_M0_WARMUP", 60), seconds("SKYWING_M0_WINDOW", 600), value("SKYWING_GPU"))
        }
    }
}

/** True when this path is [folder] or inside it. Windows paths, so case doesn't matter. */
private fun Path.isInside(folder: Path): Boolean {
    fun text(path: Path) = path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT).trimEnd('\\') + "\\"
    return text(this).startsWith(text(folder))
}

/** One measurement: [preflight], then [run]. */
internal class M0Run(private val settings: M0Settings) {
    val folder: Path = settings.out.resolve(LocalDateTime.now().format(STAMP) + "-" + settings.label)
    private val summary = mutableListOf<String>()

    /** Refuses (IllegalArgumentException) to start while another SkyWing runs or the machine is on the wrong power. */
    fun preflight() {
        val others = skyWingProcesses()
        require(others.isEmpty()) { "Close SkyWing first: ${others.size} SkyWing process(es) are running, and the run counts every one." }
        val probe = Probe.take()
        require(probe.onAc != null) { "The battery state can't be read (root\\wmi BatteryStatus)." }
        require(probe.onAc == settings.onAc) { "The machine is on ${if (probe.onAc) "AC power" else "battery"}, not ${if (settings.onAc) "AC power" else "battery"}." }
    }

    /** Measures once and writes the results folder, which it returns. Throws when the run could not be completed. */
    fun run(): Path {
        folder.createDirectories()
        val clipboard = Clipboard.text()
        try {
            line("SkyWing M0 measurement: ${settings.label}")
            line("Started: ${LocalDateTime.now().format(DATE_TIME)}")
            line("Image: ${SkyWingTestApp.image}")
            line("Clip: a local file, %.2f GiB (its name is not recorded)".format(Locale.ROOT, settings.clip.fileSize() / GIB))
            line("SKYWING_GPU: ${settings.gpu ?: "unset"}")
            line("Seeded: motion on; 2×; Balanced; G-SYNC / FreeSync ${settings.vrr}; lower automatically false")
            val app = SkyWingTestApp.launch("m0-${settings.label}", args = listOf(settings.clip.toString())) { root, _ -> seed(root) }
            try {
                measure(app)
            } finally {
                app.close(keepData = true)
                line("Exited by itself after WM_CLOSE: ${app.exitedGracefully}")
                runCatching { app.dataDir.resolve("app-output.txt").copyTo(folder.resolve("app-output.txt")) }
                app.close(keepData = false)
            }
            return folder
        } catch (stopped: Throwable) {
            line("STOPPED: ${stopped.message?.lineSequence()?.firstOrNull()}")
            folder.resolve("failure.txt").writeText(stopped.stackTraceToString())
            throw stopped
        } finally {
            folder.resolve("summary.txt").writeText(summary.joinToString("\n", postfix = "\n"))
            if (clipboard != null) Clipboard.write(clipboard)
        }
    }

    private fun line(text: String) {
        summary += text
        println(text)
    }

    /** Motion on at 2× Balanced for every video class, as section 2 sets it, and the run's G-SYNC / FreeSync choice. */
    private fun seed(root: Preferences) = root.node(MotionStatus.PLAYBACK_NODE).run {
        put("smoothing", "MOTION")
        for (size in listOf("1080", "1440", "4k")) for (range in listOf("sdr", "hdr")) {
            putInt("motion.$size-$range.stop", 0)
            put("motion.$size-$range.quality", "BALANCED")
        }
        putBoolean("motion.vrr", settings.vrr)
        putBoolean("motion.lowerAutomatically", false)
    }

    private fun measure(app: SkyWingTestApp) {
        val player = PlayerControls(app)
        player.waitAdvancing("the clip", minStepPercent = 0.0)
        val holding = app.waitForHolding()
        line("Playing at: ${clock()}; holding $holding fps")
        val monitor = Win32.monitorBounds(app.mainWindow())
        line("Monitor: ${monitor.right - monitor.left}×${monitor.bottom - monitor.top}")
        player.press("Toggle full screen (F)")
        val screen = FullScreen(app, monitor)
        app.waitUntil("a SkyWing window to cover the monitor") { screen.held() }
        line("Full screen at: ${clock()}")
        // Windows keeps a new window behind the active one when someone has just used another program. The taskbar and
        // that program then stay over the video and Windows composes them, which changes what the run measures.
        check(awaitForeground(app, timeoutMs = 10_000)) {
            "SkyWing isn't the active window (${programName(Win32.foregroundProcess())} is), so other windows cover the " +
                "video. Windows does this when another program was used just before SkyWing started. Run it again " +
                "and leave the PC alone from the start."
        }

        val warmup = Watch(app, screen).until(System.currentTimeMillis() + settings.warmupSeconds * 1000, progress = null)
        line("Warm-up: ${settings.warmupSeconds} s; ${warmup.describe()}")
        val start = probe(app, "start")
        val reportA = copyReport(app, screen, "A")
        val window = Watch(app, screen).until(start.at + settings.windowSeconds * 1000, folder.resolve("progress.txt"))
        val end = probe(app, "end")
        val reportB = copyReport(app, screen, "B")
        line("Window disturbances: ${window.describe()}")
        record(start, end, ReportNumbers(reportA), ReportNumbers(reportB), reportB, holding)
    }

    /** Whether a window of this run's SkyWing becomes the foreground window within [timeoutMs]. */
    private fun awaitForeground(app: SkyWingTestApp, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (Win32.foregroundProcess()?.let { it in app.processIds() } == true) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(250)
        }
    }

    /** Reads the SkyWing processes and the battery, and checks that every SkyWing process is the one this run started. */
    private fun probe(app: SkyWingTestApp, moment: String): Probe {
        val probe = Probe.take()
        val ours = app.processIds()
        check(probe.ids.isNotEmpty() && ours.containsAll(probe.ids)) { "A SkyWing process that this run did not start was running at the $moment of the window." }
        line("Windows power mode at the $moment: ${probe.powerMode(settings.onAc)}")
        return probe
    }

    /** Copies one playback report with the player's own button, keeps it, and leaves the player full screen again. */
    private fun copyReport(app: SkyWingTestApp, screen: FullScreen, name: String): String {
        val marker = "SkyWing M0 harness ${System.nanoTime()}"
        Clipboard.write(marker)
        Win32.postKey(screen.keyTarget(), Win32.VK_SHIFT, Win32.SCAN_SHIFT)
        // A new PlayerControls: its control bar is the full-screen window's.
        PlayerControls(app).press("Motion options")
        app.activate("Copy playback report", PlayerControls.BUTTON)
        val report = Clipboard.await("report $name on the clipboard") { it != marker && it.startsWith("SkyWing ") }
        app.activate("Done", PlayerControls.BUTTON)
        app.waitUntil("the Motion options window to close") { s -> s.nodes.none { ControlQuery.matches(it, "Motion smoothing", CHECK_BOX) } }
        folder.resolve("report-$name.txt").writeText(report)
        line("Report $name copied at ${clock()}")
        if (!screen.awaitHeld(3_000)) {
            line("Full screen was left after report $name; entering it again")
            PlayerControls(app).press("Toggle full screen (F)")
            app.waitUntil("a SkyWing window to cover the monitor again") { screen.held() }
        }
        return report
    }

    private fun record(start: Probe, end: Probe, a: ReportNumbers, b: ReportNumbers, reportB: String, holding: Double) {
        val seconds = (end.at - start.at) / 1000.0
        line("Window: ${clock(start.at)} to ${clock(end.at)} (%.1f s)".format(Locale.ROOT, seconds))
        line("SkyWing processes: ${start.ids.size} at the start, ${end.ids.size} at the end")
        line("Process CPU: %.2f cores".format(Locale.ROOT, (end.cpuSeconds - start.cpuSeconds) / seconds))
        line("Private memory, MB: %,.0f at the start, %,.0f at the end".format(Locale.ROOT, start.privateBytes / MIB, end.privateBytes / MIB))
        val used = if (start.batteryMwh != null && end.batteryMwh != null) start.batteryMwh - end.batteryMwh else null
        line(when {
            end.onAc == true -> "Battery: on AC power, not measured"
            used != null && start.fullMwh != null && start.fullMwh > 0 -> "Battery: %.1f W; %.1f %% per hour (%d mWh used)"
                .format(Locale.ROOT, used / 1000.0 * 3600 / seconds, used * 100.0 / start.fullMwh * 3600 / seconds, used)
            else -> "Battery: unavailable"
        })
        val reportLines = listOf("SkyWing ", "Build:", "Preferred motion GPU:", "Renderer:", "Display:", "VRR capability:", "File:", "Codec:", "Engine:", "Multiplier:")
        reportB.lineSequence().filter { text -> reportLines.any(text::startsWith) }.forEach { line("Report B: $it") }
        line("Filter processing throughput (report B): ${b.throughput ?: "unavailable"} fps")
        if (a.callbacks != null && b.callbacks != null) {
            line("Filter output callbacks: %d to %d, %.1f a second (holding %s fps)".format(Locale.ROOT, a.callbacks, b.callbacks, (b.callbacks - a.callbacks) / seconds, holding))
        }
        if (a.frameDrops != null && b.frameDrops != null && a.decoderDrops != null && b.decoderDrops != null) {
            val drops = (b.frameDrops - a.frameDrops) + (b.decoderDrops - a.decoderDrops)
            line("Drops in the window (video + decoder): $drops (frame-drop-count ${a.frameDrops} to ${b.frameDrops}; decoder-frame-drop-count ${a.decoderDrops} to ${b.decoderDrops})")
            line("Drops per minute: %.1f".format(Locale.ROOT, drops / (seconds / 60)))
        }
        line("Restarts (Stall recovery attempts, report B): ${b.restarts ?: "unavailable"}")
        line("Graph generations, report A to B: ${a.generations ?: "?"} to ${b.generations ?: "?"}")
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val CHECK_BOX = "check box"
        const val MIB = 1024.0 * 1024.0
        const val GIB = MIB * 1024.0

        fun skyWingProcesses(): List<ProcessHandle> = ProcessHandle.allProcesses().filter { process ->
            process.info().command().map { Path.of(it).fileName.toString().equals("SkyWing.exe", ignoreCase = true) }.orElse(false)
        }.toList()
    }
}

/** Local time of day, as HWiNFO's CSV log writes it. */
private fun clock(epochMillis: Long = System.currentTimeMillis()): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** The SkyWing window that covers [monitor] in full screen. */
private class FullScreen(private val app: SkyWingTestApp, private val monitor: Win32.Bounds) {
    private var window: Long? = null

    /** True while a SkyWing window covers the monitor. One GetWindowRect while that window is known. */
    fun held(): Boolean {
        window?.let { if (covers(it)) return true }
        window = app.windows().firstOrNull(::covers)
        return window != null
    }

    fun awaitHeld(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!held()) {
            if (System.currentTimeMillis() > deadline) return false
            Thread.sleep(150)
        }
        return true
    }

    /** Where a key goes: the full-screen window, or the main window when SkyWing is not full screen. */
    fun keyTarget(): Long = if (held()) window!! else app.mainWindow()

    private fun covers(hwnd: Long) = runCatching { Win32.bounds(hwnd) == monitor }.getOrDefault(false)
}

/**
 * What disturbed SkyWing over a stretch of time, sampled once a second with Win32 calls only (no Access Bridge):
 * another program in the foreground, full screen left, or keyboard or mouse input (input posted to a window, as
 * the harness posts Shift, isn't counted).
 */
private class Watch(private val app: SkyWingTestApp, private val screen: FullScreen) {
    private var foregroundLost = 0
    private val takenBy = linkedSetOf<String>()
    private var fullScreenLost = 0
    private var inputSeconds = 0
    private var firstInput: String? = null

    fun until(endMillis: Long, progress: Path?): Watch {
        val total = (endMillis - System.currentTimeMillis()) / 1000
        var processes = app.processIds()
        var lastInput = Win32.lastInputTick()
        var elapsed = 0
        progress?.writeText("Window started at ${clock()}; 0 of $total s\n")
        while (System.currentTimeMillis() < endMillis) {
            Thread.sleep(minOf(1_000L, endMillis - System.currentTimeMillis()).coerceAtLeast(1))
            check(app.isRunning) { "SkyWing exited during the run" }
            elapsed++
            val foreground = Win32.foregroundProcess()
            if (foreground == null || foreground !in processes) {
                processes = app.processIds()
                if (foreground == null || foreground !in processes) {
                    foregroundLost++
                    if (takenBy.size < 5) takenBy += programName(foreground)
                }
            }
            if (!screen.held()) fullScreenLost++
            val input = Win32.lastInputTick()
            if (input != lastInput) {
                inputSeconds++
                if (firstInput == null) firstInput = clock()
                lastInput = input
            }
            if (progress != null && elapsed % 60 == 0) progress.writeText("At ${clock()}: $elapsed of $total s; ${describe()}\n")
        }
        progress?.writeText("Window ended at ${clock()}; ${describe()}\n")
        return this
    }

    fun describe(): String = buildString {
        append("foreground lost $foregroundLost s")
        if (takenBy.isNotEmpty()) append(" (to ${takenBy.joinToString()})")
        append("; full screen lost $fullScreenLost s; keyboard or mouse input in $inputSeconds s")
        firstInput?.let { append(", first at $it") }
    }
}

/** The program file name of the process that owns the foreground window, for the run's notes. */
private fun programName(pid: Long?): String = pid?.let { id ->
    ProcessHandle.of(id).flatMap { it.info().command() }.map { Path.of(it).fileName.toString() }.orElse("process $id")
} ?: "no window"

/**
 * One reading of the SkyWing processes and the battery, made as the section 2 snippet makes it: PowerShell's
 * Get-Process (TotalProcessorTime and PrivateMemorySize64, summed over every process named SkyWing) and the
 * root\wmi battery classes. [at] is when the processes were read. Read only.
 */
private class Probe(
    val at: Long,
    val ids: Set<Long>,
    val cpuSeconds: Double,
    val privateBytes: Long,
    val batteryMwh: Long?,
    val onAc: Boolean?,
    val fullMwh: Long?,
    private val overlayAc: String?,
    private val overlayDc: String?,
) {
    /** Settings → System → Power's "Power mode", from the overlay scheme Windows keeps for AC or battery. */
    fun powerMode(onAc: Boolean): String = when (val guid = (if (onAc) overlayAc else overlayDc)?.lowercase(Locale.ROOT)) {
        null, "" -> "unavailable"
        "00000000-0000-0000-0000-000000000000" -> "Balanced"
        "ded574b5-45a0-4f42-8737-46345c09c238" -> "Best performance"
        "961cc777-2547-4f9d-8174-7d86181b8a7a" -> "Best power efficiency"
        else -> "unknown overlay $guid"
    }

    companion object {
        private val SCRIPT = """
            ${'$'}c = [Globalization.CultureInfo]::InvariantCulture
            ${'$'}p = @(Get-Process -Name SkyWing -ErrorAction SilentlyContinue)
            ${'$'}cpu = 0.0; ${'$'}memory = [long]0
            foreach (${'$'}x in ${'$'}p) { ${'$'}cpu += ${'$'}x.TotalProcessorTime.TotalSeconds; ${'$'}memory += ${'$'}x.PrivateMemorySize64 }
            'at=' + [DateTimeOffset]::Now.ToUnixTimeMilliseconds().ToString(${'$'}c)
            'ids=' + ((${'$'}p | ForEach-Object { ${'$'}_.Id }) -join ',')
            'cpu=' + ${'$'}cpu.ToString('R', ${'$'}c)
            'memory=' + ${'$'}memory.ToString(${'$'}c)
            ${'$'}status = @(Get-CimInstance -Namespace root\wmi -ClassName BatteryStatus -ErrorAction SilentlyContinue)
            if (${'$'}status) {
                'mWh=' + ([double](${'$'}status | Measure-Object RemainingCapacity -Sum).Sum).ToString('0', ${'$'}c)
                'ac=' + [bool](${'$'}status | Where-Object PowerOnline)
                ${'$'}full = @(Get-CimInstance -Namespace root\wmi -ClassName BatteryFullChargedCapacity -ErrorAction SilentlyContinue)
                if (${'$'}full) { 'fullmWh=' + ([double](${'$'}full | Measure-Object FullChargedCapacity -Sum).Sum).ToString('0', ${'$'}c) }
            }
            ${'$'}k = Get-ItemProperty -Path 'HKLM:\SYSTEM\CurrentControlSet\Control\Power\User\PowerSchemes' -ErrorAction SilentlyContinue
            'overlayAc=' + ${'$'}k.ActiveOverlayAcPowerScheme
            'overlayDc=' + ${'$'}k.ActiveOverlayDcPowerScheme
        """.trimIndent()

        fun take(): Probe {
            val encoded = Base64.getEncoder().encodeToString(SCRIPT.toByteArray(Charsets.UTF_16LE))
            val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "The process probe failed: $output" }
            val values = output.lineSequence().mapNotNull { text ->
                text.split('=', limit = 2).takeIf { it.size == 2 }?.let { (key, value) -> key.trim() to value.trim() }
            }.toMap()
            fun present(key: String) = values[key]?.takeIf { it.isNotEmpty() }
            return Probe(
                at = values.getValue("at").toLong(),
                ids = values["ids"].orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.toSet(),
                cpuSeconds = values.getValue("cpu").toDouble(),
                privateBytes = values.getValue("memory").toLong(),
                batteryMwh = present("mWh")?.toLong(),
                onAc = present("ac")?.equals("True", ignoreCase = true),
                fullMwh = present("fullmWh")?.toLong(),
                overlayAc = present("overlayAc"),
                overlayDc = present("overlayDc"),
            )
        }
    }
}

/** The counters the M0 table needs, read from one playback report (EmbeddedMpv.playbackReport). */
private class ReportNumbers(private val report: String) {
    private fun long(pattern: String) = Regex(pattern, RegexOption.MULTILINE).find(report)?.groupValues?.get(1)?.toLongOrNull()
    val frameDrops = long("^frame-drop-count: (\\d+)")
    val decoderDrops = long("^decoder-frame-drop-count: (\\d+)")
    val callbacks = long("^Filter output callbacks: (\\d+)")
    val generations = long("observed graph generations: (\\d+)")
    val restarts = long("^Stall recovery attempts: (\\d+)")
    val throughput = Regex("^Filter processing throughput: ([0-9.]+) fps", RegexOption.MULTILINE).find(report)?.groupValues?.get(1)
}

/** Text on the system clipboard, where the player's "Copy playback report" button puts the report. */
private object Clipboard {
    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    /** The clipboard's text, or null when it holds something else or nothing. */
    fun text(): String? = whenFree {
        try {
            clipboard.getData(DataFlavor.stringFlavor) as String
        } catch (notText: UnsupportedFlavorException) {
            null
        } catch (changed: IOException) {
            null
        }
    }

    fun write(text: String) = whenFree { clipboard.setContents(StringSelection(text), null) }

    fun await(what: String, timeoutMs: Long = 10_000, accept: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            text()?.takeIf(accept)?.let { return it }
            check(System.currentTimeMillis() < deadline) { "Timed out after $timeoutMs ms waiting for $what" }
            Thread.sleep(100)
        }
    }

    /** Another program can hold the clipboard open for a moment; Java then throws IllegalStateException. */
    private fun <T> whenFree(block: () -> T): T {
        repeat(30) {
            try {
                return block()
            } catch (busy: IllegalStateException) {
                Thread.sleep(100)
            }
        }
        return block()
    }
}
