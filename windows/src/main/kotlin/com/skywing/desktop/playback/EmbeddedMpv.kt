package com.skywing.desktop.playback

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.sun.jna.ptr.IntByReference
import java.awt.Canvas
import java.io.Closeable
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Experimental native Windows x64 libmpv host.
 * Uses mpv's Windows --wid child-window embedding; GPU-render API integration follows later.
 * Requires libmpv-2.dll and its dependencies to be bundled in the installed application.
 */
class EmbeddedMpv(
    /** The GPU SKYWING_GPU names for drawing and motion ([GpuOverride]), or null to follow the GPU plan. */
    private val forcedGpu: String? = GpuOverride.vendor(),
) : Closeable {
    private interface Api : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_request_log_messages(ctx: Pointer, level: String): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: DoubleByReference): Int
        fun mpv_get_property(ctx: Pointer, name: String, format: Int, data: IntByReference): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_command_async(ctx: Pointer, userdata: Long, args: Array<String?>): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_free(data: Pointer)
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    @Volatile private var api: Api? = null
    @Volatile private var instance: Pointer? = null
    /** Held for reading by [commandAsync] and for writing by [close], so mpv is never destroyed under a command. */
    private val lifetime = ReentrantReadWriteLock()
    private var surface: Long? = null
    private var closed = false
    private var rendererPreference = "Automatic"
    private var rendererDevice = "Not yet reported by mpv"
    /** The GPU name mpv reported opening ("Device Name:"), or null until it does. */
    private var openedGpu: String? = null
    private var canvasHandle: Pointer? = null
    private var inventory = GpuInventory(emptyList())
    /** The GPU that drives the window's monitor, for display queries such as VRR, even when SKYWING_GPU is set. */
    private var monitorGpu: GpuAdapters.Adapter? = null
    private val forcedVendor = forcedGpu?.let { name -> GpuVendor.entries.firstOrNull { it.label.equals(name, ignoreCase = true) } }
    /** The plan mpv's video output runs with (plan 5.3); Windows' choice when mpv refused [requestedPlan]'s GPU. */
    private var gpuPlan: GpuPlan? = null
    private var requestedPlan: GpuPlan? = null
    /** The choice [gpuPlan] was made for: Settings' own, or a video class's "Use <dedicated GPU> for smoothing". */
    private var gpuChoice = GpuChoice.AUTOMATIC
    /** A plan that differs from [gpuPlan], found while a video plays; the next video starts with it. */
    private var pendingPlan: GpuPlan? = null
    private val replan = Debounce<GpuSignature>(5_000)
    private var lastGpuCheck = 0L
    private var pathLuids = emptySet<Long>()
    private var inventoryReadAt = 0L
    /** Switches mpv's `vo` between two spellings of the same output, so setting it always re-creates the output. */
    private var voSpelling = false
    /** The file that has shown the "Use <dedicated GPU> for smoothing" offer; it is shown once per file. */
    private var offeredDedicated: String? = null
    private var dedicatedOffer: GpuAdapters.Adapter? = null
    private val smoothingHealth = SmoothingHealth()
    private var smoothingMode = SmoothingMode.OFF
    private var expectedPath: String? = null
    private var loadEndedWithError = false
    private val motion = MotionSession(::textProperty, { command(*it.toTypedArray()) },
        prepare = { dir -> motionGpu().let { MotionRuntime.prepare(dir, it, it != null && sharesMaker(it)) } })
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
    @Volatile private var lastActionMillis = 0L
    var lastError: String? = null
        private set
    /** A user-facing reason the video that was opened could not be played, or null. */
    var playbackFailure: String? = null
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
                set("cursor-autohide", "no")
                set("cursor-autohide-fs-only", "no")
                set("input-vo-keyboard", "no")
                set("wid", java.lang.Long.toUnsignedString(Pointer.nativeValue(handle)))
                set("force-window", "yes")
                set("keep-open", "yes")
                set("hwdec", "auto-safe")
                // The GPU plan (plan 5.3): Automatic keeps decoding, smoothing and presentation on the GPU that drives
                // this monitor, as before M2; SKYWING_GPU still forces one in test runs.
                canvasHandle = handle
                readGpus(handle, System.currentTimeMillis())
                gpuChoice = MotionSettingsStore.values.gpuChoice
                val plan = planFor(gpuChoice)
                requestedPlan = plan
                // mpv checks the name against its adapters and refuses one it can't find; Windows chooses then.
                if (plan.mpvAdapter.isNotEmpty() && native.mpv_set_option_string(context, "d3d11-adapter", plan.mpvAdapter) >= 0) {
                    set("gpu-api", "d3d11")
                    gpuPlan = plan
                    rendererPreference = plan.mpvAdapter
                    if (plan.adapter?.gpuVendor == GpuVendor.NVIDIA && System.getenv("SKYWING_D3D11_COMPAT") == "1") {
                        // Trial for dedicated-GPU displays that drop frames on the default swap chain.
                        set("vo", "gpu")
                        set("d3d11-flip", "no")
                        set("d3d11-sync-interval", "0")
                        rendererPreference += " (D3D11 compatibility)"
                    }
                } else {
                    set("d3d11-adapter", "")
                    gpuPlan = refused(plan)
                    rendererPreference = "Windows' choice"
                }
                replan.offer(signature(), System.currentTimeMillis())
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

    private fun noteAction(args: Array<out String>) {
        val action = when {
            args.firstOrNull() == "seek" -> "seek requested"
            args.firstOrNull() == "loadfile" -> "file change requested"
            args.firstOrNull() == "stop" -> "stop requested"
            args.firstOrNull() in setOf("set", "cycle") && args.getOrNull(1) == "pause" -> "pause/resume requested"
            else -> null
        }
        if (action != null) recordAction(action)
    }

    @Synchronized
    private fun command(vararg args: String): Boolean {
        noteAction(args)
        val native = api ?: return false
        val context = instance ?: return false
        return native.mpv_command(context, (args.toList() + listOf(null)).toTypedArray()) >= 0
    }

    /**
     * Sends a command the user gives from the screen thread without waiting: neither for mpv, which may be busy
     * rebuilding filters, nor for another call on this player, such as [updateSmoothing] starting motion (B7). True
     * when mpv queued it; mpv runs queued and waiting commands in the order they were sent. False while closing.
     */
    private fun commandAsync(vararg args: String): Boolean {
        val read = lifetime.readLock()
        if (!read.tryLock()) return false
        try {
            val native = api ?: return false
            val context = instance ?: return false
            noteAction(args)
            return native.mpv_command_async(context, 0L, (args.toList() + listOf(null)).toTypedArray()) >= 0
        } finally {
            read.unlock()
        }
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
        // Events still queued belong to the previous video; its failure must not be reported for this one.
        drainEvents()
        loadEndedWithError = false
        playbackFailure = null
        dedicatedOffer = null
        // A new video starts with a fresh GPU plan (plan 5.3). While smoothing is on, a video class's "Use <dedicated
        // GPU> for smoothing" is kept until this video's class is known, so videos of that class don't move twice.
        canvasHandle?.takeIf { instance != null }?.let { handle ->
            readGpus(handle, System.currentTimeMillis(), force = true)
            val settings = MotionSettingsStore.values.gpuChoice
            val choice = if (smoothingMode == SmoothingMode.MOTION && settings == GpuChoice.AUTOMATIC) gpuChoice else settings
            val plan = planFor(choice)
            gpuChoice = choice
            pendingPlan = null
            // A failed move still opens the video; the Renderer line shows the GPU mpv actually opened.
            if (!samePlan(plan, requestedPlan) && !restartVideoOutput(plan)) recordAction("GPU change failed")
        }
        // mpv keeps "pause" across loadfile: a video paused earlier (or by leaving the player) would leave the next one
        // stuck at 0:00. Opening a video means playing it.
        command("set", "pause", "no")
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
        motion.lastAction = synchronized(playerEvents) { playerEvents.lastOrNull() }
            ?.let { it + " (" + (System.currentTimeMillis() - lastActionMillis) + " ms ago)" } ?: "none"
        val motionWasActive = motion.active
        val filterFailed = drainEvents()
        checkGpus()
        // NvAPI knows only screens on NVIDIA GPUs, and Intel's driver reports nothing; ask it unless another maker's
        // GPU drives the screen.
        DisplayVrr.refresh(if (monitorGpu?.gpuVendor.let { it == null || it == GpuVendor.NVIDIA }) textProperty("display-names/0") else null)
        lastMinuteDrops = recentDrops.sample(System.nanoTime() / 1_000_000, listOf(
            numberProperty("frame-drop-count")?.toLong(), numberProperty("decoder-frame-drop-count")?.toLong()))
        if (filterFailed && motion.active) {
            engineErrorCount++
            motion.fail("The motion filter failed")
            if (flagProperty("idle-active") == true) expectedPath?.let {
                command("loadfile", it, "replace", "-1", "start=$lastPosition")
            }
        } else if (loadEndedWithError && !motionWasActive && playbackFailure == null) {
            // mpv accepted the file but could not open or decode it (damaged, truncated or unsupported).
            expectedPath?.let { playbackFailure = "SkyWing can’t play " + File(it).name + ". The file may be damaged or in a format SkyWing can’t read." }
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
                            openedGpu = rendererMessage.substringAfter("Device Name:").trim()
                            rendererDevice = MotionLog.redact(openedGpu.orEmpty())
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
                    if (end != null && end.getInt(0) == 4 && end.getInt(4) < 0) { failed = true; loadEndedWithError = true }
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
            SmoothingStatus(caps, detail, motionActive = active, report = playbackReport(caps, detail, beat), settings = settings, nextLowerStop = lower,
                gpuOffer = dedicatedOffer?.takeIf { gpuChoice == GpuChoice.AUTOMATIC }?.let(GpuPolicy::offerLabel))
        val file = expectedPath
        if (file == null || !file.equals(textProperty("path"), true)) return status("Waiting for the selected video")
        caps.unavailable(SmoothingMode.MOTION)?.let {
            if (!motion.end()) return status("Could not restore player settings; reopen video")
            return status(it)
        }
        if (source == null || settings == null) return status("Waiting for source frame rate")
        // "Use <dedicated GPU> for smoothing" is kept for this video's class, and a choice made in Settings while the
        // video plays applies now: drawing and smoothing move together, and the video goes on from where it was.
        val classChoice = MotionSettingsStore.values.gpuChoiceFor(key)
        if (classChoice != gpuChoice) when (useGpu(classChoice)) {
            true -> return status("Moving the video to " + (gpuPlan?.adapter?.name ?: "Windows' choice of graphics"))
            false -> return status("Could not change the graphics; reopen video")
            null -> {}
        }
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
            command("set", "video-sync", MotionRate.videoSync(source, target, caps.displayHz, vrr))
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
            if (motion.stalled(playing)) {
                engineErrorCount++
                if (motion.restartAfterStall()) {
                    smoothingHealth.reset()
                    command("set", "video-sync", MotionRate.videoSync(source, target, caps.displayHz, vrr))
                    return status("Motion engine restarted")
                }
                motion.fail("The motion engine stopped responding after one restart")
            } else if (playing && smoothingHealth.tripped) {
                val lower = allowed.lastOrNull { it < settings.stop }
                if (lower == null) {
                    // On a hybrid PC in Automatic the dedicated GPU is offered once per file before smoothing stops.
                    val offer = gpuPlan?.let { GpuPolicy.smoothingOffer(inventory, it, gpuChoice) }
                    if (offer != null && offeredDedicated != file) {
                        offeredDedicated = file
                        dedicatedOffer = offer
                        smoothingHealth.reset()
                        return status("Can’t keep up at 2× on " + gpuPlan?.adapter?.name, true, beat)
                    }
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
        val build = BuildIdentity.current
        // A mapped drive letter is a network share too (B29).
        val fileKind = com.skywing.desktop.NetworkPaths.kindOf(expectedPath)
        return buildString {
            appendLine("SkyWing ${build.version} · Windows x64 motion preview")
            appendLine(build.reportLine())
            appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            appendLine("Preferred motion GPU: ${MotionRuntime.preferredGpu}; driver: ${MotionRuntime.driver}; matched by: ${MotionRuntime.matchedBy}")
            appendLine("Renderer: $rendererDevice; preference: $rendererPreference; output: ${textProperty("current-vo") ?: "unavailable"}")
            val onBattery = com.skywing.desktop.PowerSource.onBattery()
            appendLine("GPU plan: " + (gpuPlan?.describe(onBattery) ?: "not made yet"))
            appendLine("Graphics for video: ${gpuChoice.label}; Settings: ${MotionSettingsStore.values.gpuChoice.label}; Windows' graphics setting for SkyWing: " +
                when (inventory.windowsPreference) {
                    WindowsGpuPreference.NONE -> "none"
                    WindowsGpuPreference.POWER_SAVING -> "power saving"
                    WindowsGpuPreference.HIGH_PERFORMANCE -> "high performance"
                })
            pendingPlan?.let { appendLine("Next video: " + it.describe(onBattery)) }
            appendLine("Display: ${caps.displayHz ?: "unavailable"} Hz; VRR activation: not measured")
            appendLine("VRR capability: " + (DisplayVrr.current.supported?.toString() ?: "unknown") + "; " + DisplayVrr.current.detail + "; choice: " + if (MotionSettingsStore.values.vrrOverride == null) "automatic" else "manual")
            appendLine("File: $fileKind; ${caps.resolution}; source ${caps.sourceFps ?: "unavailable"} fps")
            appendLine("Codec: ${textProperty("video-codec") ?: "unavailable"}; pixel format: ${textProperty("video-params/pixelformat") ?: "unavailable"}")
            appendLine("Transfer: ${textProperty("video-params/gamma") ?: "unavailable"}; decoder: ${textProperty("hwdec-current") ?: "unavailable"}")
            appendLine("Selected: ${smoothingMode.label}; state: $state")
            if (smoothingMode == SmoothingMode.MOTION) {
                appendLine("Engine: open-svpflow deadbad2; quality: ${(appliedMotionSettings?.quality ?: MotionQuality.DEFAULT).label}; target: ${motion.target?.label ?: "none"} fps")
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
            appendLine("Recent playback actions: " + synchronized(playerEvents) { playerEvents.joinToString("; ") })
            appendLine("Last 20 VapourSynth warnings/errors:")
            if (motionWarnings.isEmpty()) appendLine("none") else motionWarnings.forEach { appendLine(it) }
            appendLine("State-duration history: not collected in this preview")
        }
    }

    private fun planFor(choice: GpuChoice): GpuPlan = GpuPolicy.plan(inventory, monitorGpu, choice, forcedVendor)

    /**
     * Moves the playing video to the plan for [choice] now. Null when that is the GPU it already uses, true when it
     * moved, false when mpv would not re-create its output.
     */
    @Synchronized
    internal fun useGpu(choice: GpuChoice): Boolean? {
        gpuChoice = choice
        val plan = planFor(choice)
        if (samePlan(plan, requestedPlan)) return null
        dedicatedOffer = null
        return restartVideoOutput(plan)
    }

    /** The same GPU for mpv: a plan that differs only in its reason needs no new video output. */
    private fun samePlan(a: GpuPlan, b: GpuPlan?): Boolean = b != null && a.adapter?.luid == b.adapter?.luid && a.mpvAdapter == b.mpvAdapter

    /** mpv refused the GPU's name: Windows chooses, and the report says so. */
    private fun refused(plan: GpuPlan): GpuPlan =
        if (plan.mpvAdapter.isEmpty()) plan
        else plan.copy(adapter = null, mpvAdapter = "", reason = plan.reason + " · mpv refused it: using Windows' choice of graphics")

    /** The GPU mpv opened (from its log) as the PC lists it, else the plan's: smoothing never runs on another GPU. */
    private fun motionGpu(): GpuAdapters.Adapter? {
        val named = openedGpu?.let { name -> inventory.adapters.filter { it.name.equals(name, ignoreCase = true) } }.orEmpty()
        return named.firstOrNull { it == gpuPlan?.adapter } ?: named.firstOrNull() ?: gpuPlan?.adapter
    }

    /** Another GPU has this one's maker, so the motion engine must pick its OpenCL device by LUID. */
    private fun sharesMaker(adapter: GpuAdapters.Adapter): Boolean = inventory.adapters.count { it.gpuVendor == adapter.gpuVendor } > 1

    /**
     * Reads the screen's GPU and, when the screens changed or after a minute, the PC's GPUs again. Listing the GPUs
     * costs more than reading the screens, which is why it isn't done every time.
     */
    private fun readGpus(window: Pointer, now: Long, force: Boolean = false) {
        val paths = DisplayPaths.active()
        val luids = paths.map { it.adapterLuid }.toSet()
        if (force || luids != pathLuids || now - inventoryReadAt >= 60_000) {
            inventory = GpuInventory.read()
            pathLuids = luids
            inventoryReadAt = now
        }
        val gdi = DisplayPaths.monitorName(window)
        monitorGpu = paths.firstOrNull { gdi != null && it.gdiName.equals(gdi, ignoreCase = true) }?.adapterLuid?.let(inventory::byLuid)
            // A screen Windows' display paths can't place (or a virtual one) falls back to the adapter's maker.
            ?: WindowsDisplayAdapter.vendor(window)?.let { maker -> inventory.adapters.singleOrNull { it.gpuVendor.label == maker } }
    }

    private fun signature() = GpuSignature(monitorGpu?.luid, inventory.adapters.map { it.luid }, com.skywing.desktop.PowerSource.onBattery(),
        MotionSettingsStore.values.gpuChoice, inventory.windowsPreference)

    /**
     * Every 5 s while a video plays: a new screen, GPU, power source or graphics choice that holds for 5 s makes a new
     * plan (plan 5.3). It starts with the next video, or now when smoothing moves the video ([updateMotion]).
     */
    private fun checkGpus() {
        val now = System.currentTimeMillis()
        val handle = canvasHandle ?: return
        if (now - lastGpuCheck < 5_000) return
        lastGpuCheck = now
        readGpus(handle, now)
        replan.offer(signature(), now) ?: return
        pendingPlan = planFor(MotionSettingsStore.values.gpuChoice).takeIf { !samePlan(it, requestedPlan) }
    }

    /**
     * Moves drawing and smoothing to [plan]'s GPU while the video plays: smoothing stops, mpv gets the GPU's name and
     * re-creates its video output inside the same Canvas window, and the video goes on. Smoothing starts again on the
     * next [updateSmoothing].
     */
    private fun restartVideoOutput(plan: GpuPlan): Boolean {
        val native = api ?: return false
        val context = instance ?: return false
        if (!motion.end()) return false
        appliedMotionSettings = null
        smoothingHealth.reset()
        recordAction("GPU change")
        val accepted = plan.mpvAdapter.isNotEmpty() && command("set", "d3d11-adapter", plan.mpvAdapter)
        if (!accepted) command("set", "d3d11-adapter", "")
        requestedPlan = plan
        gpuPlan = if (accepted || plan.mpvAdapter.isEmpty()) plan else refused(plan)
        rendererPreference = gpuPlan?.mpvAdapter?.ifEmpty { null } ?: "Windows' choice"
        openedGpu = null
        rendererDevice = "Not yet reported by mpv"
        MotionRuntime.forgetDevices()
        // The new output logs the GPU it opens ("Device Name:"), so listen at the level that carries it again.
        native.mpv_request_log_messages(context, "v")
        // Setting `vo` re-creates the video output, which opens the GPU named in d3d11-adapter. "gpu" and "gpu," name
        // the same output, so alternating them always counts as a change.
        val output = textProperty("current-vo")?.takeIf { it.isNotBlank() } ?: "gpu"
        voSpelling = !voSpelling
        return command("set", "vo", if (voSpelling) "$output," else output)
    }

    private fun recordAction(action: String) {
        lastActionMillis = System.currentTimeMillis()
        synchronized(playerEvents) {
            playerEvents.addLast(action)
            while (playerEvents.size > 12) playerEvents.removeFirst()
        }
        motion.lastAction = action
    }

    /** SkyWing owns fullscreen and its shared chrome/cursor inactivity deadline. */
    fun setCursorHidden(hidden: Boolean): Boolean = commandAsync("set", "cursor-autohide", if (hidden) "always" else "no")

    /** Read-only mpv property as text, for diagnostics and tests (for example "aid", "sid", "video-codec"). */
    internal fun diagnosticProperty(name: String): String? = textProperty(name)

    /** Where the video is and whether it is paused or muted, read together. Call off the screen thread. */
    data class Reading(val position: Double?, val duration: Double?, val paused: Boolean?, val muted: Boolean?)

    @Synchronized
    fun reading(): Reading = Reading(numberProperty("time-pos"), numberProperty("duration"), flagProperty("pause"), flagProperty("mute"))

    fun isPaused(): Boolean? = flagProperty("pause")
    fun isMuted(): Boolean? = flagProperty("mute")
    fun playbackPosition(): Double? = numberProperty("time-pos")
    fun playbackDuration(): Double? = numberProperty("duration")
    fun seekAbsolute(seconds: Double): Boolean =
        seconds.isFinite() && commandAsync("seek", seconds.coerceAtLeast(0.0).toString(), "absolute")

    // Pause, seek, volume, mute and the cursor come from the screen thread, so they never wait (B7).
    fun togglePause(): Boolean = commandAsync("cycle", "pause")
    fun pause(): Boolean = commandAsync("set", "pause", "yes")
    fun seek(seconds: Int): Boolean = commandAsync("seek", seconds.toString(), "relative")
    @Synchronized
    fun stop(): Boolean {
        expectedPath = null
        loadEndedWithError = false
        playbackFailure = null
        val restored = motion.end()
        return command("stop") && restored
    }
    fun volumeUp(): Boolean = commandAsync("add", "volume", "5")
    fun volumeDown(): Boolean = commandAsync("add", "volume", "-5")
    fun toggleMute(): Boolean = commandAsync("cycle", "mute")
    fun nextAudioTrack(): Boolean = command("cycle", "aid")
    fun nextSubtitleTrack(): Boolean = command("cycle", "sid")
    fun subtitleOff(): Boolean = command("set", "sid", "no")
    fun seekPercent(percent: Int): Boolean = commandAsync("seek", percent.coerceIn(0, 100).toString(), "absolute-percent")
    @Synchronized
    override fun close() {
        closed = true
        val native: Api?
        val context: Pointer?
        lifetime.writeLock().lock()
        try {
            native = api
            context = instance
            instance = null
            api = null
            surface = null
        } finally {
            lifetime.writeLock().unlock()
        }
        // No command can start now: api and instance are cleared, and one already being sent has finished.
        if (native != null && context != null) native.mpv_terminate_destroy(context)
        motion.cleanup()
    }
}

/** What a GPU plan depends on; a change that holds for 5 s makes a new plan. */
private data class GpuSignature(
    val monitorLuid: Long?,
    val adapterLuids: List<Long>,
    val onBattery: Boolean,
    val choice: GpuChoice,
    val windowsPreference: WindowsGpuPreference,
)
