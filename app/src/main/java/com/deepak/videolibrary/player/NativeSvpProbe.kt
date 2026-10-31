package com.deepak.videolibrary.player

import android.content.Context
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * Start-up check for the native engine, run on its own thread with a time limit and no video involved. It runs a short
 * synthetic 1080p clip (24 to 60 fps) through the same engine settings as playback. A phone whose OpenCL driver fails,
 * or hangs, is found out here, in the background, instead of freezing a real video. Only hard failures (an error, or no
 * answer in time) fail the check: speed is judged during real playback, where there is real evidence.
 */
internal object NativeSvpProbe {
    data class Result(val ok: Boolean, val detail: String)

    fun run(context: Context, timeoutMs: Long = 25_000L): Result {
        if (!NativeSvpStream.available) return Result(false, "native libraries are missing")
        val answer = AtomicReference<Result?>()
        val thread = Thread({
            answer.set(
                try {
                    probe(context)
                } catch (t: Throwable) {
                    NativeSvpDiagnostics.record(t)
                    Result(false, NativeSvpDiagnostics.describe(t))
                },
            )
        }, "SkyWing-native-probe")
        // A hung driver call cannot be interrupted; the daemon thread is simply left behind.
        thread.isDaemon = true
        thread.start()
        thread.join(timeoutMs)
        return answer.get() ?: Result(false, "engine did not answer within ${timeoutMs / 1000} s")
    }

    private fun probe(context: Context): Result {
        val width = 1920
        val height = 1080
        val started = SystemClock.elapsedRealtime()
        val handle = NativeSvpStream.create(NativeSvpStream.libraryDirectory(context), width, height, 24L, 1L)
        check(handle != 0L) { "engine could not be created" }
        try {
            val bytes = width * height * 3 / 2
            val frame = ByteBuffer.allocateDirect(bytes)
            val row = ByteArray(width)
            for (n in 0 until 6) {
                frame.clear()
                for (y in 0 until height) {
                    // Textured pattern that moves 8 pixels a frame, so the engine has real motion to find.
                    for (x in 0 until width) row[x] = (((x - 8 * n) * 5 + y * 3) xor ((x - 8 * n) shr 2)).toByte()
                    frame.put(row)
                }
                val grey = ByteArray(width * height / 2) { 128.toByte() }
                frame.put(grey)
                NativeSvpStream.push(handle, frame)
            }
            NativeSvpStream.end(handle)
            val created = SystemClock.elapsedRealtime() - started
            val output = ByteBuffer.allocateDirect(bytes)
            val renderStart = SystemClock.elapsedRealtime()
            for (index in 0 until 10) {
                check(NativeSvpStream.render(handle, index, output)) { "engine returned no frame $index" }
            }
            val perFrame = (SystemClock.elapsedRealtime() - renderStart) / 10.0
            return Result(true, "set-up $created ms, ${"%.1f".format(java.util.Locale.ROOT, perFrame)} ms per output frame")
        } finally {
            NativeSvpStream.close(handle)
        }
    }
}
