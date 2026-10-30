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
            val filter = program(vertex, FrameBlendRenderer.FRAGMENT_VECTOR_FILTER)
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
            // A filled ambiguous block must never gain confidence from matching neighbours.
            // Repeat for every quality preset; Max must require reverse evidence as well.
            for (level in 0..2) {
                for (case in listOf("trusted", "ambiguous", "missingReverse", "searchLimit")) {
                    val d = if (case == "searchLimit") 18f else 4f
                    for (i in 0..1) {
                        GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                        GL.glBindTexture(GL.GL_TEXTURE_2D, textures[i])
                        val data = ByteBuffer.allocateDirect(16 * 16 * 4)
                        repeat(256) { pixel ->
                            val encoded = ((0.5f + (if (i == 0) d else -d) / 40f) * 255).toInt()
                            data.put(encoded.toByte()).put(128.toByte()).put(0.toByte())
                            val ambiguous = case == "ambiguous" && i == 0 && pixel == 8 * 16 + 8
                            val missing = case == "missingReverse" && i == 1
                            data.put(if (ambiguous || missing) 0.toByte() else 255.toByte())
                        }
                        data.position(0)
                        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 16, 16, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, data)
                    }
                    GL.glUseProgram(filter)
                    GL.glUniform1i(GL.glGetUniformLocation(filter, "uVec"), 0)
                    GL.glUniform1i(GL.glGetUniformLocation(filter, "uBack"), 1)
                    GL.glUniform2f(GL.glGetUniformLocation(filter, "uSize"), 64f, 64f)
                    GL.glUniform2f(GL.glGetUniformLocation(filter, "uVecSize"), 16f, 16f)
                    GL.glUniform1f(GL.glGetUniformLocation(filter, "uConsist"), floatArrayOf(1f, 1.5f, 2f)[level])
                    GL.glUniform1f(GL.glGetUniformLocation(filter, "uSpread"), floatArrayOf(3f, 5f, 8f)[level])
                    draw(filter)
                    result.position(0)
                    GL.glReadPixels(0, 0, 16, 16, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, result)
                    val trusted = (result.get((8 * 16 + 8) * 4 + 2).toInt() and 255) >= 128
                    assertEquals("$case, strength=$level", case == "trusted", trusted)
                }
            }
            // An untrusted motion region must show one source, never double stripe edges by cross-fading.
            val mv = IntArray(1)
            GL.glGenTextures(1, mv, 0)
            for (i in 0..2) {
                GL.glActiveTexture(GL.GL_TEXTURE0 + i)
                GL.glBindTexture(GL.GL_TEXTURE_2D, if (i < 2) textures[i] else mv[0])
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
                GL.glTexParameteri(GL.GL_TEXTURE_2D, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
                val data = ByteBuffer.allocateDirect(4)
                if (i == 2) data.put(128.toByte()).put(128.toByte()).put(0.toByte()).put(255.toByte())
                else repeat(4) { data.put(if (i == 0) 0.toByte() else 255.toByte()) }
                data.position(0)
                GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, 1, 1, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, data)
            }
            GL.glUseProgram(warp)
            for ((uniform, unit) in mapOf("uPrev" to 0, "uCurr" to 1, "uMv" to 2, "uPrevL" to 0, "uCurrL" to 1)) {
                GL.glUniform1i(GL.glGetUniformLocation(warp, uniform), unit)
            }
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uSize"), 64f, 64f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uVecSize"), 1f, 1f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uHold"), 0.03f, 0.1f)
            GL.glUniform2f(GL.glGetUniformLocation(warp, "uDisc"), 2f, 5f)
            for ((mix, expected) in listOf(0.25f to 0, 0.75f to 255)) {
                GL.glUniform1f(GL.glGetUniformLocation(warp, "uMix"), mix)
                draw(warp)
                result.position(0)
                GL.glReadPixels(0, 0, 16, 16, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, result)
                assertEquals("Untrusted area at phase $mix", expected, result.get((8 * 16 + 8) * 4).toInt() and 255)
            }
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
