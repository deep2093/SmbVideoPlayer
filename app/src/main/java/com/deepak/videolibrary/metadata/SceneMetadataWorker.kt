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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Strictly read-only metadata matching, separate from playback, grouping and artwork.
 * Single worker per SMB library, network- and battery-constrained, with durable per-file results.
 */
class SceneMetadataWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        val libraryId = inputData.getString("libraryId") ?: return Result.failure()
        if (services.libraryId != libraryId) return Result.failure()
        val settings = services.sceneMetadataPreferences.settings.value
        if (!settings.enabled || !settings.allowedOnlineLookup || (!settings.automatic && !inputData.getBoolean("manual", false))) return Result.success()

        val profile = services.settings.loadProfile() ?: return Result.success()
        if (profile.username.isNotBlank() && profile.password.isBlank()) return Result.success()
        val providers = settings.providers().filter { services.providerKeys.get(it) != null }
        if (providers.isEmpty()) return Result.success()
        val client = SceneMetadataClient(services.providerKeys)
        val dao = services.db.dao()
        val targets = dao.allIdPaths().chunked(40).flatMap { block -> dao.videosByPaths(block.map { it.relativePath }) }
            .filter { services.sceneMetadataStore.needsLookup(it) }
        if (targets.isEmpty()) return Result.success()

        setProgress(workDataOf("done" to 0, "total" to targets.size))
        var done = 0
        // A normal WorkManager worker may be stopped at any time. Saved results resume on the next run.
        return try {
            SmbConnection(profile).use { conn ->
                conn.open()
                for (video in targets) {
                    currentCoroutineContext().ensureActive()
                    if (!services.sceneMetadataPreferences.settings.value.enabled ||
                        !services.sceneMetadataPreferences.settings.value.allowedOnlineLookup) return Result.success()
                    if (System.currentTimeMillis() - startTime > TimeUnit.MINUTES.toMillis(8)) return Result.retry()
                    val result = withTimeoutOrNull(TimeUnit.SECONDS.toMillis(90)) {
                        identify(video, conn, client, providers, settings)
                    }
                    when {
                        result == null -> services.sceneMetadataStore.save(video, "RETRY")
                        result.first.isEmpty() -> services.sceneMetadataStore.save(video, if (result.second) "RETRY" else "NO_MATCH")
                        else -> {
                            val matches = result.first.distinctBy { it.provider + ":" + it.id }
                            // Only exactly one verified OSHASH result is accepted automatically.
                            // Text matches and Android pHash always wait for confirmation.
                            val safeAuto = matches.size == 1 && matches[0].method == "OSHASH"
                            if (safeAuto) services.sceneMetadataStore.save(video, "CONFIRMED", matches[0].encoded().toString())
                            else {
                                val proposals = JSONArray()
                                matches.take(20).forEach { proposals.put(it.encoded()) }
                                services.sceneMetadataStore.save(video, "REVIEW", suggestions = proposals.toString())
                            }
                        }
                    }
                    done++
                    setProgress(workDataOf("done" to done, "total" to targets.size))
                }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private val startTime = System.currentTimeMillis()

    /** Returns candidate scenes and whether a provider/network error needs retry. */
    private suspend fun identify(
        video: VideoEntity,
        conn: SmbConnection,
        client: SceneMetadataClient,
        providers: List<String>,
        options: SceneMetadataSettings,
    ): Pair<List<SceneMatch>, Boolean> {
        var transientFailure = false
        suspend fun lookup(block: suspend (String) -> List<SceneMatch>): List<SceneMatch> {
            for (provider in providers) {
                try {
                    val matches = block(provider)
                    if (matches.isNotEmpty()) return matches
                } catch (e: CancellationException) { throw e
                } catch (e: ProviderException) {
                    if (e.failure in setOf(ProviderFailure.NETWORK, ProviderFailure.RATE_LIMITED)) transientFailure = true
                }
            }
            return emptyList()
        }
        val oshash = runCatching { SceneFingerprint.oshash(conn, video) }.getOrNull()
        if (oshash != null) {
            val match = lookup { client.fingerprints(it, mapOf("OSHASH" to oshash)) }
            if (match.isNotEmpty()) return match.map { it.copy(method = "OSHASH") } to false
        }

        val matches = lookup { client.search(it, video.name) }
        if (matches.isNotEmpty()) return matches to false

        if (options.phash && !transientFailure) {
            // Expensive; do not decode if the file is too small, huge, or otherwise unprocessable.
            val phash = if (video.size >= 128 * 1024L) runCatching { SceneFingerprint.phash(conn, video) }.getOrNull() else null
            if (phash != null) {
                val match = lookup { client.fingerprints(it, mapOf("PHASH" to phash)) }
                if (match.isNotEmpty()) return match.map { it.copy(method = "PHASH") } to false
            }
        }
        return emptyList<SceneMatch>() to transientFailure
    }

    companion object {
        private const val UNIQUE = "scene_metadata"
        fun workName(id: String) = "$UNIQUE:$id"
        fun enqueue(context: Context, libraryId: String, manual: Boolean = false) {
            val req = OneTimeWorkRequestBuilder<SceneMetadataWorker>()
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
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId), if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, req,
            )
        }
        fun cancel(context: Context, libraryId: String) {
            WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(libraryId))
        }
    }
}
