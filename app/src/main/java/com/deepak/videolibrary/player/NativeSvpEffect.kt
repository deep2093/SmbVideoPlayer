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

internal object NativeSvpStream {
    val available: Boolean by lazy { runCatching { System.loadLibrary("skywing_svp_stream") }.isSuccess }
    /** [threads] native workers; [ahead] output frames the engine may prepare before they are requested. */
    external fun create(directory: String, width: Int, height: Int, fpsNum: Long, fpsDen: Long, useGpu: Boolean, threads: Int, ahead: Int): Long
    external fun push(handle: Long, yuv: ByteBuffer)
    external fun render(handle: Long, index: Int, yuv: ByteBuffer): Boolean
    external fun end(handle: Long)
    external fun cancel(handle: Long)
    external fun close(handle: Long)
    /** GPU lab only: set an environment variable for the engine before it loads OpenCL. */
    external fun setEnv(name: String, value: String)

    /** Where the engine finds its sibling libraries (extracted libs, or straight from the APK). */
    fun libraryDirectory(context: Context): String {
        val info = context.applicationInfo
        return if (File(info.nativeLibraryDir, "libsvpflow1_vs.so").isFile) info.nativeLibraryDir
        else info.sourceDir + "!/lib/arm64-v8a"
    }
}

/**
 * Pipeline settings. The OpenCL path (tablet) keeps the validated settings. The CPU path (Adreno phones) works
 * further ahead: its per-frame cost is uneven (p95 about 35 ms, worst about 80 ms at 1080p in the phone probe) and two
 * frames of lookahead (about 33 ms) let one slow frame in twenty miss its slot.
 */
internal object NativeSvpTuning {
    const val GPU_THREADS = 4
    const val GPU_AHEAD = 2
    const val GPU_TEXTURES = 3
    const val GPU_CREDIT = 8
    // Snapdragon (Adreno) GPU path: same threads as the validated GPU path, but more lookahead and buffers, because at
    // 1080p it only has about 5-10% headroom and short slowdowns otherwise made the player drop late frames.
    const val ADRENO_GPU_AHEAD = 4
    const val ADRENO_GPU_TEXTURES = 5
    const val ADRENO_GPU_CREDIT = 10
    const val CPU_THREADS = 6
    const val CPU_AHEAD = 8
    const val CPU_TEXTURES = 5
    const val CPU_CREDIT = 12

    /** What the last native stream actually runs with, for the on-screen overlay. */
    @Volatile var lastStream: String? = null

    /** Running counters for the playback report: input frames, output frames, outputs that differ from the previous one. */
    val inputs = java.util.concurrent.atomic.AtomicLong()
    val outputs = java.util.concurrent.atomic.AtomicLong()
    val newPictures = java.util.concurrent.atomic.AtomicLong()
    /** Slowest single output since the last read, in nanoseconds (read and reset with [takeSlowest]). */
    private val slowest = java.util.concurrent.atomic.AtomicLong()
    fun noteRender(ns: Long) { slowest.accumulateAndGet(ns) { a, b -> maxOf(a, b) } }
    fun takeSlowest(): Long = slowest.getAndSet(0)

    /** Whether the GPU of the last native stream was an Adreno (set on the GL thread). */
    @Volatile var lastAdreno: Boolean = false

    /** User opted in to the OpenCL path on Adreno (with the flush-while-waiting fix below). */
    @Volatile var adrenoGpu: Boolean = false

    private var adrenoFixReady = false

    /** File the OpenCL wrapper writes a stall report to during playback (watched by the player). */
    fun stallLog(context: Context): File = File(context.filesDir, "gpu-stall.log")

    /**
     * Adreno's OpenCL driver does not start a command whose wait-list event (from another queue) completes after the
     * command was flushed, unless the queue is flushed again. The GPU lab showed the engine hangs at the first frame
     * without help and runs 1080p (clip end and shutdown included) when every queue is flushed again while waiting.
     * Route the engine's OpenCL through the tracing wrapper in that mode. Must run before the engine loads OpenCL.
     */
    @Synchronized
    fun enableAdrenoFix(context: Context) {
        if (adrenoFixReady) return
        NativeSvpStream.setEnv("OSVP_OPENCL_LIB", "libskywing_cl_trace.so")
        NativeSvpStream.setEnv("OSVP_CL_VARIANT", "flushwait")
        NativeSvpStream.setEnv("OSVP_TRACE_FILE", stallLog(context).absolutePath)
        adrenoFixReady = true
    }

    fun isAdreno(): Boolean = GL.glGetString(GL.GL_RENDERER).orEmpty().contains("Adreno", ignoreCase = true)

    /** Without a GL context: whether this is a Qualcomm Snapdragon device (which has an Adreno GPU). */
    fun isQualcommSoc(): Boolean {
        val soc = if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MANUFACTURER else ""
        return soc.contains("QTI", ignoreCase = true) || soc.contains("Qualcomm", ignoreCase = true) ||
            android.os.Build.HARDWARE.contains("qcom", ignoreCase = true) || lastAdreno
    }

    /** Smoothing quality levels: processing size (the picture is fitted inside, keeping its shape). */
    val LEVEL_NAMES = listOf("720p", "900p", "1080p", "1440p")
    /**
     * When set (width, height), smoothed frames smaller than this are enlarged to fit inside it with a sharp bicubic
     * filter, in the same GPU pass that turns the engine's picture into colour. The motion work still runs at the
     * quality level's size; only the finished frame gets bigger and crisper.
     */
    @Volatile var upscaleTarget: IntArray? = null
    /** For the playback report: what the last played video was (HDR detection, codec, colour info) and the screen's HDR state. */
    @Volatile var lastVideoInfo: String = "(none yet)"
    /** The frame rate the file says it has (0 when unknown). Phone-camera videos vary from frame to frame, so the
     *  file's own figure is a better beat to snap frames to than the gap between the first two frames. */
    @Volatile var sourceFpsHint: Float = 0f
    /** One line a second while a video plays WITHOUT smoothing (frames shown, dropped, file fps, screen refresh rate); last 180 kept. */
    private val normalLog = java.util.ArrayDeque<String>()
    @Synchronized fun logNormal(line: String) { normalLog.addLast(line); while (normalLog.size > 180) normalLog.removeFirst() }
    @Synchronized fun normalLogText(): String = if (normalLog.isEmpty()) "(none)\n" else normalLog.joinToString("\n") + "\n"
    fun levelSize(level: Int): IntArray = when (level.coerceIn(0, 3)) {
        0 -> intArrayOf(1280, 720)
        1 -> intArrayOf(1600, 900)
        2 -> intArrayOf(1920, 1080)
        else -> intArrayOf(2560, 1440)
    }

    /** Raise the calling thread so the engine's worker threads (created from it, inheriting its priority) keep up. */
    fun boostCurrentThread() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY) }
    }

    /** Thread ids whose name starts with [prefix]: our worker and the engine threads it created (they inherit its name). */
    fun threadIds(prefix: String): IntArray {
        val tasks = File("/proc/self/task").listFiles() ?: return IntArray(0)
        return tasks.mapNotNull { dir ->
            val name = runCatching { File(dir, "comm").readText().trim() }.getOrNull() ?: return@mapNotNull null
            if (name.startsWith(prefix)) dir.name.toIntOrNull() else null
        }.toIntArray()
    }
}

/** SDR preview engine. Media3 owns output scheduling and the audio clock. */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeSvpEffect(private val controller: NativeSvpController, private val gpuOverride: Boolean? = null) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        check(!useHdr) { "Native interpolation currently supports SDR only" }
        check(NativeSvpStream.available) { "Native interpolation libraries are unavailable" }
        return NativeSvpShader(NativeSvpStream.libraryDirectory(context), controller, gpuOverride, context.applicationContext)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class NativeSvpShader(
    private val directory: String,
    private val controller: NativeSvpController,
    private val gpuOverride: Boolean?,
    private val appContext: Context,
) : GlShaderProgram {
    private var input: GlShaderProgram.InputListener = object : GlShaderProgram.InputListener {}
    private var output: GlShaderProgram.OutputListener = object : GlShaderProgram.OutputListener {}
    private var errors = GlShaderProgram.ErrorListener {}
    private var errorExecutor = Executor { it.run() }
    private val lock = Object()
    private var credit = false
    private var count = 0
    private var consumed = 0
    private var creditLimit = NativeSvpTuning.GPU_CREDIT
    private var useGpu = true
    @Volatile private var ended = false
    @Volatile private var active = true
    @Volatile private var finished = false
    private var handle = 0L
    private var worker: Thread? = null
    private var width = 0
    private var height = 0
    private var outWidth = 0
    private var outHeight = 0
    @Volatile private var firstUs = 0L
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
        active = false
        if (handle != 0L) NativeSvpStream.cancel(handle)
        synchronized(lock) { lock.notifyAll() }
        errorExecutor.execute { errors.onError(VideoFrameProcessingException(e)) }
    }
    private fun offerInput() {
        val ready = synchronized(lock) {
            if (active && (!ended || finished) && !credit && (finished || count - consumed < creditLimit)) { credit = true; true } else false
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
            var pushed = 1
            if (count == 0) {
                first = ByteBuffer.allocateDirect(pixels.capacity()).apply { put(pixels); rewind() }
            } else {
                if (handle == 0L) {
                    val hint = NativeSvpTuning.sourceFpsHint.toDouble()
                    val measured = if (hint in 10.0..61.0) hint else 1_000_000.0 / (timeUs - firstUs)
                    val rates = listOf(24000L to 1001L, 24L to 1L, 25L to 1L, 28L to 1L, 30000L to 1001L, 30L to 1L, 50L to 1L, 60000L to 1001L, 60L to 1L)
                    val nearest = rates.minBy { abs(it.first.toDouble() / it.second - measured) }
                    if (abs(nearest.first.toDouble() / nearest.second - measured) < measured * 0.01) {
                        fpsNum = nearest.first; fpsDen = nearest.second
                    } else { fpsNum = 1_000_000; fpsDen = timeUs - firstUs }
                    check(measured in 10.0..61.0) { "Unsupported source frame rate" }
                    open()
                    NativeSvpStream.push(handle, checkNotNull(first)); first = null
                }
                // Phone cameras record at a varying rate: snap every frame to the nearest beat of a steady grid.
                // A frame landing on a beat already filled is skipped; missed beats are filled by repeating this
                // frame. A gap of more than a second is a real discontinuity, which needs a Media3 flush instead.
                var slot = Math.round((timeUs - firstUs) * fpsNum.toDouble() / (fpsDen * 1_000_000.0)).toInt()
                if (slot - count > MAX_FILL) {
                    // A jump forward that Media3 didn't flush (frames skipped while fast-forwarding or catching up,
                    // or a quick seek). Failing here used to restart the whole player and stall for a few seconds;
                    // instead the steady grid carries on from this frame.
                    firstUs = timeUs - Math.round(count * fpsDen * 1_000_000.0 / fpsNum)
                    slot = count
                }
                pushed = if (slot < count) 0 else slot - count + 1
                repeat(pushed) {
                    pixels.rewind()
                    NativeSvpStream.push(handle, pixels)
                }
            }
            synchronized(lock) {
                lastUs = timeUs
                count += pushed
                NativeSvpTuning.inputs.incrementAndGet()
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
        // Adreno OpenCL stalled during live playback on the S26 Ultra. Keep rendering
        // on GLES, but run interpolation on the CPU for this GPU family.
        val adreno = NativeSvpTuning.isAdreno()
        NativeSvpTuning.lastAdreno = adreno
        useGpu = gpuOverride ?: (!adreno || NativeSvpTuning.adrenoGpu)
        if (useGpu && adreno) NativeSvpTuning.enableAdrenoFix(appContext)
        val adrenoGpuPath = useGpu && adreno
        val threads = if (useGpu) NativeSvpTuning.GPU_THREADS else NativeSvpTuning.CPU_THREADS
        val ahead = when {
            adrenoGpuPath -> NativeSvpTuning.ADRENO_GPU_AHEAD
            useGpu -> NativeSvpTuning.GPU_AHEAD
            else -> NativeSvpTuning.CPU_AHEAD
        }
        val outputs = when {
            adrenoGpuPath -> NativeSvpTuning.ADRENO_GPU_TEXTURES
            useGpu -> NativeSvpTuning.GPU_TEXTURES
            else -> NativeSvpTuning.CPU_TEXTURES
        }
        creditLimit = when {
            adrenoGpuPath -> NativeSvpTuning.ADRENO_GPU_CREDIT
            useGpu -> NativeSvpTuning.GPU_CREDIT
            else -> NativeSvpTuning.CPU_CREDIT
        }
        handle = NativeSvpStream.create(directory, width, height, fpsNum, fpsDen, useGpu, threads, ahead)
        check(handle != 0L)
        // Finished frames are enlarged to the requested size (never shrunk), keeping the picture's shape.
        outWidth = width
        outHeight = height
        NativeSvpTuning.upscaleTarget?.let { target ->
            val scale = minOf(target[0].toDouble() / width, target[1].toDouble() / height)
            if (scale > 1.05) {
                outWidth = (width * scale).toInt() and -2
                outHeight = (height * scale).toInt() and -2
            }
        }
        val backend = if (useGpu) "OpenCL" else "CPU"
        NativeSvpTuning.lastStream = "${width}x$height $backend · $threads threads · $ahead ahead" +
            if (outWidth != width) " → ${outWidth}x$outHeight sharp" else ""
        android.util.Log.i("NativeSvp", "Stream ${width}x$height source=$fpsNum/$fpsDen output=60 analysis=half backend=$backend threads=$threads ahead=$ahead outputs=$outputs")
        repeat(outputs) {
            val texture = checkNotNull(provider).createBuffersForTexture(GlUtil.createTexture(outWidth, outHeight, false), outWidth, outHeight)
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
            var hint: AutoCloseable? = null // a PerformanceHintManager.Session on Android 13+
            try {
                // The engine's pool threads are created from this thread on first use and inherit its priority and name.
                if (!useGpu) NativeSvpTuning.boostCurrentThread()
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
                val upscaling = outWidth != width || outHeight != height
                // Catmull-Rom bicubic (nine texture reads) for the picture detail; colour is soft enough for plain filtering.
                val sharpLuma = "uniform vec2 uYSize; float lumaAt(vec2 uv){" +
                    "vec2 sp=uv*uYSize; vec2 t1=floor(sp-.5)+.5; vec2 f=sp-t1;" +
                    "vec2 w0=f*(-.5+f*(1.-.5*f)); vec2 w1=1.+f*f*(-2.5+1.5*f); vec2 w2=f*(.5+f*(2.-1.5*f)); vec2 w3=f*f*(-.5+.5*f);" +
                    "vec2 w12=w1+w2; vec2 o12=w2/w12;" +
                    "vec2 a=(t1-1.)/uYSize; vec2 b=(t1+o12)/uYSize; vec2 c=(t1+2.)/uYSize;" +
                    "float r=texture2D(uY,vec2(a.x,a.y)).r*w0.x*w0.y+texture2D(uY,vec2(b.x,a.y)).r*w12.x*w0.y+texture2D(uY,vec2(c.x,a.y)).r*w3.x*w0.y" +
                    "+texture2D(uY,vec2(a.x,b.y)).r*w0.x*w12.y+texture2D(uY,vec2(b.x,b.y)).r*w12.x*w12.y+texture2D(uY,vec2(c.x,b.y)).r*w3.x*w12.y" +
                    "+texture2D(uY,vec2(a.x,c.y)).r*w0.x*w3.y+texture2D(uY,vec2(b.x,c.y)).r*w12.x*w3.y+texture2D(uY,vec2(c.x,c.y)).r*w3.x*w3.y;" +
                    "return r;} "
                converter = GlProgram(
                    "attribute vec2 aPosition; varying vec2 vTex; void main(){gl_Position=vec4(aPosition,0.,1.);vTex=aPosition*.5+.5;}",
                    "precision highp float; varying vec2 vTex; uniform sampler2D uY; uniform sampler2D uU; uniform sampler2D uV; " +
                        (if (upscaling) sharpLuma else "") +
                        "void main(){float y=((" + (if (upscaling) "lumaAt(vTex)" else "texture2D(uY,vTex).r") + ")-16./255.)*(255./219.);" +
                        "float u=(texture2D(uU,vTex).r-128./255.)*(255./224.);" +
                        "float v=(texture2D(uV,vTex).r-128./255.)*(255./224.);" +
                        "gl_FragColor=vec4(y+1.5748*v,y-.187324*u-.468124*v,y+1.8556*u,1.);}")
                converter.setBufferAttribute("aPosition", floatArrayOf(-1f,-1f,1f,-1f,-1f,1f,1f,1f), 2)
                if (upscaling) converter.setFloatsUniform("uYSize", floatArrayOf(width.toFloat(), height.toFloat()))
                var index = 0
                var lastPrint = 0L
                while (active) {
                    val texture = synchronized(lock) {
                        while (active && free.isEmpty()) lock.wait()
                        if (active) free.removeFirst() else null
                    } ?: break
                    if (pastEnd(index)) { synchronized(lock) { free.add(texture) }; break }
                    val renderStart = System.nanoTime()
                    if (!NativeSvpStream.render(handle, index, pixels) || !active || pastEnd(index)) {
                        synchronized(lock) { free.add(texture) }; break
                    }
                    NativeSvpTuning.noteRender(System.nanoTime() - renderStart)
                    NativeSvpTuning.outputs.incrementAndGet()
                    // Cheap picture fingerprint (64 luma samples) to tell new pictures from repeats in the report.
                    var print = 17L
                    val step = maxOf(1, width * height / 64)
                    for (k in 0 until 64) print = print * 31 + pixels.get(minOf(k * step, width * height - 1))
                    if (print != lastPrint) { NativeSvpTuning.newPictures.incrementAndGet(); lastPrint = print }
                    if (!useGpu && android.os.Build.VERSION.SDK_INT >= 33) {
                        // Tell the system these threads need a frame every 16.7 ms, so it keeps them on fast cores.
                        if (hint == null && index == 30) hint = runCatching {
                            val ids = NativeSvpTuning.threadIds("SkyWing-native")
                            appContext.getSystemService(android.os.PerformanceHintManager::class.java)
                                ?.takeIf { ids.isNotEmpty() }?.createHintSession(ids, 16_666_667L)
                        }.getOrNull()
                        (hint as? android.os.PerformanceHintManager.Session)?.let { session ->
                            runCatching { session.reportActualWorkDuration(System.nanoTime() - renderStart) }
                        }
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
                    GL.glViewport(0, 0, outWidth, outHeight)
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
                runCatching { hint?.close() }
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
        // Never wait forever: a worker stuck inside the GPU driver would otherwise freeze Media3's video thread and
        // with it any fallback. A stuck worker and its native stream are abandoned (a small leak) instead.
        val running = worker
        running?.join(1500)
        val stuck = running?.isAlive == true
        worker = null
        if (handle != 0L) { if (!stuck) NativeSvpStream.close(handle); handle = 0 }
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

/**
 * Fits the picture into the processing size of the chosen smoothing quality, keeping its shape, never enlarging, and
 * aligning to four pixels for the half-size analysis. [gpuMax] applies to the GPU engine; [cpuMax] to CPU smoothing on
 * Snapdragon phones whose GPU path is off.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class NativeSvpScale(
    private val gpuMax: IntArray = intArrayOf(1920, 1080),
    private val cpuMax: IntArray = intArrayOf(1280, 720),
) : androidx.media3.effect.GlMatrixTransformation {
    private val identity = GlUtil.create4x4IdentityMatrix()
    override fun configure(inputWidth: Int, inputHeight: Int): androidx.media3.common.util.Size {
        val cpu = NativeSvpTuning.isAdreno() && !NativeSvpTuning.adrenoGpu
        val box = if (cpu) cpuMax else gpuMax
        // Upright (portrait) videos get the box turned the same way: 1080x1920 instead of squeezing into 1920x1080.
        val max = if (inputHeight > inputWidth) intArrayOf(box[1], box[0]) else box
        val scale = minOf(1.0, max[0].toDouble() / inputWidth, max[1].toDouble() / inputHeight)
        return androidx.media3.common.util.Size(maxOf(4, (inputWidth * scale).toInt() and -4),
            maxOf(4, (inputHeight * scale).toInt() and -4))
    }
    override fun getGlMatrixArray(presentationTimeUs: Long): FloatArray = identity
}

/** Longest gap (in source frames, about a second) the variable-rate snapping fills by repeating a frame. */
private const val MAX_FILL = 30
