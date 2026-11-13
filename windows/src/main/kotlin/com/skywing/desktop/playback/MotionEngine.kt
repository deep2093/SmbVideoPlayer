package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.nio.file.Files
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class MotionRate(val numerator: Int, val denominator: Int = 1) {
    val fps: Double get() = numerator.toDouble() / denominator
    fun times(multiplier: Int) = MotionRate(numerator * multiplier, denominator)
    val label: String get() = String.format(Locale.ROOT, "%.2f", fps).removeSuffix(".00")
    companion object {
        fun source(fps: Double): MotionRate? {
            if (!fps.isFinite() || fps !in 1.0..240.0) return null
            listOf(24000, 30000, 48000, 60000, 120000).forEach {
                if (abs(fps - it / 1001.0) < 0.005) return MotionRate(it, 1001)
            }
            if (abs(fps - fps.roundToInt()) < 0.005) return MotionRate(fps.roundToInt())
            return null // Do not silently retime unknown/VFR material in this preview.
        }
        fun target(source: MotionRate, displayHz: Double?, stop: Int = MotionStops.default(source)): MotionRate? {
            if (source.fps > 60.001) return null
            return MotionStops.rate(source, stop).takeIf { stop in MotionStops.allowed(source, displayHz) }
        }
    }
}

/** Owns only its named filter and restores the properties it changes. Calls run on the player worker. */
internal class MotionSession(
    private val get: (String) -> String?,
    private val command: (List<String>) -> Boolean,
    private val runtimeDirectory: () -> File? = MotionRuntime::directory,
    private val prepare: (File) -> String? = MotionRuntime::prepare,
    private val scriptDirectory: () -> File = {
        File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "SkyWing/motion-scripts")
    },
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
    private var statusFile: File? = null
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

    fun begin(file: String, source: MotionRate, rate: MotionRate, quality: MotionQuality = MotionQuality.BALANCED): String? {
        failedFiles[file]?.let { failure = it; return it }
        if (active) return null
        if (path != file) {
            lastHeartbeat = null
            lastIncident = null
            sessionStarted = System.currentTimeMillis()
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
            val status = File(script.path + ".status")
            statusFile = status
            script.writeText(SvpScript.create(runtime, status, source, rate, quality), Charsets.UTF_8)
            previous = saved
            started = System.currentTimeMillis()
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

    fun heartbeat(): MotionHeartbeat? {
        val fields = runCatching { statusFile?.readText()?.trim()?.split('\t') }.getOrNull() ?: return null
        if (fields.firstOrNull() == "error") {
            fail("The motion engine could not process this video")
            return null
        }
        if (fields.size != 5 || fields[0] != "ok") return null
        val parsed = MotionHeartbeat(fields[1], fields[2].toLongOrNull() ?: return null,
            fields[3].toDoubleOrNull() ?: return null, fields[4].toLongOrNull() ?: return null)
        if (generation != parsed.generation) {
            generation = parsed.generation
            rebuilds++
            started = System.currentTimeMillis()
        }
        lastHeartbeat = parsed
        return parsed
    }

    fun stalled(playing: Boolean, heartbeat: MotionHeartbeat?): Boolean {
        val now = System.currentTimeMillis()
        if (!playing) { started = now; return false }
        return active && now - maxOf(started, heartbeat?.updatedMillis ?: started) > 15_000
    }

    private fun recordIncident(reason: String) {
        val now = System.currentTimeMillis()
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

    /** Also called after mpv destruction, when no filter can still access these files. */
    fun cleanup() {
        runCatching { statusFile?.let { File(it.path + ".tmp").delete(); it.delete() }; scriptFile?.delete() }
        statusFile = null
        scriptFile = null
    }
}

internal data class MotionIncident(
    val reason: String, val heartbeat: MotionHeartbeat?, val elapsedMillis: Long,
    val heartbeatAgeMillis: Long?, val filterPresent: Boolean?, val action: String,
    val counters: Map<String, String?>,
)

internal data class MotionHeartbeat(val generation: String, val completed: Long, val processingFps: Double, val updatedMillis: Long)

internal object SvpScript {
    private fun literal(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'")
        .replace("\r", "\\r").replace("\n", "\\n") + "'"
    fun create(runtime: File, status: File, source: MotionRate, target: MotionRate, quality: MotionQuality = MotionQuality.BALANCED): String = """
        import os, time, threading
        import vapoursynth as vs
        core = vs.core
        core.num_threads = 8
        status_path = ${literal(status.absolutePath)}
        def write_status(text):
            try:
                with open(status_path + '.tmp', 'w', encoding='utf-8') as f:
                    f.write(text)
                os.replace(status_path + '.tmp', status_path)
            except OSError:
                pass
        try:
            core.std.LoadPlugin(${literal(File(runtime, "svpflow1_vs.dll").absolutePath)})
            core.std.LoadPlugin(${literal(File(runtime, "svpflow2_vs.dll").absolutePath)})
            clip = core.std.AssumeFPS(video_in, fpsnum=${source.numerator}, fpsden=${source.denominator})
            sup = core.svp1.Super(clip, '${quality.superOptions}')
            vec = core.svp1.Analyse(sup['clip'], sup['data'], clip, '${quality.analyse}')
            out = core.svp2.SmoothFps(clip, sup['clip'], sup['data'], vec['clip'], vec['data'],
                '{rate:{abs:true,num:${target.numerator},den:${target.denominator}},${quality.smooth},scene:{mode:0}}',
                src=clip, fps=${source.numerator}/${source.denominator})
            out = core.std.AssumeFPS(out, fpsnum=${target.numerator}, fpsden=${target.denominator})
            generation = str(time.time_ns())
            lock = threading.Lock()
            last = [time.monotonic(), 0, 0]
            def observed(n, f):
                with lock:
                    last[2] += 1
                    now = time.monotonic()
                    if last[2] == 1 or now - last[0] >= 0.5:
                        fps = (last[2] - last[1]) / max(0.001, now - last[0])
                        write_status('ok\t%s\t%d\t%.3f\t%d' % (generation, last[2], fps, time.time()*1000))
                        last[0], last[1] = now, last[2]
                return f
            core.std.ModifyFrame(out, out, observed).set_output()
        except Exception:
            write_status('error\tinitialization')
            video_in.set_output()
    """.trimIndent() + "\n"
}

internal object MotionRuntime {
    private interface Win32 : StdCallLibrary {
        fun AddDllDirectory(path: WString): Pointer?
        fun LoadLibraryExW(path: WString, file: Pointer?, flags: Int): Pointer?
        fun SetEnvironmentVariableW(name: WString, value: WString): Boolean
    }
    private interface OpenCl : Library {
        fun clGetPlatformIDs(count: Int, platforms: Pointer?, returned: IntByReference): Int
        fun clGetDeviceIDs(platform: Pointer, type: Long, count: Int, devices: Pointer?, returned: IntByReference): Int
        fun clGetDeviceInfo(device: Pointer, key: Int, size: Long, value: Pointer, returned: Pointer?): Int
    }
    private var loaded: File? = null
    private var cookie: Pointer? = null
    private val libraries = mutableListOf<Pointer>()
    var preferredGpu: String = "Automatic"
        private set
    var driver: String = "Unavailable"
        private set
    fun directory(): File? = System.getProperty("compose.application.resources.dir")?.let {
        File(it, "motion").takeIf { dir -> listOf("VSScript.dll", "python313.dll", "portable.vs", "svpflow1_vs.dll", "svpflow2_vs.dll").all { name -> File(dir, name).isFile } }
    }
    @Synchronized fun prepare(dir: File): String? {
        if (loaded == dir) return null
        return runCatching {
            val kernel = Native.load("kernel32", Win32::class.java)
            // Select by vendor, not OpenCL platform ordering, which changes on hybrid laptops.
            runCatching {
                val cl = Native.load("OpenCL", OpenCl::class.java)
                val count = IntByReference()
                if (cl.clGetPlatformIDs(0, null, count) == 0 && count.value in 1..32) {
                    val platforms = Memory(count.value.toLong() * Native.POINTER_SIZE)
                    if (cl.clGetPlatformIDs(count.value, platforms, count) == 0) {
                        for (i in 0 until count.value) {
                            val devices = Memory(Native.POINTER_SIZE.toLong())
                            val found = IntByReference()
                            if (cl.clGetDeviceIDs(platforms.getPointer(i.toLong()*Native.POINTER_SIZE), 4, 1, devices, found) != 0) continue
                            val device = devices.getPointer(0)
                            fun info(key: Int): String {
                                val bytes = Memory(512)
                                bytes.clear()
                                return if (cl.clGetDeviceInfo(device, key, 512, bytes, null) == 0) bytes.getString(0) else ""
                            }
                            if (info(0x102C).contains("NVIDIA", true)) {
                                check(kernel.SetEnvironmentVariableW(WString("SKYWING_OPENCL_VENDOR"), WString("NVIDIA")))
                                preferredGpu = info(0x102B)
                                driver = info(0x102D)
                                break
                            }
                        }
                    }
                }
            }
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
