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
import com.deepak.videolibrary.artwork.ArtworkKeys
import com.deepak.videolibrary.thumbs.CustomThumbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent poster downloads for confirmed scenes. NEVER writes to SMB and NEVER
 * modifies the user's existing artwork selections or folder organisation.
 *
 * Several posters download at once over kept-alive connections. A run that reaches its time budget queues its own
 * continuation (no retry backoff), and new requests are appended behind a running one instead of cancelling it.
 */
class ScenePosterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val services = ServiceLocator.get(applicationContext)
        val libraryId = inputData.getString("libraryId")
        if (services.libraryId != libraryId) return Result.failure()
        val s = services.sceneMetadataPreferences.settings.value
        if (!s.enabled || !s.allowedOnlineLookup || s.posterMode == PosterMode.DISABLED || (!s.videoPosters && !s.virtualGeneratedVideoScenes && !s.needsFolderScenePosters())) return Result.success()

        services.artwork.ensureLoaded()
        val records = services.sceneMetadataStore.confirmed()
        val dao = services.db.dao()
        val done = AtomicInteger(0)
        val fetched = AtomicInteger(0)
        val downloads = Semaphore(PARALLEL_DOWNLOADS)
        for (batch in records.chunked(BATCH_SIZE)) {
            currentCoroutineContext().ensureActive()
            val fresh = services.sceneMetadataPreferences.settings.value
            if (!fresh.enabled || !fresh.allowedOnlineLookup || fresh.posterMode == PosterMode.DISABLED) break
            if (System.currentTimeMillis() - started >= RUN_BUDGET_MS) {
                continueLater(applicationContext, libraryId)
                return Result.success()
            }
            coroutineScope {
                batch.map { record ->
                    async {
                        downloads.withPermit {
                            val shouldFetch = fresh.posterMode == PosterMode.AUTOMATIC || services.scenePosters.approved(record.path)
                            if (shouldFetch && record.result != null) {
                                val video = dao.findVideo(record.path)
                                if (video != null && video.size == record.size && video.modified == record.modified &&
                                    (fresh.needsFolderScenePosters() || (
                                        !CustomThumbs.has(services.artworkContext, video.id) &&
                                            services.artwork.row(ArtworkKeys.video(video.id))?.manual != true))) {
                                    try {
                                        val selected = JSONObject(record.result)
                                        if (services.scenePosters.fetch(video, selected)) fetched.incrementAndGet()
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        // No image or transient error. Retain existing thumbnails and continue.
                                    }
                                }
                            }
                            done.incrementAndGet()
                        }
                    }
                }.awaitAll()
            }
            setProgress(workDataOf("done" to done.get(), "total" to records.size, "posters" to fetched.get()))
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "scene_posters"
        private const val PARALLEL_DOWNLOADS = 4
        private const val BATCH_SIZE = 20
        private val RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        fun workName(id: String) = "$NAME:$id"

        private fun request(libraryId: String?) = OneTimeWorkRequestBuilder<ScenePosterWorker>()
            .setInputData(workDataOf("libraryId" to libraryId))
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()

        /**
         * Starts a poster run, or queues one right behind the run in progress. A running download is never cancelled
         * and restarted from the first record; the queued run picks up whatever is new or changed.
         */
        fun enqueue(context: Context, libraryId: String) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(libraryId),
            )
        }

        private fun continueLater(context: Context, libraryId: String?) {
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId ?: return), ExistingWorkPolicy.APPEND_OR_REPLACE, request(libraryId),
            )
        }
    }
}
