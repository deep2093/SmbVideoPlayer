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
            val finishedStatus=services.db.dao().latestSession()?.status
            val partial = ok && finishedStatus == ScanStatus.COMPLETED_WITH_ERRORS
            val emptySource=ok && finishedStatus==ScanStatus.EMPTY_SOURCE
            prefs
                .edit()
                .putString("last_status", if(emptySource) "EMPTY_SOURCE" else if (partial) "COMPLETED_WITH_ERRORS" else if (ok) "COMPLETED" else "FAILED: SMB unavailable or scan incomplete")
                .apply()
            if (ok) {
                if(!partial && !emptySource) prefs.edit().putLong("last_success", System.currentTimeMillis()).apply()
                com.deepak.videolibrary.thumbs.ThumbnailWorker
                    .enqueue(applicationContext, services.libraryId)
                val metadata = services.sceneMetadataPreferences.settings.value
                if (metadata.enabled && metadata.allowedOnlineLookup) {
                    com.deepak.videolibrary.metadata.SceneMetadataWorker.enqueue(applicationContext, services.libraryId)
                }
                val art = services.artwork.settings.value
                if (art.enabled && art.adultConsent && art.portraitsOnFolders) {
                    com.deepak.videolibrary.artwork.PortraitFetchWorker.enqueue(applicationContext, services.libraryId)
                }
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
        private const val PREFS = "automatic_sync"
        private const val KEY_ENABLED = "enabled"

        /** The automatic rescan is on unless the user switched it off in Settings > Sync. */
        fun isEnabled(context: Context): Boolean =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

        /** Remembers the choice and starts or stops the schedule right away. */
        fun setEnabled(context: Context, enabled: Boolean) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) schedule(context.applicationContext) else WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
        }

        /** Schedules the rescan when it is switched on and makes sure it is off when it is not. */
        fun ensureScheduled(context: Context) {
            if (isEnabled(context)) schedule(context) else WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE)
        }

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
