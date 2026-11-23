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
     * A variable-rate MP4 (23.976 fps with every fifth frame missing, so frames are 42 or 83 ms apart) smoothed 2× and
     * 2.5×. mpv times the filter's output by adding up frame durations. Each output frame stands for a source position,
     * a half or two-fifths of a frame after the one before, and must show at that position's time between its source
     * frames, also after a seek. Retiming to the average rate instead puts frames up to 30 ms off. The fixture has no
     * B-frames, so each frame's container duration is its display time.
     */
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

    @Test(timeout = 240000) fun variableRateVideoKeepsItsFrameTimesWhenSmoothed() {
        assumeTrue(System.getenv("SKYWING_TEST_MPV") == "true")
        val ffmpeg = TestMedia.ffmpeg
        assumeTrue("ffmpeg is needed for the variable-rate fixture", ffmpeg != null)
        val resources = File(System.getProperty("compose.application.resources.dir"))
        assertNotNull(MotionRuntime.directory(), "CI must bundle the portable engine before native tests")
        val folder = Files.createTempDirectory("skywing-motion-vfr-").toFile()
        val video = File(folder, "variable-rate.mp4")
        val encode = ProcessBuilder(ffmpeg!!.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=24000/1001:duration=4",
            "-vf", "select='not(eq(mod(n,5),4))'", "-fps_mode", "vfr",
            "-c:v", "libx264", "-preset", "veryfast", "-bf", "0", "-pix_fmt", "yuv420p", video.absolutePath,
        ).redirectErrorStream(true).start()
        val encodeLog = encode.inputStream.bufferedReader().readText()
        assertTrue(encode.waitFor(60, TimeUnit.SECONDS) && encode.exitValue() == 0 && video.isFile, "ffmpeg: $encodeLog")
        val api = Native.load(File(resources, "libmpv-2.dll").path, Api::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val ctx = api.mpv_create()
        val log = File("build/reports/tests/test/motion-vfr-native.log").absoluteFile
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
        // The time of source position x: whole frames, then a fraction of the next frame's duration.
        fun timeAt(source: List<Double>, x: Double): Double {
            val j = x.toInt()
            return source[j] + (x - j) * (source[j + 1] - source[j])
        }
        // p output frames for every q source frames: each output frame is q/p of a source frame after the one before,
        // counted from the whole frame the filter started at.
        fun assertFollows(source: List<Double>, shown: List<Double>, p: Int, q: Int, what: String) {
            val j = source.indexOfLast { it <= shown[0] + 1e-4 }
            assertTrue(j >= 0 && j + 1 < source.size, "$what: the first frame, at ${shown[0]} s, is outside $source")
            val position = j + (shown[0] - source[j]) / (source[j + 1] - source[j])
            assertEquals(Math.round(position * p).toDouble(), position * p, 1e-3 * p,
                "$what: the first frame, at ${shown[0]} s, is not at an output frame's position")
            val first = Math.round(position * p).toDouble() / p
            shown.forEachIndexed { k, time ->
                val x = first + k * q.toDouble() / p
                if (x < source.size - 1) assertEquals(timeAt(source, x), time, 1e-4, "$what: output frame $k of $shown against $source")
            }
        }
        val session = MotionSession(::get, ::command, scriptDirectory = { folder })
        try {
            listOf("config" to "no", "vo" to "null", "audio" to "no", "keep-open" to "yes", "hwdec" to "no",
                "pause" to "yes", "log-file" to log.path).forEach { (k, v) -> assertTrue(api.mpv_set_option_string(ctx, k, v) >= 0) }
            assertTrue(api.mpv_initialize(ctx) >= 0)
            assertTrue(command(listOf("loadfile", video.path)))
            waitFor("The variable-rate fixture did not load") {
                get("container-fps")?.toDoubleOrNull() != null && get("time-pos")?.toDoubleOrNull() != null
            }
            // Each frame's own time, from playback without the filter.
            val source = frameTimes(24)
            assertTrue(source.zipWithNext { a, b -> b - a }.any { it > 0.08 }, "The fixture has no missing frames: $source")
            val rate = MotionRate.source(assertNotNull(get("container-fps")?.toDoubleOrNull()))
            assertNotNull(rate, "SkyWing must smooth the fixture's average rate, " + get("container-fps") + " fps")
            // 2× and 2.5× (five output frames for every two source frames), the stops for a rate near 24 fps.
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
                assertFollows(source, frameTimes((source.size - 2) * p / q), p, q, "$label from the start")
                // A seek rebuilds the filter, which then times its output from the first frame it gets.
                val generation = session.heartbeat()?.generation
                assertTrue(command(listOf("seek", String.format(Locale.ROOT, "%.6f", source[9]), "absolute+exact")))
                waitFor("Motion did not restart after the seek at $label: " + session.failure) {
                    session.heartbeat()?.generation.let { it != null && it != generation } && get("seeking") == "no"
                }
                assertFollows(source, frameTimes(20), p, q, "$label after a seek")
                assertNull(session.failure)
                assertTrue(session.end(), "Smoothing at $label did not end")
            }
        } finally {
            api.mpv_terminate_destroy(ctx)
            session.release()
            folder.deleteRecursively()
        }
    }
}
