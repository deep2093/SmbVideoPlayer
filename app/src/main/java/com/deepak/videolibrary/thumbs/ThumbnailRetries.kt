package com.deepak.videolibrary.thumbs

import android.content.Context

/** Counts thumbnail attempts that failed on a connection problem (kept across app restarts). */
class ThumbnailRetries(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("thumbnail_retries", Context.MODE_PRIVATE)

    /** Records one more failed attempt for [id] and returns the failed attempts so far. */
    fun failedAgain(id: Long): Int {
        val count = prefs.getInt(id.toString(), 0) + 1
        prefs.edit().putInt(id.toString(), count).apply()
        return count
    }

    fun clear(id: Long) {
        if (prefs.contains(id.toString())) prefs.edit().remove(id.toString()).apply()
    }

    /** Pressing Retry failed (or rebuilding thumbnails) gives every video its tries back. */
    fun clearAll() { prefs.edit().clear().apply() }
}
