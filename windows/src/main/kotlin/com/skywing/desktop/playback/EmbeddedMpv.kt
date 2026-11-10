package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import java.awt.Canvas
import java.io.Closeable
import java.io.File

/**
 * Experimental native Windows x64 libmpv host.
 * Uses mpv's Windows --wid child-window embedding; GPU-render API integration follows later.
 * Requires libmpv-2.dll and its dependencies to be bundled in the installed application.
 */
class EmbeddedMpv : Closeable {
    private interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_request_log_messages(ctx: Pointer, level: String): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: IntByReference): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_free(data: Pointer)
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    private var api: Api? = null
    private var instance: Pointer? = null
    private var surface: Long? = null
    private var closed = false
    private val smoothingHealth = SmoothingHealth()
    private var smoothingApplied = false
    private var smoothingRejected = false
    private var smoothingMode = SmoothingMode.OFF
    private var expectedPath: String? = null
    private val motion = MotionSession(::textProperty, { command(*it.toTypedArray()) })
    private var lastPosition = 0.0
    private val reportSamples = java.util.ArrayDeque<Pair<Long, List<Long?>>>()
    private var engineErrorCount = 0
    var lastError: String? = null
        private set

    /** Invoke only once the AWT Canvas has a native peer (isDisplayable). */
    @Synchronized
    fun attach(canvas: Canvas): Boolean {
        if (closed) return false
        if (!canvas.isDisplayable) {
            lastError = "Video surface is not ready"
            return false
        }
        return try {
            val handle = Native.getComponentPointer(canvas)
            require(handle != null && Pointer.nativeValue(handle) != 0L) { "No native window handle" }
            val hwnd = Pointer.nativeValue(handle)
            if (instance != null) {
                check(surface == hwnd) { "Video HWND changed while mpv is attached" }
                return true
            }
            val resources = System.getProperty("compose.application.resources.dir")
            val packaged = resources?.let { File(it, "libmpv-2.dll") }
            val native = if (packaged?.isFile == true)
                Native.load(packaged.absolutePath, Api::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            else Native.load("mpv-2", Api::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
            val context = native.mpv_create() ?: error("mpv_create failed")
            // Set before mpv_initialize. mpv creates its own video child in the Canvas HWND.
            fun set(name: String, value: String) {
                check(native.mpv_set_option_string(context, name, value) >= 0) { "mpv option: $name" }
            }
            try {
                // SkyWing owns keyboard/fullscreen/quit. Native mpv defaults must
                // not independently fullscreen or close its embedded child window.
                set("config", "no")
                set("input-default-bindings", "no")
                set("input-vo-keyboard", "no")
                set("wid", java.lang.Long.toUnsignedString(Pointer.nativeValue(handle)))
                set("force-window", "yes")
                set("keep-open", "yes")
                set("hwdec", "auto-safe")
                // Reserve decoder surfaces before loading a file for runtime temporal smoothing.
                set("hwdec-extra-frames", "8")
                check(native.mpv_initialize(context) >= 0) { "mpv_initialize failed" }
            } catch (failure: Throwable) {
                native.mpv_terminate_destroy(context)
                throw failure
            }
            native.mpv_request_log_messages(context, "warn")
            api = native
            instance = context
            surface = hwnd
            lastError = null
            true
        } catch (failure: Throwable) {
            lastError = "Embedded player unavailable: ${failure.message ?: failure.javaClass.simpleName}"
            false
        }
    }

    @Synchronized
    private fun command(vararg args: String): Boolean {
        val native = api ?: return false
        val context = instance ?: return false
        return native.mpv_command(context, (args.toList() + listOf(null)).toTypedArray()) >= 0
    }

    // This measures only how quickly mpv accepts the load request, not first-frame time.
    var lastLoadCommandMillis: Long = -1
        private set

    @Synchronized
    fun open(file: File): Boolean {
        if (!file.isFile) return false
        if (!motion.end()) { lastError = "Could not stop motion smoothing"; return false }
        setSmoothing(false)
        reportSamples.clear()
        lastPosition = 0.0
        smoothingHealth.reset()
        smoothingRejected = false
        val start = System.nanoTime()
        val accepted = command("loadfile", file.absolutePath, "replace")
        expectedPath = file.absolutePath.takeIf { accepted }
        lastLoadCommandMillis = (System.nanoTime() - start) / 1_000_000
        return accepted
    }
    // MPV_FORMAT_DOUBLE = 5. Null is returned while no file is loaded.
    @Synchronized
    private fun numberProperty(name: String): Double? {
        val native = api ?: return null
        val ctx = instance ?: return null
        val value = DoubleByReference()
        return if (native.mpv_get_property(ctx, name, 5, value) >= 0) {
            value.value.takeIf { it.isFinite() }
        } else null
    }
    // MPV_FORMAT_FLAG = 3. True or false once the player exists, otherwise null.
    @Synchronized
    private fun flagProperty(name: String): Boolean? {
        val native = api ?: return null
        val ctx = instance ?: return null
        val value = IntByReference()
        return if (native.mpv_get_property(ctx, name, 3, value) >= 0) value.value != 0 else null
    }
    @Synchronized
    private fun textProperty(name: String): String? {
        val native = api ?: return null
        val ctx = instance ?: return null
        val value = native.mpv_get_property_string(ctx, name) ?: return null
        return try { value.getString(0, "UTF-8") } finally { native.mpv_free(value) }
    }

    private fun setSmoothing(enabled: Boolean): Boolean {
        if (enabled == smoothingApplied) return true
        if (enabled) {
            if (command("set", "tscale", "oversample") &&
                command("set", "video-sync", "display-resample") &&
                command("set", "interpolation", "yes")) {
                smoothingApplied = true
                smoothingHealth.reset()
                return true
            }
            // Roll back partial configuration; never leave a failed enable on display sync.
            command("set", "interpolation", "no")
            command("set", "video-sync", "audio")
            smoothingRejected = true
            return false
        }
        val interpolationOff = command("set", "interpolation", "no")
        val audioSync = command("set", "video-sync", "audio")
        if (interpolationOff && audioSync) smoothingApplied = false
        return interpolationOff && audioSync
    }

    /** Call off the UI thread about once a second. Never overrides display mode, speed,
     * container FPS, HDR transforms, the Canvas peer, or the renderer. display-resample may
     * make mpv's small A/V clock corrections; it falls back for unsupported audio outputs. */
    @Synchronized
    fun updateSmoothing(mode: SmoothingMode): SmoothingStatus {
        val filterFailed = drainEvents()
        if (filterFailed && motion.active) {
            engineErrorCount++
            motion.fail("The motion filter failed")
            if (flagProperty("idle-active") == true) expectedPath?.let {
                command("loadfile", it, "replace", "-1", "start=$lastPosition")
            }
        }
        numberProperty("time-pos")?.let { lastPosition = it }
        if (mode != smoothingMode) {
            smoothingMode = mode
            smoothingHealth.reset()
            smoothingRejected = false
        }
        val caps = SmoothingCapabilities(
            displayHz = numberProperty("display-fps"),
            sourceFps = numberProperty("container-fps"),
            gpuOutput = textProperty("current-vo") in setOf("gpu", "gpu-next"),
            hardwareDecode = textProperty("hwdec-current")?.let { it.isNotBlank() && it != "no" } == true,
            hdrVideo = textProperty("video-params/gamma") in setOf("pq", "hlg", "st2084"),
            width = numberProperty("video-params/w")?.toInt(),
            height = numberProperty("video-params/h")?.toInt(),
        )
        if (mode == SmoothingMode.MOTION) return updateMotion(caps)
        if (!motion.end()) return SmoothingStatus(caps, "Could not stop motion smoothing; reopen video")
        var decision = caps.decision(mode)
        // loadfile is asynchronous. Do not configure a new HDR/high-FPS item using the
        // previous decoder's capabilities while mpv is still switching files.
        if (expectedPath == null || !expectedPath.equals(textProperty("path"), ignoreCase = true)) {
            decision = SmoothingDecision(false, if (mode == SmoothingMode.OFF) "Smoothing off" else "Waiting for the selected video")
        }
        if (smoothingApplied) {
            val drops = numberProperty("frame-drop-count")?.toLong()
            val decodingDrops = numberProperty("decoder-frame-drop-count")?.toLong()
            val playing = isPaused() == false && flagProperty("seeking") == false && flagProperty("paused-for-cache") == false
            smoothingHealth.sample(System.nanoTime() / 1_000_000, if (drops != null && decodingDrops != null) drops + decodingDrops else null, playing)
        }
        if (smoothingHealth.tripped) decision = SmoothingDecision(false, "Smoothing bypassed: sustained dropped frames; reopen video to retry")
        if (smoothingRejected) decision = SmoothingDecision(false, "Smoothing unavailable: player rejected settings")
        if (!setSmoothing(decision.enabled)) return SmoothingStatus(caps, "Smoothing settings failed; choose Off or reopen video")
        val active = decision.enabled && flagProperty("display-sync-active") == true
        val detail = when {
            active -> decision.detail + " · " + caps.resolution
            decision.enabled -> "Smoothing waiting for display sync · ordinary playback continues"
            else -> decision.detail
        }
        // This confirms settings and display sync, not optical flow or measured output FPS.
        return SmoothingStatus(caps, detail, active, report = playbackReport(caps, detail))
    }

    /** Drain events before enabling a filter so stale errors cannot reject a new session. */
    private fun drainEvents(): Boolean {
        val native = api ?: return false
        val context = instance ?: return false
        var failed = false
        repeat(256) {
            val event = native.mpv_wait_event(context, 0.0)
            when (event.getInt(0)) {
                0 -> return failed
                2 -> { // mpv_event_log_message; x64 pointer layout
                    val log = event.getPointer(16)
                    if (log != null) {
                        val prefix = log.getPointer(0)?.getString(0, "UTF-8").orEmpty()
                        val level = log.getPointer(8)?.getString(0, "UTF-8").orEmpty()
                        if (prefix.contains("vapoursynth") && level in setOf("fatal", "error")) failed = true
                    }
                }
                7 -> {
                    val end = event.getPointer(16)
                    if (end != null && end.getInt(0) == 4 && end.getInt(4) < 0) failed = true
                }
            }
        }
        return failed
    }

    private fun updateMotion(caps: SmoothingCapabilities): SmoothingStatus {
        fun status(detail: String, active: Boolean = false, beat: MotionHeartbeat? = null) =
            SmoothingStatus(caps, detail, motionActive = active, report = playbackReport(caps, detail, beat))
        if (!setSmoothing(false)) return status("Could not stop frame blending; reopen video")
        val file = expectedPath
        if (file == null || !file.equals(textProperty("path"), true)) return status("Waiting for the selected video")
        val unavailable = caps.unavailable(SmoothingMode.MOTION)
        if (unavailable != null) {
            if (!motion.end()) return status("Could not restore player settings; reopen video")
            return status(unavailable)
        }
        val source = caps.sourceFps?.let(MotionRate::source)
            ?: return status("Motion smoothing unavailable: source frame rate is uncertain")
        val target = MotionRate.target(source, caps.displayHz)
            ?: return status("Already at the target frame rate")
        motion.begin(file, source, target)?.let { return status("Motion smoothing unavailable: $it") }
        val beat = motion.heartbeat()
        val playing = isPaused() == false && flagProperty("seeking") == false &&
            flagProperty("paused-for-cache") == false && flagProperty("eof-reached") == false
        if (motion.active) {
            val drops = numberProperty("frame-drop-count")
            val decoderDrops = numberProperty("decoder-frame-drop-count")
            smoothingHealth.sample(System.nanoTime() / 1_000_000,
                if (drops != null && decoderDrops != null) (drops + decoderDrops).toLong() else null, playing)
            if (motion.stalled(playing, beat) || smoothingHealth.tripped) {
                engineErrorCount++
                motion.fail(if (smoothingHealth.tripped) "Too many dropped frames" else "The motion engine stopped responding")
            }
        }
        motion.failure?.let { return status("Motion smoothing unavailable: $it") }
        return if (beat != null && motion.active)
            status("Motion smoothing · ${target.label} fps target · ${caps.resolution}", true, beat)
        else status("Starting motion smoothing…")
    }

    private fun playbackReport(caps: SmoothingCapabilities, state: String, beat: MotionHeartbeat? = null): String {
        val names = listOf("frame-drop-count", "decoder-frame-drop-count", "vo-delayed-frame-count", "mistimed-frame-count")
        val totals = names.map { numberProperty(it)?.toLong() }
        val now = System.currentTimeMillis()
        reportSamples.addLast(now to totals)
        while (reportSamples.size > 1 && reportSamples.first.first < now - 60_000) reportSamples.removeFirst()
        val oldest = reportSamples.first
        val elapsed = ((now - oldest.first) / 1000).coerceAtMost(60)
        val deltas = totals.zip(oldest.second).map { (a, b) -> if (a != null && b != null && a >= b) (a-b).toString() else "unavailable" }
        val version = javaClass.getResourceAsStream("/skywing-build.txt")?.bufferedReader()?.use { it.readText().trim() } ?: "development"
        val fileKind = expectedPath?.let { if (it.startsWith("\\\\") || it.startsWith("//")) "network share" else "local file" } ?: "none"
        return buildString {
            appendLine("SkyWing $version · Windows x64 motion preview")
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Preferred motion GPU: ${MotionRuntime.preferredGpu}; driver: ${MotionRuntime.driver}")
            appendLine("Display: ${caps.displayHz ?: "unavailable"} Hz; VRR activation: not measured")
            appendLine("File: $fileKind; ${caps.resolution}; source ${caps.sourceFps ?: "unavailable"} fps")
            appendLine("Codec: ${textProperty("video-codec") ?: "unavailable"}; pixel format: ${textProperty("video-params/pixelformat") ?: "unavailable"}")
            appendLine("Transfer: ${textProperty("video-params/gamma") ?: "unavailable"}; decoder: ${textProperty("hwdec-current") ?: "unavailable"}")
            appendLine("Selected: ${smoothingMode.label}; state: $state")
            if (smoothingMode == SmoothingMode.MOTION) {
                appendLine("Engine: open-svpflow deadbad2; quality: Balanced; target: ${motion.target?.label ?: "none"} fps")
                appendLine("Filter processing throughput: ${beat?.processingFps ?: "unavailable"} fps (not presented FPS)")
                appendLine("Filter output callbacks: ${beat?.completed ?: 0}; observed graph generations: ${motion.rebuilds}")
            }
            appendLine("Presented FPS: not measured (pinned mpv estimated-vf-fps can report the source rate)")
            names.forEachIndexed { i, name -> appendLine("$name: ${totals[i] ?: "unavailable"}; last ${elapsed}s: ${deltas[i]}") }
            appendLine("Engine errors: $engineErrorCount; last failure: ${motion.failure ?: "none"}")
            appendLine("State-duration history: not collected in this preview")
        }
    }

    fun isPaused(): Boolean? = flagProperty("pause")
    fun isMuted(): Boolean? = flagProperty("mute")
    fun playbackPosition(): Double? = numberProperty("time-pos")
    fun playbackDuration(): Double? = numberProperty("duration")
    fun seekAbsolute(seconds: Double): Boolean =
        seconds.isFinite() && command("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")

    fun togglePause(): Boolean = command("cycle", "pause")
    fun pause(): Boolean = command("set", "pause", "yes")
    fun seek(seconds: Int): Boolean = command("seek", seconds.toString(), "relative")
    @Synchronized
    fun stop(): Boolean {
        expectedPath = null
        val restored = motion.end()
        return command("stop") && restored
    }
    fun volumeUp(): Boolean = command("add", "volume", "5")
    fun volumeDown(): Boolean = command("add", "volume", "-5")
    fun toggleMute(): Boolean = command("cycle", "mute")
    fun nextAudioTrack(): Boolean = command("cycle", "aid")
    fun nextSubtitleTrack(): Boolean = command("cycle", "sid")
    fun subtitleOff(): Boolean = command("set", "sid", "no")
    fun seekPercent(percent: Int): Boolean = command("seek", percent.coerceIn(0, 100).toString(), "absolute-percent")
    @Synchronized
    override fun close() {
        closed = true
        val native = api
        val context = instance
        instance = null
        api = null
        surface = null
        if (native != null && context != null) native.mpv_terminate_destroy(context)
        motion.cleanup()
    }
}
