package com.deepak.videolibrary.downloads

import android.content.Context

/** Download settings that are not per server. */
object DownloadPrefs {
    private const val FILE = "downloads"
    private const val PARALLEL = "parallel"
    const val MIN_PARALLEL = 1
    const val MAX_PARALLEL = 4

    /** How many files download at the same time (1 by default, at most 4). */
    fun parallel(context: Context): Int =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt(PARALLEL, MIN_PARALLEL).coerceIn(MIN_PARALLEL, MAX_PARALLEL)

    fun setParallel(context: Context, value: Int) {
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(PARALLEL, value.coerceIn(MIN_PARALLEL, MAX_PARALLEL)).apply()
    }
}
