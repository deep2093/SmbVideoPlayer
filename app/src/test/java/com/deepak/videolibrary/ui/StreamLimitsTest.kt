package com.deepak.videolibrary.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamLimitsTest {
    @Test fun readsSmallStreamsWhole() {
        val bytes = ByteArray(100_000) { it.toByte() }
        assertArrayEquals(bytes, readAtMost(bytes.inputStream(), 100_000))
    }

    @Test fun refusesLargerStreamsWithoutReadingThemAll() {
        var read = 0L
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 0.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertNull(readAtMost(endless, 1_000_000))
        assert(read < 2_000_000) { "read $read bytes" }
    }
}
