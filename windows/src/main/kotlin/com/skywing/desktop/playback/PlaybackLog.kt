package com.skywing.desktop.playback

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reading: its value, such as "yes", "no", "unknown" or "not measured", and what it rests on (plan 5.6). */
internal data class ScreenReading(val value: String, val reason: String? = null) {
    val text: String get() = reason?.let { "$value ($it)" } ?: value
}

/** What the screen is doing, as readings, never guesses (plan 5.6). */
internal object DisplayState {
    private val HDR_TRANSFERS = setOf("pq", "hlg", "st2084")

    /**
     * HDR is engaged only when all three hold: Windows HDR is on for the screen, the source is HDR (`video-params/gamma`
     * PQ or HLG) and mpv sends PQ or HLG to the screen (`video-target-params/gamma`). One that is false makes it "no";
     * otherwise one that is not reported makes it "unknown".
     */
    fun hdrEngaged(windows: HdrState, sourceGamma: String?, targetGamma: String?, targetPrimaries: String?, targetPeakNits: Double?): ScreenReading {
        if (sourceGamma != null && sourceGamma !in HDR_TRANSFERS) return ScreenReading("no", "source is SDR ($sourceGamma)")
        if (windows.on == false) return ScreenReading("no", "Windows HDR is off on this screen")
        if (targetGamma != null && targetGamma !in HDR_TRANSFERS) return ScreenReading("no", "mpv sends $targetGamma: tone-mapped to SDR")
        if (sourceGamma == null) return ScreenReading("unknown", "no video")
        if (windows.on == null) return ScreenReading("unknown", "Windows did not report HDR for this screen")
        if (targetGamma == null) return ScreenReading("unknown", "mpv did not report what it sends")
        val sends = listOfNotNull(targetGamma, targetPrimaries,
            targetPeakNits?.takeIf { it.isFinite() && it > 0 }?.let { "peak " + String.format(Locale.ROOT, "%.0f", it) + " nits" })
        return ScreenReading("yes", "Windows HDR on; source $sourceGamma; mpv sends " + sends.joinToString(", "))
    }

    /**
     * VRR active, as NVIDIA reports it: nvapi.h says bIsVRREnabled is "VRR Mode is currently active on given display".
     * Other GPUs report nothing, so it is "not measured" there.
     */
    fun vrrActive(capability: VrrCapability): ScreenReading {
        val nvidia = capability.nvidia ?: return ScreenReading("not measured", "only NVIDIA reports it; " + capability.detail)
        return ScreenReading(if (nvidia.enabled) "yes" else "no", nvidia.label)
    }
}

/** One reading taken on the player worker each time smoothing is updated. Counters are mpv's running totals. */
internal data class PlaybackSample(
    val atMillis: Long,
    val target: String?,
    /** The filter's processing throughput, from the heartbeat; not presented frames. */
    val outputFps: Double?,
    /** frame-drop-count, decoder-frame-drop-count, vo-delayed-frame-count, mistimed-frame-count. */
    val counters: List<Long?>,
    val vsyncJitter: Double?,
    /** mpv's estimated-display-fps. */
    val displayFps: Double?,
    /** SkyWing's CPU time so far, in all its threads (mpv and the motion engine run in the same process). */
    val cpuNanos: Long?,
    val heartbeatAgeMillis: Long?,
    val generations: Int,
    val seeks: Int,
    val restarts: Int,
    val paused: Boolean?,
    val cacheWait: Boolean?,
    val fullscreen: Boolean,
    val hdr: ScreenReading,
    val vrr: ScreenReading,
    val refreshHz: Double?,
    /** Whether SkyWing's window is the one in front; null when Windows did not say. */
    val focused: Boolean?,
)

/** One row of the per-minute log: what changed from its first sample to its last. Null: unavailable. */
internal data class PlaybackMinute(
    val minute: Int,
    val seconds: Double,
    val target: String?,
    val outputFpsMean: Double?,
    val outputFpsMin: Double?,
    val counters: List<Long?>,
    val vsyncJitterMax: Double?,
    val displayFps: Double?,
    val cpuCores: Double?,
    val heartbeatAgeMaxMillis: Long?,
    val generations: Int,
    val seeks: Int,
    val pauses: Int,
    val cacheWaits: Int,
    val fullscreenChanges: Int,
    val recoveries: Int,
    val hdr: ScreenReading,
    val vrr: ScreenReading,
    val refreshHz: Double?,
    /** "yes" or "no" when SkyWing's window was in front all minute or never, "mixed" when it changed; null: unknown. */
    val focused: String?,
) {
    fun csv(): String = listOf(
        minute.toString(), format(seconds, 1), target.orEmpty(), format(outputFpsMean, 2), format(outputFpsMin, 2),
        *counters.map { it?.toString().orEmpty() }.toTypedArray(), format(vsyncJitterMax, 4), format(displayFps, 3),
        format(cpuCores, 2), heartbeatAgeMaxMillis?.toString().orEmpty(), generations.toString(), seeks.toString(),
        pauses.toString(), cacheWaits.toString(), fullscreenChanges.toString(), recoveries.toString(),
        hdr.value, vrr.value, refreshHz?.let(ScreenSummary::hz).orEmpty(), focused.orEmpty(),
    ).joinToString(",", transform = ::quote)

    companion object {
        const val HEADER = "minute,seconds,target_fps,output_fps_mean,output_fps_min,frame_drops,decoder_drops," +
            "vo_delayed,mistimed,vsync_jitter_max,estimated_display_fps,cpu_cores,heartbeat_age_max_ms," +
            "graph_generations,seeks,pauses,cache_waits,fullscreen_changes,recoveries,hdr_engaged,vrr_active,refresh_hz,focused"

        private val COLUMNS = listOf("Minute", "Seconds", "Output fps (mean/min)", "Drops (video/decoder/delayed/mistimed)",
            "CPU cores", "Heartbeat max", "HDR", "VRR", "Focus")

        /** The test report's table: a header line, then one line per minute. */
        fun table(rows: List<PlaybackMinute>): List<String> {
            if (rows.isEmpty()) return listOf("No full minute of smoothing yet")
            fun line(cells: List<String>) = cells.mapIndexed { i, cell ->
                if (i == cells.lastIndex) cell else cell.padEnd(COLUMNS[i].length)
            }.joinToString("  ")
            return listOf(line(COLUMNS)) + rows.map { row ->
                line(listOf(row.minute.toString(), String.format(Locale.ROOT, "%.0f", row.seconds),
                    (format(row.outputFpsMean, 1) ?: "–") + "/" + (format(row.outputFpsMin, 1) ?: "–"),
                    row.counters.joinToString("/") { it?.toString() ?: "–" },
                    format(row.cpuCores, 2) ?: "–", row.heartbeatAgeMaxMillis?.let { "$it ms" } ?: "–",
                    row.hdr.value, row.vrr.value, row.focused ?: "–"))
            }
        }

        private fun format(value: Double?, places: Int): String? =
            value?.takeIf { it.isFinite() }?.let { String.format(Locale.ROOT, "%.${places}f", it) }

        private fun quote(cell: String?): String = when {
            cell == null -> ""
            cell.any { it == ',' || it == '"' || it == '\n' } -> "\"" + cell.replace("\"", "\"\"") + "\""
            else -> cell
        }
    }
}

/**
 * Turns samples into one [PlaybackMinute] a minute (plan 5.6). Each minute starts with the last sample of the one
 * before, so the rows' deltas add up with no gap. [finish] ends a session: what is left becomes a row if it covers
 * at least 5 s, and the next session counts its minutes from 1 again.
 */
internal class PlaybackMinutes(private val emit: (PlaybackMinute) -> Unit) {
    private val samples = mutableListOf<PlaybackSample>()
    private var minute = 0

    fun add(sample: PlaybackSample) {
        samples += sample
        if (sample.atMillis - samples.first().atMillis >= 60_000) {
            close()
            samples.clear()
            samples += sample
        }
    }

    fun finish() {
        if (samples.size > 1 && samples.last().atMillis - samples.first().atMillis >= 5_000) close()
        samples.clear()
        minute = 0
    }

    private fun close() {
        val first = samples.first()
        val last = samples.last()
        val spanMillis = last.atMillis - first.atMillis
        val fps = samples.mapNotNull { it.outputFps }
        fun <T> changes(value: (PlaybackSample) -> T, into: T? = null) = samples.zipWithNext()
            .count { (a, b) -> value(a) != value(b) && (into == null || value(b) == into) }
        minute++
        emit(PlaybackMinute(
            minute = minute,
            seconds = spanMillis / 1000.0,
            target = last.target,
            outputFpsMean = fps.takeIf { it.isNotEmpty() }?.average(),
            outputFpsMin = fps.minOrNull(),
            counters = first.counters.zip(last.counters) { a, b -> if (a != null && b != null && b >= a) b - a else null },
            vsyncJitterMax = samples.mapNotNull { it.vsyncJitter }.maxOrNull(),
            displayFps = samples.mapNotNull { it.displayFps }.takeIf { it.isNotEmpty() }?.average(),
            cpuCores = if (first.cpuNanos != null && last.cpuNanos != null && spanMillis > 0)
                (last.cpuNanos - first.cpuNanos) / (spanMillis * 1_000_000.0) else null,
            heartbeatAgeMaxMillis = samples.mapNotNull { it.heartbeatAgeMillis }.maxOrNull(),
            generations = last.generations - first.generations,
            seeks = last.seeks - first.seeks,
            pauses = changes({ it.paused }, into = true),
            cacheWaits = changes({ it.cacheWait }, into = true),
            fullscreenChanges = changes({ it.fullscreen }),
            recoveries = last.restarts - first.restarts,
            hdr = last.hdr,
            vrr = last.vrr,
            refreshHz = last.refreshHz,
            focused = samples.mapNotNull { it.focused }.distinct().let {
                when (it.size) { 0 -> null; 1 -> if (it.single()) "yes" else "no"; else -> "mixed" }
            },
        ))
    }
}

/** One smoothing session's per-minute log, a CSV in SkyWing's app-data folder. Only the newest [KEEP] are kept. */
internal class PlaybackCsv private constructor(val file: File) {
    /** One small append a minute, on the player worker. */
    fun append(row: PlaybackMinute) {
        runCatching { file.appendText(row.csv() + "\n", Charsets.UTF_8) }
    }

    companion object {
        const val KEEP = 20
        private val NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT)

        /** The usual folder: `%LOCALAPPDATA%\SkyWing\playback-logs`. */
        fun folder(): File = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "SkyWing/playback-logs")

        /** A new log named after [now], with its header; null when the folder can't be written. */
        fun create(folder: File = folder(), now: LocalDateTime = LocalDateTime.now()): PlaybackCsv? = runCatching {
            check(folder.isDirectory || folder.mkdirs())
            val stem = "playback-" + now.format(NAME)
            val file = generateSequence(1) { it + 1 }.map { File(folder, if (it == 1) "$stem.csv" else "$stem-$it.csv") }
                .first { it.createNewFile() }
            file.writeText(PlaybackMinute.HEADER + "\n", Charsets.UTF_8)
            folder.listFiles { f -> f.isFile && f.name.startsWith("playback-") && f.name.endsWith(".csv") }.orEmpty()
                .sortedByDescending { it.name }.drop(KEEP).forEach { it.delete() }
            PlaybackCsv(file)
        }.getOrNull()
    }
}
