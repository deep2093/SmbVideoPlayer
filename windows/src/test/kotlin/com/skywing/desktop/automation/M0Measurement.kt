package com.skywing.desktop.automation

import com.skywing.desktop.NetworkPaths
import com.skywing.desktop.playback.MotionQuality
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
import java.util.concurrent.CompletableFuture
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
 * or changed. It plays a local clip from the start, full screen, with motion on at 2× in the run's quality, leaves it
 * alone for the warm-up, and then measures a window:
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
 * - SKYWING_M0_VRR: true or false, written as SkyWing's VRR setting ("My screen uses G-SYNC / FreeSync" in 0.1.101,
 *   "Let VRR control refresh (fullscreen)" from M2), or auto to leave it unset: 0.1.101 then detects it from the
 *   display, and M2 follows the screen (on where it can vary its refresh, owner decision of 25 November).
 * - SKYWING_M0_SCREEN_SIZE: on or off, SkyWing's "Smooth at screen size" (M2, plan 5.7 item 4); off when unset.
 * - SKYWING_M0_QUALITY: the motion quality (Fastest, Fast, Balanced, Better or Best); Balanced when unset.
 * - SKYWING_M0_POWER: ac or battery, what the machine must be on.
 * - SKYWING_M0_WARMUP and SKYWING_M0_WINDOW: seconds; 60 and 600 when unset.
 * SKYWING_GPU passes through to SkyWing (laptop run B).
 *
 * The results folder gets summary.txt, report-A.txt, report-B.txt, progress.txt and SkyWing's app-output.txt, or
 * failure.txt when the run stopped. The clip's name is not written anywhere. `M0Handheld.kt` runs the handheld table
 * with this class.
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
    result.onSuccess { println("M0 run finished. Results: ${it.folder}") }
    result.onFailure { System.err.println("M0 run failed: ${it.message}\nResults: ${run.folder}") }
    exitProcess(if (result.isSuccess) 0 else 1)
}

/** One M0 run's settings, read from the environment and checked before SkyWing starts. */
internal class M0Settings private constructor(
    val clip: Path,
    val out: Path,
    val label: String,
    /** SkyWing's VRR setting; null leaves it unset (0.1.101 detects it, M2 follows the screen). */
    val vrr: Boolean?,
    /** "Smooth at screen size" (M2); 0.1.101 has no such setting and ignores it. */
    val screenSize: Boolean,
    val onAc: Boolean,
    val quality: MotionQuality,
    val warmupSeconds: Long,
    val windowSeconds: Long,
    val gpu: String?,
) {
    /** Motion on at 2× in [quality] for every video class, as section 2 sets it, "Lower automatically" off, [vrr] and [screenSize]. */
    fun seed(root: Preferences) = seedMotion(root, quality, vrr, screenSize)

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
            require(!inOneDrive(out, environment)) { "SKYWING_M0_OUT is in OneDrive, which would upload the results while they are written." }

            val label = required("SKYWING_M0_LABEL", "a short run name such as laptop-run-b")
            require(LABEL.matches(label)) { "SKYWING_M0_LABEL may only hold letters, digits, '-' and '_', at most 40." }
            val vrrText = required("SKYWING_M0_VRR", "true, false or auto")
            val vrr = vrrText.toBooleanStrictOrNull()
            require(vrr != null || vrrText == "auto") { "SKYWING_M0_VRR must be true, false or auto." }
            val power = required("SKYWING_M0_POWER", "ac or battery")
            require(power == "ac" || power == "battery") { "SKYWING_M0_POWER must be ac or battery." }
            val qualityText = value("SKYWING_M0_QUALITY") ?: MotionQuality.BALANCED.label
            val quality = requireNotNull(
                MotionQuality.entries.firstOrNull { qualityText.equals(it.label, ignoreCase = true) || qualityText.equals(it.name, ignoreCase = true) },
            ) { "SKYWING_M0_QUALITY must be one of ${MotionQuality.entries.joinToString { it.label }}." }
            val screenSizeText = value("SKYWING_M0_SCREEN_SIZE") ?: "off"
            require(screenSizeText == "on" || screenSizeText == "off") { "SKYWING_M0_SCREEN_SIZE must be on or off." }
            return M0Settings(
                clip, out, label, vrr, screenSizeText == "on", power == "ac", quality,
                seconds("SKYWING_M0_WARMUP", 60), seconds("SKYWING_M0_WINDOW", 600), value("SKYWING_GPU"),
            )
        }
    }
}

/**
 * Motion on at 2× in [quality] for every video class (stop 0), "Lower automatically" off, so SkyWing never lowers the
 * rate by itself, the VRR setting set to [vrr] (null: left unset), and "Smooth at screen size" set to [screenSize].
 */
internal fun seedMotion(root: Preferences, quality: MotionQuality, vrr: Boolean?, screenSize: Boolean = false) {
    val playback = root.node(MotionStatus.PLAYBACK_NODE)
    playback.put("smoothing", "MOTION")
    for (size in listOf("1080", "1440", "4k")) for (range in listOf("sdr", "hdr")) {
        playback.putInt("motion.$size-$range.stop", 0)
        playback.put("motion.$size-$range.quality", quality.name)
    }
    vrr?.let { playback.putBoolean("motion.vrr", it) }
    playback.putBoolean("motion.lowerAutomatically", false)
    playback.putBoolean("motion.screenSize", screenSize)
}

/** Every running SkyWing.exe, whoever started it. The runners count every one, so they start only when none runs. */
internal fun skyWingProcesses(): List<ProcessHandle> = ProcessHandle.allProcesses().filter { process ->
    process.info().command().map { Path.of(it).fileName.toString().equals("SkyWing.exe", ignoreCase = true) }.orElse(false)
}.toList()

/** True when this path is [folder] or inside it. Windows paths, so case doesn't matter. */
private fun Path.isInside(folder: Path): Boolean {
    fun text(path: Path) = path.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT).trimEnd('\\') + "\\"
    return text(this).startsWith(text(folder))
}

/** True when [path] is in OneDrive (a folder its environment variables name, or one called OneDrive…), which uploads files as they are written. */
internal fun inOneDrive(path: Path, environment: Map<String, String>): Boolean {
    val roots = listOf("OneDrive", "OneDriveConsumer", "OneDriveCommercial").mapNotNull { environment[it]?.trim()?.takeIf(String::isNotEmpty) }
    val absolute = path.toAbsolutePath().normalize()
    return roots.any { absolute.isInside(Path.of(it)) } || absolute.any { it.toString().startsWith("OneDrive", ignoreCase = true) }
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
        require(probe.onAc == settings.onAc) { "The machine is ${power(probe.onAc)}, not ${power(settings.onAc)}." }
    }

    /** Measures once, writes the results folder and returns what it measured. Throws when the run could not be completed. */
    fun run(): M0Outcome {
        folder.createDirectories()
        val clipboard = Clipboard.text()
        try {
            line("SkyWing M0 measurement: ${settings.label}")
            line("Started: ${LocalDateTime.now().format(DATE_TIME)}")
            line("Image: ${SkyWingTestApp.image}")
            line("Clip: a local file, %.2f GiB (its name is not recorded)".format(Locale.ROOT, settings.clip.fileSize() / GIB))
            line("SKYWING_GPU: ${settings.gpu ?: "unset"}")
            line("Seeded: motion on; 2×; ${settings.quality.label}; VRR ${settings.vrr ?: "unset"}; lower automatically false; smooth at screen size ${if (settings.screenSize) "on" else "off"}")
            val app = SkyWingTestApp.launch("m0-${settings.label}", args = listOf(settings.clip.toString())) { root, _ -> settings.seed(root) }
            return try {
                measure(app)
            } finally {
                app.close(keepData = true)
                line("Exited by itself after WM_CLOSE: ${app.exitedGracefully}")
                runCatching { app.dataDir.resolve("app-output.txt").copyTo(folder.resolve("app-output.txt")) }
                app.close(keepData = false)
            }
        } catch (stopped: SmoothingStopped) {
            line("Result: ${stopped.message}")
            throw stopped
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

    private fun measure(app: SkyWingTestApp): M0Outcome {
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
        check(app.awaitForeground(timeoutMs = 10_000)) {
            "SkyWing isn't the active window (${programName(Win32.foregroundProcess())} is), so other windows cover the " +
                "video. Windows does this when another program was used just before SkyWing started. Run it again " +
                "and leave the PC alone from the start."
        }

        val warmup = Watch(app, screen).until(System.currentTimeMillis() + settings.warmupSeconds * 1000, progress = null)
        line("Warm-up: ${settings.warmupSeconds} s; ${warmup.describe()}")
        val start = probe(app, "start")
        val reportA = copyReport(app, screen, "A")
            ?: throw SmoothingStopped("the warm-up", warmup.disturbances(), start.powerMode(settings.onAc))
        val window = Watch(app, screen).until(start.at + settings.windowSeconds * 1000, folder.resolve("progress.txt"))
        val end = probe(app, "end")
        val reportB = copyReport(app, screen, "B")
            ?: throw SmoothingStopped("the measured window", window.disturbances(), end.powerMode(settings.onAc))
        line("Window disturbances: ${window.describe()}")
        return record(start, end, PlaybackReport(reportA), PlaybackReport(reportB), holding, window.disturbances())
    }

    /**
     * Reads the SkyWing processes and the battery, and checks that every SkyWing process is the one this run started and
     * that the machine is still on the power the run measures.
     */
    private fun probe(app: SkyWingTestApp, moment: String): Probe {
        val probe = Probe.take()
        val ours = app.processIds()
        check(probe.ids.isNotEmpty() && ours.containsAll(probe.ids)) { "A SkyWing process that this run did not start was running at the $moment of the window." }
        check(probe.onAc == settings.onAc) { "The power changed: at the $moment of the window the machine was ${power(probe.onAc)}, not ${power(settings.onAc)}." }
        line("Windows power mode at the $moment: ${probe.powerMode(settings.onAc)}")
        return probe
    }

    /**
     * Copies one playback report with the player's own button, keeps it, and leaves the player full screen again.
     * Returns null when Motion options shows smoothing off, which has no report button; the run then ends.
     */
    private fun copyReport(app: SkyWingTestApp, screen: FullScreen, name: String): String? {
        val report = copyPlaybackReport(app, screen)
        if (report == null) {
            line("Smoothing was off when report $name was due, at ${clock()}")
            return null
        }
        folder.resolve("report-$name.txt").writeText(report)
        line("Report $name copied at ${clock()}")
        if (screen.enterAgainIfLeft()) line("Full screen was left after report $name; entered it again")
        return report
    }

    private fun record(start: Probe, end: Probe, a: PlaybackReport, b: PlaybackReport, holding: Double, disturbances: Disturbances): M0Outcome {
        val seconds = (end.at - start.at) / 1000.0
        val cores = (end.cpuSeconds - start.cpuSeconds) / seconds
        line("Window: ${clock(start.at)} to ${clock(end.at)} (%.1f s)".format(Locale.ROOT, seconds))
        line("SkyWing processes: ${start.ids.size} at the start, ${end.ids.size} at the end")
        line("Process CPU: %.2f cores".format(Locale.ROOT, cores))
        line("Private memory, MB: %,.0f at the start, %,.0f at the end".format(Locale.ROOT, start.privateBytes / MIB, end.privateBytes / MIB))
        val used = if (start.batteryMwh != null && end.batteryMwh != null) start.batteryMwh - end.batteryMwh else null
        val battery = if (end.onAc != true && used != null && start.fullMwh != null && start.fullMwh > 0) {
            BatteryDrain(watts = used / 1000.0 * 3600 / seconds, percentPerHour = used * 100.0 / start.fullMwh * 3600 / seconds)
        } else {
            null
        }
        line(when {
            end.onAc == true -> "Battery: on AC power, not measured"
            battery != null -> "Battery: %.1f W; %.1f %% per hour (%d mWh used)".format(Locale.ROOT, battery.watts, battery.percentPerHour, used)
            else -> "Battery: unavailable"
        })
        val reportLines = listOf("SkyWing ", "Build:", "Preferred motion GPU:", "Renderer:", "Display:", "VRR capability:", "File:", "Codec:", "Engine:", "Multiplier:")
        b.text.lineSequence().filter { text -> reportLines.any(text::startsWith) }.forEach { line("Report B: $it") }
        line("Filter processing throughput (report B): ${b.throughput ?: "unavailable"} fps")
        if (a.callbacks != null && b.callbacks != null) {
            line("Filter output callbacks: %d to %d, %.1f a second (holding %s fps)".format(Locale.ROOT, a.callbacks, b.callbacks, (b.callbacks - a.callbacks) / seconds, holding))
        }
        val drops = if (a.frameDrops != null && b.frameDrops != null && a.decoderDrops != null && b.decoderDrops != null) {
            (b.frameDrops - a.frameDrops) + (b.decoderDrops - a.decoderDrops)
        } else {
            null
        }
        if (drops != null) {
            line("Drops in the window (video + decoder): $drops (frame-drop-count ${a.frameDrops} to ${b.frameDrops}; decoder-frame-drop-count ${a.decoderDrops} to ${b.decoderDrops})")
            line("Drops per minute: %.1f".format(Locale.ROOT, drops / (seconds / 60)))
        }
        line("Restarts (Stall recovery attempts, report B): ${b.restarts ?: "unavailable"}")
        line("Graph generations, report A to B: ${a.generations ?: "?"} to ${b.generations ?: "?"}")
        return M0Outcome(
            folder = folder,
            windowSeconds = seconds,
            cores = cores,
            memoryStartMb = start.privateBytes / MIB,
            memoryEndMb = end.privateBytes / MIB,
            battery = battery,
            drops = drops,
            restarts = b.restarts,
            generations = a.generations to b.generations,
            powerModes = start.powerMode(settings.onAc) to end.powerMode(settings.onAc),
            disturbances = disturbances,
            reportB = b,
        )
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val MIB = 1024.0 * 1024.0
        const val GIB = MIB * 1024.0

        fun power(onAc: Boolean?) = when (onAc) {
            true -> "on AC power"
            false -> "on battery"
            null -> "in a power state Windows doesn't report"
        }
    }
}

/** What one M0 run measured. A number the reports didn't give is null. */
internal data class M0Outcome(
    val folder: Path,
    val windowSeconds: Double,
    val cores: Double,
    val memoryStartMb: Double,
    val memoryEndMb: Double,
    /** Null on AC power, or when Windows didn't report the battery. */
    val battery: BatteryDrain?,
    /** Video plus decoder drops from report A to report B. */
    val drops: Long?,
    val restarts: Long?,
    /** Graph generations in reports A and B. */
    val generations: Pair<Long?, Long?>,
    /** The Windows power mode at the start and at the end of the window. */
    val powerModes: Pair<String, String>,
    val disturbances: Disturbances,
    val reportB: PlaybackReport,
) {
    val dropsPerMinute: Double? get() = drops?.let { it / (windowSeconds / 60) }
}

internal data class BatteryDrain(val watts: Double, val percentPerHour: Double)

/**
 * SkyWing turned smoothing off by itself during [during]. With "Lower automatically" off and 2× the lowest stop, it does
 * that only when it can't keep up ("Cannot keep up at 2×; smoothing stopped" in `EmbeddedMpv.kt`). [disturbances]
 * cover the time up to the moment the runner found it off; [powerMode] is the Windows power mode then.
 */
internal class SmoothingStopped(val during: String, val disturbances: Disturbances, val powerMode: String) :
    Exception("SkyWing turned smoothing off during $during: it couldn't keep up at 2×")

/** What disturbed a measured window ([Watch]); [description] is the summary line's text. */
internal data class Disturbances(val foregroundLostSeconds: Int, val fullScreenLostSeconds: Int, val inputSeconds: Int, val description: String) {
    /** Another program was in front or SkyWing left full screen, so the numbers don't describe full-screen playback. */
    val spoiled: Boolean get() = foregroundLostSeconds > 0 || fullScreenLostSeconds > 0
}

/** Local time of day, as HWiNFO's CSV log writes it. */
internal fun clock(epochMillis: Long = System.currentTimeMillis()): String =
    LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** Whether a window of this SkyWing becomes the foreground window within [timeoutMs]. Posted keys reach it only then. */
internal fun SkyWingTestApp.awaitForeground(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        if (Win32.foregroundProcess()?.let { it in processIds() } == true) return true
        if (System.currentTimeMillis() >= deadline) return false
        Thread.sleep(250)
    }
}

/**
 * Copies one playback report with the player's own button (Motion options, then "Copy playback report") and closes
 * Motion options again. Returns null when Motion options shows smoothing off, which has no report button. The report is
 * left on the clipboard; the caller puts back what was there before.
 */
internal fun copyPlaybackReport(app: SkyWingTestApp, screen: FullScreen): String? {
    val marker = "SkyWing harness ${System.nanoTime()}"
    Clipboard.write(marker)
    Win32.postKey(screen.keyTarget(), Win32.VK_SHIFT, Win32.SCAN_SHIFT)
    // A new PlayerControls: its control bar is the full-screen window's.
    PlayerControls(app).press("Motion options")
    val report = if (app.find("Motion smoothing", CHECK_BOX).checked) {
        app.activate("Copy playback report", PlayerControls.BUTTON)
        Clipboard.await("the playback report on the clipboard") { it != marker && it.startsWith("SkyWing ") }
    } else {
        null
    }
    app.activate("Done", PlayerControls.BUTTON)
    app.waitUntil("the Motion options window to close") { s -> s.nodes.none { ControlQuery.matches(it, "Motion smoothing", CHECK_BOX) } }
    return report
}

private const val CHECK_BOX = "check box"

/** The SkyWing window that covers [monitor] in full screen. */
internal class FullScreen(private val app: SkyWingTestApp, private val monitor: Win32.Bounds) {
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

    /** Enters full screen again when SkyWing left it (closing Motion options can do that). True when it had to. */
    fun enterAgainIfLeft(): Boolean {
        if (awaitHeld(3_000)) return false
        PlayerControls(app).press("Toggle full screen (F)")
        app.waitUntil("a SkyWing window to cover the monitor again") { held() }
        return true
    }

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

    fun disturbances() = Disturbances(foregroundLost, fullScreenLost, inputSeconds, describe())

    fun describe(): String = buildString {
        append("foreground lost $foregroundLost s")
        if (takenBy.isNotEmpty()) append(" (to ${takenBy.joinToString()})")
        append("; full screen lost $fullScreenLost s; keyboard or mouse input in $inputSeconds s")
        firstInput?.let { append(", first at $it") }
    }
}

/** The program file name of the process that owns the foreground window, for the run's notes. */
internal fun programName(pid: Long?): String = pid?.let { id ->
    ProcessHandle.of(id).flatMap { it.info().command() }.map { Path.of(it).fileName.toString() }.orElse("process $id")
} ?: "no window"

/**
 * One reading of the SkyWing processes and the battery, made as the section 2 snippet makes it: PowerShell's
 * Get-Process (TotalProcessorTime and PrivateMemorySize64, summed over every process named SkyWing) and the
 * root\wmi battery classes. [at] is when the processes were read. Read only.
 */
internal class Probe(
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
            val values = runPowerShell(SCRIPT, "The process probe").lineSequence().mapNotNull { text ->
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

/**
 * Runs [script] in Windows PowerShell, without a profile, and returns its output. [what] names it if it fails. The
 * error stream isn't part of the result: with no console to show them, PowerShell writes its progress records there
 * (as CLIXML), such as "Preparing modules for first use." on a device's first PowerShell use.
 */
internal fun runPowerShell(script: String, what: String): String {
    val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
    val process = ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).start()
    // Read at the same time as the output, so a full error pipe can't stop the script.
    val errors = CompletableFuture.supplyAsync { process.errorStream.bufferedReader().readText() }
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) {
        "$what failed: $output${runCatching { errors.get(10, TimeUnit.SECONDS) }.getOrDefault("")}"
    }
    return output
}

/** One playback report (EmbeddedMpv.playbackReport): the counters the M0 tables need and the lines that describe the run. */
internal class PlaybackReport(val text: String) {
    private fun find(pattern: String) = Regex(pattern, RegexOption.MULTILINE).find(text)
    private fun long(pattern: String) = find(pattern)?.groupValues?.get(1)?.toLongOrNull()
    private fun word(pattern: String) = find(pattern)?.groupValues?.get(1)?.trim()
    val frameDrops = long("^frame-drop-count: (\\d+)")
    val decoderDrops = long("^decoder-frame-drop-count: (\\d+)")
    val callbacks = long("^Filter output callbacks: (\\d+)")
    val generations = long("observed graph generations: (\\d+)")
    val restarts = long("^Stall recovery attempts: (\\d+)")
    val throughput = word("^Filter processing throughput: ([0-9.]+) fps")
    /** The video's width and height, from the `File:` line. */
    val size: Pair<Int, Int>? = find("^File: [^;]*; (\\d+) × (\\d+);")?.destructured?.let { (width, height) -> width.toInt() to height.toInt() }
    val sourceFps = word("^File: .*; source ([0-9.]+) fps")
    /** mpv's transfer function (`video-params/gamma`): pq or hlg for HDR video. */
    val transfer = word("^Transfer: ([^;]+);")
    val quality = word("^Engine: .*; quality: ([^;]+);")
    val target = word("^Engine: .*; target: ([0-9.]+) fps")
    val multiplier = word("^Multiplier: ([^;]+);")

    /** The rest of the first line that starts with [prefix] (such as `Renderer: `), or null. */
    fun line(prefix: String): String? = text.lineSequence().firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()
}

/** Text on the system clipboard, where the player's "Copy playback report" button puts the report. */
internal object Clipboard {
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
