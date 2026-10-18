package com.deepak.videolibrary.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.mserref.NtStatus
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbProfile(
    val host: String,
    val share: String,
    /** Optional sub-folder inside the share, forward slashes, no leading/trailing slash. */
    val rootPath: String,
    val username: String,
    val password: String,
)

data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
)

/**
 * A single SMB connection. All methods block, so call them from Dispatchers.IO.
 * The connection is read-only by usage: this class only lists directories.
 */
class SmbConnection(private val profile: SmbProfile) : Closeable {

    private var client: SMBClient? = null
    private var session: Session? = null
    private var share: DiskShare? = null

    fun open() {
        close()
        val config = SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(30, TimeUnit.SECONDS)
            .build()
        val newClient = SMBClient(config)
        try {
            val connection = newClient.connect(profile.host.trim())
            val auth = if (profile.username.isBlank()) {
                AuthenticationContext.anonymous()
            } else {
                AuthenticationContext(profile.username.trim(), profile.password.toCharArray(), "")
            }
            val newSession = connection.authenticate(auth)
            val newShare = newSession.connectShare(profile.share.trim()) as DiskShare
            client = newClient
            session = newSession
            share = newShare
        } catch (t: Throwable) {
            runCatching { newClient.close() }
            throw t
        }
    }

    fun reconnect() = open()

    /** Lists a directory. [path] is relative to the share root, forward slashes, "" for the root. */
    fun list(path: String): List<SmbEntry> {
        val disk = share ?: throw IOException("Not connected")
        val smbPath = path.trim('/').replace('/', '\\')
        val result = ArrayList<SmbEntry>()
        for (info in disk.list(smbPath)) {
            val name = info.fileName
            if (name == "." || name == "..") continue
            val isDir = EnumWithValue.EnumUtils.isSet(
                info.fileAttributes,
                FileAttributes.FILE_ATTRIBUTE_DIRECTORY,
            )
            result.add(
                SmbEntry(
                    name = name,
                    isDirectory = isDir,
                    size = info.endOfFile,
                    modified = info.lastWriteTime.toEpochMillis(),
                ),
            )
        }
        return result
    }

    /** Opens a file for reading only. [path] is relative to the share root, forward slashes. */
    fun openFile(path: String): com.hierynomus.smbj.share.File {
        val disk = share ?: throw IOException("Not connected")
        return disk.openFile(
            path.trim('/').replace('/', '\\'),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
    }

    /** Remote file management. These operations WRITE to the SMB share, unlike virtual folders. */
    private fun safePath(value: String): String {
        val normalized = value.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it == "." || it == ".." || it.isBlank() }) {
            "Invalid or root file path"
        }
        return normalized.replace('/', '\\')
    }

    fun renamePath(source: String, destination: String, directory: Boolean = false) {
        val disk = share ?: throw IOException("Not connected")
        val src = safePath(source)
        val dst = safePath(destination)
        require(src != dst) { "Source and destination are the same" }
        require(!disk.fileExists(dst) && !disk.folderExists(dst)) { "Destination already exists" }
        if (directory) {
            disk.openDirectory(src, EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(dst, false) }
        } else {
            disk.openFile(src, EnumSet.of(AccessMask.DELETE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null).use { it.rename(dst, false) }
        }
    }

    fun deletePath(path: String, isDirectory: Boolean) {
        val disk = share ?: throw IOException("Not connected")
        val safe = safePath(path)
        if (isDirectory) disk.rmdir(safe, true) else disk.rm(safe)
    }

    fun copyPath(source: String, destination: String, isDirectory: Boolean) {
        val disk = share ?: throw IOException("Not connected")
        val src = safePath(source)
        val dst = safePath(destination)
        require(src != dst && !dst.startsWith("$src\\", ignoreCase = true)) { "Cannot copy a folder into itself" }
        require(!disk.fileExists(dst) && !disk.folderExists(dst)) { "Destination already exists" }
        if (isDirectory) {
            disk.mkdir(dst)
            try {
                for (child in list(source)) {
                    val from = source.trimEnd('/') + "/" + child.name
                    val to = destination.trimEnd('/') + "/" + child.name
                    copyPath(from, to, child.isDirectory)
                }
            } catch (e: Exception) {
                // A partially copied directory remains so the user can inspect it; never delete source.
                throw IOException("Copy interrupted. Destination folder may contain incomplete files: ${e.message}", e)
            }
        } else {
            disk.openFile(src, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null).use { input ->
                disk.openFile(dst, EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_CREATE, null).use { output ->
                    input.inputStream.use { stream -> output.outputStream.use { sink ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            sink.write(buffer, 0, count)
                        }
                        sink.flush()
                    } }
                }
            }
        }
    }

    override fun close() {
        runCatching { share?.close() }
        runCatching { session?.close() }
        runCatching { client?.close() }
        share = null
        session = null
        client = null
    }
}

object SmbErrors {

    /** Turns an exception into a message that is useful to show on screen. Never includes the password. */
    fun describe(e: Throwable): String {
        var cause: Throwable? = e
        while (cause != null) {
            when (cause) {
                is SMBApiException -> return describeStatus(cause)
                is UnknownHostException -> return "Server address not found. Check the address and that you are on the home Wi-Fi."
                is SocketTimeoutException -> return "The connection timed out. The router may be busy or the address may be wrong."
            }
            cause = cause.cause
        }
        // Raw exception messages can include a URL, username or share path.
        // Never surface arbitrary library exception text to the UI or scan logs.
        return when (e) {
            is TransportException, is IOException -> "Network problem. Check SMB address, Wi-Fi, permissions and connection."
            else -> "SMB operation failed (${e.javaClass.simpleName}). Check connection and access permissions."
        }
    }

    private fun describeStatus(e: SMBApiException): String = when (e.status) {
        NtStatus.STATUS_LOGON_FAILURE -> "Incorrect username or password."
        NtStatus.STATUS_ACCOUNT_DISABLED -> "This account is disabled on the server."
        NtStatus.STATUS_BAD_NETWORK_NAME -> "Share not found. Check the share name."
        NtStatus.STATUS_ACCESS_DENIED -> "Access denied."
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "Folder not found."
        else -> "Server error: ${e.status}"
    }

    /** True for failures that a reconnect might fix (network drops), false for permission/path errors. */
    fun isTransient(e: Throwable): Boolean {
        if (e is SMBApiException) return false
        return e is TransportException || e is IOException || e is SMBRuntimeException
    }
}
