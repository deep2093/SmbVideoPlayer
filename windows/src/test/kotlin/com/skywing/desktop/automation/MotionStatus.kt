package com.skywing.desktop.automation

/** The motion status line the player shows ("Smoothing off", "Holding 59.94 fps", ...), or null while none is shown. */
internal fun Screen.motionStatus(): String? =
    nodes.firstOrNull { it.roleName == PlayerControls.LABEL && MotionStatus.LINE.containsMatchIn(it.name) }?.name

/** Waits for the motion engine to report the rate it holds, and returns that rate. */
internal fun SkyWingTestApp.waitForHolding(timeoutMs: Long = 45_000): Double {
    var rate = 0.0
    waitUntil("the motion engine to report 'Holding N fps'", timeoutMs) { s ->
        val text = s.motionStatus() ?: return@waitUntil false
        rate = holdingRate(text)
        true
    }
    return rate
}

/**
 * The rate a "Holding N fps" status gives. Any other status throws an [AssertionError] that names it, so a wait that
 * times out says what SkyWing showed.
 */
internal fun holdingRate(status: String): Double {
    if (MotionStatus.UNAVAILABLE.any { it in status }) throw AssertionError("Motion smoothing did not start: $status")
    return MotionStatus.HOLDING.find(status)?.groupValues?.get(1)?.toDouble()
        ?: throw AssertionError("SkyWing shows “$status”")
}

internal object MotionStatus {
    /** The preferences node of SkyWing's playback and motion settings. */
    const val PLAYBACK_NODE = "com/skywing/desktop/playback"
    val HOLDING = Regex("""^Holding ([0-9.]+) fps""")
    val LINE = Regex("""^(Smoothing off|Holding |Starting motion|Motion |Play a |Waiting |Could not |Can.t keep|Can.t smooth|Lowered|No smoothing|Cannot keep|Already smooth|HDR smoothing)""")
    val UNAVAILABLE = listOf("unavailable:", "Play a hardware-decoded video first", "No smoothing rate", "Could not",
        "Already smooth", "HDR smoothing is not available", "Can’t smooth")
}
