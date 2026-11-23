package com.skywing.desktop.playback

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal data class MotionRate(val numerator: Int, val denominator: Int = 1) {
    val fps: Double get() = numerator.toDouble() / denominator
    fun times(multiplier: Int) = MotionRate(numerator * multiplier, denominator)
    val label: String get() = String.format(Locale.ROOT, "%.2f", fps).removeSuffix(".00")
    /** A whole-number or NTSC (×1000/1001) source rate, which the motion engine retimes exactly. */
    val standard: Boolean get() = denominator == 1 || denominator == 1001 && numerator in NTSC
    companion object {
        /** Slower video, such as a slideshow, is not smoothed. */
        const val LOWEST_FPS = 1.0
        private val NTSC = listOf(24000, 30000, 48000, 60000, 120000)

        /**
         * The rate to smooth a video at, from the container rate mpv reports: an average for variable-rate video.
         * A standard rate is taken as exact. Any other is measured to three decimals, and the motion engine then
         * times its output frames from the source frames' own durations (SvpScript).
         */
        fun source(fps: Double): MotionRate? {
            if (!fps.isFinite() || fps !in LOWEST_FPS..240.0) return null
            NTSC.forEach { if (abs(fps - it / 1001.0) < 0.005) return MotionRate(it, 1001) }
            if (abs(fps - fps.roundToInt()) < 0.005) return MotionRate(fps.roundToInt())
            val thousandths = (fps * 1000).roundToLong()
            val divisor = gcd(thousandths, 1000)
            return MotionRate((thousandths / divisor).toInt(), (1000 / divisor).toInt())
        }
        fun target(source: MotionRate, displayHz: Double?, stop: Int = MotionStops.default(source, displayHz)): MotionRate? {
            if (source.fps > 60.001) return null
            return MotionStops.rate(source, stop).takeIf { stop in MotionStops.allowed(source, displayHz) }
        }
        /**
         * mpv's video-sync while smoothing to [target]. Resampling to a fixed screen whose refresh is the output rate
         * or a whole multiple of it keeps a standard rate's exact cadence (plan 5.4). Any other rate may be variable,
         * so its frames keep their own times against the audio, as do all frames when [vrr] lets the screen follow.
         * [variable]: the standard rate is a variable rate's average, found from its frames (plan 5.8 rule 10).
         */
        fun videoSync(source: MotionRate, target: MotionRate, displayHz: Double?, vrr: Boolean, variable: Boolean = false): String {
            val even = displayHz?.takeIf { it.isFinite() && it > 0 }?.let { MotionStops.fits(target.fps, it) } == true
            return if (source.standard && !variable && !vrr && even) "display-resample" else "audio"
        }
    }
}

/** Greatest common divisor, for keeping rates and multipliers in lowest terms. */
internal tailrec fun gcd(a: Long, b: Long): Long = if (b == 0L) a else gcd(b, a % b)

/** Owns only its named filter and restores the properties it changes. Calls run on the player worker. */
internal class MotionSession(
    private val get: (String) -> String?,
    private val command: (List<String>) -> Boolean,
    private val runtimeDirectory: () -> File? = MotionRuntime::directory,
    private val prepare: (File) -> String? = { MotionRuntime.prepare(it) },
    private val scriptDirectory: () -> File = {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "SkyWing/motion-scripts")
    },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var previous: Map<String, String>? = null
    private var source: MotionRate? = null
    private var quality = MotionQuality.BALANCED
    private val restartedFiles = mutableSetOf<String>()
    private var sessionStarted = 0L
    var lastHeartbeat: MotionHeartbeat? = null
        private set
    var lastIncident: MotionIncident? = null
        private set
    var lastAction: String = "none"
    var restarts: Int = 0
        private set
    /** The heartbeat memory the current script writes, or null when no script runs. */
    var heartbeatAddress: Long? = null
        private set
    /**
     * Every heartbeat block this session gave a script. A removed filter's script may still be running, so a block is
     * freed only by [release], after mpv is destroyed. Each block is [HeartbeatMemory.SIZE] bytes, one per start.
     */
    private val heartbeatBlocks = mutableListOf<Long>()
    private var scriptFile: File? = null
    private var started = 0L
    private var generation: String? = null
    private val failedFiles = mutableMapOf<String, String>()
    private var path: String? = null
    var target: MotionRate? = null
        private set
    var failure: String? = null
        private set
    var rebuilds: Int = 0
        private set
    val active: Boolean get() = previous != null
    /** Files whose standard rate turned out to be a variable rate's average (plan 5.8 rule 10), kept per file. */
    private val variableFiles = mutableSetOf<String>()
    /** The current file's frames were found off its standard rate's period: they keep their own times. */
    val variableRate: Boolean get() = path in variableFiles

    fun begin(file: String, source: MotionRate, rate: MotionRate, quality: MotionQuality = MotionQuality.BALANCED): String? {
        failedFiles[file]?.let { failure = it; return it }
        if (active) return null
        if (path != file) {
            lastHeartbeat = null
            lastIncident = null
            sessionStarted = nowMillis()
            restarts = 0
        }
        path = file
        this.source = source
        this.quality = quality
        failure = null
        target = rate
        try {
            val runtime = runtimeDirectory() ?: return reject("Motion engine files are missing")
            prepare(runtime)?.let { return reject(it) }
            val saved = listOf("hwdec", "video-sync", "interpolation").associateWith {
                get(it) ?: return reject("Player settings are not ready")
            }
            val folder = scriptDirectory()
            check(folder.isDirectory || folder.mkdirs())
            val script = Files.createTempFile(folder.toPath(), "motion-", ".vpy").toFile()
            scriptFile = script
            val memory = HeartbeatMemory.allocate().also { heartbeatBlocks += it }
            heartbeatAddress = memory
            script.writeText(SvpScript.create(runtime, memory, source, rate, quality), Charsets.UTF_8)
            previous = saved
            started = nowMillis()
            generation = null
            val quoted = script.absolutePath.replace('\\', '/')
            val filter = "@skywing-motion:vapoursynth=file=%" + quoted.toByteArray(Charsets.UTF_8).size +
                "%" + quoted + ":buffered-frames=8:concurrent-frames=auto"
            if (!command(listOf("set", "hwdec", "auto-copy")) ||
                !command(listOf("set", "interpolation", "no")) ||
                !command(listOf("set", "video-sync", "audio")) ||
                !command(listOf("vf", "add", filter))) {
                return reject("The player could not start the motion engine")
            }
            return null
        } catch (_: Exception) {
            return reject("The motion engine could not start")
        }
    }

    private fun reject(reason: String): String { fail(reason); return failure ?: reason }

    /** The script's latest beat, or null when none is written yet or this read caught the script writing one. */
    fun heartbeat(): MotionHeartbeat? {
        val read = heartbeatAddress?.let(HeartbeatMemory::read) ?: return null
        if (read.failed) {
            fail("The motion engine could not process this video")
            return null
        }
        val parsed = read.heartbeat
        if (generation != parsed.generation) {
            generation = parsed.generation
            rebuilds++
            started = nowMillis()
        }
        lastHeartbeat = parsed
        if (parsed.irregularFrames >= VARIABLE_FRAMES) path?.let(variableFiles::add)
        return parsed
    }

    fun stalled(playing: Boolean): Boolean {
        val now = nowMillis()
        if (!playing) { started = now; return false }
        // A missed heartbeat read does not reset the last heartbeat for this graph.
        val lastUpdate = lastHeartbeat?.updatedMillis.takeIf { generation != null } ?: started
        return active && now - maxOf(started, lastUpdate) > 15_000
    }

    private fun recordIncident(reason: String) {
        val now = nowMillis()
        lastIncident = MotionIncident(reason, lastHeartbeat, now - sessionStarted,
            lastHeartbeat?.let { now - it.updatedMillis },
            if (active) get("vf")?.contains("skywing-motion") else null,
            lastAction, listOf("frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count", "mistimed-frame-count")
                .associateWith { if (active) get(it) else null })
    }

    fun restartAfterStall(): Boolean {
        val file = path ?: return false
        val input = source ?: return false
        val rate = target ?: return false
        if (!restartedFiles.add(file)) return false
        recordIncident("Heartbeat stalled; attempting one restart")
        if (!end()) return false
        restarts++
        return begin(file, input, rate, quality) == null
    }

    fun fail(reason: String) {
        recordIncident(reason)
        path?.let { failedFiles[it] = reason }
        failure = reason
        if (!end()) failure = "Could not restore player settings; reopen this video"
    }

    fun end(): Boolean {
        val saved = previous
        if (saved != null) {
            // Remove only our filter, including when mpv has already disabled it after an error.
            val removed = command(listOf("vf", "remove", "@skywing-motion")) ||
                get("vf")?.contains("skywing-motion") == false
            val restored = saved.map { (key, value) -> command(listOf("set", key, value)) }.all { it }
            if (!removed || !restored) return false
            previous = null
        }
        cleanup()
        return true
    }

    /** Deletes the script file and stops reading its heartbeat. The heartbeat memory stays until [release]. */
    fun cleanup() {
        runCatching { scriptFile?.delete() }
        scriptFile = null
        heartbeatAddress = null
    }

    /** Frees the heartbeat memory. Call only after mpv is destroyed, when no script can still write to it. */
    fun release() {
        cleanup()
        heartbeatBlocks.forEach(HeartbeatMemory::free)
        heartbeatBlocks.clear()
    }

    companion object {
        /**
         * Source frames off a standard rate's period that make it a variable rate's average. One or two, such as a frame
         * lost in a recording, still play at the exact cadence; a phone or screen recording has many within seconds.
         */
        const val VARIABLE_FRAMES = 3
    }
}

/**
 * The watchdog's heartbeat (B28): memory in SkyWing's process that the motion script writes through ctypes and the
 * player worker reads, with no file in between. The script writes it under a sequence lock: [SEQUENCE] is odd while
 * it writes the other fields and even once they are complete, so a read that sees an odd or changed sequence is a
 * missed read, never a wrong beat. Every field is an aligned 8 bytes. x64 keeps each thread's stores, and each
 * thread's loads, in program order, so no fence is needed on Windows x64.
 */
internal object HeartbeatMemory {
    const val SIZE = 56L
    const val SEQUENCE = 0L
    const val STATE = 8L
    const val GENERATION = 16L
    const val COMPLETED = 24L
    const val FPS = 32L
    const val UPDATED = 40L
    /** Source frames the script found off a standard rate's period, up to 64 (plan 5.8 rule 10). */
    const val IRREGULAR = 48L
    const val RUNNING = 1L
    const val FAILED = 2L

    data class Read(val failed: Boolean, val heartbeat: MotionHeartbeat)

    fun allocate(): Long = Native.malloc(SIZE).also {
        check(it != 0L) { "No memory for the motion heartbeat" }
        Pointer(it).clear(SIZE)
    }

    fun free(address: Long) = Native.free(address)

    fun read(address: Long): Read? {
        val memory = Pointer(address)
        val sequence = memory.getLong(SEQUENCE)
        if (sequence == 0L || sequence % 2 != 0L) return null
        val state = memory.getLong(STATE)
        val heartbeat = MotionHeartbeat(memory.getLong(GENERATION).toString(), memory.getLong(COMPLETED),
            memory.getDouble(FPS), memory.getLong(UPDATED), memory.getLong(IRREGULAR))
        if (memory.getLong(SEQUENCE) != sequence) return null
        return when (state) {
            RUNNING -> Read(false, heartbeat)
            FAILED -> Read(true, heartbeat)
            else -> null
        }
    }
}

internal data class MotionIncident(
    val reason: String, val heartbeat: MotionHeartbeat?, val elapsedMillis: Long,
    val heartbeatAgeMillis: Long?, val filterPresent: Boolean?, val action: String,
    val counters: Map<String, String?>,
)

internal data class MotionHeartbeat(
    val generation: String, val completed: Long, val processingFps: Double, val updatedMillis: Long,
    /** Source frames off the standard rate's period in this graph, counted to 64 (plan 5.8 rule 10). */
    val irregularFrames: Long = 0,
)

internal object SvpScript {
    private fun literal(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
        .replace("\r", "\\r").replace("\n", "\\n") + "'"

    /**
     * How mpv times the smoothed frames. mpv adds up each output frame's duration from the first source frame, so each
     * output frame takes its duration from the source frames it falls between (follow_source_times). A standard rate's
     * frames within mpv's rounding tolerance of its period count as that exact period, so a constant rate keeps its
     * exact cadence; frames further off keep their own times, as a variable rate averaging that rate needs (plan 5.8
     * rule 10). Any other rate may be a variable rate's average, so its frames always keep their own times.
     */
    private fun timing(source: MotionRate, target: MotionRate): String {
        val num = target.numerator.toLong() * source.denominator
        val den = target.denominator.toLong() * source.numerator
        val divisor = gcd(num, den)
        val p = num / divisor
        val q = den / divisor
        require(p <= 8 && q <= p) { "The motion target must be the source rate times a smoothing stop" }
        return "out = follow_source_times(out, $p, $q, ${source.numerator}, ${source.denominator}, ${if (source.standard) "True" else "False"})"
    }

    /** [heartbeat] is a [HeartbeatMemory] block the script writes its progress to. */
    fun create(runtime: File, heartbeat: Long, source: MotionRate, target: MotionRate, quality: MotionQuality = MotionQuality.BALANCED): String = """
        import ctypes, math, time, threading
        from fractions import Fraction
        import vapoursynth as vs
        core = vs.core
        core.num_threads = 8
        class Heartbeat(ctypes.Structure):
            _fields_ = [('sequence', ctypes.c_int64), ('state', ctypes.c_int64), ('generation', ctypes.c_int64),
                        ('completed', ctypes.c_int64), ('fps', ctypes.c_double), ('updated', ctypes.c_int64),
                        ('irregular', ctypes.c_int64)]
        heartbeat = Heartbeat.from_address($heartbeat)
        generation = time.time_ns()
        # Source frames off a standard rate's period (plan 5.8 rule 10), by number, counted to 64.
        irregular = set()
        def write_status(state, completed=0, fps=0.0):
            # Odd while writing, even when done. Taking the odd value from what is there keeps the parity right even
            # if a script from before a seek writes at the same time.
            writing = heartbeat.sequence | 1
            heartbeat.sequence = writing
            heartbeat.state = state
            heartbeat.generation = generation
            heartbeat.completed = completed
            heartbeat.fps = fps
            heartbeat.updated = time.time_ns() // 1000000
            heartbeat.irregular = len(irregular)
            heartbeat.sequence = writing + 1
        def follow_source_times(clip, p, q, rate_num, rate_den, regular):
            # mpv times output frames by adding up their durations from the first source frame. With p output frames
            # for every q source frames, output frame n stands for source position q*n/p, so its duration is the
            # source time from there to the next output frame's position.
            us = 1000000
            rate = Fraction(container_fps).limit_denominator(100000) if container_fps > 0 else Fraction(rate_num, rate_den)
            # mpv rounds each source duration to whole microseconds. Snap it back to the period it rounds from, a few
            # frames of a standard rate or of the container's, so a long video does not drift by the rounding.
            periods = [Fraction(1, r) for r in range(1, 241)]
            periods += [Fraction(1001, r) for r in (24000, 30000, 48000, 60000, 120000)] + [1 / rate]
            exact = {}
            for period in periods:
                for k in range(1, 9):
                    exact.setdefault(math.floor(period * k * us + Fraction(1, 2)), period * k)
            limit = 2**31 - 1  # mpv reads durations as 32-bit integers
            # A standard rate's frame within mpv's rounding tolerance of its period (MP_PTS_ROUNDING_TOLERANCE, 3.1 ms:
            # files round times to 1 ms, and some muxers by 1 ms more) is that exact period. One further off keeps its
            # own time and is counted, so the player plays the file against the audio (plan 5.8 rule 10).
            period = Fraction(rate_den, rate_num) if regular else None
            tolerance = Fraction(31, 10000)
            def duration(f, number):
                num, den = f.props.get('_DurationNum', 0), f.props.get('_DurationDen', 0)
                if num <= 0 or den <= 0:
                    # mpv gives each frame the time to the next one's, so only the last frame before the end can lack one.
                    return period if period is not None else 1 / rate
                d = exact.get(num, Fraction(num, den)) if den == us else Fraction(num, den)
                if period is None:
                    return d
                if abs(d - period) < tolerance:
                    return period
                if len(irregular) < 64:
                    irregular.add(number)
                return d
            def source_frame(offset):
                # Frame n of the result is source frame q*(n + offset)//p.
                spread = core.std.Interleave([video_in] * p, modify_duration=False)[q * offset:]
                return spread if q == 1 else core.std.SelectEvery(spread, cycle=q, offsets=[0], modify_duration=False)
            def retime(n, f):
                a, b = q * n // p, q * (n + 1) // p
                start, end = Fraction(q * n, p) - a, Fraction(q * (n + 1), p) - b
                d = (end - start) * duration(f[1], a) if a == b else (1 - start) * duration(f[1], a) + end * duration(f[2], b)
                if d.numerator > limit or d.denominator > limit:
                    d = d.limit_denominator(limit // max(1, math.ceil(d)))
                frame = f[0].copy()
                frame.props['_DurationNum'] = d.numerator
                frame.props['_DurationDen'] = d.denominator
                return frame
            return core.std.ModifyFrame(clip, [clip, source_frame(0), source_frame(1)], retime)
        try:
            core.std.LoadPlugin(${literal(File(runtime, "svpflow1_vs.dll").absolutePath)})
            core.std.LoadPlugin(${literal(File(runtime, "svpflow2_vs.dll").absolutePath)})
            clip = core.std.AssumeFPS(video_in, fpsnum=${source.numerator}, fpsden=${source.denominator})
            sup = core.svp1.Super(clip, '${quality.superOptions}')
            vec = core.svp1.Analyse(sup['clip'], sup['data'], clip, '${quality.analyse}')
            out = core.svp2.SmoothFps(clip, sup['clip'], sup['data'], vec['clip'], vec['data'],
                '{rate:{abs:true,num:${target.numerator},den:${target.denominator}},${quality.smooth},scene:{mode:0}}',
                src=clip, fps=${source.numerator}/${source.denominator})
            ${timing(source, target)}
            lock = threading.Lock()
            last = [time.monotonic(), 0, 0]
            def observed(n, f):
                with lock:
                    last[2] += 1
                    now = time.monotonic()
                    if last[2] == 1 or now - last[0] >= 0.5:
                        fps = (last[2] - last[1]) / max(0.001, now - last[0])
                        write_status(${HeartbeatMemory.RUNNING}, last[2], fps)
                        last[0], last[1] = now, last[2]
                return f
            core.std.ModifyFrame(out, out, observed).set_output()
        except Exception:
            write_status(${HeartbeatMemory.FAILED})
            video_in.set_output()
    """.trimIndent() + "\n"
}

internal object MotionRuntime {
    private interface Win32 : StdCallLibrary {
        fun AddDllDirectory(path: WString): Pointer?
        fun LoadLibraryExW(path: WString, file: Pointer?, flags: Int): Pointer?
        fun SetEnvironmentVariableW(name: WString, value: WString): Boolean
    }
    private var loaded: File? = null
    private var cookie: Pointer? = null
    private val libraries = mutableListOf<Pointer>()
    /** The OpenCL devices, listed once; [forgetDevices] lists them again after a GPU change. */
    private var devices: List<OpenClDevice>? = null
    var preferredGpu: String = "Automatic"
        private set
    var driver: String = "Unavailable"
        private set
    /** How the OpenCL device was matched to the plan's GPU, for the playback report. */
    var matchedBy: String = "not chosen yet"
        private set
    fun directory(): File? = System.getProperty("compose.application.resources.dir")?.let {
        File(it, "motion").takeIf { dir -> listOf("VSScript.dll", "python313.dll", "portable.vs", "svpflow1_vs.dll", "svpflow2_vs.dll").all { name -> File(dir, name).isFile } }
    }

    @Synchronized fun forgetDevices() { devices = null }

    /**
     * Where the motion engine runs for [adapter] ([OpenClDevice.choose]): its OpenCL device by maker, and by LUID when
     * [byLuid] (two GPUs from one maker); else another GPU's; else the processor. Null [adapter] means any device.
     */
    @Synchronized fun choose(adapter: GpuAdapters.Adapter? = null, byLuid: Boolean = false): MotionDevice =
        OpenClDevice.choose(devices ?: OpenClDevice.list().also { devices = it }, adapter, byLuid)

    /**
     * Loads the motion DLLs once, and before every motion start points the patched plugins at [engine]'s device. The
     * plugins read this filter each time they open a device, so every video can follow its own GPU plan.
     */
    @Synchronized fun prepare(dir: File, engine: MotionDevice = choose()): String? {
        // open-svpflow may use AVX2 anywhere; loading it on a processor without it could end SkyWing (plan 5.9).
        Processor.current.motionBlocked?.let { return it }
        return runCatching {
            val kernel = Native.load("kernel32", Win32::class.java)
            engine.filter.forEach { (name, value) ->
                check(kernel.SetEnvironmentVariableW(WString(name), WString(value)))
            }
            preferredGpu = engine.device?.name ?: "the processor (no OpenCL device found)"
            driver = engine.device?.driver ?: "Unavailable"
            matchedBy = engine.matchedBy
            if (loaded == dir) return null
            cookie = kernel.AddDllDirectory(WString(dir.absolutePath)) ?: error("DLL directory")
            // VapourSynth destroys its core on every seek. Rust plugins own process-wide
            // Rayon/OpenCL state, so their DLLs must outlive those cores and worker threads.
            // Keep explicit Windows loader references until process exit; never FreeLibrary.
            for (name in listOf("VSScript.dll", "svpflow1_vs.dll", "svpflow2_vs.dll")) {
                libraries.add(kernel.LoadLibraryExW(WString(File(dir, name).absolutePath), null, 0x1100)
                    ?: error("Motion runtime: $name"))
            }
            loaded = dir
            null
        }.getOrElse { "Motion engine could not load; ordinary playback is available" }
    }
}
