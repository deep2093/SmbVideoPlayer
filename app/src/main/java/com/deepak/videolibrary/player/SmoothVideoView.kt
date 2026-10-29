package com.deepak.videolibrary.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Experimental video output that shows 24/25/30 fps video at 60 Hz by blending the two
 * neighbouring decoded frames on the GPU (stage 1 of the 60 fps work). It replaces the
 * PlayerView surface: the player decodes into [FrameBlendRenderer]'s SurfaceTexture and the
 * renderer draws at display vsync into a TextureView sized by an AspectRatioFrameLayout.
 *
 * Blending is a cross-fade, not motion-compensated yet. If GL setup fails, [onFailure] is
 * invoked so the screen can fall back to the normal PlayerView.
 */
@OptIn(UnstableApi::class)
internal class SmoothVideoView(context: Context) : FrameLayout(context) {
    private val frame = AspectRatioFrameLayout(context)
    private val texture = TextureView(context)
    private var renderer: FrameBlendRenderer? = null
    private var player: Player? = null
    private var inputSurface: Surface? = null
    var onFailure: (() -> Unit)? = null
    var onStats: ((FrameBlendRenderer.Stats) -> Unit)? = null

    /** 0 = clean (only trusted motion, otherwise the real frame), 1 = balanced, 2 = maximum smoothing. */
    var strength: Int = 1
        set(value) {
            field = value
            renderer?.strength = value
        }

    var debug: Boolean = false
        set(value) {
            field = value
            renderer?.debug = value
        }

    var resizeMode: Int
        get() = frame.resizeMode
        set(value) {
            if (frame.resizeMode != value) frame.resizeMode = value
        }

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                frame.setAspectRatio(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height)
                renderer?.setVideoSize(videoSize.width, videoSize.height)
            }
        }
    }

    init {
        frame.addView(texture, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, android.view.Gravity.CENTER))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val refresh = display?.refreshRate ?: 60f
                val divisor = Math.round(refresh / 60f).coerceAtLeast(1)
                val created = FrameBlendRenderer(
                    divisor,
                    onInputReady = { surface -> post { attachInput(surface) } },
                    onError = { post { onFailure?.invoke() } },
                )
                created.onStats = { stats -> post { onStats?.invoke(stats) } }
                created.strength = strength
                created.debug = debug
                renderer = created
                player?.videoSize?.let { if (it.width > 0) created.setVideoSize(it.width, it.height) }
                created.start(st)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                detachInput()
                renderer?.stopBlocking()
                renderer = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun attach(newPlayer: Player) {
        player?.removeListener(listener)
        player = newPlayer
        newPlayer.addListener(listener)
        listener.onVideoSizeChanged(newPlayer.videoSize)
        inputSurface?.let { newPlayer.setVideoSurface(it) }
    }

    fun release() {
        detachInput()
        player?.removeListener(listener)
        player = null
        renderer?.stopBlocking()
        renderer = null
    }

    private fun attachInput(surface: Surface) {
        inputSurface = surface
        player?.setVideoSurface(surface)
    }

    private fun detachInput() {
        inputSurface?.let { player?.clearVideoSurface(it) }
        inputSurface = null
    }
}

/** Owns the GL thread: decoded frames in via SurfaceTexture, blended 60 Hz frames out. */
internal class FrameBlendRenderer(
    initialDivisor: Int,
    private val onInputReady: (Surface) -> Unit,
    private val onError: () -> Unit,
) {
    private val thread = HandlerThread("smooth60").also { it.start() }
    private val handler = Handler(thread.looper)
    private val clock = BlendClock()

    /** What the screen is really getting, once a second: frames decoded, frames drawn, and whether motion is being added. */
    data class Stats(
        val sourceFps: Float,
        val outputFps: Float,
        val interpolating: Boolean,
        val warping: Boolean,
        /** Share of drawn frames shown as plain real frames because too much of the picture had untrusted motion. */
        val fallbackPercent: Int = 0,
    )

    var onStats: ((Stats) -> Unit)? = null

    @Volatile var strength: Int = 1
    @Volatile var debug: Boolean = false
    private var framesIn = 0
    private var fallbackDrawn = 0
    private var frameFallback = false
    private var vectorPixels: ByteBuffer? = null
    private var framesOut = 0
    private var statsSince = 0L

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var windowSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var inputTexture = 0
    private var inputTexture2D: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private val textures = IntArray(2)
    private var framebuffer = 0
    private var copyProgram = 0
    private var blendProgram = 0
    private var lumaProgram = 0
    private var meProgram = 0
    private var warpProgram = 0
    private var vectorFilterProgram = 0
    private var vectorRawTexture = 0
    private var vectorBackTexture = 0
    private val lumaTextures = IntArray(2)
    private var vectorTexture = 0
    private var lumaWidth = 0
    private var lumaHeight = 0
    private var vectorWidth = 0
    private var vectorHeight = 0
    private var vectorsValid = false
    private var frameWidth = 1920
    private var frameHeight = 1080
    private var allocatedWidth = 0
    private var allocatedHeight = 0
    private var current = 0
    private var ready = false
    private var running = false
    private var failed = false
    private var dirty = false
    private var tick = 0
    // How many screen refreshes to skip between drawn frames: measured from the real refresh interval as it runs. The
    // screen can change mode after this view starts (for example from 120 Hz down to the 60 Hz that was asked for), so
    // a number worked out once at the start would leave smoothing drawing every other refresh.
    private var divisor = initialDivisor.coerceAtLeast(1)
    private var lastVsync = 0L
    private var vsyncPeriod = 0.0
    private var lastDrawn = -1f
    private val matrix = FloatArray(16)

    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
        position(0)
    }

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            Choreographer.getInstance().postFrameCallback(this)
            if (lastVsync != 0L) {
                val dt = (frameTimeNanos - lastVsync).toDouble()
                if (dt in 4_000_000.0..60_000_000.0) {
                    vsyncPeriod = if (vsyncPeriod == 0.0) dt else vsyncPeriod * 0.9 + dt * 0.1
                    divisor = Math.round(vsyncPeriod / 16_667_000.0).toInt().coerceIn(1, 4)
                }
            }
            lastVsync = frameTimeNanos
            tick++
            if (tick % divisor == 0) guarded { drawIfNeeded() }
            reportStats(System.nanoTime())
        }
    }

    private fun reportStats(now: Long) {
        if (statsSince == 0L) { statsSince = now; return }
        val elapsed = now - statsSince
        if (elapsed < 1_000_000_000L) return
        val seconds = elapsed / 1e9f
        val fallback = if (framesOut > 0) fallbackDrawn * 100 / framesOut else 0
        onStats?.invoke(Stats(framesIn / seconds, framesOut / seconds, clock.needsInterpolation, vectorsValid, fallback))
        framesIn = 0
        fallbackDrawn = 0
        framesOut = 0
        statsSince = now
    }

    fun setVideoSize(width: Int, height: Int) {
        handler.post {
            frameWidth = width
            frameHeight = height
            inputTexture2D?.setDefaultBufferSize(width, height)
        }
    }

    fun start(output: SurfaceTexture) {
        handler.post {
            guarded {
                initGl(output)
                running = true
                Choreographer.getInstance().postFrameCallback(vsync)
            }
        }
    }

    /** Releases GL objects on the GL thread and waits briefly so the output surface can be freed. */
    fun stopBlocking() {
        val done = CountDownLatch(1)
        handler.post {
            running = false
            ready = false
            Choreographer.getInstance().removeFrameCallback(vsync)
            runCatching { releaseGl() }
            done.countDown()
        }
        done.await(500, TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            if (!failed) {
                failed = true
                running = false
                onError()
            }
        }
    }

    private fun initGl(output: SurfaceTexture) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL init" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) && count[0] > 0) { "EGL config" }
        context = EGL14.eglCreateContext(
            display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "EGL context" }
        windowSurface = EGL14.eglCreateWindowSurface(display, configs[0], output, intArrayOf(EGL14.EGL_NONE), 0)
        check(windowSurface != EGL14.EGL_NO_SURFACE) { "EGL surface" }
        check(EGL14.eglMakeCurrent(display, windowSurface, windowSurface, context)) { "EGL current" }
        EGL14.eglSwapInterval(display, 0)

        copyProgram = link(VERTEX_COPY, FRAGMENT_COPY)
        blendProgram = link(VERTEX_BLEND, FRAGMENT_BLEND)
        lumaProgram = link(VERTEX_BLEND, FRAGMENT_LUMA)
        meProgram = link(VERTEX_BLEND, FRAGMENT_MOTION)
        warpProgram = link(VERTEX_BLEND, FRAGMENT_WARP)
        vectorFilterProgram = link(VERTEX_BLEND, FRAGMENT_VECTOR_FILTER)

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        inputTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        setFilters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glGenTextures(2, textures, 0)
        GLES20.glGenTextures(2, lumaTextures, 0)
        GLES20.glGenTextures(1, ids, 0)
        vectorTexture = ids[0]
        GLES20.glGenTextures(1, ids, 0)
        vectorRawTexture = ids[0]
        GLES20.glGenTextures(1, ids, 0)
        vectorBackTexture = ids[0]
        GLES20.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]

        val st = SurfaceTexture(inputTexture)
        st.setDefaultBufferSize(frameWidth, frameHeight)
        st.setOnFrameAvailableListener({ onFrame() }, handler)
        inputTexture2D = st
        inputSurface = Surface(st)
        ready = true
        onInputReady(inputSurface!!)
    }

    private fun onFrame() {
        if (!ready) return
        guarded {
            val st = inputTexture2D ?: return
            st.updateTexImage()
            st.getTransformMatrix(matrix)
            val pts = st.timestamp
            ensureTargets()
            val write = 1 - current
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, textures[write], 0,
            )
            GLES20.glViewport(0, 0, allocatedWidth, allocatedHeight)
            GLES20.glUseProgram(copyProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(copyProgram, "uTex"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(copyProgram, "uMatrix"), 1, false, matrix, 0)
            drawQuad(copyProgram)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            // With no usable predecessor (first frame or seek) the clock reports fraction 1,
            // so only the newest picture is shown.
            framesIn++
            clock.onFrame(pts, System.nanoTime())
            estimateMotion(write)
            current = write
            dirty = true
        }
    }

    /** Small luma copy of the new frame, then block matching against the previous frame's luma. */
    private fun estimateMotion(write: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        if (vectorsValid) updateFrameFallback() else frameFallback = false
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, lumaTextures[write], 0,
        )
        GLES20.glViewport(0, 0, lumaWidth, lumaHeight)
        GLES20.glUseProgram(lumaProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[write])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(lumaProgram, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(lumaProgram, "uStep"), 0.25f / lumaWidth, 0.25f / lumaHeight)
        drawQuad(lumaProgram)
        // The previous frame's finished vectors are a hint for this frame's search (only if they are fresh).
        val hintFresh = vectorsValid
        vectorsValid = false
        if (clock.needsInterpolation) {
            // 1. Raw motion per block, forwards (what each block of the new frame looks like in the old one)...
            runMotion(lumaTextures[write], lumaTextures[1 - write], vectorRawTexture, hintFresh)
            // ...and backwards (the old frame against the new one), used to spot hidden / uncovered areas.
            runMotion(lumaTextures[1 - write], lumaTextures[write], vectorBackTexture, false)
            // 2. Fill undecided blocks from their neighbours, median-filter, and mark blocks the backward search
            // does not confirm (see FRAGMENT_VECTOR_FILTER).
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, vectorTexture, 0,
            )
            GLES20.glViewport(0, 0, vectorWidth, vectorHeight)
            GLES20.glUseProgram(vectorFilterProgram)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorRawTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(vectorFilterProgram, "uVec"), 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorBackTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(vectorFilterProgram, "uBack"), 1)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(vectorFilterProgram, "uVecSize"), vectorWidth.toFloat(), vectorHeight.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(vectorFilterProgram, "uSize"), lumaWidth.toFloat(), lumaHeight.toFloat())
            GLES20.glUniform1f(GLES20.glGetUniformLocation(vectorFilterProgram, "uConsist"), floatArrayOf(1.6f, 2.5f, 4.0f)[strength.coerceIn(0, 2)])
            GLES20.glUniform1f(GLES20.glGetUniformLocation(vectorFilterProgram, "uSpread"), floatArrayOf(6f, 9f, 9f)[strength.coerceIn(0, 2)])
            drawQuad(vectorFilterProgram)
            vectorsValid = true
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * If a large share of the picture had untrusted motion (fast, complicated action), patching it block by block
     * shows, so the next in-between frames show the nearest real frame instead (a brief drop to the video's own rate,
     * with no artifacts). Read from the previous frame's finished vectors, so the GPU never has to stop and wait.
     */
    private fun updateFrameFallback() {
        val count = vectorWidth * vectorHeight
        if (count == 0) return
        val pixels = vectorPixels?.takeIf { it.capacity() >= count * 4 }
            ?: ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).also { vectorPixels = it }
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, vectorTexture, 0,
        )
        pixels.position(0)
        GLES20.glReadPixels(0, 0, vectorWidth, vectorHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
        var bad = 0
        for (i in 0 until count) {
            val decided = (pixels.get(i * 4 + 3).toInt() and 0xFF) >= 128
            if (decided && (pixels.get(i * 4 + 2).toInt() and 0xFF) < 128) bad++
        }
        val limit = floatArrayOf(0.03f, 0.08f, 0.2f)[strength.coerceIn(0, 2)]
        frameFallback = bad > count * limit
    }

    /** One block-matching pass: for each block of [cur], where is it in [prev]. Writes vectors into [target]. */
    private fun runMotion(cur: Int, prev: Int, target: Int, useHint: Boolean) {
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, target, 0,
        )
        GLES20.glViewport(0, 0, vectorWidth, vectorHeight)
        GLES20.glUseProgram(meProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cur)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meProgram, "uCurL"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, prev)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meProgram, "uPrevL"), 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorTexture)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(meProgram, "uHint"), 2)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meProgram, "uHintOn"), if (useHint) 1f else 0f)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(meProgram, "uClear"), floatArrayOf(0.75f, 0.88f, 0.95f)[strength.coerceIn(0, 2)])
        GLES20.glUniform2f(GLES20.glGetUniformLocation(meProgram, "uSize"), lumaWidth.toFloat(), lumaHeight.toFloat())
        GLES20.glUniform2f(GLES20.glGetUniformLocation(meProgram, "uVecSize"), vectorWidth.toFloat(), vectorHeight.toFloat())
        drawQuad(meProgram)
    }

    private fun ensureTargets() {
        if (allocatedWidth == frameWidth && allocatedHeight == frameHeight) return
        vectorsValid = false
        lumaWidth = minOf(480, frameWidth).coerceAtLeast(16)
        lumaHeight = (lumaWidth.toLong() * frameHeight / frameWidth).toInt().coerceAtLeast(16)
        vectorWidth = (lumaWidth + 3) / 4
        vectorHeight = (lumaHeight + 3) / 4
        for (t in lumaTextures) allocate(t, lumaWidth, lumaHeight)
        allocate(vectorTexture, vectorWidth, vectorHeight)
        allocate(vectorRawTexture, vectorWidth, vectorHeight)
        allocate(vectorBackTexture, vectorWidth, vectorHeight)
        for (t in textures) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, frameWidth, frameHeight, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
            )
            setFilters(GLES20.GL_TEXTURE_2D)
        }
        allocatedWidth = frameWidth
        allocatedHeight = frameHeight
    }

    private fun drawIfNeeded() {
        if (!ready || allocatedWidth == 0) return
        val fraction = clock.fraction(System.nanoTime())
        if (!dirty && fraction == lastDrawn) return
        val size = IntArray(1)
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_WIDTH, size, 0)
        val width = size[0]
        EGL14.eglQuerySurface(display, windowSurface, EGL14.EGL_HEIGHT, size, 0)
        val height = size[0]
        GLES20.glViewport(0, 0, width, height)
        val useFallback = vectorsValid && frameFallback
        val program = if (vectorsValid && !useFallback) warpProgram else blendProgram
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1 - current])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrev"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[current])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCurr"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uMix"), if (useFallback) (if (fraction < 0.5f) 0f else 1f) else fraction)
        if (useFallback) fallbackDrawn++
        if (vectorsValid && !useFallback) {
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uDebug"), if (debug) 1f else 0f)
            when (strength.coerceIn(0, 2)) {
                0 -> GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHold"), 0.03f, 0.10f)
                1 -> GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHold"), 0.06f, 0.20f)
                else -> GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHold"), 0.10f, 0.30f)
            }
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, vectorTexture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uMv"), 2)
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uSize"), lumaWidth.toFloat(), lumaHeight.toFloat())
            GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uVecSize"), vectorWidth.toFloat(), vectorHeight.toFloat())
            GLES20.glActiveTexture(GLES20.GL_TEXTURE3)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTextures[1 - current])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uPrevL"), 3)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE4)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lumaTextures[current])
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uCurrL"), 4)
        }
        drawQuad(program)
        EGL14.eglSwapBuffers(display, windowSurface)
        framesOut++
        dirty = false
        lastDrawn = fraction
    }

    private fun drawQuad(program: Int) {
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coord = GLES20.glGetAttribLocation(program, "aCoord")
        vertices.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(position)
        vertices.position(2)
        GLES20.glVertexAttribPointer(coord, 2, GLES20.GL_FLOAT, false, 16, vertices)
        GLES20.glEnableVertexAttribArray(coord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(coord)
    }

    private fun allocate(texture: Int, width: Int, height: Int) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null,
        )
        setFilters(GLES20.GL_TEXTURE_2D)
    }

    private fun setFilters(target: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun link(vertex: String, fragment: String): Int {
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, vertex))
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "link: " + GLES20.glGetProgramInfoLog(program) }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { "shader: " + GLES20.glGetShaderInfoLog(shader) }
        return shader
    }

    private fun releaseGl() {
        inputSurface?.release()
        inputTexture2D?.release()
        inputSurface = null
        inputTexture2D = null
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (windowSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, windowSurface)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        windowSurface = EGL14.EGL_NO_SURFACE
    }

    private companion object {
        const val VERTEX_BLEND = """
            attribute vec4 aPosition;
            attribute vec2 aCoord;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = aCoord;
            }
        """
        const val VERTEX_COPY = """
            attribute vec4 aPosition;
            attribute vec2 aCoord;
            uniform mat4 uMatrix;
            varying vec2 vCoord;
            void main() {
                gl_Position = aPosition;
                vCoord = (uMatrix * vec4(aCoord, 0.0, 1.0)).xy;
            }
        """
        const val FRAGMENT_COPY = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vCoord;
            uniform samplerExternalOES uTex;
            void main() { gl_FragColor = texture2D(uTex, vCoord); }
        """
        // Small grey copy of a frame, averaged over four taps, used only for motion search.
        const val FRAGMENT_LUMA = """
            precision mediump float;
            varying vec2 vCoord;
            uniform sampler2D uTex;
            uniform vec2 uStep;
            void main() {
                vec3 c = texture2D(uTex, vCoord + vec2(-uStep.x, -uStep.y)).rgb
                    + texture2D(uTex, vCoord + vec2(uStep.x, -uStep.y)).rgb
                    + texture2D(uTex, vCoord + vec2(-uStep.x, uStep.y)).rgb
                    + texture2D(uTex, vCoord + vec2(uStep.x, uStep.y)).rgb;
                float l = dot(c * 0.25, vec3(0.299, 0.587, 0.114));
                gl_FragColor = vec4(l, l, l, 1.0);
            }
        """
        // One fragment per 4x4 block of the small frame. Each block is matched over an 8x8 window (16 taps, each
        // averaging 2x2 texels), looking for d with cur(p) ~ prev(p + d). The search is coarse-to-fine: first every
        // 4th shift out to +-16 (fast action), then every shift within +-3 of the best coarse one, so the reach is
        // +-19 small-frame pixels (about 4% of the picture width) for roughly half the cost of the old +-7 full search.
        // A non-zero vector must beat the zero vector by 10%, and must also beat the best match at any clearly
        // different shift by 12%. Repeating patterns (stripes, checks) and flat or noisy skin match about equally at
        // several shifts, so they stay still instead of picking a random one that changes every frame.
        const val FRAGMENT_MOTION = """
            precision highp float;
            uniform sampler2D uCurL;
            uniform sampler2D uPrevL;
            uniform sampler2D uHint;
            uniform vec2 uSize;
            uniform vec2 uVecSize;
            uniform float uHintOn;
            uniform float uClear;
            vec2 base;
            float cost(vec2 d) {
                float raw = 0.0;
                for (int by = 0; by < 4; by++) {
                    for (int bx = 0; bx < 4; bx++) {
                        vec2 p = (base + vec2(float(bx), float(by)) * 2.0 + 1.0) / uSize;
                        raw += abs(texture2D(uCurL, p).r - texture2D(uPrevL, p + d / uSize).r);
                    }
                }
                return raw;
            }
            void main() {
                base = floor(gl_FragCoord.xy) * 4.0 - 2.0;
                float sad0 = cost(vec2(0.0));
                float best = sad0 * 0.9;
                float bestRaw = sad0;
                float second = 1e9;
                vec2 bestV = vec2(0.0);
                // Coarse stage.
                vec2 coarseV = vec2(0.0);
                float coarseBest = 1e9;
                float coarseSecond = 1e9;
                for (int cy = -4; cy <= 4; cy++) {
                    for (int cx = -4; cx <= 4; cx++) {
                        vec2 d = vec2(float(cx), float(cy)) * 4.0;
                        float raw = cost(d);
                        float sad = raw + 0.002 * length(d);
                        if (sad < coarseBest) {
                            if (length(d - coarseV) > 6.0) coarseSecond = min(coarseSecond, coarseBest);
                            coarseBest = sad;
                            coarseV = d;
                        } else if (sad < coarseSecond && length(d - coarseV) > 6.0) {
                            coarseSecond = sad;
                        }
                    }
                }
                // Fine stage around the best coarse shift.
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dx = -3; dx <= 3; dx++) {
                        vec2 d = coarseV + vec2(float(dx), float(dy));
                        if (d.x == 0.0 && d.y == 0.0) continue;
                        float raw = cost(d);
                        float sad = raw + 0.002 * length(d);
                        if (sad < best) {
                            if (length(d - bestV) > 1.5) second = min(second, bestRaw);
                            best = sad;
                            bestRaw = raw;
                            bestV = d;
                        } else if (raw < second && length(d - bestV) > 1.5) {
                            second = raw;
                        }
                    }
                }
                // A different coarse shift that matched nearly as well makes the choice ambiguous.
                if (coarseSecond < second) second = coarseSecond;
                bool clear = bestRaw < second * uClear;
                // Temporal hint: the motion this block had in the previous frame. If it matches nearly as well as
                // the new winner (and clearly beats standing still) keep it, so fast motion does not flip from
                // frame to frame.
                if (uHintOn > 0.5) {
                    vec2 h = floor((texture2D(uHint, (floor(gl_FragCoord.xy) + 0.5) / uVecSize).rg - 0.5) * 40.0 + 0.5);
                    if (length(h) > 0.5 && length(h - bestV) > 0.5) {
                        float rawH = cost(h);
                        // An undecided block (stripes) only needs the hint to match about as well as anything else.
                        if (rawH < bestRaw * 1.08 + 0.01 && (rawH < sad0 * 0.9 || !clear)) {
                            bestV = h;
                            clear = true;
                        }
                    }
                }
                // Alpha 0 = this block alone cannot tell (stripes, flat skin): the next pass fills it from its
                // neighbours instead of forcing it to stand still inside a moving body.
                vec2 v = clear ? bestV : vec2(0.0);
                gl_FragColor = vec4(v / 20.0 * 0.5 + 0.5, 1.0, clear ? 1.0 : 0.0);
            }
        """
        // Cleans up the block vectors.
        // 1. Blocks that could not decide on their own (alpha 0: stripes, checks, plain skin) take the average motion
        //    of the decided blocks around them (up to 3 blocks away), so a striped shirt moves with the body around
        //    it instead of standing still in patches.
        // 2. Median of the 3x3 neighbours (per component, the standard 19-exchange network) outvotes lone wrong ones.
        // 3. Forward/backward check: the block this one points at must point back. Unconfirmed blocks get blue = 0
        //    so the picture uses a soft fallback there.
        const val FRAGMENT_VECTOR_FILTER = """
            precision highp float;
            uniform sampler2D uVec;
            uniform sampler2D uBack;
            uniform vec2 uVecSize;
            uniform vec2 uSize;
            uniform float uConsist;
            uniform float uSpread;
            vec2 temp;
            vec2 fillSum;
            float fillCount;
            #define s2(a, b) temp = a; a = min(a, b); b = max(temp, b);
            #define mn3(a, b, c) s2(a, b); s2(a, c);
            #define mx3(a, b, c) s2(b, c); s2(a, c);
            #define mnmx3(a, b, c) mx3(a, b, c); s2(a, b);
            #define mnmx4(a, b, c, d) s2(a, b); s2(c, d); s2(a, c); s2(b, d);
            #define mnmx5(a, b, c, d, e) s2(a, b); s2(c, d); mn3(a, c, e); mx3(b, d, e);
            #define mnmx6(a, b, c, d, e, f) s2(a, d); s2(b, e); s2(c, f); mn3(a, b, c); mx3(d, e, f);
            vec4 at(vec2 p) {
                vec4 s = texture2D(uVec, p);
                if (s.a > 0.5) {
                    fillSum += s.rg;
                    fillCount += 1.0;
                }
                return s;
            }
            vec2 pick(vec4 s, vec2 fill) { return s.a > 0.5 ? s.rg : fill; }
            void main() {
                vec2 c = (floor(gl_FragCoord.xy) + 0.5) / uVecSize;
                vec2 o = 1.0 / uVecSize;
                fillSum = vec2(0.0);
                fillCount = 0.0;
                vec4 s0 = at(c + vec2(-o.x, -o.y));
                vec4 s1 = at(c + vec2(0.0, -o.y));
                vec4 s2v = at(c + vec2(o.x, -o.y));
                vec4 s3 = at(c + vec2(-o.x, 0.0));
                vec4 s4 = texture2D(uVec, c);
                vec4 s5 = at(c + vec2(o.x, 0.0));
                vec4 s6 = at(c + vec2(-o.x, o.y));
                vec4 s7 = at(c + vec2(0.0, o.y));
                vec4 s8 = at(c + vec2(o.x, o.y));
                // Few decided neighbours: look further out (3, then 7, then 12 blocks away in eight directions),
                // so the middle of a large striped area still gets the motion of the body around it.
                for (int ring = 0; ring < 3; ring++) {
                    if (fillCount < 2.0 && s4.a < 0.5) {
                        vec2 r = (ring == 0 ? 3.0 : (ring == 1 ? 7.0 : 12.0)) * o;
                        at(c + vec2(-r.x, -r.y)); at(c + vec2(0.0, -r.y)); at(c + vec2(r.x, -r.y));
                        at(c + vec2(-r.x, 0.0)); at(c + vec2(r.x, 0.0));
                        at(c + vec2(-r.x, r.y)); at(c + vec2(0.0, r.y)); at(c + vec2(r.x, r.y));
                    }
                }
                vec2 fill = fillCount > 0.5 ? fillSum / fillCount : vec2(0.5);
                vec2 mid = pick(s4, fill);
                vec2 v0 = pick(s0, mid);
                vec2 v1 = pick(s1, mid);
                vec2 v2 = pick(s2v, mid);
                vec2 v3 = pick(s3, mid);
                vec2 v4 = mid;
                vec2 v5 = pick(s5, mid);
                vec2 v6 = pick(s6, mid);
                vec2 v7 = pick(s7, mid);
                vec2 v8 = pick(s8, mid);
                // How many decided neighbours point somewhere clearly different (3+ small-frame pixels).
                float spread = 0.0;
                spread += length(v0 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v1 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v2 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v3 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v5 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v6 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v7 - mid) > 0.075 ? 1.0 : 0.0;
                spread += length(v8 - mid) > 0.075 ? 1.0 : 0.0;
                mnmx6(v0, v1, v2, v3, v4, v5);
                mnmx5(v1, v2, v3, v4, v6);
                mnmx4(v2, v3, v4, v7);
                mnmx3(v3, v4, v8);
                vec2 d = (v4 - 0.5) * 40.0;
                // Any decided block around the target confirming it is enough; if none there could decide, there is
                // no evidence against it.
                float confirmed = 0.0;
                float evidence = 0.0;
                vec2 target = c + d / uSize;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec4 be = texture2D(uBack, target + vec2(float(i), float(j)) * o);
                        if (be.a > 0.5) {
                            evidence = 1.0;
                            if (length(d + (be.rg - 0.5) * 40.0) <= uConsist) confirmed = 1.0;
                        }
                    }
                }
                if (evidence < 0.5) confirmed = 1.0;
                float bad = (length(d) > 0.5 && (confirmed < 0.5 || spread >= uSpread)) ? 1.0 : 0.0;
                // Alpha = 1 where this block decided its own motion; filled blocks (flat walls, plain areas) do not
                // count towards the whole-frame fallback.
                gl_FragColor = vec4(v4, 1.0 - bad, s4.a > 0.5 ? 1.0 : 0.0);
            }
        """
        // Moves both neighbouring frames along the motion vector to time uMix. Where the two moved pictures
        // disagree (hidden/uncovered areas, scene cuts) it holds the nearest real frame instead of cross-fading, so
        // there is no ghosting. The disagreement is measured on the smooth small copy of the picture, so the choice
        // changes smoothly across the screen and does not flicker pixel by pixel on fine patterns.
        const val FRAGMENT_WARP = """
            precision highp float;
            varying vec2 vCoord;
            uniform sampler2D uPrev;
            uniform sampler2D uCurr;
            uniform sampler2D uMv;
            uniform sampler2D uPrevL;
            uniform sampler2D uCurrL;
            uniform vec2 uSize;
            uniform vec2 uVecSize;
            uniform float uMix;
            uniform vec2 uHold;
            uniform float uDebug;
            void main() {
                vec2 g = vCoord * uVecSize - 0.5;
                vec2 f = floor(g);
                vec2 w = g - f;
                vec2 mv[4];
                float okv[4];
                for (int k = 0; k < 4; k++) {
                    vec2 cc = (f + vec2(float(k - (k / 2) * 2), float(k / 2)) + 0.5) / uVecSize;
                    vec3 sv = texture2D(uMv, clamp(cc, 0.0, 1.0)).rgb;
                    mv[k] = (sv.rg - 0.5) * 40.0 / uSize;
                    okv[k] = step(0.5, sv.b);
                }
                // One smooth motion field everywhere: the four nearest block vectors blended by distance, and their
                // trust blended the same way. Picking a different block's vector pixel by pixel, or switching hard
                // between moved picture and fallback, is what left block-shaped patches and torn stripes.
                vec2 m = mix(mix(mv[0], mv[1], w.x), mix(mv[2], mv[3], w.x), w.y);
                float ok = mix(mix(okv[0], okv[1], w.x), mix(okv[2], okv[3], w.x), w.y);
                vec2 a = vCoord + m * uMix;
                vec2 b = vCoord - m * (1.0 - uMix);
                vec4 p = texture2D(uPrev, a);
                vec4 c = texture2D(uCurr, b);
                // Disagreement between the two moved pictures, on the smooth small copies.
                float diff = abs(texture2D(uPrevL, a).r - texture2D(uCurrL, b).r);
                // Where the motion is not trusted, fall back to a plain cross-fade of the two real frames at this spot.
                // It softens there, but never shows a piece of picture in the wrong place.
                vec4 fallback = mix(texture2D(uPrev, vCoord), texture2D(uCurr, vCoord), uMix);
                float fb = max(smoothstep(uHold.x, uHold.y, diff), smoothstep(0.0, 1.0, 1.0 - ok));
                vec4 colour = mix(mix(p, c, uMix), fallback, fb);
                if (uDebug > 0.5) {
                    // Debug colours: green = moved along a motion vector, red = fallback cross-fade.
                    float moving = step(0.5, length(m * uSize));
                    colour.rgb = mix(colour.rgb, vec3(1.0, 0.0, 0.0), 0.45 * fb);
                    colour.rgb = mix(colour.rgb, vec3(0.0, 1.0, 0.0), 0.35 * (1.0 - fb) * moving);
                }
                gl_FragColor = colour;
            }
        """
        const val FRAGMENT_BLEND = """
            precision mediump float;
            varying vec2 vCoord;
            uniform sampler2D uPrev;
            uniform sampler2D uCurr;
            uniform float uMix;
            void main() { gl_FragColor = mix(texture2D(uPrev, vCoord), texture2D(uCurr, vCoord), uMix); }
        """
    }
}
