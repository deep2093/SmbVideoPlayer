package com.skywing.desktop.playback

/** Diagnostics must not expose a media path, share host or embedded URL. */
internal object MotionLog {
    fun redact(message: String): String = message.lineSequence().joinToString(" | ") { line ->
        line.replace(Regex("""(?i)(?:[a-z]:[\\/]|\\\\|https?://).*"""), "[path omitted]")
            .replace(Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b"""), "[host omitted]")
    }.take(600)
}
