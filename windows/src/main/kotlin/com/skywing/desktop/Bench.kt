package com.skywing.desktop

import com.skywing.desktop.playback.EmbeddedMpv
import com.skywing.desktop.playback.MotionExperiment
import com.skywing.desktop.playback.MotionQuality
import com.skywing.desktop.playback.MotionSettings
import com.skywing.desktop.playback.MotionStops
import com.skywing.desktop.playback.PlaybackCsv
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStatus
import java.awt.Canvas
import java.awt.Color
import java.awt.Frame
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities

/**
 * The hidden benchmark mode (plan 5.6): `--bench <file> [--minutes 10] [--quality Fast] [--stop 2x] [--start 0]
 * [--out <folder>] [--experiment PotPlayer-like] [--screen-size on]`. Defaults are fixed so runs on different PCs compare.
 */
internal data class BenchOptions(
    val file: File,
    val minutes: Double = 10.0,
    val quality: MotionQuality = MotionQuality.DEFAULT,
    /** 0 = 2×, 1 = 2.5×, 2 = 3×, 3 = 4× ([com.skywing.desktop.playback.MotionStops]). */
    val stop: Int = 0,
    val startSeconds: Double = 0.0,
    /** Where the CSV and the report go; null: the usual playback-log folder. */
    val out: File? = null,
    /** A plan 5.7 experiment that replaces the preset's options; never saved. */
    val experiment: MotionExperiment? = null,
    /** "Smooth at screen size" (plan 5.7 item 4), in place of Settings' switch; off unless asked. */
    val screenSize: Boolean = false,
) {
    companion object {
        private val STOPS = listOf("2", "2.5", "3", "4")

        /** Null when [args] is not a bench run; a failure says what is wrong with the bench arguments. */
        fun parse(args: List<String>): Result<BenchOptions>? {
            if (args.firstOrNull() != "--bench") return null
            return runCatching {
                val file = args.getOrNull(1)?.takeIf { !it.startsWith("--") } ?: throw IllegalArgumentException("--bench needs a video file")
                var options = BenchOptions(File(file))
                var i = 2
                while (i < args.size) {
                    val value = args.getOrNull(i + 1)
                    options = when (args[i]) {
                        "--minutes" -> options.copy(minutes = value?.toDoubleOrNull()?.takeIf { it in 0.1..120.0 }
                            ?: throw IllegalArgumentException("--minutes must be a number from 0.1 to 120"))
                        "--quality" -> options.copy(quality = MotionQuality.entries.firstOrNull { it.label.equals(value, ignoreCase = true) }
                            ?: throw IllegalArgumentException("Unknown quality '$value': use Fastest, Fast, Balanced, Better or Best"))
                        "--stop" -> options.copy(stop = STOPS.indexOf(value?.lowercase()?.removeSuffix("x")?.removeSuffix("×")).takeIf { it >= 0 }
                            ?: throw IllegalArgumentException("Unknown stop '$value': use 2x, 2.5x, 3x or 4x"))
                        "--start" -> options.copy(startSeconds = value?.toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
                            ?: throw IllegalArgumentException("--start must be 0 or more seconds"))
                        "--out" -> options.copy(out = value?.let(::File) ?: throw IllegalArgumentException("--out needs a folder"))
                        "--screen-size" -> options.copy(screenSize = when (value?.lowercase()) {
                            "on" -> true
                            "off" -> false
                            else -> throw IllegalArgumentException("--screen-size must be on or off")
                        })
                        "--experiment" -> options.copy(experiment = MotionExperiment.find(value)
                            ?: throw IllegalArgumentException("Unknown experiment '$value': use " + MotionExperiment.entries.joinToString { it.label }))
                        else -> throw IllegalArgumentException("Unknown bench option '${args[i]}'")
                    }
                    i += 2
                }
                options
            }
        }
    }
}

/**
 * Runs the benchmark in a window of its own, apart from SkyWing's library and pages: the video plays full screen
 * from [BenchOptions.startSeconds] with the given smoothing, for [BenchOptions.minutes] once smoothing holds. Nothing
 * is saved to SkyWing's settings or library. It writes the per-minute CSV and `bench-<time>.txt` (the test report)
 * to [BenchOptions.out], or the usual playback-log folder. Closing the window ends the run early.
 */
internal object Bench {
    /** 0: smoothing held for the whole run; 1: it did not, or the run ended early; 2: the run could not start. */
    fun run(options: BenchOptions): Int {
        val folder = options.out ?: PlaybackCsv.folder()
        val started = LocalDateTime.now()
        val lines = mutableListOf(
            "SkyWing benchmark " + started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)),
            "Options: " + String.format(Locale.ROOT, "%.1f", options.minutes) + " min; quality " + MotionSettings(options.stop, options.quality, options.experiment).qualityLabel +
                "; stop " + MotionStops.labels[options.stop] + "; start " + String.format(Locale.ROOT, "%.1f", options.startSeconds) + " s" +
                "; smooth at screen size " + (if (options.screenSize) "on" else "off"),
        )
        var code = 2
        val player = EmbeddedMpv()
        player.benchSettings = MotionSettings(options.stop, options.quality, options.experiment)
        player.benchScreenSize = options.screenSize
        player.logFolder = folder
        lateinit var frame: Frame
        lateinit var canvas: Canvas
        lateinit var fullscreen: BorderlessFullscreen
        val closed = AtomicBoolean(false)
        SwingUtilities.invokeAndWait {
            frame = Frame("SkyWing benchmark")
            frame.background = Color.BLACK
            canvas = Canvas().apply { background = Color.BLACK }
            frame.add(canvas)
            frame.setSize(1280, 720)
            frame.setLocationRelativeTo(null)
            frame.addWindowListener(object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) = closed.set(true)
            })
            frame.isVisible = true
            fullscreen = BorderlessFullscreen(frame)
        }
        var report: String? = null
        try {
            code = measure(options, player, canvas, { on -> SwingUtilities.invokeAndWait { fullscreen.set(on) } }, closed, lines)
            val csv = player.finishLog()
            lines += "Per-minute CSV: " + (csv?.name ?: "none written (under 5 s of smoothing)")
            report = player.testReport(if (code == 0) "benchmark finished" else "benchmark ended early")
        } catch (failure: Exception) {
            lines += "The benchmark failed: " + (failure.message ?: failure.javaClass.simpleName)
        } finally {
            // Close on a thread of its own, so a hang in mpv's teardown cannot keep the benchmark open forever.
            val done = CountDownLatch(1)
            Thread({ player.close(); done.countDown() }, "SkyWing benchmark close").apply { isDaemon = true }.start()
            if (!done.await(30, TimeUnit.SECONDS)) { lines += "Closing the player took more than 30 s"; code = maxOf(code, 1) }
            SwingUtilities.invokeAndWait {
                if (fullscreen.isFullscreen) fullscreen.set(false)
                frame.dispose()
            }
        }
        lines += "On-screen frame times: not measured by SkyWing; windows/tools/bench.ps1 -PresentMon records them when you have the rights"
        lines += "Exit code: $code"
        report?.let { lines += ""; lines += it.trimEnd() }
        write(folder, started, lines)
        return code
    }

    /** Bad bench arguments: a start from Explorer or a shortcut has no console, so the reason goes in the report file. */
    fun reject(message: String): Int {
        write(PlaybackCsv.folder(), LocalDateTime.now(), listOf("SkyWing benchmark not started: $message", "Exit code: 2"))
        return 2
    }

    private fun write(folder: File, started: LocalDateTime, lines: List<String>) {
        runCatching {
            check(folder.isDirectory || folder.mkdirs())
            File(folder, "bench-" + started.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)) + ".txt")
                .writeText(lines.joinToString("\n") + "\n", Charsets.UTF_8)
        }.onFailure { System.err.println(lines.joinToString("\n")) }
    }

    private fun measure(options: BenchOptions, player: EmbeddedMpv, canvas: Canvas, fullscreen: (Boolean) -> Unit,
                        closed: AtomicBoolean, lines: MutableList<String>): Int {
        if (!options.file.isFile) { lines += "The video file was not found"; return 2 }
        if (!waitFor(10_000, closed) { canvas.isDisplayable }) { lines += "The benchmark window did not open"; return 2 }
        var attached = false
        SwingUtilities.invokeAndWait { attached = player.attach(canvas) }
        if (!attached) { lines += "The player could not start: " + player.lastError; return 2 }
        fullscreen(true)
        if (!player.open(options.file)) { lines += "The video could not be opened"; return 2 }
        if (!waitFor(20_000, closed) { player.playbackDuration() != null }) {
            lines += "The video did not load: " + (player.playbackFailure ?: "no duration after 20 s"); return 2
        }
        // The video must not end during the run: a minute to start smoothing, then the measured minutes.
        val needed = options.startSeconds + options.minutes * 60 + 60
        val duration = player.playbackDuration() ?: 0.0
        if (duration < needed) {
            lines += String.format(Locale.ROOT, "The video is %.0f s long; this run needs %.0f s (--start, --minutes and a minute to start smoothing)", duration, needed)
            return 2
        }
        if (options.startSeconds > 0) player.seekAbsolute(options.startSeconds)
        val opened = System.nanoTime()
        var status: SmoothingStatus = player.updateSmoothing(SmoothingMode.MOTION, true)
        while (!status.motionActive && !closed.get() && System.nanoTime() - opened < 60_000_000_000L) {
            Thread.sleep(500)
            status = player.updateSmoothing(SmoothingMode.MOTION, true)
        }
        if (!status.motionActive) { lines += "Smoothing did not start: " + status.detail; return 1 }
        lines += String.format(Locale.ROOT, "Smoothing held after %.1f s: %s", (System.nanoTime() - opened) / 1e9, status.detail)
        val start = System.nanoTime()
        val end = start + (options.minutes * 60_000_000_000L).toLong()
        while (System.nanoTime() < end) {
            if (closed.get()) { lines += "The window was closed"; return 1 }
            Thread.sleep(500)
            status = player.updateSmoothing(SmoothingMode.MOTION, true)
            if (!status.motionActive) {
                lines += String.format(Locale.ROOT, "Smoothing stopped after %.1f min: %s", (System.nanoTime() - start) / 6e10, status.detail)
                return 1
            }
        }
        lines += String.format(Locale.ROOT, "Smoothing held for %.1f min: %s", options.minutes, status.detail)
        return 0
    }

    private fun waitFor(timeoutMs: Long, closed: AtomicBoolean, condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline && !closed.get()) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return false
    }
}
