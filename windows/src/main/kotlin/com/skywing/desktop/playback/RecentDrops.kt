package com.skywing.desktop.playback

/** Rolling increments survive mpv counter resets without retaining old drops forever. */
internal class RecentDrops {
    private val events = java.util.ArrayDeque<Pair<Long, Long>>()
    private var previous: List<Long?>? = null
    private var unknownUntil = Long.MIN_VALUE
    fun reset() { events.clear(); previous = null; unknownUntil = Long.MIN_VALUE }
    fun sample(now: Long, totals: List<Long?>): Long? {
        val before = previous
        if (totals.any { it == null } || before?.any { it == null } == true) unknownUntil = now + 60_000
        if (before != null) {
            val delta = totals.zip(before).sumOf { (a, b) ->
                if (a == null || b == null) 0L else if (a >= b) a - b else a
            }
            if (delta > 0) events.addLast(now to delta)
        }
        previous = totals
        while (events.isNotEmpty() && events.first.first <= now - 60_000) events.removeFirst()
        return if (now < unknownUntil) null else events.sumOf { it.second }
    }
}
