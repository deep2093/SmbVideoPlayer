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
        val profile = services.settings.loadProfile() ?: return Result.failure()

        try {
            setForeground(WorkNotifications.info(applicationContext, 2, "Creating video thumbnails"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now; carry on as a normal worker.
        }

        services.thumbnails.run(profile) { true }
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "library_thumbnails"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ThumbnailWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
