package com.deepak.videolibrary.metadata

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
import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Strictly read-only metadata matching, separate from playback, grouping and artwork.
 * Single worker per SMB library, network- and battery-constrained, with durable per-file results.
 *
 * Videos are handled in batches: their OSHASHes are read from SMB a few at a time, then each provider gets ONE
 * fingerprint request for the whole batch (as Stash's Scene Tagger does for a page). Only videos without a
 * fingerprint match fall back to title search and the optional pHash, several at a time. A run stops shortly before
 * WorkManager's 10-minute limit and queues its own continuation, instead of returning retry() and waiting out an
 * ever-growing backoff.
 */
class SceneMetadataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val services = ServiceLocator.get(applicationContext)
        val libraryId = inputData.getString("libraryId") ?: return Result.failure()
        if (services.libraryId != libraryId) return Result.failure()
        val manual = inputData.getBoolean("manual", false)
        val settings = services.sceneMetadataPreferences.settings.value
        if (!settings.enabled || !settings.allowedOnlineLookup) return Result.success()

        val profile = services.settings.loadProfile() ?: return Result.success()
        if (profile.username.isNotBlank() && profile.password.isBlank()) return Result.success()
        val providers = settings.providers().filter { services.providerKeys.get(it) != null }
        if (providers.isEmpty()) return Result.success()
        val client = SceneMetadataClient(services.providerKeys)
        val dao = services.db.dao()
        val targets = dao.allIdPaths().chunked(40).flatMap { block -> dao.videosByPaths(block.map { it.relativePath }) }
            .filter { services.sceneMetadataStore.needsLookup(it) }
        if (targets.isEmpty()) {
            if (settings.posterMode != PosterMode.DISABLED) ScenePosterWorker.enqueue(applicationContext, libraryId)
            return Result.success()
        }

        setProgress(workDataOf("done" to 0, "total" to targets.size))
        val done = AtomicInteger(0)
        // A normal WorkManager worker may be stopped at any time. Saved results resume on the next run.
        return try {
            SmbConnection(profile).use { conn ->
                conn.open()
                for (batch in targets.chunked(BATCH_SIZE)) {
                    currentCoroutineContext().ensureActive()
                    val live = services.sceneMetadataPreferences.settings.value
                    if (!live.enabled || !live.allowedOnlineLookup) return Result.success()
                    if (System.currentTimeMillis() - started > RUN_BUDGET_MS) {
                        // Out of time for this run: carry on straight away in a fresh one (no backoff).
                        continueLater(applicationContext, libraryId, manual)
                        return Result.success()
                    }
                    processBatch(batch, conn, client, providers, settings, services)
                    setProgress(workDataOf("done" to done.addAndGet(batch.size), "total" to targets.size))
                }
            }
            if (settings.posterMode != PosterMode.DISABLED) ScenePosterWorker.enqueue(applicationContext, libraryId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun processBatch(
        batch: List<VideoEntity>,
        conn: SmbConnection,
        client: SceneMetadataClient,
        providers: List<String>,
        settings: SceneMetadataSettings,
        services: ServiceLocator,
    ) = coroutineScope {
        // 1. OSHASH: 64 KiB from each end of the file, a few files at a time over the one SMB session.
        val hashes = batch.map { video ->
            async { smbReads.withPermit { runCatching { SceneFingerprint.oshash(conn, video) }.getOrNull() } }
        }.awaitAll()

        // 2. One fingerprint request per provider for the whole batch, providers in parallel.
        val fingerprintTransient = BooleanArray(providers.size)
        val byProvider = providers.mapIndexed { p, provider ->
            async {
                try {
                    client.fingerprintsBatch(provider, hashes.map { h -> if (h == null) emptyMap<String, String>() else mapOf("OSHASH" to h) })
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ProviderException) {
                    if (e.failure in TRANSIENT) fingerprintTransient[p] = true
                    null
                }
            }
        }.awaitAll()

        // 3. Per video: accept the fingerprint result, otherwise fall back to title search and pHash.
        batch.mapIndexed { i, video ->
            async {
                lookups.withPermit {
                    val viaHash = byProvider.flatMap { it?.getOrNull(i).orEmpty() }
                    val result = if (viaHash.isNotEmpty()) {
                        viaHash.map { it.copy(method = "OSHASH") } to false
                    } else {
                        withTimeoutOrNull(PER_VIDEO_TIMEOUT_MS) {
                            fallback(video, conn, client, providers, settings, fingerprintTransient.any { it })
                        }
                    }
                    save(services, video, result)
                }
            }
        }.awaitAll()
    }

    private fun save(services: ServiceLocator, video: VideoEntity, result: Pair<List<SceneMatch>, Boolean>?) {
        when {
            result == null -> services.sceneMetadataStore.save(video, "RETRY")
            result.first.isEmpty() -> services.sceneMetadataStore.save(video, if (result.second) "RETRY" else "NO_MATCH")
            else -> {
                val matches = result.first.distinctBy { it.provider + ":" + it.id }
                // A file-hash match, or a title that clearly is this file's, is applied automatically.
                // Anything unclear (several scenes, a partial title, Android pHash) waits in Review.
                val chosen = SceneAutoMatch.choose(matches, video.name, services.sceneMetadataPreferences.settings.value.providers())
                if (chosen != null) services.sceneMetadataStore.save(video, "CONFIRMED", chosen.encoded().toString())
                else {
                    val proposals = JSONArray()
                    matches.take(20).forEach { proposals.put(it.encoded()) }
                    services.sceneMetadataStore.save(video, "REVIEW", suggestions = proposals.toString())
                }
            }
        }
    }

    /** Title search, then the optional pHash. Returns candidate scenes and whether a provider/network error needs retry. */
    private suspend fun fallback(
        video: VideoEntity,
        conn: SmbConnection,
        client: SceneMetadataClient,
        providers: List<String>,
        options: SceneMetadataSettings,
        fingerprintTransient: Boolean,
    ): Pair<List<SceneMatch>, Boolean> {
        var transientFailure = fingerprintTransient
        suspend fun lookup(block: suspend (String) -> List<SceneMatch>): List<SceneMatch> = coroutineScope {
            // Query every keyed provider at once; results keep the user-selected provider order.
            providers.map { provider ->
                async {
                    try {
                        block(provider)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: ProviderException) {
                        if (e.failure in TRANSIENT) transientFailure = true
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }

        val matches = lookup { client.search(it, video.name) }
        if (matches.isNotEmpty()) return matches to false

        if (options.phash && !transientFailure) {
            // Expensive: decodes 25 frames over SMB. One file at a time, with its own shorter limit, and skipped
            // for files too small to be real videos.
            val phash = if (video.size >= 128 * 1024L) {
                phashLane.withPermit {
                    withTimeoutOrNull(PHASH_TIMEOUT_MS) { runCatching { SceneFingerprint.phash(conn, video) }.getOrNull() }
                }
            } else null
            if (phash != null) {
                val match = lookup { client.fingerprints(it, mapOf("PHASH" to phash)) }
                if (match.isNotEmpty()) return match.map { it.copy(method = "PHASH") } to false
            }
        }
        return emptyList<SceneMatch>() to transientFailure
    }

    /** Limits for this run: SMB reads share one session; network lookups are small and latency-bound. */
    private val smbReads = Semaphore(3)
    private val lookups = Semaphore(5)
    private val phashLane = Semaphore(1)

    companion object {
        private const val UNIQUE = "scene_metadata"
        private const val BATCH_SIZE = 25
        private val RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)
        private val PER_VIDEO_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(90)
        private val PHASH_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(45)
        private val TRANSIENT = setOf(ProviderFailure.NETWORK, ProviderFailure.RATE_LIMITED, ProviderFailure.SERVER_ERROR)

        fun workName(id: String) = "$UNIQUE:$id"

        private fun request(libraryId: String, manual: Boolean) = OneTimeWorkRequestBuilder<SceneMetadataWorker>()
            .setInputData(workDataOf("libraryId" to libraryId, "manual" to manual))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()

        fun enqueue(context: Context, libraryId: String, manual: Boolean = false) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId), if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(libraryId, manual),
            )
        }

        /** Queues the next run right behind the current one, so a long library keeps going without backoff gaps. */
        internal fun continueLater(context: Context, libraryId: String, manual: Boolean) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(libraryId, manual),
            )
        }

        fun cancel(context: Context, libraryId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(libraryId))
        }
    }
}
