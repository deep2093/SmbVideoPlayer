package com.deepak.videolibrary.thumbs

import android.content.Context
import com.deepak.videolibrary.data.VideoEntity

/** Video lengths found while making thumbnails or playing, kept on the phone so lists never read the drive for them. */
object VideoDurations {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("video_durations", Context.MODE_PRIVATE)
    private fun key(video: VideoEntity) = "${video.relativePath}|${video.size}|${video.modified}"

    fun remember(context: Context, video: VideoEntity, durationMs: Long) {
        if (durationMs > 0) prefs(context).edit().putLong(key(video), durationMs).apply()
    }

    /** A video whose length could not be read is noted so later scans do not open it again. */
    fun markTried(context: Context, video: VideoEntity) {
        prefs(context).edit().putBoolean("tried:" + key(video), true).apply()
    }

    fun wasTried(context: Context, video: VideoEntity): Boolean = prefs(context).getBoolean("tried:" + key(video), false)

    /** Forget the "could not read" notes (a rebuild of thumbnails gives every video another chance). */
    fun forgetTried(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("tried:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun get(context: Context, video: VideoEntity): Long? =
        prefs(context).getLong(key(video), 0L).takeIf { it > 0L }
}
