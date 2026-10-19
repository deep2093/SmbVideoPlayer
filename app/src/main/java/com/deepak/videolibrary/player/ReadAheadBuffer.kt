package com.deepak.videolibrary.player

import java.io.EOFException
import kotlin.math.min

/** Bounded sequential read-ahead; remote reads can be shorter than the requested block. */
internal class ReadAheadBuffer(capacity: Int = 1024 * 1024) {
    private val block = ByteArray(capacity)
    private var cursor = 0
    private var end = 0

    fun clear() { cursor = 0; end = 0 }

    fun read(target: ByteArray, offset: Int, length: Int, remaining: Long,
             fetch: (ByteArray, Int) -> Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        if (cursor == end) {
            val requested = min(block.size.toLong(), remaining).toInt()
            val count = fetch(block, requested)
            if (count <= 0) throw EOFException("SMB file ended before its declared size")
            require(count <= requested)
            cursor = 0
            end = count
        }
        val count = min(min(length, end - cursor).toLong(), remaining).toInt()
        block.copyInto(target, offset, cursor, cursor + count)
        cursor += count
        return count
    }
}
