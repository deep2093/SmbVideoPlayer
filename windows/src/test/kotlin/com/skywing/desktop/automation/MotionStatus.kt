package com.skywing.desktop.automation

/** The motion status line the player shows ("Smoothing off", "Holding 59.94 fps", ...), or null while none is shown. */
internal fun Screen.motionStatus(): String? =
    nodes.firstOrNull { it.roleName == PlayerControls.LABEL && MotionStatus.LINE.containsMatchIn(it.name) }?.name

/** Waits for the motion engine to report the rate it holds, and returns that rate. */
internal fun SkyWingTestApp.waitForHolding(timeoutMs: Long = 45_000): Double {
    var rate = 0.0
    waitUntil("the motion engine to report 'Holding N fps'", timeoutMs) { s ->
        val text = s.motionStatus() ?: return@waitUntil false
        if (MotionStatus.UNAVAILABLE.any { it in text }) throw AssertionError("Motion smoothing did not start: $text")
        MotionStatus.HOLDING.find(text)?.let { rate = it.groupValues[1].toDouble() } != null
    }
    return rate
}

internal object MotionStatus {
    /** The preferences node of SkyWing's playback and motion settings. */
    const val PLAYBACK_NODE = "com/skywing/desktop/playback"
    val HOLDING = Regex("""^Holding ([0-9.]+) fps""")
    val LINE = Regex("""^(Smoothing off|Holding |Starting motion|Motion |Play a |Waiting |Could not |Can.t keep|Lowered|No smoothing|Cannot keep)""")
    val UNAVAILABLE = listOf("unavailable:", "Play a hardware-decoded video first", "No smoothing rate", "Could not")
}
