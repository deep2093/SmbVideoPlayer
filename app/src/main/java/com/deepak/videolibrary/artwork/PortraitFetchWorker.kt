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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Background lookup of performer portraits for folders. It only runs when the user enabled artwork, enabled internet
 * portraits for that kind of item, and (for automatic runs after a scan) turned on "Fetch on scan". It stops on
 * authorisation errors and backs off on rate limits.
 */
class PortraitFetchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val services = ServiceLocator.get(applicationContext)
        val expected = inputData.getString("libraryId")
        if (expected != null && expected != services.libraryId) return Result.failure()
        val repo = services.artwork
        repo.ensureLoaded()
        val settings = repo.settings.value
        val forced = inputData.getBoolean("force", false)
        if (!settings.enabled || !settings.adultConsent) return Result.success()
        if (!settings.portraitsOnFolders && !settings.portraitsOnVideos) return Result.success()

        // A run that was stopped and restarted (time limit, rate limit) continues where it left off: items already
        // looked up since the run was requested are skipped.
        val since = inputData.getLong("since", 0L)
        val targets = FolderTargets.collect(services, settings, forced, since)
        if (targets.isEmpty()) return Result.success()
        val fetcher = services.portraitFetcher
        val applied = AtomicInteger(0)
        val unclear = AtomicInteger(0)
        val done = AtomicInteger(0)
        val consecutiveNetworkErrors = AtomicInteger(0)
        // Providers whose key was rejected during this run are not asked again; the others carry on.
        val rejected = ConcurrentHashMap<String, ProviderFailure>()
        val stop = AtomicReference<ProviderFailure?>(null)
        var outOfTime = false
        val lanes = Semaphore(PARALLEL_LOOKUPS)
        // Shown as a bar at the top of the app: how many of the folders have been looked up.
        setProgress(workDataOf(PROGRESS_DONE to 0, PROGRESS_TOTAL to targets.size))
        for (batch in targets.chunked(BATCH_SIZE)) {
            if (stop.get() != null) break
            if (System.currentTimeMillis() - started >= RUN_BUDGET_MS) {
                outOfTime = true
                break
            }
            coroutineScope {
                batch.map { (identity, label) ->
                    async {
                        lanes.withPermit {
                            if (stop.get() != null) return@withPermit
                            val outcome = try {
                                fetcher.autoFetch(identity, label, rejected.keys) { provider, failure -> rejected[provider] = failure }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                PortraitFetcher.Outcome.ItemError
                            }
                            if (outcome == PortraitFetcher.Outcome.NetworkError) {
                                // A few in a row means the connection itself is down: stop and retry the run later.
                                if (consecutiveNetworkErrors.incrementAndGet() >= MAX_CONSECUTIVE_NETWORK_ERRORS) stop.compareAndSet(null, ProviderFailure.NETWORK)
                            } else {
                                consecutiveNetworkErrors.set(0)
                            }
                            when (outcome) {
                                is PortraitFetcher.Outcome.Failed -> { stop.compareAndSet(null, outcome.failure) }
                                PortraitFetcher.Outcome.Applied -> applied.incrementAndGet()
                                PortraitFetcher.Outcome.Ambiguous, PortraitFetcher.Outcome.Suggested -> unclear.incrementAndGet()
                                else -> Unit
                            }
                            done.incrementAndGet()
                            // Gentle pace per lane, only after an item that actually asked a provider.
                            if (outcome != PortraitFetcher.Outcome.Skipped && outcome != PortraitFetcher.Outcome.NoMatch) delay(PACE_MS)
                        }
                    }
                }.awaitAll()
            }
            setProgress(workDataOf(PROGRESS_DONE to done.get(), PROGRESS_TOTAL to targets.size))
        }
        stop.get()?.let { failure ->
            repo.preferences.lastBackgroundNote = "Stopped: ${failure.message}"
            return when (failure) {
                ProviderFailure.RATE_LIMITED, ProviderFailure.NETWORK, ProviderFailure.BLOCKED_BY_SETTINGS, ProviderFailure.SERVER_ERROR -> Result.retry()
                else -> Result.failure()
            }
        }
        if (outOfTime) {
            // Keep going in a fresh run straight away instead of being stopped by WorkManager's time limit.
            continueLater(applicationContext, services.libraryId, forced, since)
            return Result.success()
        }
        val skipped = if (rejected.isEmpty()) "" else " Key rejected by: " + rejected.keys.joinToString() + "."
        repo.preferences.lastBackgroundNote = "Last lookup: ${applied.get()} applied" +
            (if (unclear.get() > 0) ", ${unclear.get()} waiting in Review suggestions." else ".") + skipped
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "artwork_portraits"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"

        /** The WorkManager name of the lookup for one library (to follow its progress). */
        fun workName(libraryId: String) = "$UNIQUE:$libraryId"
        private const val PACE_MS = 500L
        private const val PARALLEL_LOOKUPS = 3
        private const val BATCH_SIZE = 12
        private const val MAX_CONSECUTIVE_NETWORK_ERRORS = 5
        private val RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        private fun request(context: Context, libraryId: String, force: Boolean, since: Long) =
            OneTimeWorkRequestBuilder<PortraitFetchWorker>()
                .setInputData(workDataOf("libraryId" to libraryId, "force" to force, "since" to since))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (ServiceLocator.get(context).artwork.settings.value.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED,
                        )
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()

        fun enqueue(context: Context, libraryId: String, force: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE:$libraryId",
                if (force) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request(context, libraryId, force, System.currentTimeMillis()),
            )
        }

        /** Queues the next run behind this one, keeping the original start time so finished folders stay skipped. */
        private fun continueLater(context: Context, libraryId: String, force: Boolean, since: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$UNIQUE:$libraryId", ExistingWorkPolicy.APPEND_OR_REPLACE, request(context, libraryId, force, since),
            )
        }

        fun cancel(context: Context, libraryId: String) {
            WorkManager.getInstance(context).cancelUniqueWork("$UNIQUE:$libraryId")
        }
    }
}

/** The folders a background run may look up: those with no stored result yet (all of them when forced). */
internal object FolderTargets {
    private val ERROR_RETRY_MS = TimeUnit.HOURS.toMillis(24)

    suspend fun collect(services: ServiceLocator, settings: ArtworkSettings, forced: Boolean, since: Long): List<Pair<String, String>> {
        val repo = services.artwork
        val dao = services.db.dao()
        val out = LinkedHashMap<String, String>()
        fun consider(identity: String, label: String) {
            val scope = ArtworkKeys.scopeOf(identity)
            if (services.sceneMetadataPreferences.settings.value.folderChoice(identity) !=
                com.deepak.videolibrary.metadata.FolderCoverChoice.PERFORMER) return
            if (!settings.enabled || !settings.adultConsent || !settings.portraitsOnFolders ||
                settings.selectionFor(scope) == PortraitSelection.CUSTOM) return
            // A folder whose cover you chose, or that already has a portrait (also from earlier folders of the same
            // name), is never looked up again.
            val row = repo.effectiveRow(identity)
            if (row?.manual == true) return
            // Already has a portrait: never downloaded again. Earlier "no match" / "unclear" results are only
            // retried when the user asks for a lookup from Settings.
            if (row?.imageName != null) return
            // A lookup that failed (unreadable picture, provider error) is retried on normal runs after a cooldown,
            // instead of leaving that folder without a portrait until a manual lookup.
            val staleError = row != null && row.lookupStatus == ArtworkOverride.STATUS_ERROR &&
                System.currentTimeMillis() - row.updatedAt > ERROR_RETRY_MS && row.updatedAt < since
            if (row != null && !staleError && (!forced || row.updatedAt >= since)) return
            out[identity] = label
        }
        dao.allMatchFolders().forEach { consider(ArtworkKeys.matchFolder(it.id), it.name) }
        // The subfolders generated inside the user's Virtual folders are named after people too.
        val saved = services.settings.load()
        val serverKey = saved.host.trim().lowercase(java.util.Locale.ROOT) + "/" + saved.share.trim().lowercase(java.util.Locale.ROOT)
        // Original folders: the folders you added there and every real sub-folder inside them, by their own names.
        for (path in OriginalFolderPaths.within(com.deepak.videolibrary.data.OriginalRootStore(services.artworkContext).load(serverKey), dao.allParentDirs())) {
            consider(ArtworkKeys.originalFolder(path), path.substringAfterLast('/'))
        }
        // Groups of single videos inside Custom folders.
        val customRules = com.deepak.videolibrary.scan.GroupingPrefs.load(services.artworkContext, com.deepak.videolibrary.scan.GroupingPrefs.CUSTOM)
        for (own in com.deepak.videolibrary.data.OwnFolderStore(services.artworkContext).load(serverKey)) {
            consider("own:${own.id}", own.name)
            if (own.files.isEmpty()) continue
            val videos = own.files.chunked(500).flatMap { dao.videosByPaths(it) }
            com.deepak.videolibrary.data.groupByNames(videos, customRules).keys.forEach { identity ->
                consider(ArtworkKeys.virtualFolder(identity), com.deepak.videolibrary.scan.VirtualNames.display(identity))
            }
        }
        for (set in services.virtualSets.load(serverKey)) {
            consider(ArtworkKeys.virtualSet(set.id), set.name)
            if (set.isEmpty) continue
            val videos = services.db.libraryState().videoList(
                com.deepak.videolibrary.data.LibraryQueries.videos(
                    com.deepak.videolibrary.data.LibraryScope(serverKey, members = set.members()),
                ),
            )
            val rules = com.deepak.videolibrary.scan.GroupingPrefs.load(services.artworkContext, com.deepak.videolibrary.scan.GroupingPrefs.VIRTUAL)
            com.deepak.videolibrary.data.VirtualGroups.build(set, videos, rules).forEach { consider(ArtworkKeys.virtualSub(set.id, it.identity), it.title) }
        }
        return out.entries.map { it.key to it.value }
    }
}

/**
 * The folders that get a full movie poster. A folder you added is a container for movies, so its subfolders get the
 * posters and it does not. An added folder with no subfolders holds the movie's videos itself, so it gets one.
 */
internal fun OriginalFolderPaths.movieFolders(roots: List<String>, parentDirs: List<String>): List<String> {
    val containers = roots.filter { root -> parentDirs.any { it.startsWith("$root/") } }.toSet()
    return within(roots, parentDirs).filter { it !in containers }
}

/** The Original folders a lookup covers: each added folder and every folder between it and an indexed video. */
internal object OriginalFolderPaths {
    /** True when [path] is [scope] itself or lies below it; an empty [scope] means everything. */
    fun inScope(path: String, scope: String): Boolean {
        val base = scope.trim('/')
        return base.isEmpty() || path == base || path.startsWith("$base/")
    }

    fun within(roots: List<String>, parentDirs: List<String>): List<String> {
        val out = LinkedHashSet<String>()
        roots.forEach { out += it }
        for (dir in parentDirs) {
            val root = roots.firstOrNull { dir == it || dir.startsWith("$it/") } ?: continue
            var current = dir
            while (current.length > root.length) {
                out += current
                current = current.substringBeforeLast('/', root)
            }
        }
        return out.toList()
    }
}
