package com.skywing.desktop.playback

import com.sun.jna.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
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
            assertTrue(command(listOf("set", "pause", "yes")))
            assertFalse(session.stalled(false, session.heartbeat()))
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
            session.cleanup()
            folder.deleteRecursively()
        }
    }
}
