package com.deepak.videolibrary.downloads

/**
 * Works out download speed from the progress the queue reports (bytes done per file over time), smoothed over a few
 * seconds so the figure does not jump. Pure logic: the screen feeds it samples.
 */
class DownloadSpeed(private val windowMs: Long = 3_000) {
    private val samples = HashMap<Long, ArrayDeque<Pair<Long, Long>>>()

    /** Records [bytes] done for file [id] at [now] (milliseconds). */
    fun record(id: Long, bytes: Long, now: Long) {
        val list = samples.getOrPut(id) { ArrayDeque() }
        // A restarted file (fewer bytes than before) starts a new measurement.
        if (list.isNotEmpty() && bytes < list.last().second) list.clear()
        if (list.isEmpty() || list.last().first != now) list.addLast(now to bytes)
        while (list.size > 2 && now - list.first().first > windowMs) list.removeFirst()
    }

    /** Bytes per second for file [id] over the recent window, or 0 when unknown. */
    fun bytesPerSecond(id: Long): Double {
        val list = samples[id] ?: return 0.0
        if (list.size < 2) return 0.0
        val (t0, b0) = list.first()
        val (t1, b1) = list.last()
        val ms = t1 - t0
        if (ms <= 0) return 0.0
        return (b1 - b0).coerceAtLeast(0) * 1000.0 / ms
    }

    /** Forgets files that are no longer running. */
    fun keepOnly(ids: Set<Long>) {
        samples.keys.retainAll(ids)
    }

    companion object {
        private const val MB = 1024.0 * 1024.0

        /** "12.4 MB/s"; below 0.1 MB/s shows "0.0 MB/s". */
        fun format(bytesPerSecond: Double): String = String.format(java.util.Locale.ROOT, "%.1f MB/s", bytesPerSecond / MB)
    }
}
