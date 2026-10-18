package com.deepak.videolibrary.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

/** Random-access SMB file transport for Media3. Never downloads an entire video. */
class SmbPlaybackDataSource(private val profile: SmbProfile) : BaseDataSource(true) {
    private var conn: SmbConnection? = null
    private var handle: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        try {
            val connection = SmbConnection(profile)
            conn = connection
            connection.open()
            val path = dataSpec.uri.getQueryParameter("path") ?: throw IOException("Missing video path")
            handle = connection.openFile(path)
            position = dataSpec.position
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                        else (dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: throw IOException("Missing video size")) - position
            if (remaining < 0) throw EOFException("Seek past end of video")
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) { close(); throw IOException("Unable to open SMB video", e) }
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        // Media3 reads on its loader thread; recover broken SMB TCP sessions at the
        // exact previous byte position, without downloading the full file.
        var n = -1
        var failure: Exception? = null
        for (attempt in 0..3) {
            try {
                n = handle?.read(buffer, position, offset, min(length.toLong(), remaining).toInt())
                    ?: throw IOException("SMB file is closed")
                failure = null
                break
            } catch (e: Exception) {
                failure = e
                if (attempt == 3) break
                try {
                    reconnectAtPosition()
                    Thread.sleep(400L * (attempt + 1))
                } catch (retryError: Exception) { failure = retryError }
            }
        }
        if (failure != null) throw IOException("SMB stream could not reconnect", failure)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }
    private fun reconnectAtPosition() {
        runCatching { handle?.close() }; handle = null
        runCatching { conn?.close() }; conn = null
        val newConn = SmbConnection(profile)
        newConn.open()
        val path = uri?.getQueryParameter("path") ?: throw IOException("Missing playback file")
        handle = newConn.openFile(path)
        conn = newConn
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        runCatching { handle?.close() }
        handle = null
        runCatching { conn?.close() }
        conn = null
        uri = null
        if (remaining > 0) remaining = 0
    }
    class Factory(private val profile: SmbProfile) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbPlaybackDataSource(profile)
    }
}
