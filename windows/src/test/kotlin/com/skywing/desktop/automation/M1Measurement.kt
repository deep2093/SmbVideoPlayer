package com.skywing.desktop.automation

import com.skywing.desktop.ThumbnailStore
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

/**
 * The M1 before/after runner (docs/modernise/M1-RESULTS.md): the same measurements on two SkyWing app images, the
 * build before M1 ("before") and the one after it ("after"), on one machine, with nobody at it. Nothing is installed:
 * each image runs from the runner folder on isolated data ([SkyWingTestApp]), so the installed SkyWing, its library and
 * its settings stay as they are. SkyWing logs its own numbers with SKYWING_PERF_LOG ([com.skywing.desktop.PerfMonitor]);
 * the runner reads that log, PowerShell's CPU time and private memory of the SkyWing process, and nothing else.
 *
 * The library is [M1Library.VIDEOS] hard links to the runner's 50 short test clips (1080p H.264 and 4K HEVC, made
 * with ffmpeg on the laptop by `gradlew -p windows m1Media`), added under This PC. Each image gets three starts:
 * 1. first start: the first scan of the library, thumbnails made in a fixed window, then scrolling the Videos page
 *    while thumbnails are still being made
 * 2. everyday start, on the same data with every thumbnail already made: start time, the startup check, idle CPU on
 *    the start page, then scrolling the Videos page and the memory after it
 * 3. the same with the Adaptive Glow theme, whose wallpaper follows the covers on screen: scrolling the Videos page
 *
 * Scrolling is posted mouse-wheel messages ([Win32.postWheel]), not input: the pointer stays where it is. A start
 * doesn't count when someone used the keyboard, mouse or touch screen, or another window covered SkyWing's grid; the
 * runner then tries that image once more. `gradlew -p windows m1Runner` builds the portable folder: a Java runtime,
 * the jars, both images, the clips, `Run M1.cmd` and `README.txt`. `Run M1.cmd trial` does short windows instead,
 * which tests the runner, not the device. Results go to `results\<device>`: `M1-table.md`, and per image
 * `numbers.txt` and SkyWing's logs, which hold times and counts only.
 *
 * SKYWING_M1_BEFORE, SKYWING_M1_AFTER and SKYWING_M1_MEDIA name the images and the clips instead of the runner's
 * folders; SKYWING_M1_ONLY=before (or after) measures one image and keeps the other's numbers.
 */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "prepare") {
        M1Media.prepare(Path.of(args[1]))
        exitProcess(0)
    }
    val trial = args.any { it.equals("trial", ignoreCase = true) }
    // exitProcess: AWT and the Access Bridge client keep threads that would hold the JVM open.
    val code = try {
        HandheldSystem.keepConsoleRunning()
        HandheldSystem.keepAwake()
        M1Runner(trial, System.getenv(), Path.of("").toAbsolutePath()).runAll()
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

/** How long each window of a start lasts. */
internal data class M1Timing(val thumbnailSeconds: Long, val idleSeconds: Long, val scrollSeconds: Long, val settleSeconds: Long) {
    companion object {
        val FULL = M1Timing(thumbnailSeconds = 60, idleSeconds = 30, scrollSeconds = 30, settleSeconds = 10)
        val TRIAL = M1Timing(thumbnailSeconds = 15, idleSeconds = 8, scrollSeconds = 8, settleSeconds = 3)
    }
}

/** One line of SkyWing's measuring log: a second's numbers ([PerfRow]) or a moment ([PerfMark]). */
internal class PerfLog(val seconds: List<PerfRow>, val marks: List<PerfMark>) {
    /** The first mark called [name] logged after [afterMs] that [matches]. */
    fun mark(name: String, afterMs: Long = Long.MIN_VALUE, matches: (PerfMark) -> Boolean = { true }): PerfMark? =
        marks.firstOrNull { it.name == name && it.atMs > afterMs && matches(it) }

    /** The seconds that began at or after [fromMs] and ended by [toMs] (times since SkyWing started). */
    fun between(fromMs: Long, toMs: Long): List<PerfRow> = seconds.filter { it.atMs - 1000 >= fromMs && it.atMs <= toMs }

    /** The last second that ended by [atMs]. */
    fun at(atMs: Long): PerfRow? = seconds.lastOrNull { it.atMs <= atMs }

    companion object {
        /** Reads the log; a last line SkyWing is still writing (no line end yet) is left out. */
        fun parse(text: String): PerfLog {
            val seconds = ArrayList<PerfRow>()
            val marks = ArrayList<PerfMark>()
            for (line in text.substringBeforeLast('\n', "").lineSequence()) {
                val fields = line.trimEnd('\r').split('\t')
                val at = fields.firstOrNull()?.toLongOrNull() ?: continue
                when (fields.getOrNull(1)) {
                    "second" -> seconds += PerfRow(at, values(fields.drop(2)))
                    "mark" -> fields.getOrNull(2)?.let { name ->
                        marks += PerfMark(at, name, values(fields.drop(3)).mapNotNull { (key, value) -> value.toDoubleOrNull()?.let { key to it } }.toMap())
                    }
                }
            }
            return PerfLog(seconds, marks)
        }

        private fun values(fields: List<String>): Map<String, String> =
            fields.mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()

        fun read(file: Path): PerfLog = if (file.isRegularFile()) parse(file.readText()) else PerfLog(emptyList(), emptyList())
    }
}

internal data class PerfRow(val atMs: Long, val values: Map<String, String>) {
    fun number(key: String): Double? = values[key]?.toDoubleOrNull()
    val frames: Long get() = values["frames"]?.toLongOrNull() ?: 0
    fun gauge(name: String): Long? = values["gauge.$name"]?.toLongOrNull()
}

internal data class PerfMark(val atMs: Long, val name: String, val values: Map<String, Double>)

/** What the seconds of one window say about frames and the UI thread. */
internal data class UiSummary(
    val seconds: Int,
    val framesPerSecond: Double,
    /** The median of the seconds' frame-time p95. */
    val frameP95Ms: Double?,
    val worstFrameP95Ms: Double?,
    val longestUiTaskMs: Double,
    val uiTasksOver8: Long,
    val uiBusyPercent: Double,
    /** Null when the build's log has no recompositions (a build before they were counted). */
    val recompositionsPerSecond: Double?,
) {
    companion object {
        fun of(rows: List<PerfRow>): UiSummary {
            require(rows.isNotEmpty()) { "No seconds to sum up" }
            val p95s = rows.mapNotNull { it.number("frame_p95_ms") }.sorted()
            val middle = p95s.size / 2
            return UiSummary(
                seconds = rows.size,
                framesPerSecond = rows.sumOf { it.frames }.toDouble() / rows.size,
                frameP95Ms = if (p95s.isEmpty()) null else if (p95s.size % 2 == 1) p95s[middle] else (p95s[middle - 1] + p95s[middle]) / 2,
                worstFrameP95Ms = p95s.lastOrNull(),
                longestUiTaskMs = rows.maxOf { it.number("ui_max_ms") ?: 0.0 },
                uiTasksOver8 = rows.sumOf { it.number("ui_over8")?.toLong() ?: 0L },
                uiBusyPercent = rows.sumOf { it.number("ui_busy_ms") ?: 0.0 } / (rows.size * 10.0),
                recompositionsPerSecond = rows.mapNotNull { it.number("recompositions") }
                    .takeIf { it.size == rows.size }?.average(),
            )
        }
    }
}

/** The test clips the runner carries, made with ffmpeg on the laptop: 40 at 1080p H.264, 10 at 4K HEVC, each with its picture. */
internal object M1Media {
    private const val HD_CLIPS = 40
    private const val UHD_CLIPS = 10
    private val SOURCES = listOf("testsrc2", "smptehdbars", "mandelbrot", "life", "cellauto", "gradients")

    fun clips(folder: Path): List<Path> = (1..HD_CLIPS + UHD_CLIPS).map { folder.resolve("clips").resolve(String.format(Locale.ROOT, "clip-%02d.mp4", it)) }

    fun picture(folder: Path, clip: Path): Path = folder.resolve("pictures").resolve(clip.name.substringBeforeLast('.') + ".jpg")

    /** Makes the clips and pictures that aren't there yet. Needs ffmpeg with libx264 and libx265 on PATH. */
    fun prepare(folder: Path) {
        folder.resolve("clips").createDirectories()
        folder.resolve("pictures").createDirectories()
        for ((index, clip) in clips(folder).withIndex()) {
            if (!clip.isRegularFile() || clip.fileSize() == 0L) {
                val uhd = index >= HD_CLIPS
                val source = SOURCES[index % SOURCES.size] + "=size=" + (if (uhd) "3840x2160" else "1920x1080") + ":rate=24"
                val codec = if (uhd) {
                    listOf("-c:v", "libx265", "-preset", "ultrafast", "-crf", "32", "-tag:v", "hvc1", "-x265-params", "log-level=error")
                } else {
                    listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "30")
                }
                val partial = clip.resolveSibling(clip.name + ".part.mp4")
                runFfmpeg(
                    listOf("-f", "lavfi", "-i", source, "-t", if (uhd) "2" else "3", "-vf", "hue=h=${index * 47 % 360},format=yuv420p") +
                        codec + listOf("-movflags", "+faststart", partial.toString()),
                )
                Files.move(partial, clip, StandardCopyOption.REPLACE_EXISTING)
            }
            val picture = picture(folder, clip)
            if (!picture.isRegularFile()) runFfmpeg(listOf("-ss", "0.5", "-i", clip.toString(), "-frames:v", "1", "-vf", "scale=480:-2", "-q:v", "4", picture.toString()))
        }
    }

}

/** Runs ffmpeg from PATH with [args], quietly and overwriting, and fails with its output when it fails. */
internal fun runFfmpeg(args: List<String>, timeoutMinutes: Long = 10) {
    val process = ProcessBuilder(listOf("ffmpeg", "-hide_banner", "-v", "error", "-y") + args).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(timeoutMinutes, TimeUnit.MINUTES) && process.exitValue() == 0) { "ffmpeg failed: $output" }
}

/** The test library: [VIDEOS] hard links to the clips, made in name order, so the first names use every clip once. */
internal object M1Library {
    const val VIDEOS = 5_000

    fun linkName(index: Int): String = String.format(Locale.ROOT, "video %05d.mp4", index + 1)

    fun clipIndex(index: Int, clips: Int): Int = index % clips

    /** Makes the links in [folder] that aren't there yet and returns all of them. Hard links need an NTFS drive. */
    fun build(clips: List<Path>, folder: Path): List<Path> {
        folder.createDirectories()
        return (0 until VIDEOS).map { index ->
            val link = folder.resolve(linkName(index))
            if (!link.exists()) {
                try {
                    Files.createLink(link, clips[clipIndex(index, clips.size)])
                } catch (e: Exception) {
                    throw IllegalArgumentException("The test library can't be made (${e.javaClass.simpleName}). Put the runner folder on the device's own NTFS drive.", e)
                }
            }
            link
        }
    }

    /** The picture file SkyWing keeps for [video] in a data folder whose LocalAppData is [localAppData]. */
    fun thumbnailFile(localAppData: Path, video: Path): Path {
        val key = ThumbnailStore.keyOf(video.toAbsolutePath().normalize().toString(), video.toFile().lastModified())
        return localAppData.resolve("SkyWing").resolve("library").resolve("thumbs").resolve("$key.jpg")
    }

    /** Gives every video its clip's picture as its thumbnail, so SkyWing makes none. */
    fun seedThumbnails(videos: List<Path>, pictures: List<Path>, localAppData: Path) {
        for ((index, video) in videos.withIndex()) {
            val target = thumbnailFile(localAppData, video)
            if (target.isRegularFile()) continue
            target.parent.createDirectories()
            Files.copy(pictures[clipIndex(index, pictures.size)], target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

/** The rows of M1-table.md: a label and the key of the value in each image's numbers.txt. */
internal val M1_MEASURES: List<Pair<String, String>> = listOf(
    "SkyWing build" to "build",
    "Power" to "power",
    "Start to first page, first start (s)" to "start_first_s",
    "Start to first page, everyday start (s)" to "start_everyday_s",
    "Library on screen, everyday start (s)" to "library_everyday_s",
    "First scan of 5,000 videos (s)" to "scan_first_s",
    "Startup check of 5,000 videos (s)" to "scan_everyday_s",
    "Thumbnails made per minute" to "thumbs_per_min",
    "CPU cores while making thumbnails" to "thumbs_cores",
    "Idle on the start page: CPU cores" to "idle_cores",
    "Idle on the start page: UI thread busy (%)" to "idle_busy_pct",
    "Idle on the start page: recompositions per second" to "idle_recomp",
) + scrollRows("Scrolling Videos", "scroll") + scrollRows("Scrolling Videos, Adaptive Glow", "adaptive") +
    scrollRows("Scrolling while thumbnails are made", "busy") + listOf(
        "Memory after scrolling: private (MB)" to "memory_private_mb",
        "Memory after scrolling: Java heap (MB)" to "memory_heap_mb",
        "Memory after scrolling: pictures (MB)" to "memory_pictures_mb",
    )

private fun scrollRows(title: String, key: String) = listOf(
    "$title: frames per second" to "${key}_fps",
    "$title: frame time p95 (ms)" to "${key}_p95_ms",
    "$title: worst second's p95 (ms)" to "${key}_worst_p95_ms",
    "$title: UI tasks over 8 ms" to "${key}_over8",
    "$title: longest UI task (ms)" to "${key}_longest_ms",
    "$title: UI thread busy (%)" to "${key}_busy_pct",
    "$title: recompositions per second" to "${key}_recomp",
    "$title: CPU cores" to "${key}_cores",
)

/** M1-table.md: one column per image in [passes] (its numbers.txt), a dash where a value wasn't measured. */
internal fun m1Table(device: String, display: String, passes: Map<String, Map<String, String>>): String = buildString {
    appendLine("# M1 measurements on $device")
    appendLine()
    appendLine("Display: $display. One column per SkyWing image; – means not measured. See M1-RESULTS.md for what each row measures.")
    appendLine()
    appendLine("| Measure | ${passes.keys.joinToString(" | ")} |")
    appendLine("|---|${passes.keys.joinToString("") { "---|" }}")
    for ((label, key) in M1_MEASURES) appendLine("| $label | ${passes.values.joinToString(" | ") { it[key] ?: "–" }} |")
}

/** One image's three starts. Returns its numbers, or throws [Spoiled] when the machine wasn't left alone. */
private class M1Pass(
    private val name: String,
    private val image: Path,
    private val library: Path,
    private val videos: List<Path>,
    private val pictures: List<Path>,
    private val folder: Path,
    private val timing: M1Timing,
) {
    private val numbers = LinkedHashMap<String, String>()
    private val disturbances = mutableListOf<String>()
    private val inputAtStart = Win32.lastInputTick()

    /** The isolated data of the three starts, deleted at the end. */
    private var data: Path? = null

    fun run(): Map<String, String> {
        folder.createDirectories()
        for (stage in STAGES) folder.resolve("perf-$stage.log").deleteIfExists()
        try {
            val data = firstStart()
            M1Library.seedThumbnails(videos, pictures, data.resolve("LocalAppData"))
            everydayStart(data)
            adaptiveStart(data)
        } finally {
            data?.toFile()?.deleteRecursively()
        }
        if (Win32.lastInputTick() != inputAtStart) disturbances += "someone used the keyboard, mouse or touch screen"
        if (disturbances.isNotEmpty()) throw Spoiled(disturbances.distinct().joinToString("; "))
        return numbers
    }

    /** Start 1: the first scan, thumbnails in a fixed window, then scrolling while they are made. Returns the data folder. */
    private fun firstStart(): Path {
        val (app, log) = launch("first", dataDir = null, theme = null)
        data = app.dataDir
        try {
            val started = startOf(app)
            put("start_first_s", seconds(awaitMark(app, log, "first frame", 60_000).atMs))
            val scan = awaitMark(app, log, "scan finished", SCAN_TIMEOUT_MS)
            check(scan.values["succeeded"] == 1.0) { "The first scan didn't succeed." }
            put("scan_first_s", two(scan.values.getValue("seconds")))
            awaitMark(app, log, "library loaded", 60_000, afterMs = scan.atMs) { (it.values["videos"] ?: 0.0) >= videos.size }
            Thread.sleep(2_000)
            val a = probe(app)
            Thread.sleep(timing.thumbnailSeconds * 1000)
            val b = probe(app)
            Thread.sleep(1_500)
            val parsed = PerfLog.read(log)
            val made = (parsed.at(b.at - started)?.gauge("thumbnails_made") ?: 0) - (parsed.at(a.at - started)?.gauge("thumbnails_made") ?: 0)
            put("thumbs_per_min", one(made * 60_000.0 / (b.at - a.at)))
            put("thumbs_cores", two(cores(a, b)))
            app.activate("Videos", PAGE_TAB)
            Thread.sleep(2_000)
            scroll(app, log, started, "busy")
            check(app.isRunning) { "SkyWing closed during the first start." }
            return app.dataDir
        } finally {
            app.close(keepData = true)
        }
    }

    /** Start 2: every thumbnail made; start time, the startup check, idle, then scrolling and the memory after it. */
    private fun everydayStart(data: Path) {
        val (app, log) = launch("everyday", dataDir = data, theme = null)
        try {
            val started = startOf(app)
            put("start_everyday_s", seconds(awaitMark(app, log, "first frame", 60_000).atMs))
            val loaded = awaitMark(app, log, "library loaded", 60_000) { (it.values["videos"] ?: 0.0) >= videos.size }
            put("library_everyday_s", seconds(loaded.atMs))
            val scan = awaitMark(app, log, "scan finished", SCAN_TIMEOUT_MS)
            put("scan_everyday_s", two(scan.values.getValue("seconds")))
            Thread.sleep(timing.settleSeconds * 1000)
            val a = probe(app)
            Thread.sleep(timing.idleSeconds * 1000)
            val b = probe(app)
            Thread.sleep(1_500)
            put("idle_cores", two(cores(a, b)))
            val idle = PerfLog.read(log).between(a.at - started, b.at - started)
            if (idle.isNotEmpty()) UiSummary.of(idle).let { summary ->
                put("idle_busy_pct", one(summary.uiBusyPercent))
                summary.recompositionsPerSecond?.let { put("idle_recomp", one(it)) }
            }
            app.activate("Videos", PAGE_TAB)
            Thread.sleep(2_000)
            val end = scroll(app, log, started, "scroll")
            val last = PerfLog.read(log).at(end.at - started)
            check((last?.gauge("thumbnails_made") ?: 0) == 0L) { "SkyWing made thumbnails on the everyday start, so the pictures the runner gave it weren't used." }
            put("memory_private_mb", one(end.privateBytes / MIB))
            last?.number("heap_mb")?.let { put("memory_heap_mb", one(it)) }
            if (last != null) put("memory_pictures_mb", ((last.gauge("picture_memory_thumbnails_mb") ?: 0) + (last.gauge("picture_memory_covers_mb") ?: 0)).toString())
        } finally {
            app.close(keepData = true)
        }
    }

    /** Start 3: the Adaptive Glow theme, scrolling the Videos page. */
    private fun adaptiveStart(data: Path) {
        val (app, log) = launch("adaptive", dataDir = data, theme = ADAPTIVE_THEME)
        try {
            val started = startOf(app)
            awaitMark(app, log, "first frame", 60_000)
            awaitMark(app, log, "scan finished", SCAN_TIMEOUT_MS)
            Thread.sleep(timing.settleSeconds * 1000)
            app.activate("Videos", PAGE_TAB)
            Thread.sleep(2_000)
            scroll(app, log, started, "adaptive")
        } finally {
            app.close(keepData = true)
        }
    }

    private fun launch(stage: String, dataDir: Path?, theme: String?): Pair<SkyWingTestApp, Path> {
        val log = folder.resolve("perf-$stage.log")
        val app = SkyWingTestApp.launch("m1-$name-$stage", dataDir = dataDir, image = image, extraEnvironment = mapOf("SKYWING_PERF_LOG" to log.toString())) { root, _ ->
            root.node("com/skywing/desktop/local-library-folders").apply {
                putInt("count", 1)
                put("folder.0", library.toString())
            }
            if (theme != null) root.node("com/skywing/desktop/appearance").put("theme", theme)
        }
        return app to log
    }

    /**
     * Scrolls the grid down for the scroll window with posted wheel turns, 12 a second, and keeps what SkyWing logged
     * under [key]. Returns the process reading taken at the end.
     */
    private fun scroll(app: SkyWingTestApp, log: Path, started: Long, key: String): Probe {
        val hwnd = app.mainWindow()
        val bounds = Win32.bounds(hwnd)
        val x = bounds.left + (bounds.right - bounds.left) * 6 / 10
        val y = bounds.top + (bounds.bottom - bounds.top) * 7 / 10
        val ours = app.processIds()
        val a = probe(app)
        val from = System.currentTimeMillis()
        var covered = 0
        var behind = 0
        while (System.currentTimeMillis() - from < timing.scrollSeconds * 1000) {
            if (Win32.processAt(x, y) !in ours) covered++
            if (Win32.foregroundProcess() !in ours) behind++
            Win32.postWheel(hwnd, x, y, -120)
            Thread.sleep(80)
        }
        val to = System.currentTimeMillis()
        val b = probe(app)
        Thread.sleep(1_500)
        if (covered > 0) disturbances += "another window covered SkyWing's grid while scrolling ($key)"
        if (behind > 0) disturbances += "SkyWing wasn't the front window while scrolling ($key)"
        val rows = PerfLog.read(log).between(from - started, to - started)
        check(rows.any { it.frames > 0 }) { "SkyWing drew no frames while scrolling ($key): the wheel turns didn't reach it." }
        val summary = UiSummary.of(rows)
        put("${key}_fps", one(summary.framesPerSecond))
        summary.frameP95Ms?.let { put("${key}_p95_ms", two(it)) }
        summary.worstFrameP95Ms?.let { put("${key}_worst_p95_ms", two(it)) }
        put("${key}_over8", summary.uiTasksOver8.toString())
        put("${key}_longest_ms", two(summary.longestUiTaskMs))
        put("${key}_busy_pct", one(summary.uiBusyPercent))
        summary.recompositionsPerSecond?.let { put("${key}_recomp", one(it)) }
        put("${key}_cores", two(cores(a, b)))
        return b
    }

    /** Waits until SkyWing logs the mark, and fails when it closes or the time is up. */
    private fun awaitMark(app: SkyWingTestApp, log: Path, mark: String, timeoutMs: Long, afterMs: Long = Long.MIN_VALUE, matches: (PerfMark) -> Boolean = { true }): PerfMark {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            PerfLog.read(log).mark(mark, afterMs, matches)?.let { return it }
            check(app.isRunning) { "SkyWing closed before it logged \"$mark\"." }
            check(System.currentTimeMillis() < deadline) { "SkyWing didn't log \"$mark\" within ${timeoutMs / 1000} s." }
            Thread.sleep(250)
        }
    }

    /** Reads the SkyWing processes and checks that every one is this run's. */
    private fun probe(app: SkyWingTestApp): Probe {
        val probe = Probe.take()
        check(probe.ids.isNotEmpty() && app.processIds().containsAll(probe.ids)) { "A SkyWing process that the runner did not start is running." }
        return probe
    }

    private fun put(key: String, value: String) {
        numbers[key] = value
    }

    /** Thrown when the machine wasn't left alone; the image is measured again. */
    class Spoiled(message: String) : Exception(message)

    private companion object {
        val STAGES = listOf("first", "everyday", "adaptive")
        const val PAGE_TAB = "page tab"
        const val ADAPTIVE_THEME = "ADAPTIVE_GLOW"
        const val SCAN_TIMEOUT_MS = 10 * 60 * 1000L
        const val MIB = 1024.0 * 1024.0

        /** When SkyWing's process started (epoch ms); its log counts from there. */
        fun startOf(app: SkyWingTestApp): Long = ProcessHandle.of(app.pid).flatMap { it.info().startInstant() }.map { it.toEpochMilli() }
            .orElseThrow { IllegalStateException("SkyWing's start time can't be read.") }

        fun cores(a: Probe, b: Probe) = (b.cpuSeconds - a.cpuSeconds) / ((b.at - a.at) / 1000.0)
        fun seconds(ms: Long) = two(ms / 1000.0)
        fun one(value: Double) = String.format(Locale.ROOT, "%.1f", value)
        fun two(value: Double) = String.format(Locale.ROOT, "%.2f", value)
    }
}

private class M1Runner(private val trial: Boolean, private val environment: Map<String, String>, private val runner: Path) {
    private val timing = if (trial) M1Timing.TRIAL else M1Timing.FULL
    private val product = registryValue(BIOS, "SystemProductName")
    private val device = listOfNotNull(registryValue(BIOS, "SystemManufacturer"), product).joinToString(" ").ifBlank { "this device" }
    private val results = runner.resolve("results").resolve(machineFolder(product, environment["COMPUTERNAME"])).let { if (trial) it.resolve("trial") else it }
    private val media = path("SKYWING_M1_MEDIA") ?: runner.resolve("media")
    private val images = listOf("before", "after").associateWith { name -> path("SKYWING_M1_${name.uppercase(Locale.ROOT)}") ?: runner.resolve("images").resolve(name) }
    private val only = environment["SKYWING_M1_ONLY"]?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun path(name: String) = environment[name]?.trim()?.takeIf { it.isNotEmpty() }?.let { Path.of(it).toAbsolutePath().normalize() }

    fun runAll(): Int {
        runnerFolderRefusal(runner, environment, HandheldSystem::removable, runner = "M1")?.let { throw IllegalArgumentException(it) }
        require(only == null || only in images) { "SKYWING_M1_ONLY is before or after, not $only." }
        results.createDirectories()
        logOutput(results.resolve("runner-log.txt"))
        System.setProperty(SkyWingTestApp.REPORTS_PROPERTY, results.resolve("diagnostics").toString())
        // Started again, it keeps an image it measured already (same build), unless SKYWING_M1_ONLY names that image.
        val todo = images.filterKeys { name -> if (only != null) name == only else numbersOf(name)["build"] != buildOf(name) }
        if (todo.isEmpty()) {
            writeTable(display())
            println("Both images are already measured. The table is in ${results.resolve("M1-table.md")}.")
            return 0
        }
        for ((name, image) in todo) require(image.resolve("SkyWing.exe").isRegularFile()) { "The $name image isn't in the runner folder (images\\$name\\SkyWing.exe)." }
        val clips = M1Media.clips(media)
        require(clips.all { it.isRegularFile() }) { "The test clips aren't in the runner folder (media\\clips)." }
        val pictures = clips.map { M1Media.picture(media, it) }
        require(pictures.all { it.isRegularFile() }) { "The test pictures aren't in the runner folder (media\\pictures)." }
        require(skyWingProcesses().isEmpty()) { "Close SkyWing first: the runner measures every SkyWing process." }
        val library = runner.resolve("work").resolve("library")
        val videos = M1Library.build(clips, library)
        val power = Probe.take()
        val display = display()
        val quietWait = quietWaitMs(HandheldSystem.foregroundLockTimeoutMs())
        println("SkyWing M1 ${if (trial) "trial" else "measurements"} on $device")
        println("Display: $display")
        println("Power: ${powerText(power)}")
        println("${todo.size} image(s) to measure, about ${todo.size * (if (trial) 3 else 6)} minutes. Don't touch the device until it beeps.")

        for ((index, entry) in todo.entries.withIndex()) {
            val (name, image) = entry
            if (index > 0) {
                println("Next image in ${COOL_DOWN_MS / 1000} s.")
                Thread.sleep(COOL_DOWN_MS)
            }
            val folder = results.resolve(name)
            var attempt = 1
            while (true) {
                waitUntilQuiet(quietWait)
                println()
                println("Measuring the $name image${if (attempt > 1) " again" else ""}. Don't touch the device.")
                val build = buildOf(name)
                val outcome = runCatching { M1Pass(name, image, library, videos, pictures, folder, timing).run() }
                val failure = outcome.exceptionOrNull()
                if (failure == null) {
                    val numbers = linkedMapOf("build" to build, "power" to powerText(Probe.take())) + outcome.getOrThrow()
                    folder.resolve("numbers.txt").writeText(numbers.entries.joinToString("") { "${it.key}=${it.value}\n" })
                    folder.resolve("failure.txt").deleteIfExists()
                    println("The $name image is done.")
                    break
                }
                folder.resolve("failure.txt").writeText("${failure.message}\n")
                if (failure !is M1Pass.Spoiled || attempt == 2) {
                    writeTable(display)
                    throw IllegalArgumentException("The $name image couldn't be measured: ${failure.message} (failure.txt in its results folder).")
                }
                println("That didn't count: ${failure.message}. Trying once more.")
                attempt++
            }
            writeTable(display)
        }
        HandheldSystem.beep(3)
        println()
        println("Done. The table is in ${results.resolve("M1-table.md")}. Bring back the whole results folder.")
        return 0
    }

    private fun writeTable(display: String) {
        results.resolve("M1-table.md").writeText(m1Table(device, display, images.keys.associateWith(::numbersOf)))
    }

    /** The numbers.txt of an image measured earlier, or nothing. */
    private fun numbersOf(name: String): Map<String, String> {
        val file = results.resolve(name).resolve("numbers.txt")
        if (!file.isRegularFile()) return emptyMap()
        return file.readLines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    /** The commit the runner's image was built from (images\<name>-build.txt). */
    private fun buildOf(name: String): String =
        runCatching { runner.resolve("images").resolve("$name-build.txt").readText().trim() }.getOrDefault("unknown")

    private fun display(): String {
        val mode = GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.displayMode
        return "${mode.width} × ${mode.height} at ${mode.refreshRate} Hz"
    }

    private fun powerText(probe: Probe): String = when (probe.onAc) {
        true -> "plugged in, " + probe.powerMode(true)
        false -> "on battery, " + probe.powerMode(false)
        null -> "no battery reading"
    }

    private companion object {
        const val BIOS = "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS"
        const val COOL_DOWN_MS = 60_000L
    }
}
