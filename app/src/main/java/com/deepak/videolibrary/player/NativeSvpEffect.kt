package com.deepak.videolibrary.player

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20 as GL
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.math.abs

/** Why the native engine last failed, in plain text, so the app can show it instead of only an error code. */
internal object NativeSvpDiagnostics {
    @Volatile var lastFailure: String? = null

    fun describe(error: Throwable): String {
        val parts = generateSequence(error) { it.cause }.mapNotNull { it.message?.takeIf { m -> m.isNotBlank() } }.distinct().toList()
        return (parts.lastOrNull() ?: error.javaClass.simpleName).take(200)
    }

    fun record(error: Throwable) {
        lastFailure = describe(error)
        android.util.Log.e("NativeSvp", "native smoothing failed: ${lastFailure}", error)
    }
}

internal object NativeSvpStream {
    val available: Boolean by lazy { runCatching { System.loadLibrary("skywing_svp_stream") }.isSuccess }

    /** Where the engine finds its sibling libraries (extracted libs, or straight from the APK). */
    fun libraryDirectory(context: Context): String {
        val info = context.applicationInfo
        return if (File(info.nativeLibraryDir, "libsvpflow1_vs.so").isFile) info.nativeLibraryDir
        else info.sourceDir + "!/lib/arm64-v8a"
    }
    external fun create(directory: String, width: Int, height: Int, fpsNum: Long, fpsDen: Long): Long
    external fun push(handle: Long, yuv: ByteBuffer)
    external fun render(handle: Long, index: Int, yuv: ByteBuffer): Boolean
    external fun end(handle: Long)
    external fun cancel(handle: Long)
    external fun close(handle: Long)
}

/** SDR preview engine. Media3 owns output scheduling and the audio clock. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeSvpEffect(private val controller: NativeSvpController) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        check(!useHdr) { "Native interpolation currently supports SDR only" }
        check(NativeSvpStream.available) { "Native interpolation libraries are unavailable" }
        return NativeSvpShader(NativeSvpStream.libraryDirectory(context), controller)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class NativeSvpShader(private val directory: String, private val controller: NativeSvpController) : GlShaderProgram {
    private var input: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var output: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errors = GlShaderProgram.ErrorListener {}
    private var errorExecutor = Executor { it.run() }
    private val lock = Object()
    private var credit = false
    private var count = 0
    private var consumed = 0
    @Volatile private var ended = false
    @Volatile private var active = true
    @Volatile private var finished = false
    private var handle = 0L
    private var worker: Thread? = null
    private var width = 0
    private var height = 0
    private var firstUs = 0L
    @Volatile private var lastUs = 0L
    @Volatile private var requestedEndUs = Long.MAX_VALUE
    private var first: ByteBuffer? = null
    private var readback: ByteBuffer? = null
    private var fpsNum = 24L
    private var fpsDen = 1L
    private var readFbo = 0
    private var packedTexture = 0
    private var packedWidth = 0
    private var packedHeight = 0
    private var inputConverter: GlProgram? = null
    private val textures = mutableListOf<GlTextureInfo>()
    private val free = java.util.ArrayDeque<GlTextureInfo>()
    private var provider: GlObjectsProvider? = null
    private val decoderEnd: (Long) -> Unit = { timeUs ->
        synchronized(lock) {
            requestedEndUs = timeUs
            if (active && count > 0 && lastUs >= timeUs) {
                if (handle == 0L) fail(IllegalStateException("Native smoothing requires two decoded frames"))
                else { ended = true; NativeSvpStream.end(handle) }
            }
        }
    }
    init { controller.bind(decoderEnd) }

    override fun setInputListener(listener: GlShaderProgram.InputListener) {
        input = listener
        synchronized(lock) { credit = false }
        offerInput()
    }
    override fun setOutputListener(listener: GlShaderProgram.OutputListener) { output = listener }
    override fun setErrorListener(executor: Executor, listener: GlShaderProgram.ErrorListener) {
        errorExecutor = executor; errors = listener
    }
    private fun fail(e: Throwable) {
        NativeSvpDiagnostics.record(e)
        active = false
        if (handle != 0L) NativeSvpStream.cancel(handle)
        synchronized(lock) { lock.notifyAll() }
        errorExecutor.execute { errors.onError(VideoFrameProcessingException(e)) }
    }
    private fun offerInput() {
        val ready = synchronized(lock) {
            if (active && (!ended || finished) && !credit && (finished || count - consumed < 8)) { credit = true; true } else false
        }
        if (ready) input.onReadyToAcceptInputFrame()
    }
    override fun queueInputFrame(glObjectsProvider: GlObjectsProvider, texture: GlTextureInfo, timeUs: Long) {
        try {
            if (finished) {
                stop()
                count = 0; consumed = 0; ended = false; active = true; finished = false; requestedEndUs = Long.MAX_VALUE
            }
            synchronized(lock) { check(credit); credit = false }
            if (count == 0) {
                width = texture.width and -2; height = texture.height and -2
                check(width > 0 && height > 0)
                firstUs = timeUs
                provider = glObjectsProvider
                val ids = IntArray(1); GL.glGenFramebuffers(1, ids, 0); readFbo = ids[0]
                packedWidth = (width + 3) / 4
                packedHeight = (width * height * 3 / 2 + packedWidth * 4 - 1) / (packedWidth * 4)
                packedTexture = GlUtil.createTexture(packedWidth, packedHeight, false)
                readback = ByteBuffer.allocateDirect(packedWidth * packedHeight * 4)
                inputConverter = GlProgram(
                    "attribute vec2 aPosition; void main(){gl_Position=vec4(aPosition,0.,1.);}",
                    "precision highp float; uniform sampler2D uRgb; uniform vec2 uSize; uniform float uPackedWidth;" +
                        "float valueAt(float i){float area=uSize.x*uSize.y; if(i>=area*1.5)return 0.;" +
                        "if(i<area){vec2 xy=vec2(mod(i,uSize.x),floor(i/uSize.x));" +
                        "vec3 c=texture2D(uRgb,(xy+.5)/uSize).rgb;return (16.+219.*dot(c,vec3(.2126,.7152,.0722)))/255.;}" +
                        "float j=mod(i-area,area*.25);vec2 xy=vec2(mod(j,uSize.x*.5),floor(j/(uSize.x*.5)))*2.;" +
                        "vec3 c=(texture2D(uRgb,(xy+vec2(.5,.5))/uSize).rgb+texture2D(uRgb,(xy+vec2(1.5,.5))/uSize).rgb+" +
                        "texture2D(uRgb,(xy+vec2(.5,1.5))/uSize).rgb+texture2D(uRgb,(xy+vec2(1.5,1.5))/uSize).rgb)*.25;" +
                        "float y=dot(c,vec3(.2126,.7152,.0722));float uv=i<area*1.25?(c.b-y)/1.8556:(c.r-y)/1.5748;" +
                        "return (128.+224.*uv)/255.;}" +
                        "void main(){float i=(floor(gl_FragCoord.y)*uPackedWidth+floor(gl_FragCoord.x))*4.;" +
                        "gl_FragColor=vec4(valueAt(i),valueAt(i+1.),valueAt(i+2.),valueAt(i+3.));}").apply {
                    setBufferAttribute("aPosition", floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f), 2)
                    setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
                    setFloatUniform("uPackedWidth", packedWidth.toFloat())
                }
            }
            check(width == texture.width and -2 && height == texture.height and -2) { "Native input size changed without a flush" }
            check(count == 0 || timeUs > lastUs) { "Native input timestamps must increase" }
            val pixels = checkNotNull(readback)
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, readFbo)
            GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, packedTexture, 0)
            check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE)
            GL.glViewport(0, 0, packedWidth, packedHeight)
            checkNotNull(inputConverter).apply {
                use(); setSamplerTexIdUniform("uRgb", texture.texId, 0); bindAttributesAndUniforms()
            }
            val dither = GL.glIsEnabled(GL.GL_DITHER)
            val blend = GL.glIsEnabled(GL.GL_BLEND)
            GL.glDisable(GL.GL_DITHER); GL.glDisable(GL.GL_BLEND)
            GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
            pixels.clear()
            GL.glReadPixels(0, 0, packedWidth, packedHeight, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
            if (dither) GL.glEnable(GL.GL_DITHER)
            if (blend) GL.glEnable(GL.GL_BLEND)
            GlUtil.checkGlError()
            if (count == 0) {
                first = ByteBuffer.allocateDirect(pixels.capacity()).apply { put(pixels); rewind() }
            } else {
                if (handle == 0L) {
                    val measured = 1_000_000.0 / (timeUs - firstUs)
                    val rates = listOf(24000L to 1001L, 24L to 1L, 25L to 1L, 28L to 1L, 30000L to 1001L, 30L to 1L, 50L to 1L, 60000L to 1001L, 60L to 1L)
                    val nearest = rates.minBy { abs(it.first.toDouble() / it.second - measured) }
                    if (abs(nearest.first.toDouble() / nearest.second - measured) < measured * 0.002) {
                        fpsNum = nearest.first; fpsDen = nearest.second
                    } else { fpsNum = 1_000_000; fpsDen = timeUs - firstUs }
                    check(measured in 10.0..61.0) { "Unsupported source frame rate" }
                    open()
                    NativeSvpStream.push(handle, checkNotNull(first)); first = null
                }
                // A discontinuity must be handled by a Media3 flush, never by interpolating across it.
                check(abs((timeUs - firstUs) - count * 1_000_000.0 * fpsDen / fpsNum) < 20_000) {
                    "Variable-rate video is not supported by native interpolation yet"
                }
                NativeSvpStream.push(handle, pixels)
            }
            synchronized(lock) {
                lastUs = timeUs
                count++
                if (lastUs >= requestedEndUs) {
                    check(handle != 0L) { "Native smoothing requires two decoded frames" }
                    ended = true; NativeSvpStream.end(handle)
                }
            }
            input.onInputFrameProcessed(texture)
            if (handle != 0L && worker == null) startWorker()
            offerInput()
        } catch (e: Throwable) { fail(e) }
    }
    private fun open() {
        handle = NativeSvpStream.create(directory, width, height, fpsNum, fpsDen)
        check(handle != 0L)
        android.util.Log.i("NativeSvp", "Stream ${width}x$height source=$fpsNum/$fpsDen output=60 analysis=half")
        repeat(3) {
            val texture = checkNotNull(provider).createBuffersForTexture(GlUtil.createTexture(width, height, false), width, height)
            textures.add(texture); free.add(texture)
        }
    }
    private fun startWorker() {
        val display = EGL14.eglGetCurrentDisplay()
        val parent = EGL14.eglGetCurrentContext()
        val configList = arrayOfNulls<EGLConfig>(1); val configCount = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE),
            0, configList, 0, 1, configCount, 0) && configCount[0] > 0)
        val shared = EGL14.eglCreateContext(display, configList[0], parent,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        check(shared != EGL14.EGL_NO_CONTEXT)
        val surface = EGL14.eglCreatePbufferSurface(display, configList[0],
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(surface != EGL14.EGL_NO_SURFACE)
        GL.glFinish()
        worker = Thread({
            val yuvTextures = IntArray(3)
            val workerFbo = IntArray(1)
            var converter: GlProgram? = null
            try {
                check(EGL14.eglMakeCurrent(display, surface, surface, shared))
                val pixels = ByteBuffer.allocateDirect(width * height * 3 / 2)
                GL.glGenTextures(3, yuvTextures, 0)
                GL.glPixelStorei(GL.GL_UNPACK_ALIGNMENT, 1)
                for (p in 0..2) {
                    GL.glBindTexture(GL.GL_TEXTURE_2D, yuvTextures[p])
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                    GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                    GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_LUMINANCE, width shr (if (p == 0) 0 else 1),
                        height shr (if (p == 0) 0 else 1), 0, GL.GL_LUMINANCE, GL.GL_UNSIGNED_BYTE, null)
                }
                GL.glGenFramebuffers(1, workerFbo, 0)
                converter = GlProgram(
                    "attribute vec2 aPosition; varying vec2 vTex; void main(){gl_Position=vec4(aPosition,0.,1.);vTex=aPosition*.5+.5;}",
                    "precision highp float; varying vec2 vTex; uniform sampler2D uY; uniform sampler2D uU; uniform sampler2D uV; " +
                        "void main(){float y=(texture2D(uY,vTex).r-16./255.)*(255./219.);" +
                        "float u=(texture2D(uU,vTex).r-128./255.)*(255./224.);" +
                        "float v=(texture2D(uV,vTex).r-128./255.)*(255./224.);" +
                        "gl_FragColor=vec4(y+1.5748*v,y-.187324*u-.468124*v,y+1.8556*u,1.);}")
                converter.setBufferAttribute("aPosition", floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f), 2)
                var index = 0
                while (active) {
                    val texture = synchronized(lock) {
                        while (active && free.isEmpty()) lock.wait()
                        if (active) free.removeFirst() else null
                    } ?: break
                    if (pastEnd(index)) { synchronized(lock) { free.add(texture) }; break }
                    if (!NativeSvpStream.render(handle, index, pixels) || !active || pastEnd(index)) {
                        synchronized(lock) { free.add(texture) }; break
                    }
                    var offset = 0
                    for (p in 0..2) {
                        val pw = width shr (if (p == 0) 0 else 1)
                        val ph = height shr (if (p == 0) 0 else 1)
                        pixels.position(offset)
                        GL.glActiveTexture(GL.GL_TEXTURE0 + p)
                        GL.glBindTexture(GL.GL_TEXTURE_2D, yuvTextures[p])
                        GL.glTexSubImage2D(GL.GL_TEXTURE_2D, 0, 0, 0, pw, ph, GL.GL_LUMINANCE, GL.GL_UNSIGNED_BYTE, pixels)
                        offset += pw * ph
                    }
                    GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, workerFbo[0])
                    GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture.texId, 0)
                    GL.glViewport(0, 0, width, height)
                    converter.use()
                    converter.setSamplerTexIdUniform("uY", yuvTextures[0], 0)
                    converter.setSamplerTexIdUniform("uU", yuvTextures[1], 1)
                    converter.setSamplerTexIdUniform("uV", yuvTextures[2], 2)
                    converter.bindAttributesAndUniforms()
                    GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
                    GL.glFinish(); GlUtil.checkGlError()
                    if (!active) break
                    output.onOutputFrameAvailable(texture, if (ended) minOf(lastUs, firstUs + index * 1_000_000L / 60) else firstUs + index * 1_000_000L / 60)
                    index++
                    synchronized(lock) { consumed = minOf(count, (index.toLong() * fpsNum / (60 * fpsDen)).toInt()) }
                    offerInput()
                }
                if (active && ended) {
                    synchronized(lock) { while (active && free.size < textures.size) lock.wait() }
                    if (active) finishStream()
                }
            } catch (e: Throwable) { if (active) fail(e) }
            finally {
                converter?.delete()
                GL.glDeleteTextures(3, yuvTextures, 0)
                GL.glDeleteFramebuffers(1, workerFbo, 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, shared)
                EGL14.eglReleaseThread()
            }
        }, "SkyWing-native-interpolation").also { it.start() }
    }
    private fun pastEnd(index: Int): Boolean = synchronized(lock) {
        ended && index > ((lastUs - firstUs) * 60 + 999_999) / 1_000_000
    }
    override fun releaseOutputFrame(texture: GlTextureInfo) {
        synchronized(lock) {
            if (active && textures.contains(texture) && !free.contains(texture)) { free.add(texture); lock.notifyAll() }
        }
    }
    private fun finishStream() {
        finished = true
        output.onCurrentOutputStreamEnded()
        // End-of-stream can consume the last input credit while draining a full queue.
        // Advertise one credit for the next stream; queueInputFrame resets its graph on the GL thread.
        val ready = synchronized(lock) { if (!credit) { credit = true; true } else false }
        if (ready) input.onReadyToAcceptInputFrame()
    }
    override fun signalEndOfCurrentInputStream() {
        ended = true
        if (count == 0) { finishStream(); return }
        try {
            if (handle == 0L) { open(); NativeSvpStream.push(handle, checkNotNull(first)); first = null }
            NativeSvpStream.end(handle)
            if (worker == null) startWorker()
        } catch (e: Throwable) { fail(e) }
    }
    private fun stop() {
        active = false
        if (handle != 0L) NativeSvpStream.cancel(handle)
        synchronized(lock) { lock.notifyAll() }
        // Never wait forever for the worker: if it is stuck inside the graphics driver, waiting here would freeze
        // Media3's video thread too and the fallback to normal playback could never happen. A stuck worker and its
        // native stream are abandoned (a small leak) instead of freed from under it.
        val running = worker
        running?.join(1500)
        val stuck = running?.isAlive == true
        worker = null
        if (handle != 0L) {
            if (!stuck) NativeSvpStream.close(handle)
            handle = 0
        }
        textures.forEach { it.release() }; textures.clear(); free.clear()
        if (readFbo != 0) GL.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        if (packedTexture != 0) GL.glDeleteTextures(1, intArrayOf(packedTexture), 0)
        inputConverter?.delete(); inputConverter = null; packedTexture = 0
        readFbo = 0; first = null; readback = null
    }
    override fun flush() {
        stop()
        count = 0; consumed = 0; credit = false; ended = false; active = true; finished = false; requestedEndUs = Long.MAX_VALUE
        input.onFlush(); offerInput()
    }
    override fun release() { controller.unbind(decoderEnd); stop() }
}

/** Match the tested 1920-wide analysis limit, preserving aspect and even YUV dimensions. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeSvpScale : androidx.media3.effect.GlMatrixTransformation {
    private val identity = GlUtil.create4x4IdentityMatrix()
    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        val scale = minOf(1.0, 1920.0 / inputWidth, 1080.0 / inputHeight)
        return androidx.media3.common.util.Size(maxOf(2, (inputWidth * scale).toInt() and -2),
            maxOf(2, (inputHeight * scale).toInt() and -2))
    }
    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray = identity
}
