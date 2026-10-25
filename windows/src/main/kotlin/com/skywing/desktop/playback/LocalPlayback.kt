package com.skywing.desktop.playback

import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Phase 2 bridge to an installed Windows x64 mpv.exe.
 * Next milestone embeds libmpv directly and packages native binaries.
 * Never passes filename contents to a shell; uses ProcessBuilder argument tokens.
 */
class LocalPlayback : Closeable {
    private var process: Process? = null
    private var pipe: String? = null

    fun play(file: File): String {
        if (!file.isFile) return "File not found."
        if (file.extension.lowercase() !in setOf("mp4", "mkv", "avi", "wmv", "mov", "m4v", "webm", "ts", "flv", "mpg", "mpeg")) {
            return "Unsupported file extension."
        }
        stop()
        val ipc = "\\\\.\\pipe\\skywing-${UUID.randomUUID()}"
        return try {
            process = ProcessBuilder(
                "mpv.exe", "--force-window=yes", "--keep-open=no", "--hwdec=auto-safe",
                "--input-ipc-server=$ipc", "--", file.absolutePath,
            ).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            pipe = ipc
            "Opened ${file.name} in mpv."
        } catch (ex: Exception) {
            pipe = null
            "Could not start mpv.exe. Install mpv x64 and add it to PATH. (${ex.javaClass.simpleName})"
        }
    }

    private fun command(vararg arguments: String): String {
        if (process?.isAlive != true) return "No active video."
        val p = pipe ?: return "No playback control connection."
        return try {
            // mpv's Windows named pipe accepts JSON IPC as a newline-terminated message.
            java.io.RandomAccessFile(p, "rw").use { channel ->
                val json = arguments.joinToString(",") { "\""+it.replace("\\", "\\\\").replace("\"", "\\\"")+"\"" }
                channel.write(("{\"command\":[$json]}\n").toByteArray(Charsets.UTF_8))
            }
            "Playback command sent."
        } catch (_: Exception) {
            "mpv controls not available yet; use the controls in the video window."
        }
    }

    fun togglePause(): String = command("cycle", "pause")
    fun seek(seconds: Int): String = command("seek", seconds.toString(), "relative")

    fun stop(): String {
        val running = process
        process = null
        pipe = null
        if (running != null && running.isAlive) {
            running.destroy()
            return "Playback stopped."
        }
        return "Nothing playing."
    }
    override fun close() { stop() }
}
