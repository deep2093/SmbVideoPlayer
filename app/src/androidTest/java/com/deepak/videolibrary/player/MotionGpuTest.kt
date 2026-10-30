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
            program(vertex, FrameBlendRenderer.FRAGMENT_WARP)
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
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
    }
}
