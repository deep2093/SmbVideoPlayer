package com.skywing.desktop

/** Which video follows when one ends by itself. */
internal object QueueAdvance {
    /** The next index when the queue moves on by itself and there is one more video, otherwise null. */
    fun next(index: Int, size: Int, autoNext: Boolean): Int? =
        if (autoNext && index + 1 < size) index + 1 else null

    /**
     * The index to play after a video ended, or null when nothing should happen: the player moved on or was left while
     * the ended video was being saved (a different open, a different file, or not on the player page).
     */
    fun afterEnd(
        index: Int, size: Int, autoNext: Boolean,
        sameOpen: Boolean, sameFile: Boolean, onPlayerPage: Boolean,
    ): Int? = if (sameOpen && sameFile && onPlayerPage) next(index, size, autoNext) else null
}
