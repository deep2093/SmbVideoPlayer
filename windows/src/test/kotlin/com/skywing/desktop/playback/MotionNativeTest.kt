package com.skywing.desktop.playback

import com.skywing.desktop.automation.TestMedia
import com.sun.jna.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.*

/** Exercises portable Python inside the JVM with the shipped script, not a standalone Python host. */
class MotionNativeTest {
    private interface Api : Library {
        fun mpv_create(): Pointer
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
        fun mpv_free(p: Pointer)
        fun mpv_terminate_destroy(ctx: Pointer)
    }
    @Test(timeout = 150000) fun portableEngineSurvivesSeekPauseDisableAndClose() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        val resources = File(System.getProperty("compose.application.resources.dir"))
        assertNotNull(MotionRuntime.directory(), "CI must bundle the portable engine before native tests")
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        val log = File("build/reports/tests/test/motion-native.log").absoluteFile
        log.parentFile.mkdirs()
        val folder = Files.createTempDirectory("skywing-motion-é-").toFile()
        fun get(name: String): String? {
            val p = api.mpv_get_property_string(ctx, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
        }
        fun command(args: List<String>): Boolean = api.mpv_command(ctx, (args + listOf(null)).toTypedArray()) >= 0
        fun waitFor(message: String, predicate: () -> Boolean) {
            val until = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message + "\n" + if (log.isFile) log.readText().takeLast(8000) else "No native log")
        }
        val session = MotionSession(::get, ::command, scriptDirectory = { folder })
        try {
            listOf("config" to "no", "vo" to "null", "audio" to "no", "keep-open" to "yes",
                "hwdec" to "no", "pause" to "yes").forEach { (k,v) -> assertTrue(api.mpv_set_option_string(ctx,k,v) >= 0) }
            assertTrue(api.mpv_set_option_string(ctx, "log-file", log.path) >= 0)
            // A missing vendor must be rejected without preventing default playback.
            assertTrue(api.mpv_set_option_string(ctx, "d3d11-adapter", "SkyWing-nonexistent-adapter") < 0)
            assertTrue(api.mpv_set_option_string(ctx, "d3d11-adapter", "") >= 0)
            assertTrue(api.mpv_initialize(ctx) >= 0)
            // Locally generated 8-bit YUV source: no network or user media access.
            val source = File(folder, "sample-é.y4m")
            source.outputStream().buffered().use { out ->
                out.write("YUV4MPEG2 W192 H128 F24:1 Ip A1:1 C420jpeg\n".toByteArray())
                repeat(240) { n ->
                    out.write("FRAME\n".toByteArray())
                    out.write(ByteArray(192*128) { i -> (16 + ((i % 192 + n*3) xor (i/192)) % 220).toByte() })
                    out.write(ByteArray(192*128/2) { 128.toByte() })
                }
            }
            assertTrue(command(listOf("loadfile", source.path)))
            waitFor("Source did not load") { get("container-fps")?.toDoubleOrNull() == 24.0 }
            assertTrue(command(listOf("vf", "add", "@keep:hflip")))
            val original = listOf("hwdec", "video-sync", "interpolation").associateWith(::get)
            assertNull(session.begin(source.path, MotionRate(24), MotionRate(48)))
            assertTrue(command(listOf("set", "pause", "no")))
            waitFor("No output from portable motion runtime: " + session.failure) { (session.heartbeat()?.completed ?: 0) >= 24 }
            if (File(resources, "MPV-PREVIEW-MANIFEST.json").isFile) {
                waitFor("Experimental mpv did not enable VapourSynth output sharing") {
                    log.readText().contains("Using experimental read-only VapourSynth output planes")
                }
            }
            assertTrue(command(listOf("set", "pause", "yes")))
            assertFalse(session.stalled(false))
            assertTrue(command(listOf("set", "pause", "no")))
            repeat(60) { n ->
                val generation = session.heartbeat()?.generation
                assertTrue(command(listOf("seek", (1+n%4).toString(), "absolute+exact")))
                waitFor("Motion did not recover after seek") { session.heartbeat()?.let { it.generation != generation && it.completed > 5 } == true }
            }
            for (quality in MotionQuality.entries) {
                assertTrue(session.end())
                assertNull(session.begin(source.path, MotionRate(24), MotionRate(48), quality))
                waitFor("No output for quality " + quality.label) {
                    val beat = session.heartbeat()
                    assertNull(session.failure, "Preset failed: " + quality.label)
                    (beat?.completed ?: 0) > 5
                }
            }
            assertTrue(session.end())
            original.forEach { (k,v) -> assertEquals(v,get(k), "Did not restore $k") }
            assertTrue(get("vf").orEmpty().contains("keep"))
            assertFalse(get("vf").orEmpty().contains("skywing-motion"))
            // A plugin load error happens asynchronously after vf add has succeeded.
            val broken = MotionSession(::get, ::command, { folder }, { null }, { folder })
            assertNull(broken.begin(source.path, MotionRate(24), MotionRate(48)))
            waitFor("Failed plugin did not fall back") { broken.heartbeat(); broken.failure != null }
            assertFalse(broken.active)
            original.forEach { (k,v) -> assertEquals(v,get(k), "Error did not restore $k") }
            val position = get("time-pos")?.toDoubleOrNull() ?: 0.0
            waitFor("Ordinary playback did not continue after plugin failure") { (get("time-pos")?.toDoubleOrNull() ?: 0.0) > position + 0.2 }
            // A second file and closing with an active filter exercise ownership/teardown.
            assertTrue(command(listOf("loadfile", source.path)))
            waitFor("Second file did not load") { get("container-fps")?.toDoubleOrNull() == 24.0 }
            assertNull(session.begin(source.path, MotionRate(24), MotionRate(48)))
            waitFor("Second filter did not produce output") { (session.heartbeat()?.completed ?: 0) > 5 }
        } finally {
            api.mpv_terminate_destroy(ctx)
            session.release()
            folder.deleteRecursively()
        }
    }

    /**
     * Plan 5.8 rule 9, checked in the pinned libmpv: `deinterlace=auto` (SkyWing's setting) deinterlaces only frames
     * flagged interlaced, and `deinterlace-active` or `video-frame-info/interlaced` tells SkyWing so.
     */
    @Test(timeout = 120000) fun interlacedVideoIsFlaggedAndDeinterlacedOnlyWhenInterlaced() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        val ffmpeg = TestMedia.ffmpeg
        assumeTrue("ffmpeg is needed for the interlaced fixture", ffmpeg != null)
        val resources = File(System.getProperty("compose.application.resources.dir"))
        val folder = Files.createTempDirectory("skywing-interlaced-").toFile()
        fun encode(name: String, vararg options: String): File {
            val video = File(folder, name)
            val process = ProcessBuilder(listOf(ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=720x576:rate=50:duration=3") + options.toList() +
                listOf("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", video.absolutePath))
                .redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertTrue(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 && video.isFile, "ffmpeg: $output")
            return video
        }
        // Two 50 fps frames woven into each 25 fps interlaced frame, top field first; and the same clip progressive.
        val interlaced = encode("interlaced.mkv", "-vf", "interlace=scan=tff", "-flags", "+ilme+ildct", "-field_order", "tt")
        val progressive = encode("progressive.mkv", "-vf", "fps=25")
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        fun get(name: String): String? {
            val p = api.mpv_get_property_string(ctx, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
        }
        fun command(args: List<String>): Boolean = api.mpv_command(ctx, (args + listOf(null)).toTypedArray()) >= 0
        fun waitFor(message: String, predicate: () -> Boolean) {
            val until = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message)
        }
        try {
            listOf("config" to "no", "vo" to "null", "ao" to "null", "pause" to "yes", "keep-open" to "yes",
                "deinterlace" to "auto").forEach { (k, v) -> assertTrue(api.mpv_set_option_string(ctx, k, v) >= 0, k) }
            assertTrue(api.mpv_initialize(ctx) >= 0)
            assertTrue(command(listOf("loadfile", interlaced.absolutePath)))
            // deinterlace=auto deinterlaces it to the field rate. video-frame-info then describes the deinterlaced
            // frame, so SkyWing also reads deinterlace-active to know the source is interlaced.
            waitFor("deinterlace=auto did not deinterlace an interlaced frame") { get("deinterlace-active") == "yes" }
            assertEquals("25.000000", get("container-fps"))
            waitFor("Deinterlacing did not output the field rate") { get("estimated-vf-fps")?.toDoubleOrNull()?.let { abs(it - 50) < 0.5 } == true }
            assertEquals("no", get("video-frame-info/interlaced"))
            // Without deinterlacing, the frame itself is flagged.
            assertTrue(command(listOf("set", "deinterlace", "no")))
            waitFor("The interlaced frame was not flagged") { get("video-frame-info/interlaced") == "yes" }
            assertEquals("yes", get("video-frame-info/tff"))
            assertEquals("no", get("deinterlace-active"))
            assertTrue(command(listOf("set", "deinterlace", "auto")))
            assertTrue(command(listOf("loadfile", progressive.absolutePath)))
            waitFor("The progressive fixture never showed a frame") {
                get("path") == progressive.absolutePath && get("video-frame-info/interlaced") != null
            }
            assertEquals("no", get("video-frame-info/interlaced"))
            waitFor("deinterlace=auto deinterlaced a progressive frame") { get("deinterlace-active") == "no" }
        } finally {
            api.mpv_terminate_destroy(ctx)
            folder.deleteRecursively()
        }
    }

    /**
     * Plan 5.8 rule 4, checked with the pinned open-svpflow: with no OpenCL device to use, it smooths on the processor.
     * SkyWing takes that path, up to 1080p at 2×, only when the PC has no OpenCL device at all. Each in-between frame
     * must be a new picture, not a repeated one. The rates printed are this PC's unpaced throughput (mpv untimed, no
     * display) and the processor cores the whole process used meanwhile, on the processor and then on OpenCL; they
     * are measurements, not pass marks.
     */
    @Test(timeout = 240000) fun theMotionEngineSmoothsOnTheProcessorWithoutAnOpenClDevice() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        val ffmpeg = TestMedia.ffmpeg
        assumeTrue("ffmpeg is needed for the 1080p fixture", ffmpeg != null)
        val resources = File(System.getProperty("compose.application.resources.dir"))
        assertNotNull(MotionRuntime.directory(), "CI must bundle the portable engine before native tests")
        val folder = Files.createTempDirectory("skywing-motion-cpu-").toFile()
        val video = File(folder, "1080p24.mkv")
        val encode = ProcessBuilder(ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=1920x1080:rate=24:duration=60",
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectErrorStream(true).start()
        val encodeLog = encode.inputStream.bufferedReader().readText()
        assertTrue(encode.waitFor(90, TimeUnit.SECONDS) && encode.exitValue() == 0 && video.isFile, "ffmpeg: $encodeLog")
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        val log = File("build/reports/tests/test/motion-cpu-native.log").absoluteFile
        log.parentFile.mkdirs()
        fun get(name: String): String? {
            val p = api.mpv_get_property_string(ctx, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
        }
        fun command(args: List<String>): Boolean = api.mpv_command(ctx, (args + listOf(null)).toTypedArray()) >= 0
        fun waitFor(message: String, predicate: () -> Boolean) {
            val until = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message + "\n" + if (log.isFile) log.readText().takeLast(8000) else "No native log")
        }
        val cpu = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        // What SkyWing chooses on a PC without OpenCL: the filter no device's vendor contains.
        val processor = OpenClDevice.choose(emptyList(), adapter = null, byLuid = false)
        assertTrue(processor.processor)
        var engine = processor
        val session = MotionSession(::get, ::command, prepare = { MotionRuntime.prepare(it, engine) }, scriptDirectory = { folder })
        // Smooths from the start and prints the unpaced output rate and the cores the process used, after a warm-up.
        fun measure(what: String) {
            assertTrue(command(listOf("set", "pause", "yes")))
            assertTrue(command(listOf("seek", "0", "absolute+exact")))
            waitFor("Playback did not return to the start") {
                get("seeking") == "no" && get("time-pos")?.toDoubleOrNull()?.let { it < 0.05 } == true
            }
            assertNull(session.begin(video.path, MotionRate(24), MotionRate(48), MotionQuality.FAST))
            assertTrue(command(listOf("set", "pause", "no")))
            waitFor("No output from the motion engine $what: " + session.failure) { (session.heartbeat()?.completed ?: 0) >= 96 }
            val start = assertNotNull(session.heartbeat())
            val busy = cpu.processCpuTime
            val wall = System.nanoTime()
            waitFor("The motion engine $what stopped: " + session.failure) {
                (session.heartbeat()?.completed ?: 0) >= start.completed + 1440 || get("eof-reached") == "yes"
            }
            val cores = (cpu.processCpuTime - busy) / ((System.nanoTime() - wall).toDouble())
            val beat = assertNotNull(session.heartbeat())
            assertNull(session.failure)
            val frames = beat.completed - start.completed
            val seconds = (beat.updatedMillis - start.updatedMillis) / 1000.0
            assertTrue(frames > 0 && seconds > 0, "No frames after warm-up $what")
            println(String.format(Locale.ROOT, "Motion engine %s, 1080p 24 to 48 fps, Fast, unpaced: %.0f output frames/s (%d in %.1f s), %.1f of %d cores",
                what, frames / seconds, frames, seconds, cores, Runtime.getRuntime().availableProcessors()))
        }
        try {
            listOf("config" to "no", "vo" to "null", "audio" to "no", "keep-open" to "yes", "hwdec" to "no",
                "pause" to "yes", "untimed" to "yes", "screenshot-format" to "png", "log-file" to log.path)
                .forEach { (k, v) -> assertTrue(api.mpv_set_option_string(ctx, k, v) >= 0, k) }
            assertTrue(api.mpv_initialize(ctx) >= 0)
            assertTrue(command(listOf("loadfile", video.path)))
            waitFor("The 1080p fixture did not load") { get("container-fps")?.toDoubleOrNull() == 24.0 }
            measure("on the processor")
            // Three output frames in a row: every second one is made between two source frames, and none repeats.
            assertTrue(command(listOf("set", "pause", "yes")))
            waitFor("Playback did not pause") { get("pause") == "yes" && get("seeking") == "no" }
            val shots = (0..2).map { k ->
                val before = assertNotNull(get("time-pos")?.toDoubleOrNull())
                assertTrue(command(listOf("frame-step")))
                waitFor("A frame step showed no later frame") { (get("time-pos")?.toDoubleOrNull() ?: before) > before }
                File(folder, "frame-$k.png").also { assertTrue(command(listOf("screenshot-to-file", it.path, "video")), "screenshot") }
            }
            shots.zipWithNext().forEach { (a, b) ->
                assertFalse(a.readBytes().contentEquals(b.readBytes()), "${a.name} and ${b.name} are the same picture")
            }
            assertNull(session.failure)
            assertTrue(session.end(), "Smoothing on the processor did not end")
            engine = MotionRuntime.choose()
            if (!engine.processor) {
                measure("on OpenCL (" + engine.device?.vendor + ")")
                assertTrue(session.end(), "Smoothing on OpenCL did not end")
            }
        } finally {
            api.mpv_terminate_destroy(ctx)
            session.release()
            folder.deleteRecursively()
        }
    }

    /**
     * A variable-rate MP4 (23.976 fps with every fifth frame missing, so frames are 42 or 83 ms apart) smoothed 2× and
     * 2.5×. mpv times the filter's output by adding up frame durations. Each output frame stands for a source position,
     * a half or two-fifths of a frame after the one before, and must show at that position's time between its source
     * frames, also after a seek. Retiming to the average rate instead puts frames up to 30 ms off. The fixture has no
     * B-frames, so each frame's container duration is its display time.
     */
    @Test(timeout = 240000) fun variableRateVideoKeepsItsFrameTimesWhenSmoothed() = withFixtures { folder ->
        val video = encode(folder, "variable-rate.mp4", "24000/1001", "-vf", "select='not(eq(mod(n,5),4))'",
            "-fps_mode", "vfr", "-bf", "0")
        val smoothed = smooth(video, "motion-vfr-native.log") { source, shown, p, q, what -> assertFollows(source, shown, p, q, what) }
        assertTrue(smoothed.gaps.any { it > 0.08 }, "The fixture has no missing frames: ${smoothed.gaps}")
        assertFalse(smoothed.rate.standard, "the fixture's average is measured, ${smoothed.rate}")
    }

    /**
     * Plan 5.8 rule 10: a phone video can average a standard rate while its frames are not evenly spaced. Here frames
     * are 50 and 17 ms apart, 30 fps on average, and mpv reports 30 fps for MP4. Smoothed, each output frame must still
     * show at its source position's time, and SkyWing must learn that the rate varies, to play it against the audio.
     * Checked in MP4 without and with B-frames: mpv gives each frame the time to the next one's in display order, so
     * B-frames change nothing. Also checked in Matroska. There mpv reports the default duration ffmpeg writes, 60 fps
     * (the fastest frames), and frame times rounded to the millisecond: 17 and 16 ms gaps are 1/60 s rounded, and count
     * as that exact period, while the 50 ms gaps are off it. Output frames are then within a millisecond of the
     * rounded times.
     */
    @Test(timeout = 400000) fun aVariableRateThatAveragesAStandardRateKeepsItsFrameTimes() = withFixtures { folder ->
        val vary = arrayOf("-vf", "select='not(mod(n,4))+eq(mod(n,4),3)'", "-fps_mode", "vfr")
        for ((name, bFrames, rate) in listOf(Triple("averages-30.mp4", "0", MotionRate(30)),
            Triple("averages-30-b-frames.mp4", "3", MotionRate(30)), Triple("averages-30.mkv", "3", MotionRate(60)))) {
            val video = encode(folder, name, "60", *vary, "-bf", bFrames)
            val within = if (name.endsWith(".mkv")) 1e-3 else 1e-4
            val smoothed = smooth(video, "motion-vfr-standard-native.log") { source, shown, p, q, what ->
                assertFollows(source, shown, p, q, "$name, $what", within)
            }
            assertEquals(rate, smoothed.rate, "$name's standard rate")
            assertTrue(smoothed.gaps.any { it > 0.045 } && smoothed.gaps.any { it < 0.02 }, "$name is not uneven: ${smoothed.gaps}")
            assertTrue(smoothed.variable, "$name: SkyWing did not learn that its rate varies")
        }
    }

    /**
     * Plan 5.8 rule 10's other side: a constant 23.976 fps Matroska file, whose frame times are rounded to the
     * millisecond (41 or 42 ms apart), is retimed to its exact period, every output frame 1001/48000 s after the one
     * before at 2×, and stays a constant rate, so it keeps display-resample's exact cadence.
     */
    @Test(timeout = 240000) fun aConstantRateMatroskaFileIsRetimedToItsExactPeriod() = withFixtures { folder ->
        val video = encode(folder, "constant-23.976.mkv", "24000/1001", "-bf", "3")
        val smoothed = smooth(video, "motion-mkv-native.log") { _, shown, p, q, what ->
            val step = q * 1001.0 / (p * 24000.0)
            shown.forEachIndexed { k, time -> assertEquals(shown[0] + k * step, time, 1e-5, "$what: output frame $k of $shown") }
        }
        assertEquals(MotionRate(24000, 1001), smoothed.rate)
        assertTrue(smoothed.gaps.any { abs(it - 0.041) < 1e-4 } && smoothed.gaps.any { abs(it - 0.042) < 1e-4 },
            "Matroska rounds frame times to the millisecond: ${smoothed.gaps}")
        assertFalse(smoothed.variable, "a constant rate's rounded times are not a variable rate")
    }

    /**
     * Plan 5.7: what each option set costs on this PC. Each plays in real time (mpv timed, no display, no audio),
     * 24 to 48 fps, on the OpenCL device SkyWing chooses; after 5 s of warm-up, 20 s give the output rate and the
     * processor cores the whole process used (decoding, the motion search and the copies; not the GPU's own time).
     * Fast, Balanced and the PotPlayer-like experiment, at 1080p and 4K. The fixture is a generated pattern, not film,
     * so only the differences between rows mean anything. They are measurements, not pass marks;
     * `build/reports/tests/test/motion-cost.txt` keeps them.
     */
    @Test(timeout = 900000) fun eachOptionSetsCostIsMeasuredInRealTime() = withFixtures { folder ->
        val rows = mutableListOf<String>()
        val sets = listOf(Triple("Fast", MotionQuality.FAST, null), Triple("Balanced", MotionQuality.BALANCED, null),
            Triple("PotPlayer-like", MotionQuality.FAST, MotionExperiment.POTPLAYER_LIKE))
        for (size in listOf("1920x1080", "3840x2160")) {
            val video = encodePattern(folder, "pattern-$size.mkv", size, 40)
            measureInRealTime(video, "motion-cost-native.log") { smooth ->
                for ((label, quality, experiment) in sets) rows += "$size, $label: " + smooth(quality, experiment)
            }
        }
        rows.add(0, "OpenCL device: " + MotionRuntime.preferredGpu + " (driver " + MotionRuntime.driver + "); processor: " +
            Processor.current.line())
        rows.forEach(::println)
        File("build/reports/tests/test/motion-cost.txt").absoluteFile.apply { parentFile.mkdirs() }
            .writeText(rows.joinToString("\n", postfix = "\n"))
    }

    private fun withFixtures(block: (File) -> Unit) {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        assumeTrue("ffmpeg is needed for the frame-time fixtures", TestMedia.ffmpeg != null)
        assertNotNull(MotionRuntime.directory(), "CI must bundle the portable engine before native tests")
        val folder = Files.createTempDirectory("skywing-motion-times-").toFile()
        try { block(folder) } finally { folder.deleteRecursively() }
    }

    /** A 4-second generated clip at [rate] fps, encoded by ffmpeg with [options]. */
    private fun encode(folder: File, name: String, rate: String, vararg options: String): File {
        val video = File(folder, name)
        val encode = ProcessBuilder(listOf(TestMedia.ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=$rate:duration=4") + options +
            listOf("-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", video.absolutePath),
        ).redirectErrorStream(true).start()
        val encodeLog = encode.inputStream.bufferedReader().readText()
        assertTrue(encode.waitFor(60, TimeUnit.SECONDS) && encode.exitValue() == 0 && video.isFile, "ffmpeg: $encodeLog")
        return video
    }

    /** A generated pattern of [size] for [seconds] at 24 fps, as SkyWing would get a camera or film file. */
    private fun encodePattern(folder: File, name: String, size: String, seconds: Int): File {
        val video = File(folder, name)
        val encode = ProcessBuilder(TestMedia.ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=$size:rate=24:duration=$seconds",
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectErrorStream(true).start()
        val encodeLog = encode.inputStream.bufferedReader().readText()
        assertTrue(encode.waitFor(300, TimeUnit.SECONDS) && encode.exitValue() == 0 && video.isFile, "ffmpeg: $encodeLog")
        return video
    }

    /**
     * Opens [video] with no display, and gives [block] a function that smooths it from the start at 2× with one option
     * set, in real time, and says what it took: the output rate and the process's cores over 20 s after 5 s.
     */
    private fun measureInRealTime(video: File, logName: String, block: ((MotionQuality, MotionExperiment?) -> String) -> Unit) {
        val resources = File(System.getProperty("compose.application.resources.dir"))
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        val log = File("build/reports/tests/test/$logName").absoluteFile
        log.parentFile.mkdirs()
        fun get(name: String): String? {
            val p = api.mpv_get_property_string(ctx, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
        }
        fun command(args: List<String>): Boolean = api.mpv_command(ctx, (args + listOf(null)).toTypedArray()) >= 0
        fun waitFor(message: String, predicate: () -> Boolean) {
            val until = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message + "\n" + if (log.isFile) log.readText().takeLast(8000) else "No native log")
        }
        val cpu = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean
        val session = MotionSession(::get, ::command, scriptDirectory = { video.parentFile })
        try {
            listOf("config" to "no", "vo" to "null", "audio" to "no", "keep-open" to "yes", "pause" to "yes",
                "log-file" to log.path).forEach { (k, v) -> assertTrue(api.mpv_set_option_string(ctx, k, v) >= 0, k) }
            assertTrue(api.mpv_initialize(ctx) >= 0)
            assertTrue(command(listOf("loadfile", video.path)))
            waitFor("${video.name} did not load") { get("container-fps")?.toDoubleOrNull() == 24.0 }
            val rate = MotionRate(24)
            block { quality, experiment ->
                assertTrue(command(listOf("set", "pause", "yes")))
                assertTrue(command(listOf("seek", "0", "absolute+exact")))
                waitFor("Playback did not return to the start") {
                    get("seeking") == "no" && get("time-pos")?.toDoubleOrNull()?.let { it < 0.05 } == true
                }
                assertNull(session.begin(video.path, rate, MotionStops.rate(rate, 0), quality, experiment))
                assertTrue(command(listOf("set", "pause", "no")))
                waitFor("No output from the motion engine: " + session.failure) {
                    (session.heartbeat()?.completed ?: 0) > 0 && (get("time-pos")?.toDoubleOrNull() ?: 0.0) >= 5.0
                }
                val start = assertNotNull(session.heartbeat())
                val busy = cpu.processCpuTime
                val wall = System.nanoTime()
                Thread.sleep(20_000)
                val cores = (cpu.processCpuTime - busy) / ((System.nanoTime() - wall).toDouble())
                assertNotEquals("yes", get("eof-reached"), "The fixture ended during the measurement")
                val beat = assertNotNull(session.heartbeat())
                assertNull(session.failure)
                val frames = beat.completed - start.completed
                val seconds = (beat.updatedMillis - start.updatedMillis) / 1000.0
                assertTrue(frames > 0 && seconds > 0, "No frames after warm-up")
                assertTrue(session.end(), "Smoothing did not end")
                String.format(Locale.ROOT, "%.2f of %d cores at %.1f output frames/s (%d in %.1f s)",
                    cores, Runtime.getRuntime().availableProcessors(), frames / seconds, frames, seconds)
            }
        } finally {
            api.mpv_terminate_destroy(ctx)
            session.release()
        }
    }

    /** The rate SkyWing smoothed [video] at, its frames' own gaps, and whether it found the rate varies. */
    private data class Smoothed(val rate: MotionRate, val gaps: List<Double>, val variable: Boolean)

    /**
     * Smooths [video] at 2× and 2.5× from the first frame and after a seek, and gives [check] the frames' own times and
     * the output frames' times, one frame step at a time, with p output frames for every q source frames.
     */
    private fun smooth(video: File, logName: String, check: (List<Double>, List<Double>, Int, Int, String) -> Unit): Smoothed {
        val resources = File(System.getProperty("compose.application.resources.dir"))
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        val log = File("build/reports/tests/test/$logName").absoluteFile
        log.parentFile.mkdirs()
        fun get(name: String): String? {
            val p = api.mpv_get_property_string(ctx, name) ?: return null
            return try { p.getString(0, "UTF-8") } finally { api.mpv_free(p) }
        }
        fun command(args: List<String>): Boolean = api.mpv_command(ctx, (args + listOf(null)).toTypedArray()) >= 0
        fun waitFor(message: String, predicate: () -> Boolean) {
            val until = System.nanoTime() + 20_000_000_000L
            while (System.nanoTime() < until) { if (predicate()) return; Thread.sleep(50) }
            fail(message + "\n" + if (log.isFile) log.readText().takeLast(8000) else "No native log")
        }
        fun position(): Double = assertNotNull(get("time-pos")?.toDoubleOrNull(), "No playback position")
        // The time of the frame shown now, then of each frame after it, one frame step at a time.
        fun frameTimes(steps: Int): List<Double> = buildList {
            add(position())
            repeat(steps) {
                val before = last()
                assertTrue(command(listOf("frame-step")))
                waitFor("A frame step from $before s showed no later frame") {
                    (get("time-pos")?.toDoubleOrNull() ?: before) > before && get("seeking") == "no"
                }
                add(position())
            }
        }
        val session = MotionSession(::get, ::command, scriptDirectory = { video.parentFile })
        try {
            listOf("config" to "no", "vo" to "null", "audio" to "no", "keep-open" to "yes", "hwdec" to "no",
                "pause" to "yes", "log-file" to log.path).forEach { (k, v) -> assertTrue(api.mpv_set_option_string(ctx, k, v) >= 0) }
            assertTrue(api.mpv_initialize(ctx) >= 0)
            assertTrue(command(listOf("loadfile", video.path)))
            waitFor("${video.name} did not load") {
                get("container-fps")?.toDoubleOrNull() != null && get("time-pos")?.toDoubleOrNull() != null
            }
            // Each frame's own time, from playback without the filter.
            val source = frameTimes(24)
            val rate = MotionRate.source(assertNotNull(get("container-fps")?.toDoubleOrNull()))
            assertNotNull(rate, "SkyWing must smooth ${video.name}'s average rate, " + get("container-fps") + " fps")
            // 2× and 2.5× (five output frames for every two source frames), the stops for a rate near 24 or 30 fps.
            for ((stop, p, q) in listOf(Triple(0, 2, 1), Triple(1, 5, 2))) {
                val label = MotionStops.labels[stop]
                assertTrue(command(listOf("seek", "0", "absolute+exact")))
                waitFor("Playback did not return to the first frame") {
                    get("seeking") == "no" && get("time-pos")?.toDoubleOrNull()?.let { abs(it - source[0]) < 1e-4 } == true
                }
                assertNull(session.begin(video.path, rate, MotionStops.rate(rate, stop)))
                waitFor("No output from the motion engine at $label: " + session.failure) {
                    (session.heartbeat()?.completed ?: 0) > 0 && get("seeking") == "no"
                }
                check(source, frameTimes((source.size - 2) * p / q), p, q, "$label from the start")
                // A seek rebuilds the filter, which then times its output from the first frame it gets.
                val generation = session.heartbeat()?.generation
                assertTrue(command(listOf("seek", String.format(Locale.ROOT, "%.6f", source[9]), "absolute+exact")))
                waitFor("Motion did not restart after the seek at $label: " + session.failure) {
                    session.heartbeat()?.generation.let { it != null && it != generation } && get("seeking") == "no"
                }
                check(source, frameTimes(20), p, q, "$label after a seek")
                assertNull(session.failure)
                assertTrue(session.end(), "Smoothing at $label did not end")
            }
            return Smoothed(rate, source.zipWithNext { a, b -> b - a }, session.variableRate)
        } finally {
            api.mpv_terminate_destroy(ctx)
            session.release()
        }
    }

    // The time of source position x: whole frames, then a fraction of the next frame's duration.
    private fun timeAt(source: List<Double>, x: Double): Double {
        val j = x.toInt()
        return source[j] + (x - j) * (source[j + 1] - source[j])
    }

    // p output frames for every q source frames: each output frame is q/p of a source frame after the one before,
    // counted from the whole frame the filter started at.
    private fun assertFollows(source: List<Double>, shown: List<Double>, p: Int, q: Int, what: String, within: Double = 1e-4) {
        val j = source.indexOfLast { it <= shown[0] + within }
        assertTrue(j >= 0 && j + 1 < source.size, "$what: the first frame, at ${shown[0]} s, is outside $source")
        // The nearest output frame's position; output frame 0 below must show at its time.
        val first = Math.round((j + (shown[0] - source[j]) / (source[j + 1] - source[j])) * p).toDouble() / p
        shown.forEachIndexed { k, time ->
            val x = first + k * q.toDouble() / p
            if (x < source.size - 1) assertEquals(timeAt(source, x), time, within, "$what: output frame $k of $shown against $source")
        }
    }
}
