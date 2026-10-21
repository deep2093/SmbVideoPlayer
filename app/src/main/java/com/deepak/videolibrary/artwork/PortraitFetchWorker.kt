package com.deepak.videolibrary.artwork

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.artwork.online.ProviderFailure
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Background lookup of performer portraits for folders. It only runs when the user enabled artwork, enabled internet
 * portraits for that kind of item, and (for automatic runs after a scan) turned on "Fetch on scan". It stops on
 * authorisation errors and backs off on rate limits.
 */
class PortraitFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        val expected = inputData.getString("libraryId")
        if (expected != null && expected != services.libraryId) return Result.failure()
        val repo = services.artwork
        repo.ensureLoaded()
        val settings = repo.settings.value
        val forced = inputData.getBoolean("force", false)
        if (!settings.enabled || !settings.adultConsent) return Result.success()
        if (!forced && !settings.autoFetchOnScan) return Result.success()

        val targets = FolderTargets.collect(services, settings, forced)
        if (targets.isEmpty()) return Result.success()
        val fetcher = services.portraitFetcher
        for ((identity, label) in targets) {
            when (val outcome = fetcher.autoFetch(identity, label)) {
                is PortraitFetcher.Outcome.Failed -> return when (outcome.failure) {
                    ProviderFailure.RATE_LIMITED, ProviderFailure.NETWORK, ProviderFailure.BLOCKED_BY_SETTINGS -> Result.retry()
                    else -> Result.failure()
                }
                else -> Unit
            }
            // Gentle pace: well under any provider's rate limit, and cancellable between lookups.
            delay(PACE_MS)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "artwork_portraits"
        private const val PACE_MS = 1_200L

        fun enqueue(context: Context, libraryId: String, force: Boolean = false) {
            val settings = ServiceLocator.get(context).artwork.settings.value
            val request = OneTimeWorkRequestBuilder<PortraitFetchWorker>()
                .setInputData(workDataOf("libraryId" to libraryId, "force" to force))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (settings.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("$UNIQUE:$libraryId", ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, libraryId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("$UNIQUE:$libraryId")
        }
    }
}

/** The folders a background run may look up: those with no stored result yet (all of them when forced). */
internal object FolderTargets {
    suspend fun collect(services: ServiceLocator, settings: ArtworkSettings, forced: Boolean): List<Pair<String, String>> {
        val repo = services.artwork
        val dao = services.db.dao()
        val out = LinkedHashMap<String, String>()
        fun consider(identity: String, label: String) {
            val scope = ArtworkKeys.scopeOf(identity)
            val wanted = settings.portraitsAllowedFor(scope) &&
                (settings.modeFor(scope) == ArtworkMode.PORTRAIT || (scope == ArtworkScope.VIRTUAL_FOLDER && settings.modeFor(ArtworkScope.VIDEO) == ArtworkMode.PORTRAIT))
            if (!wanted) return
            val row = repo.row(identity)
            if (row?.manual == true) return
            if (!forced && row != null) return
            out[identity] = label
        }
        dao.allGroupNames().forEach { consider(ArtworkKeys.virtualFolder(it), it) }
        dao.allMatchFolders().forEach { consider(ArtworkKeys.matchFolder(it.id), it.name) }
        dao.allParentDirs().forEach { consider(ArtworkKeys.originalFolder(it), it.substringAfterLast('/')) }
        return out.entries.map { it.key to it.value }
    }
}
