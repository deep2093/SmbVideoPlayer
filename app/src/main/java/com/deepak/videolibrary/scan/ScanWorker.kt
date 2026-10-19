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
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val requested = inputData.getString(KEY_MODE)
            ?.let { runCatching { ScanMode.valueOf(it) }.getOrNull() }
            ?: return Result.failure()

        val services = ServiceLocator.get(applicationContext)
        val profile = services.settings.loadProfile() ?: return Result.failure()

        try {
            setForeground(WorkNotifications.info(applicationContext, 1, "Scanning your video library"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not allowed to run as a foreground service right now; carry on as a normal worker.
        }

        // If Android stopped this worker and is retrying it, continue the saved scan instead of
        // starting over.
        val mode = if (runAttemptCount > 0 && (requested == ScanMode.INITIAL || requested == ScanMode.REFRESH)) {
            ScanMode.RESUME
        } else {
            requested
        }

        val completed = coroutineScope {
            val scan = async { services.scanRepository.run(mode, profile) }
            // Thumbnails are made while the scan is still running, so artwork appears progressively.
            val thumbs = launch { services.thumbnails.run(profile) { scan.isCompleted } }
            val ok=scan.await()
            thumbs.join()
            ok
        }
        return if(completed) Result.success() else Result.retry()
    }

    companion object {
        const val UNIQUE_NAME = "library_scan"
        private const val KEY_MODE = "mode"

        fun enqueue(context: Context, mode: ScanMode) {
            val request = OneTimeWorkRequestBuilder<ScanWorker>()
                .setInputData(workDataOf(KEY_MODE to mode.name))
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
