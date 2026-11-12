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
    private var rendererPreference = "Automatic"
    private var rendererDevice = "Not yet reported by mpv"
    private val smoothingHealth = SmoothingHealth()
    private var smoothingMode = SmoothingMode.OFF
    private var expectedPath: String? = null
    private val motion = MotionSession(::textProperty, { command(*it.toTypedArray()) })
    private var lastPosition = 0.0
    private var appliedMotionSettings: MotionSettings? = null
    private var appliedVrr: Boolean? = null
    private var motionNotice = ""
    private var motionNoticeUntil = 0L
    private val recentDrops = RecentDrops()
    private var lastMinuteDrops: Long? = null
    private val reportSamples = java.util.ArrayDeque<Pair<Long, List<Long?>>>()
    private var engineErrorCount = 0
    private val motionWarnings = java.util.ArrayDeque<String>()
    private val playerEvents = java.util.ArrayDeque<String>()
    private var lastActionMillis = 0L
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
                // The pinned mpv validates this name against installed DXGI adapters.
                // A rejected preference leaves the default adapter available on other PCs.
                if (native.mpv_set_option_string(context, "d3d11-adapter", "NVIDIA") >= 0) {
                    set("gpu-api", "d3d11")
                    rendererPreference = "NVIDIA D3D11"
                } else {
                    set("d3d11-adapter", "")
                }
                // Reserve decoder surfaces before loading a file for runtime temporal smoothing.
                set("hwdec-extra-frames", "8")
                check(native.mpv_initialize(context) >= 0) { "mpv_initialize failed" }
            } catch (failure: Throwable) {
                native.mpv_terminate_destroy(context)
                throw failure
            }
            native.mpv_request_log_messages(context, "v")
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
        val action = when {
            args.firstOrNull() == "seek" -> "seek requested"
            args.firstOrNull() == "loadfile" -> "file change requested"
            args.firstOrNull() == "stop" -> "stop requested"
            args.firstOrNull() in setOf("set", "cycle") && args.getOrNull(1) == "pause" -> "pause/resume requested"
            else -> null
        }
        if (action != null) recordAction(action)
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
        appliedMotionSettings = null
        motionNoticeUntil = 0L
        reportSamples.clear()
        recentDrops.reset()
        lastMinuteDrops = null
        lastPosition = 0.0
        smoothingHealth.reset()
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

    /** Call off the UI thread about once a second. Never overrides display mode, speed,
     * container FPS, HDR transforms, the Canvas peer, or the renderer. display-resample may
     * make mpv's small A/V clock corrections; it falls back for unsupported audio outputs. */
    @Synchronized
    fun updateSmoothing(mode: SmoothingMode): SmoothingStatus {
        motion.lastAction = playerEvents.lastOrNull()?.let { it + " (" + (System.currentTimeMillis() - lastActionMillis) + " ms ago)" } ?: "none"
        val filterFailed = drainEvents()
        lastMinuteDrops = recentDrops.sample(System.nanoTime() / 1_000_000, listOf(
            numberProperty("frame-drop-count")?.toLong(), numberProperty("decoder-frame-drop-count")?.toLong()))
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
        return SmoothingStatus(caps, "Smoothing off", report = playbackReport(caps, "Smoothing off"))
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
                        val rendererMessage = log.getPointer(16)?.getString(0, "UTF-8").orEmpty()
                        if (prefix.startsWith("vo/") && rendererMessage.trim().startsWith("Device Name:")) {
                            rendererDevice = MotionLog.redact(rendererMessage.substringAfter("Device Name:").trim())
                            native.mpv_request_log_messages(context, "warn")
                        }
                        if (prefix.contains("vapoursynth") && level in setOf("fatal", "error", "warn")) {
                            val message = log.getPointer(16)?.getString(0, "UTF-8").orEmpty()
                            motionWarnings.addLast("$level: " + MotionLog.redact(message))
                            while (motionWarnings.size > 20) motionWarnings.removeFirst()
                            if (level in setOf("fatal", "error")) failed = true
                        }
                    }
                }
                8 -> recordAction("file loaded")
                20 -> { recordAction("seek started"); smoothingHealth.reset() }
                21 -> { recordAction("playback resumed after reconfiguration"); smoothingHealth.reset() }
                7 -> {
                    val end = event.getPointer(16)
                    if (end != null && end.getInt(0) == 4 && end.getInt(4) < 0) failed = true
                }
            }
        }
        return failed
    }

    private fun updateMotion(caps: SmoothingCapabilities): SmoothingStatus {
        val source = caps.sourceFps?.let(MotionRate::source)
        val key = MotionStops.type(caps)
        val stored = source?.let { MotionSettingsStore.values.load(key, MotionStops.default(it)) }
        val allowed = source?.let { MotionStops.allowed(it, caps.displayHz) }.orEmpty()
        val settings = stored?.copy(stop = allowed.lastOrNull { it <= stored.stop } ?: allowed.firstOrNull() ?: stored.stop)
        fun status(detail: String, active: Boolean = false, beat: MotionHeartbeat? = null, lower: Int? = null) =
            SmoothingStatus(caps, detail, motionActive = active, report = playbackReport(caps, detail, beat), settings = settings, nextLowerStop = lower)
        val file = expectedPath
        if (file == null || !file.equals(textProperty("path"), true)) return status("Waiting for the selected video")
        caps.unavailable(SmoothingMode.MOTION)?.let {
            if (!motion.end()) return status("Could not restore player settings; reopen video")
            return status(it)
        }
        if (source == null || settings == null) return status("Waiting for source frame rate")
        val target = MotionRate.target(source, caps.displayHz, settings.stop)
            ?: return status("No smoothing rate fits this display")
        val vrr = MotionSettingsStore.values.vrr
        val changed = motion.active && (appliedMotionSettings != settings || appliedVrr != vrr)
        val position = if (changed) numberProperty("time-pos") else null
        if (changed && !motion.end()) return status("Could not change motion settings; reopen video")
        val starting = !motion.active
        motion.begin(file, source, target, settings.quality)?.let { return status("Motion smoothing unavailable: $it") }
        if (starting || appliedMotionSettings != settings || appliedVrr != vrr) {
            smoothingHealth.reset()
            appliedMotionSettings = settings
            appliedVrr = vrr
            val matchingDisplay = caps.displayHz?.let { kotlin.math.abs(it-target.fps) / target.fps < 0.01 } == true
            command("set", "video-sync", if (!vrr && matchingDisplay) "display-resample" else "audio")
            if (position != null) command("seek", position.toString(), "absolute+exact")
        }
        val beat = motion.heartbeat()
        val playing = isPaused() == false && flagProperty("seeking") == false &&
            flagProperty("paused-for-cache") == false && flagProperty("eof-reached") == false
        val drops = numberProperty("frame-drop-count")
        val decoderDrops = numberProperty("decoder-frame-drop-count")
        if (motion.active) {
            smoothingHealth.sample(System.nanoTime() / 1_000_000,
                if (drops != null && decoderDrops != null) (drops + decoderDrops).toLong() else null, playing,
                beat?.processingFps, target.fps)
            if (motion.stalled(playing, beat)) {
                engineErrorCount++
                if (motion.restartAfterStall()) {
                    smoothingHealth.reset()
                    val match = caps.displayHz?.let { kotlin.math.abs(it-target.fps) / target.fps < 0.01 } == true
                    command("set", "video-sync", if (!vrr && match) "display-resample" else "audio")
                    return status("Motion engine restarted")
                }
                motion.fail("The motion engine stopped responding after one restart")
            } else if (playing && smoothingHealth.tripped) {
                val lower = allowed.lastOrNull { it < settings.stop }
                if (lower == null) {
                    motion.fail("Cannot keep up at 2×; smoothing stopped")
                    return status("Cannot keep up at 2×; smoothing stopped").copy(requestOff = true)
                } else if (MotionSettingsStore.values.lowerAutomatically) {
                    MotionSettingsStore.values.save(key, settings.copy(stop = lower))
                    motionNotice = "Lowered to " + MotionStops.rate(source, lower).label + " fps (" + MotionStops.labels[lower] + ") to keep up"
                    motionNoticeUntil = System.currentTimeMillis() + 8000
                    return status(motionNotice, true, beat)
                } else {
                    return status("Can’t keep up at " + target.label, true, beat, lower)
                }
            }
        }
        motion.failure?.let { return status("Motion smoothing unavailable: $it") }
        return if (beat != null && motion.active) {
            val detail = if (System.currentTimeMillis() < motionNoticeUntil) motionNotice else
                "Holding " + target.label + " fps · " + (lastMinuteDrops?.let { "$it drops in last minute" } ?: "recent drops unavailable")
            status(detail, true, beat)
        } else status("Starting motion smoothing…")
    }

    private fun playbackReport(caps: SmoothingCapabilities, state: String, beat: MotionHeartbeat? = null): String {
        val retainedBeat = beat ?: motion.lastHeartbeat
        val incident = motion.lastIncident
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
            appendLine("Renderer: $rendererDevice; preference: $rendererPreference; output: ${textProperty("current-vo") ?: "unavailable"}")
            appendLine("Display: ${caps.displayHz ?: "unavailable"} Hz; VRR activation: not measured")
            appendLine("File: $fileKind; ${caps.resolution}; source ${caps.sourceFps ?: "unavailable"} fps")
            appendLine("Codec: ${textProperty("video-codec") ?: "unavailable"}; pixel format: ${textProperty("video-params/pixelformat") ?: "unavailable"}")
            appendLine("Transfer: ${textProperty("video-params/gamma") ?: "unavailable"}; decoder: ${textProperty("hwdec-current") ?: "unavailable"}")
            appendLine("Selected: ${smoothingMode.label}; state: $state")
            if (smoothingMode == SmoothingMode.MOTION) {
                appendLine("Engine: open-svpflow deadbad2; quality: ${appliedMotionSettings?.quality?.label ?: "Balanced"}; target: ${motion.target?.label ?: "none"} fps")
                appendLine("Filter processing throughput: ${retainedBeat?.processingFps ?: "unavailable"} fps (not presented FPS)")
                appendLine("Filter output callbacks: ${retainedBeat?.completed ?: 0}; observed graph generations: ${motion.rebuilds}")
            }
            appendLine("Multiplier: " + (appliedMotionSettings?.let { MotionStops.labels[it.stop] } ?: "none") + "; G-SYNC / FreeSync: " + MotionSettingsStore.values.vrr + "; lower automatically: " + MotionSettingsStore.values.lowerAutomatically)
            appendLine("Drops in last 60 seconds (video + decoder): " + (lastMinuteDrops ?: "unavailable"))
            appendLine("Presented FPS: not measured (pinned mpv estimated-vf-fps can report the source rate)")
            names.forEachIndexed { i, name -> appendLine("$name: ${totals[i] ?: "unavailable"}; last ${elapsed}s: ${deltas[i]}") }
            appendLine("Engine errors: $engineErrorCount; last failure: ${motion.failure ?: "none"}")
            appendLine("Queue: concurrent=auto; buffered=8; status interval=0.5 s")
            appendLine("Last heartbeat age: " + (retainedBeat?.let { now - it.updatedMillis } ?: "unavailable") + " ms")
            appendLine("Stall recovery attempts: " + motion.restarts)
            if (incident != null) {
                appendLine("Last incident: " + incident.reason)
                appendLine("Time to incident: " + incident.elapsedMillis + " ms; heartbeat age then: " + incident.heartbeatAgeMillis + " ms")
                appendLine("Motion filter present at incident: " + incident.filterPresent + "; preceding action: " + incident.action)
                appendLine("Retained incident throughput: " + incident.heartbeat?.processingFps + " fps; output callbacks: " + incident.heartbeat?.completed)
                incident.counters.forEach { (key, value) -> appendLine("At incident $key: " + (value ?: "unavailable")) }
            }
            appendLine("Recent playback actions: " + playerEvents.joinToString("; "))
            appendLine("Last 20 VapourSynth warnings/errors:")
            if (motionWarnings.isEmpty()) appendLine("none") else motionWarnings.forEach { appendLine(it) }
            appendLine("State-duration history: not collected in this preview")
        }
    }

    private fun recordAction(action: String) {
        lastActionMillis = System.currentTimeMillis()
        playerEvents.addLast(action)
        while (playerEvents.size > 12) playerEvents.removeFirst()
        motion.lastAction = action
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
