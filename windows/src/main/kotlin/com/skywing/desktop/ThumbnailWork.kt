package com.skywing.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How many thumbnails are made at once (B24): one on battery or while a video plays, two with graphics built into the
 * processor (handhelds), three with a graphics card of its own.
 */
internal fun thumbnailWorkers(onBattery: Boolean, dedicatedGpu: Boolean, playing: Boolean): Int = when {
    playing || onBattery -> 1
    dedicatedGpu -> 3
    else -> 2
}

/**
 * Lets at most [limit] blocks run at once, like a semaphore whose size can change. The limit is read again when a
 * block wants to start, when a running one ends, and once a second while blocks wait, so plugging the charger in or
 * closing a video takes effect without a restart. A limit below one still lets one block run.
 */
internal class WorkLimit(private val limit: () -> Int) {
    private val running = MutableStateFlow(0)

    /** How many blocks run now. */
    val active: Int get() = running.value

    suspend fun <T> withPermit(block: suspend () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            running.update { it - 1 }
        }
    }

    private suspend fun acquire() {
        while (true) {
            val now = running.value
            if (now < limit().coerceAtLeast(1)) {
                if (running.compareAndSet(now, now + 1)) return
                continue
            }
            withTimeoutOrNull(RECHECK_MS) { running.first { it < now } }
        }
    }

    private companion object {
        const val RECHECK_MS = 1_000L
    }
}
