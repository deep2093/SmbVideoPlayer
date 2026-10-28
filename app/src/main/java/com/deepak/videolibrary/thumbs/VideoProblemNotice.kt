package com.deepak.videolibrary.thumbs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.deepak.videolibrary.data.VideoEntity

/** A notification naming videos the app could not read a length from after three tries. */
object VideoProblemNotice {
    private const val CHANNEL = "video_problems"
    private const val MAX_LISTED = 8

    fun lengthsUnreadable(context: Context, videos: List<VideoEntity>) {
        if (videos.isEmpty()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video problems", NotificationManager.IMPORTANCE_DEFAULT))
        val style = Notification.InboxStyle()
        videos.take(MAX_LISTED).forEach { style.addLine(it.name + "  -  " + it.relativePath) }
        if (videos.size > MAX_LISTED) style.addLine("and ${videos.size - MAX_LISTED} more")
        val title = if (videos.size == 1) "Could not read the length of a video" else "Could not read the length of ${videos.size} videos"
        val first = videos.first()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(first.name + "  -  " + first.relativePath)
            .setStyle(style)
            .setAutoCancel(true)
            .build()
        manager.notify(("video-lengths-" + System.currentTimeMillis()).hashCode(), notification)
    }
}
