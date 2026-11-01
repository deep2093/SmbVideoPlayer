package com.deepak.videolibrary.player

import android.content.Context
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Measures whether this device can run CPU smoothing at 1080p, with the same settings as playback
 * ([NativeSvpTuning] CPU threads and lookahead), on a synthetic 1080p 24 fps clip: a panning textured background
 * plus a striped object moving the other way. 24 fps is the heaviest common case (48 new pictures a second).
 *
 * After a short warm-up it measures (1) flat-out throughput and (2) ten seconds requested at the real 60 fps pace,
 * counting outputs that finish later than the display queue can hide. Only the CPU path is exercised; no GPU, no
 * video decoding. Decoding and display also need some of the phone, so the pass mark keeps headroom.
 */
internal object NativeSvpSpeedCheck {
    data class Result(val qualifies: Boolean, val summary: String)

    private const val WIDTH = 1920
    private const val HEIGHT = 1080
    private const val FPS = 24
    private const val WARMUP = 24
    private const val BURST = 120
    private const val PACED = 600
    private const val FRAME_NS = 16_666_667L

    fun run(context: Context, timeoutMs: Long = 90_000L): Result {
        if (!NativeSvpStream.available) return Result(false, "Speed check unavailable: native libraries are missing")
        val answer = AtomicReference<Result?>()
        // Named like the playback worker so engine threads it creates are recognisable too.
        val thread = Thread({
            answer.set(
                try {
                    measure(context)
                } catch (t: Throwable) {
                    Result(false, "Speed check failed: ${t.message ?: t.javaClass.simpleName}")
                },
            )
        }, "SkyWing-native-check")
        thread.isDaemon = true
        thread.start()
        thread.join(timeoutMs)
        return answer.get() ?: Result(false, "Speed check did not finish within ${timeoutMs / 1000} s")
    }

    private fun measure(context: Context): Result {
        val result = measurePaced(
            context, WIDTH, HEIGHT, useGpu = false, threads = NativeSvpTuning.CPU_THREADS,
            ahead = NativeSvpTuning.CPU_AHEAD, textures = NativeSvpTuning.CPU_TEXTURES, minFlatOut = 90.0,
        )
        val verdict = if (result.qualifies) "1080p OK" else "stays at 720p"
        return Result(result.qualifies, "$verdict · ${result.summary}")
    }

    /**
     * Paced engine test shared by the CPU 1080p check and the GPU quality-level check: warm-up, a flat-out burst, then
     * ten seconds requested at the real 60 fps pace. Passes when at most 0.5% of outputs finish later than the display
     * queue ([textures] - 1 frames) can hide and flat-out speed is at least [minFlatOut] fps.
     */
    fun measurePaced(
        context: Context,
        width: Int,
        height: Int,
        useGpu: Boolean,
        threads: Int,
        ahead: Int,
        textures: Int,
        minFlatOut: Double,
    ): Result {
        NativeSvpTuning.boostCurrentThread()
        val handle = NativeSvpStream.create(
            NativeSvpStream.libraryDirectory(context), width, height, FPS.toLong(), 1L, useGpu, threads, ahead,
        )
        check(handle != 0L) { "engine could not be created" }
        try {
            val scene = NativeSvpTestScene(width, height)
            val frame = ByteBuffer.allocateDirect(width * height * 3 / 2)
            val output = ByteBuffer.allocateDirect(width * height * 3 / 2)
            val totalOutputs = WARMUP + BURST + PACED
            val totalSource = totalOutputs * FPS / 60 + 12
            var pushed = 0
            // Keep source frames a little ahead of what the engine may prefetch, well inside its 32-frame ring.
            fun feed(index: Int) {
                val want = minOf(totalSource, (index + ahead) * FPS / 60 + 6)
                while (pushed < want) {
                    scene.fill(frame, pushed)
                    NativeSvpStream.push(handle, frame)
                    pushed++
                }
            }
            fun render(index: Int) {
                feed(index)
                check(NativeSvpStream.render(handle, index, output)) { "engine returned no frame $index" }
            }
            for (i in 0 until WARMUP) render(i)
            val burstStart = System.nanoTime()
            for (i in WARMUP until WARMUP + BURST) render(i)
            val burstFps = BURST / ((System.nanoTime() - burstStart) / 1e9)

            val durations = LongArray(PACED)
            var late = 0
            // The worker can be this far ahead of the screen (output textures minus the one on screen).
            val slack = (textures - 1) * FRAME_NS
            val pacedStart = System.nanoTime()
            for (k in 0 until PACED) {
                val target = pacedStart + k * FRAME_NS
                val wait = target - System.nanoTime()
                if (wait > 0) LockSupport.parkNanos(wait)
                val begin = System.nanoTime()
                render(WARMUP + BURST + k)
                val done = System.nanoTime()
                durations[k] = done - begin
                if (done - target > slack) late++
            }
            durations.sort()
            fun ms(ns: Long) = String.format(Locale.ROOT, "%.1f", ns / 1e6)
            val p50 = durations[PACED / 2]
            val p95 = durations[(PACED * 95) / 100]
            val worst = durations[PACED - 1]
            // Pass: (almost) nothing late at the real pace, and enough spare speed for decoding and display.
            val qualifies = late <= PACED / 200 && burstFps >= minFlatOut
            val summary = "flat out ${String.format(Locale.ROOT, "%.0f", burstFps)} fps · per frame typical ${ms(p50)} ms, " +
                "slowest 5% ${ms(p95)} ms, worst ${ms(worst)} ms · late $late of $PACED · $threads threads, $ahead ahead"
            android.util.Log.i("NativeSvp", "Paced check ${width}x$height gpu=$useGpu: $summary")
            return Result(qualifies, summary)
        } finally {
            NativeSvpStream.cancel(handle)
            NativeSvpStream.close(handle)
        }
    }
}

/** Synthetic YUV420 frames for engine tests: textured background panning right, striped block moving left. */
internal class NativeSvpTestScene(private val width: Int, private val height: Int) {
    private val pan = 6
    private val span = width + pan * 400
    private val world = ByteArray(span * height).also { world ->
        for (y in 0 until height) {
            for (x in 0 until span) {
                val h = (x * 73856093) xor (y * 19349663)
                val noise = (h ushr 13) and 0x3F
                world[y * span + x] = (32 + ((x / 3 + y / 2) and 0x7F) + noise).toByte()
            }
        }
    }
    private val stripes = ByteArray(minOf(400, width / 3)) { if ((it / 5) % 2 == 0) 230.toByte() else 30.toByte() }
    private val chroma = ByteArray(width * height / 2) { 128.toByte() }

    fun fill(frame: ByteBuffer, n: Int) {
        frame.clear()
        val shift = (pan * n) % (span - width)
        for (y in 0 until height) frame.put(world, y * span + shift, width)
        val left = Math.floorMod(width * 3 / 4 - 9 * n, width - stripes.size)
        for (y in height * 3 / 8 until height * 5 / 8) {
            frame.position(y * width + left)
            frame.put(stripes)
        }
        frame.position(width * height)
        frame.put(chroma)
    }
}
