package com.deepak.videolibrary.scan

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.videolibrary.ServiceLocator
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * WorkManager refresh: best-effort, NOT instant SMB filesystem watching.
 * Runs approximately every 12 hours while network, battery and storage permit.
 * NetworkType.CONNECTED does not guarantee that the SMB server is reachable on the LAN.
 */
class PeriodicSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        val prefs = applicationContext.getSharedPreferences("$DIAGNOSTICS-${services.libraryId}", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        prefs
            .edit()
            .putLong("last_attempt", now)
            .putString("last_status", "RUNNING")
            .apply()
        val saved = services.settings.load()
        if (com.deepak.videolibrary.settings.LibraryIdentity.of(saved) != services.libraryId) return Result.retry()
        val profile = saved.takeIf { it.host.isNotBlank() && it.share.isNotBlank() }?.let(services.settings::toProfile)
        if (profile == null) {
            prefs.edit().putString("last_status", "SKIPPED: Configure SMB server and share").apply()
            return Result.success()
        }
        if (saved.username.isNotBlank() && profile.password.isBlank() && !saved.savePassword) {
            // Do not repeatedly attempt authenticated scans after a process restart with no password.
            prefs.edit().putString("last_status", "SKIPPED: Save credentials for unattended sync").apply()
            return Result.success()
        }
        return try {
            val ok = services.scanRepository.runPeriodic(profile)
            prefs
                .edit()
                .putString("last_status", if (ok) "COMPLETED" else "FAILED: SMB unavailable or scan incomplete")
                .apply()
            if (ok) {
                prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                com.deepak.videolibrary.thumbs.ThumbnailWorker
                    .enqueue(applicationContext)
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: CancellationException) {
            prefs.edit().putString("last_status", "INTERRUPTED").apply()
            throw e
        } catch (_: Exception) {
            // Never log credentials or unfiltered SMB exception messages.
            prefs.edit().putString("last_status", "FAILED: Will retry when constraints permit").apply()
            Result.retry()
        }
    }

    companion object {
        const val DIAGNOSTICS = "periodic_sync_diagnostics"
        private const val UNIQUE = "library_periodic_sync"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .setRequiresStorageNotLow(true)
                        .build(),
                ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
