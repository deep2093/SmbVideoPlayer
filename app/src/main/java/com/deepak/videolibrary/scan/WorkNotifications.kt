package com.deepak.videolibrary.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.ForegroundInfo

/**
 * Long scans and thumbnail runs are shown as an ongoing low-priority notification. This is what
 * lets Android keep them running for more than a few minutes in the background.
 */
object WorkNotifications {
    private const val CHANNEL_ID = "library_work"

    fun info(context: Context, notificationId: Int, title: String): ForegroundInfo {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Library scanning", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }
}
