package com.skywing.desktop

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import java.awt.Frame
import java.awt.Window
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * One SkyWing per data folder (B38). The first start owns a named pipe. A later start, such as a double-click on a
 * video while SkyWing is open, sends the running one its video, lets it take the foreground and exits, so the open
 * window plays the video instead of a second window opening on the same library and settings.
 *
 * The pipe name is a hash of the data folder (`LOCALAPPDATA`), so the isolated UI tests and M0 runs, which each have
 * their own, never hand a file to the owner's SkyWing. The pipe refuses other computers, and a start hands over only
 * to a process of the same Windows user. When the check fails, the start runs as a window of its own, as before.
 */
internal object SingleInstance {
    sealed interface Claim

    /** This start runs SkyWing. Later starts' paths arrive on [handoffs]; an empty list asks only for the window. */
    class Owner private constructor(private val name: String, private val pipe: Pointer) : Claim, AutoCloseable {
        private val received = Channel<List<String>>(Channel.UNLIMITED)
        val handoffs: ReceiveChannel<List<String>> get() = received

        @Volatile private var closed = false
        private val listener = Thread(::listen, "SkyWing single instance").apply { isDaemon = true; start() }

        private fun listen() {
            try {
                while (!closed) {
                    val connected = kernel32.ConnectNamedPipe(pipe, null) || Native.getLastError() == ERROR_PIPE_CONNECTED
                    if (closed) break
                    // A start that fails half way must not stop the next one.
                    if (connected) runCatching { serve() } else Thread.sleep(100)
                    kernel32.DisconnectNamedPipe(pipe)
                }
            } finally {
                kernel32.CloseHandle(pipe)
                received.close()
            }
        }

        private fun serve() {
            val size = ByteBuffer.wrap(readFully(pipe, 4)).int
            require(size in 0..MAX_MESSAGE) { "message of $size bytes" }
            val paths = String(readFully(pipe, size), Charsets.UTF_8).split('\n').filter { it.isNotEmpty() }
            received.trySend(paths)
            writeFully(pipe, byteArrayOf(ACK))
            // Returns once the other start has read the answer, so disconnecting doesn't discard it.
            kernel32.FlushFileBuffers(pipe)
        }

        /** Stops taking hand-offs; later starts run SkyWing themselves. */
        override fun close() {
            if (closed) return
            closed = true
            // The listener waits in ConnectNamedPipe; connecting once wakes it.
            runCatching { connect(name, System.nanoTime() + TimeUnit.SECONDS.toNanos(2))?.let(kernel32::CloseHandle) }
            listener.join(2_000)
        }

        internal companion object {
            /** Null when another process already owns [name]. */
            fun create(name: String): Owner? {
                val pipe = kernel32.CreateNamedPipeW(WString(name), PIPE_ACCESS_DUPLEX or FILE_FLAG_FIRST_PIPE_INSTANCE,
                    PIPE_REJECT_REMOTE_CLIENTS, 1, 512, 64 * 1024, 0, null)
                if (valid(pipe)) return Owner(name, pipe!!)
                val error = Native.getLastError()
                if (error == ERROR_ACCESS_DENIED || error == ERROR_PIPE_BUSY) return null
                throw IllegalStateException("CreateNamedPipe failed ($error)")
            }
        }
    }

    /** The running SkyWing took the paths; this start should exit. */
    data object HandedOff : Claim

    /** The check failed, so this start runs a window of its own. */
    data class Alone(val reason: String) : Claim

    /** SkyWing's data folder, as the library and settings stores find it. */
    fun dataFolder(): String = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")

    /** The pipe for [dataFolder]. Pipe names are visible to every user, so it holds a hash, not the folder. */
    fun pipeName(dataFolder: String): String {
        val key = dataFolder.trimEnd('\\', '/').lowercase(Locale.ROOT)
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return "\\\\.\\pipe\\SkyWing-" + hash.take(16).joinToString("") { "%02x".format(it) }
    }

    /**
     * Makes this start the SkyWing for [dataFolder], or hands [args] to the one already running. Relative paths are
     * made absolute here, since the running SkyWing has another working folder.
     */
    fun claim(dataFolder: String, args: List<String>, timeoutMs: Long = 5_000): Claim {
        val name = pipeName(dataFolder)
        val paths = args.map { File(it).absolutePath }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return try {
            ownOrHandOff(name, paths, deadline)
        } catch (e: TimeoutException) {
            Alone("the running SkyWing did not answer")
        } catch (e: ExecutionException) {
            Alone(e.cause?.toString() ?: e.toString())
        } catch (e: Exception) {
            Alone(e.toString())
        } catch (e: LinkageError) {
            Alone(e.toString())
        }
    }

    private fun ownOrHandOff(name: String, paths: List<String>, deadline: Long): Claim {
        while (true) {
            Owner.create(name)?.let { return it }
            // Synchronous pipe calls can't time out, so a running SkyWing that never answers is waited for here.
            val sent = CompletableFuture.supplyAsync { handOff(name, paths, deadline) }
                .get(TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0) + 2_000, TimeUnit.MILLISECONDS)
            if (sent != null) return sent
            // The running SkyWing exited between the two calls: try again to be the one.
            if (System.nanoTime() > deadline) return Alone("the running SkyWing exited during the check")
        }
    }

    /** Null when nothing owns [name] any more. */
    private fun handOff(name: String, paths: List<String>, deadline: Long): Claim? {
        val pipe = connect(name, deadline) ?: return null
        try {
            val owner = IntByReference()
            check(kernel32.GetNamedPipeServerProcessId(pipe, owner)) { "GetNamedPipeServerProcessId failed (${Native.getLastError()})" }
            if (!sameUser(owner.value.toLong())) return Alone("another user's process owns the pipe")
            // Windows only lets the foreground process give the foreground away.
            runCatching { user32.AllowSetForegroundWindow(owner.value) }
            val message = paths.joinToString("\n").toByteArray(Charsets.UTF_8)
            check(message.size <= MAX_MESSAGE) { "too many paths" }
            writeFully(pipe, ByteBuffer.allocate(4).putInt(message.size).array() + message)
            return if (readFully(pipe, 1)[0] == ACK) HandedOff else Alone("the running SkyWing gave no answer")
        } finally {
            kernel32.CloseHandle(pipe)
        }
    }

    /** A client end of [name], waiting while another start is being served; null when nothing owns it. */
    private fun connect(name: String, deadline: Long): Pointer? {
        val wide = WString(name)
        while (true) {
            val pipe = kernel32.CreateFileW(wide, GENERIC_READ or GENERIC_WRITE, 0, null, OPEN_EXISTING, 0, null)
            if (valid(pipe)) return pipe
            when (val error = Native.getLastError()) {
                ERROR_FILE_NOT_FOUND -> return null
                ERROR_PIPE_BUSY -> {
                    val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                    if (left <= 0) throw TimeoutException("the pipe stayed busy")
                    kernel32.WaitNamedPipeW(wide, left.coerceAtMost(1_000).toInt())
                }
                else -> throw IllegalStateException("CreateFile on the pipe failed ($error)")
            }
        }
    }

    private fun sameUser(pid: Long): Boolean {
        val mine = ProcessHandle.current().info().user().orElse(null) ?: return false
        val theirs = ProcessHandle.of(pid).flatMap { it.info().user() }.orElse(null) ?: return false
        return mine.equals(theirs, ignoreCase = true)
    }

    /** Brings [window] forward after a hand-off; the starting process let this one take the foreground. */
    fun bringToFront(window: Window) {
        (window as? Frame)?.let { if (it.extendedState and Frame.ICONIFIED != 0) it.extendedState = it.extendedState and Frame.ICONIFIED.inv() }
        window.toFront()
        runCatching { Native.getComponentPointer(window)?.let(user32::SetForegroundWindow) }
    }

    private fun readFully(pipe: Pointer, size: Int): ByteArray {
        val result = ByteArray(size)
        var done = 0
        val read = IntByReference()
        while (done < size) {
            val chunk = ByteArray(size - done)
            check(kernel32.ReadFile(pipe, chunk, chunk.size, read, null) && read.value > 0) { "ReadFile failed (${Native.getLastError()})" }
            System.arraycopy(chunk, 0, result, done, read.value)
            done += read.value
        }
        return result
    }

    private fun writeFully(pipe: Pointer, bytes: ByteArray) {
        val written = IntByReference()
        check(kernel32.WriteFile(pipe, bytes, bytes.size, written, null) && written.value == bytes.size) {
            "WriteFile failed (${Native.getLastError()})"
        }
    }

    private fun valid(handle: Pointer?) = handle != null && Pointer.nativeValue(handle) != -1L

    private interface Kernel32 : StdCallLibrary {
        fun CreateNamedPipeW(name: WString, openMode: Int, pipeMode: Int, maxInstances: Int, outBuffer: Int, inBuffer: Int,
            timeoutMs: Int, security: Pointer?): Pointer?
        fun ConnectNamedPipe(pipe: Pointer, overlapped: Pointer?): Boolean
        fun DisconnectNamedPipe(pipe: Pointer): Boolean
        fun CreateFileW(name: WString, access: Int, share: Int, security: Pointer?, disposition: Int, flags: Int, template: Pointer?): Pointer?
        fun WaitNamedPipeW(name: WString, timeoutMs: Int): Boolean
        fun GetNamedPipeServerProcessId(pipe: Pointer, processId: IntByReference): Boolean
        fun ReadFile(file: Pointer, buffer: ByteArray, size: Int, read: IntByReference, overlapped: Pointer?): Boolean
        fun WriteFile(file: Pointer, buffer: ByteArray, size: Int, written: IntByReference, overlapped: Pointer?): Boolean
        fun FlushFileBuffers(file: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private interface User32 : StdCallLibrary {
        fun AllowSetForegroundWindow(processId: Int): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
    }

    private val kernel32 by lazy { Native.load("kernel32", Kernel32::class.java) }
    private val user32 by lazy { Native.load("user32", User32::class.java) }

    private const val ACK: Byte = 1
    private const val MAX_MESSAGE = 1 shl 20
    private const val PIPE_ACCESS_DUPLEX = 0x3
    private const val FILE_FLAG_FIRST_PIPE_INSTANCE = 0x80000
    private const val PIPE_REJECT_REMOTE_CLIENTS = 0x8
    private const val GENERIC_READ = 0x80000000.toInt()
    private const val GENERIC_WRITE = 0x40000000
    private const val OPEN_EXISTING = 3
    private const val ERROR_FILE_NOT_FOUND = 2
    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_PIPE_BUSY = 231
    private const val ERROR_PIPE_CONNECTED = 535
}
