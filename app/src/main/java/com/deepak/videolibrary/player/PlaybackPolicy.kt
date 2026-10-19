package com.deepak.videolibrary.player

/** Completed or nearly completed videos start from the beginning on the next play. */
object PlaybackPolicy {
    fun resumePosition(position:Long,duration:Long):Long =
        if(duration>0 && position>=duration-10_000L) 0L else position.coerceAtLeast(0L)
}
