package com.deepak.videolibrary.thumbs

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.scan.WorkNotifications
import kotlin.coroutines.cancellation.CancellationException

/** Generates all pending thumbnails, then stops. Used after a scan and from the Scan screen. */
class ThumbnailWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        val saved = services.settings.load()
        if (saved.host.isBlank() || saved.share.isBlank()) return Result.failure()
        if (com.deepak.videolibrary.settings.LibraryIdentity.of(saved) != services.libraryId) return Result.retry()
        val profile = services.settings.toProfile(saved)
        val expected = inputData.getString("libraryId")
        if (expected != null && expected != services.libraryId) return Result.failure()

        try {
            setForeground(WorkNotifications.info(applicationContext, 2, "Creating video thumbnails"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now; carry on as a normal worker.
        }

        return if (services.thumbnails.run(profile) { true }) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "library_thumbnails"
        fun name(id: String) = "$UNIQUE_NAME:$id"

        fun enqueue(context: Context, libraryId: String = ServiceLocator.get(context).libraryId) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>()
                .setInputData(androidx.work.workDataOf("libraryId" to libraryId))
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                ).build()
            WorkManager
                .getInstance(context)
                .enqueueUniqueWork(name(libraryId), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(name(ServiceLocator.get(context).libraryId))
        }
    }
}
