package com.deepak.videolibrary.player

import android.app.ActivityManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.SystemClock
import android.provider.MediaStore
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * GPU lab: runs the native engine's OpenCL path on a synthetic 1080p 24 fps clip in a separate, disposable process
 * (":gpulab"), with every OpenCL call going through the tracing wrapper (libskywing_cl_trace.so). A driver hang there
 * cannot freeze the app: the app watches the log and kills the lab process when it stops making progress. Each run
 * also exercises the two phases that failed on Adreno: the last frames of a finite clip and engine shutdown.
 */
class GpuLabService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val variant = intent?.getStringExtra(EXTRA_VARIANT) ?: "trace"
        val path = intent?.getStringExtra(EXTRA_LOG) ?: return START_NOT_STICKY
        val levels = intent.getBooleanExtra(EXTRA_LEVELS, false)
        Thread({
            if (levels) runLevels(this, File(path)) else runLab(this, variant, File(path))
            // A fresh process for every variant: environment, loaded libraries and any wedged driver state go away.
            android.os.Process.killProcess(android.os.Process.myPid())
        }, "SkyWing-gpu-lab").start()
        return START_NOT_STICKY
    }

    companion object {
        const val EXTRA_VARIANT = "variant"
        const val EXTRA_LOG = "log"
        const val EXTRA_LEVELS = "levels"
        const val SOURCE_FRAMES = 120
        const val WIDTH = 1920
        const val HEIGHT = 1080
    }
}

private fun runLab(context: Context, variant: String, log: File) {
    val started = SystemClock.elapsedRealtime()
    fun line(text: String) {
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        log.appendText(String.format(Locale.ROOT, "[lab] %6.2fs %s\n", seconds, text))
    }
    try {
        line("variant=$variant device=${Build.MANUFACTURER} ${Build.MODEL} android=${Build.VERSION.RELEASE} soc=${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "?"}")
        if (!NativeSvpStream.available) { line("DONE error: native libraries missing"); return }
        NativeSvpStream.setEnv("OSVP_OPENCL_LIB", "libskywing_cl_trace.so")
        NativeSvpStream.setEnv("OSVP_CL_VARIANT", variant)
        NativeSvpStream.setEnv("OSVP_TRACE_FILE", log.absolutePath)
        val width = GpuLabService.WIDTH
        val height = GpuLabService.HEIGHT
        val handle = NativeSvpStream.create(
            NativeSvpStream.libraryDirectory(context), width, height, 24L, 1L, true,
            NativeSvpTuning.GPU_THREADS, NativeSvpTuning.GPU_AHEAD,
        )
        check(handle != 0L) { "engine could not be created" }
        line("engine created: OpenCL, ${width}x$height, 24 to 60 fps, ${NativeSvpTuning.GPU_THREADS} threads, ${NativeSvpTuning.GPU_AHEAD} ahead")
        val scene = NativeSvpTestScene(width, height)
        val frame = ByteBuffer.allocateDirect(width * height * 3 / 2)
        val output = ByteBuffer.allocateDirect(width * height * 3 / 2)
        val total = GpuLabService.SOURCE_FRAMES
        val lastOutput = (total - 1) * 60 / 24
        var pushed = 0
        var ended = false
        val renderStart = SystemClock.elapsedRealtime()
        for (index in 0..lastOutput) {
            val want = minOf(total, index * 24 / 60 + 10)
            while (pushed < want) {
                scene.fill(frame, pushed)
                NativeSvpStream.push(handle, frame)
                pushed++
            }
            if (pushed == total && !ended) {
                NativeSvpStream.end(handle)
                ended = true
                line("all $total source frames sent; clip end signalled")
            }
            if (index == 0 || index % 15 == 0 || index >= lastOutput - 3) line("requesting output $index of $lastOutput")
            check(NativeSvpStream.render(handle, index, output)) { "engine returned no frame $index" }
        }
        val seconds = (SystemClock.elapsedRealtime() - renderStart) / 1000.0
        line(String.format(Locale.ROOT, "all %d outputs done in %.1f s (%.0f fps)", lastOutput + 1, seconds, (lastOutput + 1) / seconds))
        line("closing engine (this is where shutdown hung before)")
        NativeSvpStream.close(handle)
        line("DONE ok")
    } catch (t: Throwable) {
        line("DONE error: ${t.message ?: t.javaClass.simpleName}")
    }
}

/**
 * GPU quality-level check, in the lab process: the same paced test as the CPU check, on the GPU engine with playback's
 * settings (and the Adreno fix on Snapdragon), at 900p, 1080p and 1440p in turn. Stops at the first level that fails.
 * Pass mark: almost nothing late at 60 fps and at least 66 fps flat out (10% headroom for decoding, display and heat).
 */
private fun runLevels(context: Context, log: File) {
    val started = SystemClock.elapsedRealtime()
    fun line(text: String) {
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        log.appendText(String.format(Locale.ROOT, "[lab] %6.2fs %s\n", seconds, text))
    }
    var max = 0
    try {
        if (!NativeSvpStream.available) { line("DONE max=-1 native libraries missing"); return }
        val qualcomm = NativeSvpTuning.isQualcommSoc()
        if (qualcomm) NativeSvpTuning.enableAdrenoFix(context)
        line("GPU quality check: ${Build.MANUFACTURER} ${Build.MODEL}${if (qualcomm) " (Snapdragon: Adreno fix on)" else ""}")
        for (level in 1..3) {
            val size = NativeSvpTuning.levelSize(level)
            line("testing ${NativeSvpTuning.LEVEL_NAMES[level]} (${size[0]}x${size[1]})")
            val result = NativeSvpSpeedCheck.measurePaced(
                context, size[0], size[1], useGpu = true, threads = NativeSvpTuning.GPU_THREADS,
                ahead = if (qualcomm) NativeSvpTuning.ADRENO_GPU_AHEAD else NativeSvpTuning.GPU_AHEAD,
                textures = if (qualcomm) NativeSvpTuning.ADRENO_GPU_TEXTURES else NativeSvpTuning.GPU_TEXTURES,
                minFlatOut = 66.0,
            )
            line("level=$level ${NativeSvpTuning.LEVEL_NAMES[level]} ${if (result.qualifies) "PASS" else "FAIL"} · ${result.summary}")
            if (!result.qualifies) break
            max = level
        }
        line("DONE max=$max")
    } catch (t: Throwable) {
        line("level error: ${t.message ?: t.javaClass.simpleName}")
        line("DONE max=$max")
    }
}

/** Runs the lab variants one after another from the app's own process. */
internal object GpuLabRunner {
    val variants = listOf("trace", "flushwait", "flushall", "blockwrite", "all")

    data class Outcome(val variant: String, val status: String)

    private fun labPid(context: Context): Int? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.runningAppProcesses?.firstOrNull { it.processName == context.packageName + ":gpulab" }?.pid
    }

    private fun lastLab(file: File): String? =
        runCatching { file.readLines().lastOrNull { it.startsWith("[lab]") } }.getOrNull()

    /** [onStatus] gets a short progress text; returns the outcome of each variant. */
    suspend fun run(context: Context, onStatus: (String) -> Unit): List<Outcome> {
        val dir = File(context.filesDir, "gpulab").apply { mkdirs() }
        val outcomes = mutableListOf<Outcome>()
        for (variant in variants) {
            labPid(context)?.let { android.os.Process.killProcess(it); delay(1000) }
            val file = File(dir, "$variant.log")
            file.delete()
            context.startService(
                Intent(context, GpuLabService::class.java)
                    .putExtra(GpuLabService.EXTRA_VARIANT, variant)
                    .putExtra(GpuLabService.EXTRA_LOG, file.absolutePath),
            )
            var last: String? = null
            var lastChange = SystemClock.elapsedRealtime()
            var firstOutput = false
            var status = ""
            while (true) {
                delay(500)
                val now = lastLab(file)
                if (now != last) { last = now; lastChange = SystemClock.elapsedRealtime() }
                if (now?.contains("requesting output") == true) firstOutput = true
                onStatus("Variant $variant: " + (now?.substringAfter("s ", now) ?: "starting…"))
                if (now?.contains("DONE ok") == true) { status = "OK — " + summary(file); break }
                if (now?.contains("DONE error") == true) { status = "ERROR — " + now.substringAfter("DONE error: "); break }
                // Kernel builds can take a while before the first output; after that, 20 s without progress is a hang.
                val limit = if (firstOutput) 20_000L else 60_000L
                if (SystemClock.elapsedRealtime() - lastChange > limit) {
                    labPid(context)?.let { android.os.Process.killProcess(it) }
                    file.appendText("[lab] HANG: no progress for ${limit / 1000} s; lab process killed\n")
                    status = "HANG at: " + (last?.substringAfter("s ", last) ?: "start")
                    break
                }
            }
            outcomes += Outcome(variant, status)
            delay(1500)
        }
        return outcomes
    }

    /**
     * Runs the GPU quality-level check in the lab process and returns "<highest passing level>|<summary>". A hang at
     * some level counts as that level failing; level 0 (720p) is assumed workable.
     */
    suspend fun runLevels(context: Context, onStatus: (String) -> Unit): String {
        val dir = File(context.filesDir, "gpulab").apply { mkdirs() }
        labPid(context)?.let { android.os.Process.killProcess(it); delay(1000) }
        val file = File(dir, "levels.log")
        file.delete()
        context.startService(
            Intent(context, GpuLabService::class.java)
                .putExtra(GpuLabService.EXTRA_LEVELS, true)
                .putExtra(GpuLabService.EXTRA_LOG, file.absolutePath),
        )
        var last: String? = null
        var lastChange = SystemClock.elapsedRealtime()
        while (true) {
            delay(500)
            val now = lastLab(file)
            if (now != last) { last = now; lastChange = SystemClock.elapsedRealtime() }
            onStatus(now?.substringAfter("s ", now) ?: "starting…")
            if (now?.contains("DONE max=") == true) break
            // Each level takes about 15 s; 45 s without a new line means the GPU hung at the level being tested.
            if (SystemClock.elapsedRealtime() - lastChange > 45_000L) {
                labPid(context)?.let { android.os.Process.killProcess(it) }
                file.appendText("[lab] HANG: no progress for 45 s; lab process killed\n")
                break
            }
        }
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        val passed = lines.mapNotNull { Regex("level=(\\d) \\S+ PASS").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val max = passed.maxOrNull() ?: 0
        val details = lines.filter { it.contains("level=") || it.contains("HANG") || it.contains("error") }
            .joinToString(" | ") { it.substringAfter("s ").trim() }
        return "$max|${NativeSvpTuning.LEVEL_NAMES[max]} is the highest level that kept up · $details"
    }

    private fun summary(file: File): String =
        runCatching { file.readLines().lastOrNull { it.contains("outputs done") }?.substringAfter("s ")?.trim() }.getOrNull() ?: "finished"

    /** Saves everything to Downloads and returns the file's URI (for sharing). */
    fun saveReport(context: Context, outcomes: List<Outcome>): Uri? = runCatching {
        val text = buildString {
            appendLine("SkyWing GPU lab report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrNull()
            appendLine("App version code: $version")
            appendLine()
            outcomes.forEach { appendLine("${it.variant}: ${it.status}") }
            for (variant in variants) {
                val file = File(File(context.filesDir, "gpulab"), "$variant.log")
                appendLine()
                appendLine("===== $variant =====")
                val lines = runCatching { file.readLines() }.getOrDefault(listOf("(no log)"))
                (if (lines.size > 600) lines.take(300) + listOf("… ${lines.size - 600} lines cut …") + lines.takeLast(300) else lines)
                    .forEach { appendLine(it) }
            }
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "SkyWing-gpu-lab-$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        uri
    }.getOrNull()
}

/**
 * Report for live GPU playback problems: per-second playback log, the OpenCL wrapper's stall reports, the app's own
 * recent exits as Android recorded them (including the native crash trace for a native crash) and the last Java crash.
 */
internal object GpuPlaybackReport {
    fun playbackLog(context: Context): File = File(context.filesDir, "gpu-playback.log")
    private fun javaCrash(context: Context): File = File(context.filesDir, "last-java-crash.txt")
    private var installed = false

    /** Keep the stack of an uncaught Java exception for the report, then let the app crash as usual. */
    @Synchronized
    fun installCrashCapture(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = java.io.StringWriter().also { error.printStackTrace(java.io.PrintWriter(it)) }.toString()
                javaCrash(app).writeText("time=${java.util.Date()} thread=${thread.name}\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun log(context: Context, line: String) {
        runCatching {
            val file = playbackLog(context)
            if (file.length() > 2_000_000L) file.delete()
            file.appendText(line + "\n")
        }
    }

    private fun exitReasons(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) return "(needs Android 11)"
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 6) }.getOrDefault(emptyList())
        return buildString {
            for (exit in exits) {
                appendLine("${java.util.Date(exit.timestamp)} process=${exit.processName} reason=${exit.reason} status=${exit.status} ${exit.description ?: ""}")
                if (exit.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE && Build.VERSION.SDK_INT >= 31) {
                    val bytes = runCatching { exit.traceInputStream?.use { it.readBytes() } }.getOrNull() ?: continue
                    // The native trace is a protobuf; its readable strings hold the signal, cause and backtrace frames.
                    val strings = Regex("[ -~]{6,}").findAll(String(bytes, Charsets.ISO_8859_1)).map { it.value }.toList()
                    strings.filter { s -> listOf(".so", "SIG", "signal", "Cause", "Abort", "fault", "pc ", "backtrace", "#").any { s.contains(it) } }
                        .take(160).forEach { appendLine("    $it") }
                }
            }
            if (exits.isEmpty()) appendLine("(none recorded)")
        }
    }

    /** Builds the report, saves it to Downloads and returns its URI for sharing. */
    fun save(context: Context): Uri? = runCatching {
        fun tail(file: File, lines: Int): String {
            val all = runCatching { file.readLines() }.getOrDefault(emptyList())
            return if (all.isEmpty()) "(empty)\n" else all.takeLast(lines).joinToString("\n") + "\n"
        }
        val text = buildString {
            appendLine("SkyWing GPU playback report")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
            appendLine("App version code: ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrNull()}")
            appendLine("GPU on Snapdragon: ${NativeSvpTuning.adrenoGpu}; last stream: ${NativeSvpTuning.lastStream}")
            appendLine("Last video: ${NativeSvpTuning.lastVideoInfo}")
            appendLine()
            appendLine("===== normal playback, no smoothing (one line a second) =====")
            append(NativeSvpTuning.normalLogText())
            appendLine()
            appendLine("===== recent app exits (Android) =====")
            append(exitReasons(context))
            appendLine()
            appendLine("===== last Java crash =====")
            append(runCatching { javaCrash(context).readText() }.getOrDefault("(none)\n"))
            appendLine()
            appendLine("===== OpenCL stall reports =====")
            append(tail(NativeSvpTuning.stallLog(context), 500))
            appendLine()
            appendLine("===== playback log (one line a second) =====")
            append(tail(playbackLog(context), 900))
        }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "SkyWing-gpu-report-$stamp.txt")
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        uri
    }.getOrNull()
}
