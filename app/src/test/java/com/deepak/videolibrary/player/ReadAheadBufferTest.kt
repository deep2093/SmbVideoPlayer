package com.deepak.videolibrary.player

import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class ReadAheadBufferTest {
    @Test fun smallConsumerReadsUseOneRemoteBlock() {
        val cache = ReadAheadBuffer(1024)
        var requests = 0
        var position = 0
        val source = ByteArray(2048) { (it % 251).toByte() }
        val result = ByteArray(2048)
        while (position < source.size) {
            val n = cache.read(result, position, 16, (source.size - position).toLong()) { block, count ->
                requests++
                source.copyInto(block, 0, position, position + count)
                count
            }
            position += n
        }
        assertArrayEquals(source, result)
        assertEquals(2, requests)
    }
    @Test fun shortServerReadsAndFinalPartialBlockPreserveBytes() {
        val cache = ReadAheadBuffer(16)
        val source = ByteArray(41) { it.toByte() }
        val result = ByteArray(41)
        var position = 0
        while (position < source.size) {
            position += cache.read(result, position, minOf(5, source.size - position), (source.size - position).toLong()) { block, count ->
                val n = minOf(7, count)
                source.copyInto(block, 0, position, position + n)
                n
            }
        }
        assertArrayEquals(source, result)
        assertEquals(-1, cache.read(result, 0, 1, 0) { _, _ -> fail("EOF must not fetch"); 0 })
    }
    @Test fun clearDiscardsBytesAfterSeek() {
        val cache = ReadAheadBuffer(16)
        val result = ByteArray(8)
        cache.read(result, 0, 1, 100) { block, count -> block.fill(1); count }
        cache.clear()
        cache.read(result, 2, 3, 3) { block, count -> block.fill(9); count }
        assertArrayEquals(byteArrayOf(1, 0, 9, 9, 9, 0, 0, 0), result)
    }
    @Test fun failedFetchCanRetryAtSamePosition() {
        val cache = ReadAheadBuffer(16)
        val result = ByteArray(4)
        try { cache.read(result, 0, 4, 4) { _, _ -> throw IOException() }; fail() } catch (_: IOException) { }
        assertEquals(4, cache.read(result, 0, 4, 4) { block, count -> block.fill(7); count })
        assertArrayEquals(ByteArray(4) { 7 }, result)
        assertEquals(0, cache.read(result, 0, 0, 0) { _, _ -> fail(); 0 })
    }
    @Test(expected = EOFException::class) fun prematureEofIsAnError() {
        ReadAheadBuffer(16).read(ByteArray(4), 0, 4, 4) { _, _ -> -1 }
    }
}
