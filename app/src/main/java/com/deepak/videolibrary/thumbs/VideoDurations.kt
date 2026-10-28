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

    /** Failed reads so far for this video (each scan tries once). Saved lengths are never touched by this. */
    fun failedReads(context: Context, video: VideoEntity): Int = prefs(context).getInt("failed:" + key(video), 0)

    /** Notes one more failed read and returns the total. */
    fun noteFailedRead(context: Context, video: VideoEntity): Int {
        val total = failedReads(context, video) + 1
        prefs(context).edit().putInt("failed:" + key(video), total).apply()
        return total
    }

    /** After the first try and two more, a video is left alone. */
    const val MAX_FAILED_READS = 3

    fun gaveUp(context: Context, video: VideoEntity): Boolean = failedReads(context, video) >= MAX_FAILED_READS

    /** Forget the failed-read counts only (clearing thumbnails gives those videos fresh tries; saved lengths stay). */
    fun forgetFailedReads(context: Context) {
        val p = prefs(context)
        val edit = p.edit()
        p.all.keys.filter { it.startsWith("failed:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    fun get(context: Context, video: VideoEntity): Long? =
        prefs(context).getLong(key(video), 0L).takeIf { it > 0L }
}
