package com.skywing.desktop

/** Which video follows when one ends by itself. */
internal object QueueAdvance {
    /** The next index when the queue moves on by itself and there is one more video, otherwise null. */
    fun next(index: Int, size: Int, autoNext: Boolean): Int? =
        if (autoNext && index + 1 < size) index + 1 else null
}
