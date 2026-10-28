package com.deepak.videolibrary.scan

import android.content.Context
import com.deepak.videolibrary.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * The library refreshes by itself only when the app is opened and its last finished scan is more than 12 hours old.
 * Nothing runs in the background while the app is closed, and nothing touches the drive while you browse a library
 * that has already been scanned. Any other time, the Refresh button (or pulling the list down) does it.
 */
object AutoRefresh {
    val INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(12)

    /** True when a scan finished at [lastFinished] (null: never) is old enough to repeat. A library never scanned is left to you. */
    fun isDue(lastFinished: Long?, now: Long): Boolean = lastFinished != null && now - lastFinished >= INTERVAL_MS

    suspend fun runIfDue(context: Context, services: ServiceLocator) {
        val saved = services.settings.load()
        if (saved.host.isBlank() || saved.share.isBlank()) return
        // A password that is not saved cannot be used unattended.
        if (saved.username.isNotBlank() && !saved.savePassword && services.settings.toProfile(saved).password.isBlank()) return
        val dao = services.db.dao()
        if (!isDue(dao.lastFinishedScanAt(), System.currentTimeMillis())) return
        ScanWorker.enqueue(context.applicationContext, ScanMode.REFRESH, libraryId = services.libraryId)
    }
}
