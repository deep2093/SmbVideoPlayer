package com.deepak.videolibrary.scan

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deepak.videolibrary.ServiceLocator
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val requested = inputData
            .getString(KEY_MODE)
            ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        val services = ServiceLocator.get(applicationContext)
        val saved = services.settings.load()
        if (saved.host.isBlank() || saved.share.isBlank()) return Result.failure()
        if (com.deepak.videolibrary.settings.LibraryIdentity.of(saved) != services.libraryId) return Result.retry()
        val profile = services.settings.toProfile(saved)
        val expected = inputData.getString("libraryId")
        if (expected != null && expected != services.libraryId) return Result.failure()

        try {
            setForeground(WorkNotifications.info(applicationContext, 1, "Scanning your video library"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now; carry on as a normal worker.
        }

        // The repository binds retry ownership to the exact persisted scan session.
        val completed = coroutineScope {
            val scan = async { services.scanRepository.run(requested, profile, id.toString()) }
            // Thumbnails are made while the scan is still running, so artwork appears progressively.
            val thumbs = async { services.thumbnails.run(profile) { scan.isCompleted } }
            val ok = scan.await()
            if (!thumbs.await()) com.deepak.videolibrary.thumbs.ThumbnailWorker.enqueue(applicationContext, services.libraryId)
            ok
        }
        return if (completed || services.db.dao().pendingCountNow() == 0) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "library_scan"
        fun name(id: String) = "$UNIQUE_NAME:$id"
        private const val KEY_MODE = "mode"

        fun enqueue(context: Context, mode: ScanMode, afterCurrent: Boolean = false, libraryId: String = ServiceLocator.get(context).libraryId) {
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(androidx.work.workDataOf("libraryId" to libraryId, "mode" to mode.name))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(name(libraryId), if(afterCurrent) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(name(ServiceLocator.get(context).libraryId))
        }
    }
}
