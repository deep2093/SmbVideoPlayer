package com.deepak.videolibrary.player

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20 as GL
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs production shaders on the device; never opens a library or an SMB connection. */
@RunWith(AndroidJUnit4::class)
class MotionGpuTest {
    @Test fun productionShadersCompileAndCutsRetainResidual() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE,
        ), 0, configs, 0, 1, count, 0))
        check(count[0] > 0)
        val context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreatePbufferSurface(display, configs[0],
            intArrayOf(EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context))
        try {
            val vertex = FrameBlendRenderer.VERTEX_BLEND
            val motion = program(vertex, FrameBlendRenderer.FRAGMENT_MOTION)
            program(vertex, FrameBlendRenderer.FRAGMENT_VECTOR_FILTER)
            val warp = program(vertex, FrameBlendRenderer.FRAGMENT_WARP)
            program(vertex, FrameBlendRenderer.FRAGMENT_BLEND)
            program(vertex, FrameBlendRenderer.FRAGMENT_LUMA)
            program(FrameBlendRenderer.VERTEX_COPY, FrameBlendRenderer.FRAGMENT_COPY)

            val textures = IntArray(2)
            GL.glGenTextures(2, textures, 0)
            for (i in 0..1) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
                repeat(64 * 64) { repeat(3) { pixels.put(if (i == 0) 0.toByte() else 255.toByte()) }; pixels.put(255.toByte()) }
                pixels.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 64, 64, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
            }
            GL.glUseProgram(motion)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uCurL"), 0)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uPrevL"), 1)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uHint"), 0)
            GL.glUniform1f(GL.glGetUniformLocation(motion, "uHintOn"), 0f)
            GL.glUniform1f(GL.glGetUniformLocation(motion, "uClear"), 0.82f)
            GL.glUniform2f(GL.glGetUniformLocation(motion, "uSize"), 64f, 64f)
            GL.glUniform2f(GL.glGetUniformLocation(motion, "uVecSize"), 16f, 16f)
            GL.glViewport(0, 0, 16, 16)
            draw(motion)
            val result = ByteBuffer.allocateDirect(16 * 16 * 4)
            GL.glReadPixels(0, 0, 16, 16, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, result)
            assertEquals(GL.GL_NO_ERROR, GL.glGetError())
            // Black -> white has maximum residual even though motion is ambiguous.
            assertTrue((result.get((8 * 16 + 8) * 4 + 2).toInt() and 255) > 240)
            assertTrue(MotionReliability.decide(256, 0, 256, 1).sceneCut)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uPrevL"), 0)
            draw(motion)
            result.position(0)
            GL.glReadPixels(0, 0, 16, 16, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, result)
            assertEquals(0, result.get((8 * 16 + 8) * 4 + 2).toInt() and 255)
            // Known static fine stripes must remain stationary when the actual motion is zero.
            // The byte encoding rounds 0.5 to 128/255; at an 8x analysis scale this shifts each input.
            val ext = GL.glGetString(GL.GL_EXTENSIONS).orEmpty()
            val versionString = GL.glGetString(GL.GL_VERSION).orEmpty()
            val coreHalf = versionString.contains("OpenGL ES 3")
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                android.os.Bundle().apply { putString("stream", "\nGL=$versionString, coreHalf=$coreHalf\n") })
            org.junit.Assume.assumeTrue(coreHalf || (ext.contains("GL_OES_texture_half_float") && ext.contains("GL_OES_texture_half_float_linear")))
            for (i in 0..1) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                val data = ByteBuffer.allocateDirect(64 * 64 * 4)
                repeat(64) { repeat(64) { x ->
                    val c = if ((x / 2) % 2 == 0) 0 else 255
                    repeat(3) { data.put(c.toByte()) }; data.put(255.toByte())
                } }
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 64, 64, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, data)
            }
            val mv = IntArray(1)
            GL.glGenTextures(1, mv, 0)
            GL.glActiveTexture(GL.GL_TEXTURE2)
            GL.glBindTexture(GL.GL_TEXTURE_2D, mv[0])
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
            GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
            GL.glUseProgram(warp)
            for ((uniform, unit) in mapOf("uPrev" to 0, "uCurr" to 1, "uMv" to 2, "uPrevL" to 0, "uCurrL" to 1)) {
                GL.glUniform1i(GL.glGetUniformLocation(warp, uniform), unit)
            }
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uSize"), 8f, 8f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uVecSize"), 1f, 1f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uHold"), 10f, 20f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uDisc"), 10f, 20f)
            GL.glUniform1f(GL.glGetUniformLocation(warp, "uTrust"), 1f)
            GL.glUniform1f(GL.glGetUniformLocation(warp, "uMix"), 0.5f)
            GL.glViewport(0, 0, 64, 64)
            val errors = mutableListOf<Double>()
            for (half in listOf(false, true)) {
                val data = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
                if (half) { data.putShort(0x3800.toShort()).putShort(0x3800.toShort()).putShort(0x3c00.toShort()).putShort(0x3c00.toShort()) }
                else { data.put(128.toByte()).put(128.toByte()).put(255.toByte()).put(255.toByte()) }
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, if (half && coreHalf) 0x881A else GL.GL_RGBA, 1, 1, 0, GL.GL_RGBA, if (half) (if (coreHalf) 0x140B else 0x8D61) else GL.GL_UNSIGNED_BYTE, data)
                draw(warp)
                val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
                GL.glReadPixels(0, 0, 64, 64, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
                assertEquals(GL.GL_NO_ERROR, GL.glGetError())
                var sum = 0L
                for (y in 4..59) for (x in 4..59) {
                    val expected = if ((x / 2) % 2 == 0) 0 else 255
                    sum += kotlin.math.abs((pixels.get((y * 64 + x) * 4).toInt() and 255) - expected)
                }
                errors.add(sum.toDouble() / (56 * 56))
            }
            assertTrue("Byte baseline must expose stripe drift: $errors", errors[0] > 10)
            assertTrue("Half precision must preserve static stripes: $errors", errors[1] < 1)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                android.os.Bundle().apply { putString("stream", "\nStripe mean error: byte=${errors[0]}, half=${errors[1]}\n") })
            // Known four-pixel translation of stripes: midpoint must match the two-pixel ground truth.
            for (i in 0..1) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                val data = ByteBuffer.allocateDirect(64 * 64 * 4)
                repeat(64) { repeat(64) { x ->
                    val c = if (Math.floorMod(x - i * 4, 8) < 4) 0 else 255
                    repeat(3) { data.put(c.toByte()) }; data.put(255.toByte())
                } }
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 64, 64, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, data)
            }
            GL.glActiveTexture(GL.GL_TEXTURE2)
            val movingErrors = mutableListOf<Double>()
            for (half in listOf(false, true)) {
                val data = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
                if (half) data.putShort(android.util.Half.toHalf(0.4875f)).putShort(0x3800.toShort()).putShort(0x3c00.toShort()).putShort(0x3c00.toShort())
                else data.put(124.toByte()).put(128.toByte()).put(255.toByte()).put(255.toByte())
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, if (half && coreHalf) 0x881A else GL.GL_RGBA, 1, 1, 0,
                    GL.GL_RGBA, if (half) (if (coreHalf) 0x140B else 0x8D61) else GL.GL_UNSIGNED_BYTE, data)
                draw(warp)
                val pixels = ByteBuffer.allocateDirect(64 * 64 * 4)
                GL.glReadPixels(0, 0, 64, 64, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
                var sum = 0L
                for (y in 4..59) for (x in 4..59) {
                    val expected = if (Math.floorMod(x - 2, 8) < 4) 0 else 255
                    sum += kotlin.math.abs((pixels.get((y * 64 + x) * 4).toInt() and 255) - expected)
                }
                movingErrors.add(sum.toDouble() / (56 * 56))
            }
            assertTrue("Moving-stripe precision: $movingErrors", movingErrors[1] < 2 && movingErrors[0] > movingErrors[1] + 5)
            // Exercise the estimator itself, rather than supplying a perfect vector to the warp.
            // A textured striped plane moves (3,1) pixels between the two input images.
            for (i in 0..1) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                val data = ByteBuffer.allocateDirect(64 * 64 * 4)
                repeat(64) { y -> repeat(64) { x ->
                    val sx = x - if (i == 1) 3 else 0
                    val sy = y - if (i == 1) 1 else 0
                    val stripe = if (Math.floorMod(sy, 6) < 3) 40 else 180
                    val detail = Math.floorMod(sx * 17 + sy * 31 + sx * sy * 7, 61)
                    repeat(3) { data.put((stripe + detail).toByte()) }; data.put(255.toByte())
                } }
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 64, 64, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, data)
            }
            GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
            GL.glUseProgram(motion)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uCurL"), 0)
            GL.glUniform1i(GL.glGetUniformLocation(motion, "uPrevL"), 1)
            GL.glUniform1f(GL.glGetUniformLocation(motion, "uHintOn"), 0f)
            GL.glViewport(0, 0, 16, 16)
            draw(motion)
            val measured = ByteBuffer.allocateDirect(16 * 16 * 4)
            GL.glReadPixels(0, 0, 16, 16, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, measured)
            assertEquals(GL.GL_NO_ERROR, GL.glGetError())
            var correct = 0
            var confident = 0
            for (y in 3..12) for (x in 3..12) {
                val index = (y * 16 + x) * 4
                // Predictor pass stores four times its displacement in the shared encoding.
                val dx = ((measured.get(index).toInt() and 255) / 255f - 0.5f) * 10f
                val dy = ((measured.get(index + 1).toInt() and 255) / 255f - 0.5f) * 10f
                if (kotlin.math.abs(dx - 3f) < 0.2f && kotlin.math.abs(dy - 1f) < 0.2f) correct++
                if ((measured.get(index + 3).toInt() and 255) > 127) confident++
            }
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                android.os.Bundle().apply { putString("stream", "\nActual striped-plane search: correct=$correct/100 confident=$confident/100\n") })
            assertTrue("Actual motion search recovered only $correct/100 textured-stripe vectors", correct >= 90)
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(0,
                android.os.Bundle().apply { putString("stream", "\nMoving stripe mean error: byte=${movingErrors[0]}, half=${movingErrors[1]}\n") })
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    private fun program(vertex: String, fragment: String): Int {
        fun shader(type: Int, source: String): Int {
            val id = GL.glCreateShader(type)
            GL.glShaderSource(id, source)
            GL.glCompileShader(id)
            val status = IntArray(1)
            GL.glGetShaderiv(id, GL.GL_COMPILE_STATUS, status, 0)
            assertEquals(GL.glGetShaderInfoLog(id), GL.GL_TRUE, status[0])
            return id
        }
        val id = GL.glCreateProgram()
        val v = shader(GL.GL_VERTEX_SHADER, vertex)
        val f = shader(GL.GL_FRAGMENT_SHADER, fragment)
        GL.glAttachShader(id, v)
        GL.glAttachShader(id, f)
        GL.glLinkProgram(id)
        val status = IntArray(1)
        GL.glGetProgramiv(id, GL.GL_LINK_STATUS, status, 0)
        assertEquals(GL.glGetProgramInfoLog(id), GL.GL_TRUE, status[0])
        GL.glDeleteShader(v)
        GL.glDeleteShader(f)
        return id
    }

    private fun draw(program: Int) {
        val vertices = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertices.put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)).position(0)
        val position = GL.glGetAttribLocation(program, "aPosition")
        GL.glVertexAttribPointer(position, 2, GL.GL_FLOAT, false, 0, vertices)
        GL.glEnableVertexAttribArray(position)
        val coord = GL.glGetAttribLocation(program, "aCoord")
        val uv = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer()
        uv.put(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)).position(0)
        if (coord >= 0) {
            GL.glVertexAttribPointer(coord, 2, GL.GL_FLOAT, false, 0, uv)
            GL.glEnableVertexAttribArray(coord)
        }
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }
}
