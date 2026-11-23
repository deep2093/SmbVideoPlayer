package com.skywing.desktop.automation

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Synthetic test videos, encoded by the same libmpv that SkyWing ships (mpv's encoding mode with ffmpeg's lavfi
 * test sources). Nothing is downloaded and no user media is read. Files are written only into the given folder.
 */
internal object TestMedia {
    private interface Mpv : Library {
        fun mpv_create(): Pointer?
        fun mpv_set_option_string(ctx: Pointer, name: String, value: String): Int
        fun mpv_initialize(ctx: Pointer): Int
        fun mpv_command(ctx: Pointer, args: Array<String?>): Int
        fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
        fun mpv_terminate_destroy(ctx: Pointer)
    }

    data class Spec(
        val name: String,
        val videoCodec: String,
        val audioCodec: String?,
        val seconds: Int = 6,
        val fps: Int = 24,
        val width: Int = 320,
        val height: Int = 240,
        val toneHz: Int = 440,
    )

    val MP4_H264 = Spec("clip-h264.mp4", "libx264", "aac")
    val MKV_VP9 = Spec("clip-vp9.mkv", "libvpx-vp9", "libopus")

    private val apis = mutableMapOf<String, Mpv>()

    /**
     * The libmpv already used by in-process native tests (compose.application.resources.dir), else the test image's.
     * One process must not load two libmpv copies: their ffmpeg DLLs share names.
     */
    fun libmpv(image: Path = SkyWingTestApp.image): File {
        val resources = System.getProperty("compose.application.resources.dir")?.let { File(it, "libmpv-2.dll") }
        if (resources?.isFile == true) return resources
        val packaged = image.resolve("app/resources/libmpv-2.dll").toFile()
        check(packaged.isFile) { "No libmpv-2.dll in compose.application.resources.dir or the test image" }
        return packaged
    }

    @Synchronized
    fun encode(folder: File, spec: Spec, dll: File = libmpv()): File {
        val out = File(folder, spec.name)
        val mpv = apis.getOrPut(dll.absolutePath) { Native.load(dll.absolutePath, Mpv::class.java, mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")) }
        val ctx = mpv.mpv_create() ?: error("mpv_create failed")
        try {
            fun set(name: String, value: String) = check(mpv.mpv_set_option_string(ctx, name, value) >= 0) { "mpv encode option $name=$value" }
            set("config", "no")
            set("o", out.absolutePath)
            set("ovc", spec.videoCodec)
            if (spec.audioCodec != null) {
                set("oac", spec.audioCodec)
                set("audio-files", "av://lavfi:sine=frequency=${spec.toneHz}:sample_rate=48000:duration=${spec.seconds}")
            } else {
                set("audio", "no")
            }
            check(mpv.mpv_initialize(ctx) >= 0) { "mpv_initialize failed for encoding" }
            val source = "av://lavfi:testsrc2=size=${spec.width}x${spec.height}:rate=${spec.fps}:duration=${spec.seconds}"
            check(mpv.mpv_command(ctx, arrayOf("loadfile", source, null)) >= 0) { "loadfile failed for encoding" }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
            while (System.nanoTime() < deadline) {
                // MPV_EVENT_END_FILE = 7: the source has been fully encoded.
                if (mpv.mpv_wait_event(ctx, 0.5).getInt(0) == 7) break
            }
        } finally {
            mpv.mpv_terminate_destroy(ctx)
        }
        check(out.isFile && out.length() > 1000) { "Encoding ${spec.name} produced no file" }
        return out
    }

    /** A subtitle file mpv loads automatically next to [video] (same base name). */
    fun subtitlesFor(video: File, text: String = "SkyWing test subtitle"): File =
        File(video.parentFile, video.nameWithoutExtension + ".srt").apply {
            writeText("1\r\n00:00:00,000 --> 00:00:30,000\r\n$text\r\n\r\n")
        }

    /** ffmpeg on PATH, used only to mux several generated streams into one MKV. Null when it is not installed. */
    val ffmpeg: File? by lazy {
        System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg.exe") }.firstOrNull { it.isFile }
    }

    /**
     * A constant-rate H.264/AAC MP4 written by ffmpeg. libmpv's encoding mode leaves the last frame without a duration,
     * so its files report a slightly-off average rate (24.34 fps for 24). SkyWing smooths that as a measured rate, but
     * motion tests expect the exact rates of a 24 fps video, so they use this instead.
     */
    fun constantRateMp4(folder: File, name: String, seconds: Int, width: Int, height: Int, fps: Int): File {
        val tool = checkNotNull(ffmpeg) { "ffmpeg is required for constant-rate fixtures" }
        val out = File(folder, name)
        val process = ProcessBuilder(
            tool.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=${width}x$height:rate=$fps:duration=$seconds",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=$seconds",
            "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120, TimeUnit.SECONDS) && process.exitValue() == 0 && out.isFile) { "ffmpeg encode failed: $log" }
        return out
    }

    /**
     * An MKV with one VP9 video, two Opus audio tracks (different tones, languages eng and fra) and two embedded
     * SubRip tracks. Streams are encoded by libmpv; ffmpeg only copies them into the container.
     */
    fun multiTrackMkv(folder: File): File {
        val tool = checkNotNull(ffmpeg) { "ffmpeg is required to mux multi-track MKV fixtures" }
        val video = encode(folder, Spec("mt-video.mkv", "libvpx-vp9", null, seconds = 8))
        val first = encode(folder, Spec("mt-audio1.mkv", "libvpx-vp9", "libopus", seconds = 8, toneHz = 440, width = 16, height = 16))
        val second = encode(folder, Spec("mt-audio2.mkv", "libvpx-vp9", "libopus", seconds = 8, toneHz = 880, width = 16, height = 16))
        val english = File(folder, "mt-en.srt").apply { writeText("1\r\n00:00:00,000 --> 00:00:30,000\r\nEnglish line\r\n\r\n") }
        val french = File(folder, "mt-fr.srt").apply { writeText("1\r\n00:00:00,000 --> 00:00:30,000\r\nLigne française\r\n\r\n") }
        val out = File(folder, "clip-multitrack.mkv")
        val process = ProcessBuilder(
            tool.absolutePath, "-hide_banner", "-loglevel", "error", "-y",
            "-i", video.absolutePath, "-i", first.absolutePath, "-i", second.absolutePath,
            "-i", english.absolutePath, "-i", french.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-map", "2:a:0", "-map", "3:0", "-map", "4:0",
            "-c", "copy", "-c:s", "srt",
            "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=fra",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=fra",
            out.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0 && out.isFile) { "ffmpeg mux failed: $log" }
        listOf(video, first, second, english, french).forEach { it.delete() }
        return out
    }
}
