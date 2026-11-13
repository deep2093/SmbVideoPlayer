package com.deepak.videolibrary.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

/**
 * Random-access SMB file transport for Media3. Never downloads an entire video.
 *
 * Media3 closes and re-opens its data source on every seek. Logging in to the server and opening the file again each
 * time made every jump slower than it had to be, so the connection and the open file are kept for a few seconds after
 * a close and reused when the same video is opened again. They are closed for real once nothing has asked for them
 * for [KEEP_OPEN_MS] (a closed player, or the end of a video).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbPlaybackDataSource(private val profile: SmbProfile, private val diagnostic: (String) -> Unit = {}) : BaseDataSource(true) {
    private var conn: SmbConnection? = null
    private var handle: com.hierynomus.smbj.share.File? = null
    private var uri: Uri? = null
    /** The video [handle] belongs to; guarded by [link] together with [conn], [handle] and [idleSerial]. */
    private var linkedPath: String? = null
    private val link = Any()
    private var idleSerial = 0
    private var position = 0L
    private var remaining = 0L
    private val readAhead = ReadAheadBuffer()
    private var opened = false
    private var remoteBytes = 0L
    private var remoteReadMs = 0L
    private var remoteReads = 0
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        try {
            val path = dataSpec.uri.getQueryParameter("path") ?: throw IOException("Missing video path")
            synchronized(link) {
                idleSerial++ // Cancels a pending idle close.
                if (handle == null || conn == null || linkedPath != path) {
                    closeLink()
                    val connection = SmbConnection(profile)
                    conn = connection // closeLink() also owns a failed connect
                    connection.open()
                    handle = connection.openFile(path)
                    linkedPath = path
                }
            }
            readAhead.clear()
            position = dataSpec.position
            val size = dataSpec.uri.getQueryParameter("size")?.toLongOrNull() ?: throw IOException("Missing video size")
            if (position > size) throw EOFException("Seek past end of video")
            remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) min(dataSpec.length, size - position) else size - position
            if (remaining < 0) throw EOFException("Seek past end of video")
            opened = true
            transferStarted(dataSpec)
            return remaining
        } catch (e: Exception) {
            close()
            synchronized(link) { idleSerial++; closeLink() }
            throw IOException("Unable to open SMB video", e)
        }
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
                n = readAhead.read(buffer, offset, length, remaining) { block, count ->
                    val start = android.os.SystemClock.elapsedRealtime()
                    val fetched = handle?.read(block, position, 0, count) ?: throw IOException("SMB file is closed")
                    remoteBytes += fetched.coerceAtLeast(0)
                    remoteReadMs += android.os.SystemClock.elapsedRealtime() - start
                    remoteReads++
                    if (remoteReads >= 32) {
                        diagnostic("readBytes=$remoteBytes readMs=$remoteReadMs requests=$remoteReads")
                        remoteBytes = 0L
                        remoteReadMs = 0L
                        remoteReads = 0
                    }
                    fetched
                }
                failure = null
                break
            } catch (e: Exception) {
                // A seek or a closing player interrupts the read: that is not a network fault. Stop at once and keep
                // the connection, instead of logging in again just to be cancelled.
                if (Thread.currentThread().isInterrupted || interrupted(e)) {
                    throw java.io.InterruptedIOException("SMB read interrupted")
                }
                failure = e
                diagnostic("reconnectAttempt=${attempt + 1} cause=${e.javaClass.simpleName}")
                if (attempt == 3 || e is EOFException) break
                try {
                    reconnectAtPosition()
                    Thread.sleep(400L * (attempt + 1))
                } catch (retryError: Exception) {
                    if (retryError is InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw IOException("SMB read interrupted", retryError)
                    }
                    failure = retryError
                }
            }
        }
        if (failure != null) throw IOException("SMB stream could not reconnect", failure)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }
    private fun interrupted(error: Throwable): Boolean {
        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth++ < 12) {
            if (cause is InterruptedException) return true
            if (cause is java.io.InterruptedIOException && cause !is java.net.SocketTimeoutException) return true
            cause = cause.cause
        }
        return false
    }
    private fun reconnectAtPosition() {
        synchronized(link) {
            closeLink()
            val newConn = SmbConnection(profile)
            conn = newConn // closeLink() also owns failed reconnects
            newConn.open()
            val path = uri?.getQueryParameter("path") ?: throw IOException("Missing playback file")
            handle = newConn.openFile(path)
            linkedPath = path
        }
    }

    /** Closes the open file and the connection. Call with [link] held. */
    private fun closeLink() {
        runCatching { handle?.close() }
        handle = null
        runCatching { conn?.close() }
        conn = null
        linkedPath = null
    }
    override fun getUri(): Uri? = uri
    override fun close() {
        uri = null
        remaining = 0
        readAhead.clear()
        // Keep the connection for the next open (a seek re-opens within moments); close it if none comes.
        val serial = synchronized(link) { if (handle == null && conn == null) -1 else ++idleSerial }
        if (serial >= 0) {
            runCatching {
                idleCloser.schedule(
                    Runnable { synchronized(link) { if (idleSerial == serial) closeLink() } },
                    KEEP_OPEN_MS,
                    java.util.concurrent.TimeUnit.MILLISECONDS,
                )
            }.onFailure { synchronized(link) { closeLink() } }
        }
        if (opened) {
            opened = false
            transferEnded()
        }
    }
    private companion object {
        /** How long an unused connection is kept for the next open. Short, so the drive is never held awake. */
        const val KEEP_OPEN_MS = 8_000L
        val idleCloser: java.util.concurrent.ScheduledExecutorService =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "smb-playback-idle-close").apply { isDaemon = true }
            }
    }
    class Factory(private val profile: SmbProfile, private val diagnostic: (String) -> Unit = {}) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbPlaybackDataSource(profile, diagnostic)
    }
}
