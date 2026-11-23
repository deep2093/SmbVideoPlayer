package com.skywing.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.Toolkit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.delay

/** One second of [PerfMonitor] numbers. Frame times are null when no frame loop ran long enough to time a frame. */
internal data class PerfSecond(
    val atMs: Long,
    val uiTasks: Int,
    val uiBusyMs: Double,
    val uiP95Ms: Double,
    val uiMaxMs: Double,
    val uiOver8: Int,
    val frames: Int,
    val frameP95Ms: Double?,
    val frameMaxMs: Double?,
    val stateChanges: Int,
    val heapUsedMb: Long,
    val heapMaxMb: Long,
    val gauges: Map<String, Long>,
) {
    /** "<ms since start>\tsecond\tkey=value..." with times in milliseconds. */
    fun logLine(): String = buildList {
        add(atMs.toString())
        add("second")
        add("ui_tasks=$uiTasks")
        add("ui_busy_ms=${twoPlaces(uiBusyMs)}")
        add("ui_p95_ms=${twoPlaces(uiP95Ms)}")
        add("ui_max_ms=${twoPlaces(uiMaxMs)}")
        add("ui_over8=$uiOver8")
        add("frames=$frames")
        add("frame_p95_ms=${frameP95Ms?.let(::twoPlaces).orEmpty()}")
        add("frame_max_ms=${frameMaxMs?.let(::twoPlaces).orEmpty()}")
        add("state_changes=$stateChanges")
        add("heap_mb=$heapUsedMb")
        add("heap_max_mb=$heapMaxMb")
        gauges.forEach { (name, value) -> add("gauge.${logKey(name)}=$value") }
    }.joinToString("\t")

    /** What the overlay shows. */
    fun overlayLines(): List<String> = buildList {
        add(if (frameMaxMs == null) "Frames: $frames/s (frame times while the overlay is open)"
            else "Frames: $frames/s; 95 % within ${twoPlaces(frameP95Ms!!)} ms; slowest ${twoPlaces(frameMaxMs)} ms")
        add("UI thread: busy ${(uiBusyMs / 10).toInt()} %; longest task ${twoPlaces(uiMaxMs)} ms; over 8 ms: $uiOver8")
        add("State changes: $stateChanges/s")
        add("Java heap: $heapUsedMb of $heapMaxMb MB")
        gauges.forEach { (name, value) -> add("$name: $value") }
    }
}

/** A mark in the log: "<ms since start>\tmark\t<name>\tkey=value...". Marks carry numbers only, never names or paths. */
internal fun perfMarkLine(atMs: Long, name: String, values: Map<String, Number>): String = buildList {
    add(atMs.toString())
    add("mark")
    add(name.replace(Regex("[\\t\\r\\n]+"), " ").replace(Regex(" {2,}"), " ").trim())
    values.forEach { (key, value) -> add("${logKey(key)}=${if (value is Double || value is Float) twoPlaces(value.toDouble()) else value}") }
}.joinToString("\t")

private fun twoPlaces(value: Double) = String.format(Locale.ROOT, "%.2f", value)

private fun logKey(name: String) = name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')

private class LongList {
    private var values = LongArray(256)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == values.size) values = values.copyOf(size * 2)
        values[size++] = value
    }

    fun sorted(): LongArray = values.copyOf(size).also { it.sort() }

    fun clear() { size = 0 }
}

/** The numbers of the current second. Not thread-safe: [PerfMonitor] guards it. */
internal class PerfWindow {
    private val tasks = LongList()
    private var busyNanos = 0L
    private var frames = 0
    private val intervals = LongList()
    private var lastFrame: Long? = null
    private var states = 0

    fun uiTask(nanos: Long) {
        tasks.add(nanos)
        busyNanos += nanos
    }

    /** The frame loop (re)started: the time since its last frame was a pause, not a frame. */
    fun frameLoopStarted() { lastFrame = null }

    fun frame(frameNanos: Long) {
        frames++
        lastFrame?.let { intervals.add(frameNanos - it) }
        lastFrame = frameNanos
    }

    fun stateChanges(count: Int) { states += count }

    /** Ends the second: returns its numbers and starts the next one. */
    fun roll(atMs: Long, heapUsedMb: Long, heapMaxMb: Long, gauges: Map<String, Long>): PerfSecond {
        val sortedTasks = tasks.sorted()
        val sortedIntervals = intervals.sorted()
        val second = PerfSecond(
            atMs = atMs,
            uiTasks = sortedTasks.size,
            uiBusyMs = busyNanos / MS,
            uiP95Ms = percentile95(sortedTasks)?.div(MS) ?: 0.0,
            uiMaxMs = sortedTasks.lastOrNull()?.div(MS) ?: 0.0,
            uiOver8 = sortedTasks.count { it > 8 * 1_000_000L },
            frames = frames,
            frameP95Ms = percentile95(sortedIntervals)?.div(MS),
            frameMaxMs = sortedIntervals.lastOrNull()?.div(MS),
            stateChanges = states,
            heapUsedMb = heapUsedMb,
            heapMaxMb = heapMaxMb,
            gauges = gauges,
        )
        tasks.clear()
        intervals.clear()
        busyNanos = 0
        frames = 0
        states = 0
        return second
    }

    private fun percentile95(sorted: LongArray): Double? =
        if (sorted.isEmpty()) null else sorted[(ceil(sorted.size * 0.95).toInt() - 1).coerceIn(0, sorted.size - 1)].toDouble()

    private companion object {
        const val MS = 1_000_000.0
    }
}

/**
 * The hidden performance overlay (Ctrl+Shift+F12) and the measuring log of test runs (plan 4.5). Nothing is recorded
 * until the overlay opens or the SKYWING_PERF_LOG environment variable names a file. From then on it keeps, each second:
 * - how long every task on the UI thread (the AWT event thread, where Compose works) took
 * - frame times, while the overlay is open or the mouse wheel turned in the last half second (timing frames asks for
 *   frames, which costs power, so the loop doesn't run otherwise)
 * - how many Compose state objects changed (thousands a second mean a recomposition storm)
 * - the Java heap in use, and the gauges parts of the app report (pictures in memory, thumbnails waiting)
 * The log gets one line a second and one per mark (first frame, library loaded, scan finished). It holds times and
 * counts only, never file names or paths.
 */
internal object PerfMonitor {
    private val logFile: Path? = System.getenv("SKYWING_PERF_LOG")?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Path.of(it) }.getOrNull() }
    private val startMillis: Long = ProcessHandle.current().info().startInstant()
        .map { it.toEpochMilli() }.orElse(System.currentTimeMillis())
    private val window = PerfWindow()
    private val gauges = ConcurrentHashMap<String, () -> Long>()
    private val logLock = Any()

    @Volatile var recording = false
        private set
    @Volatile private var lastWheel = 0L

    /** True when a test run asked for the log. */
    val logging: Boolean get() = logFile != null

    var overlay by mutableStateOf(false)
        private set
    var latest by mutableStateOf<PerfSecond?>(null)
        private set

    /** Milliseconds since the process started. */
    fun now(): Long = System.currentTimeMillis() - startMillis

    /** Starts recording when a test run asked for the log. Call once, before the window opens. */
    fun startIfLogging() {
        if (logging) start()
    }

    fun toggleOverlay() {
        overlay = !overlay
        if (overlay) start()
    }

    fun wantsFrames(): Boolean = overlay || (recording && logging && System.currentTimeMillis() - lastWheel < 500)

    fun mark(name: String, vararg values: Pair<String, Number>) {
        if (recording) write(perfMarkLine(now(), name, values.toMap()))
    }

    /** A number the overlay and the log show each second; [read] must be cheap and thread-safe. */
    fun gauge(name: String, read: () -> Long) {
        gauges[name] = read
    }

    /** The last second, for the playback report; null while nothing is recorded. */
    fun reportLine(): String? = latest?.takeIf { recording }?.let {
        "UI: busy ${(it.uiBusyMs / 10).toInt()} %; longest task ${twoPlaces(it.uiMaxMs)} ms; over 8 ms: ${it.uiOver8}; Java heap ${it.heapUsedMb} MB"
    }

    internal fun frameLoopStarted() = synchronized(window) { window.frameLoopStarted() }

    internal fun frame(nanos: Long) = synchronized(window) { window.frame(nanos) }

    private fun uiTask(nanos: Long) = synchronized(window) { window.uiTask(nanos) }

    @Synchronized
    private fun start() {
        if (recording) return
        recording = true
        Toolkit.getDefaultToolkit().systemEventQueue.push(TimedQueue())
        Toolkit.getDefaultToolkit().addAWTEventListener({ lastWheel = System.currentTimeMillis() }, AWTEvent.MOUSE_WHEEL_EVENT_MASK)
        Snapshot.registerApplyObserver { changed, _ -> synchronized(window) { window.stateChanges(changed.size) } }
        Thread({
            while (true) {
                Thread.sleep(1_000)
                val runtime = Runtime.getRuntime()
                val values = gauges.entries.sortedBy { it.key }.associate { (name, read) -> name to runCatching(read).getOrDefault(-1L) }
                val second = synchronized(window) {
                    window.roll(now(), (runtime.totalMemory() - runtime.freeMemory()) / MB, runtime.maxMemory() / MB, values)
                }
                Snapshot.withMutableSnapshot { latest = second }
                write(second.logLine())
            }
        }, "skywing-perf").apply { isDaemon = true }.start()
    }

    private fun write(line: String) {
        val file = logFile ?: return
        synchronized(logLock) {
            runCatching {
                file.parent?.let { Files.createDirectories(it) }
                Files.writeString(file, line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            }
        }
    }

    /** Times every event the UI thread runs, Compose frames included. Nested modal loops count in the outer task. */
    private class TimedQueue : EventQueue() {
        override fun dispatchEvent(event: AWTEvent) {
            val start = System.nanoTime()
            try {
                super.dispatchEvent(event)
            } finally {
                uiTask(System.nanoTime() - start)
            }
        }
    }

    private const val MB = 1024L * 1024L
}

/** Times frames while [PerfMonitor.wantsFrames], and marks the first frame of a logged run. Draws nothing. */
@Composable
internal fun PerfFrameProbe() {
    LaunchedEffect(Unit) {
        if (PerfMonitor.logging) {
            withFrameNanos { }
            PerfMonitor.mark("first frame")
        }
    }
    val active = PerfMonitor.logging || PerfMonitor.overlay
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            if (PerfMonitor.wantsFrames()) {
                PerfMonitor.frameLoopStarted()
                while (PerfMonitor.wantsFrames()) withFrameNanos(PerfMonitor::frame)
            }
            delay(100)
        }
    }
}

/** The overlay itself, shown over the pages (not over the video, which is a native window). */
@Composable
internal fun PerfOverlay(modifier: Modifier = Modifier) {
    if (!PerfMonitor.overlay) return
    val lines = PerfMonitor.latest?.overlayLines() ?: listOf("Measuring…")
    Column(modifier.background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("Performance (Ctrl+Shift+F12 to hide)", color = Color.White, style = MaterialTheme.typography.labelMedium)
        lines.forEach { Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}
