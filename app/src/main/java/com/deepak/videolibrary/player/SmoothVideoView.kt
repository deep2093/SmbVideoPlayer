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
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                val refresh = display?.refreshRate ?: 60f
                val divisor = Math.round(refresh / 60f).coerceAtLeast(1)
                val created = FrameBlendRenderer(
                    divisor,
                    onInputReady = { surface -> post { attachInput(surface) } },
                    onError = { post { onFailure?.invoke() } },
                )
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
    private val vsyncDivisor: Int,
    private val onInputReady: (Surface) -> Unit,
    private val onError: () -> Unit,
) {
    private val thread = HandlerThread("smooth60").also { it.start() }
    private val handler = Handler(thread.looper)
    private val clock = BlendClock()

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
            tick++
            if (tick % vsyncDivisor == 0) guarded { drawIfNeeded() }
        }
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

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        inputTexture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTexture)
        setFilters(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        GLES20.glGenTextures(2, textures, 0)
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
            clock.onFrame(pts, System.nanoTime())
            current = write
            dirty = true
        }
    }

    private fun ensureTargets() {
        if (allocatedWidth == frameWidth && allocatedHeight == frameHeight) return
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
        GLES20.glUseProgram(blendProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1 - current])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uPrev"), 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[current])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(blendProgram, "uCurr"), 1)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(blendProgram, "uMix"), fraction)
        drawQuad(blendProgram)
        EGL14.eglSwapBuffers(display, windowSurface)
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
