package com.deepak.videolibrary.thumbs

import android.media.MediaDataSource
import com.deepak.videolibrary.smb.SmbConnection
import java.io.IOException
import kotlin.math.min

/**
 * Lets MediaMetadataRetriever read a video straight off the SMB share, reading only the parts it
 * asks for. Reads go through a small block cache because the retriever issues many tiny reads.
 */
class SmbMediaDataSource(
    conn: SmbConnection,
    path: String,
    private val length: Long,
) : MediaDataSource() {

    private val file = conn.openFile(path)

    /** The last network/read error, so the caller can tell "bad file" from "network dropped". */
    @Volatile
    var lastError: Throwable? = null
        private set

    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > MAX_BLOCKS
    }

    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= length) return -1
        if (size == 0) return 0
        return try {
            val blockIndex = position / BLOCK_SIZE
            val block = loadBlock(blockIndex)
            val inBlock = (position - blockIndex * BLOCK_SIZE).toInt()
            val n = min(size, block.size - inBlock)
            if (n <= 0) {
                -1
            } else {
                System.arraycopy(block, inBlock, buffer, offset, n)
                n
            }
        } catch (e: Exception) {
            lastError = e
            -1
        }
    }

    private fun loadBlock(index: Long): ByteArray {
        blocks[index]?.let { return it }
        val start = index * BLOCK_SIZE
        val len = min(BLOCK_SIZE.toLong(), length - start).toInt()
        var data = ByteArray(len)
        var read = 0
        while (read < len) {
            val chunk = min(len - read, CHUNK_SIZE)
            val n = file.read(data, start + read, read, chunk)
            if (n <= 0) break
            read += n
        }
        if (read == 0) throw IOException("Unexpected end of file")
        if (read < len) data = data.copyOf(read)
        blocks[index] = data
        return data
    }

    @Synchronized
    override fun close() {
        runCatching { file.close() }
        blocks.clear()
    }

    private companion object {
        const val BLOCK_SIZE = 256 * 1024
        const val CHUNK_SIZE = 64 * 1024
        const val MAX_BLOCKS = 8
    }
}
