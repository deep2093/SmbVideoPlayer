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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Independent poster downloads for confirmed scenes. NEVER writes to SMB and NEVER
 * modifies the user's existing artwork selections or folder organisation.
 */
class ScenePosterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        if (services.libraryId != inputData.getString("libraryId")) return Result.failure()
        val s = services.sceneMetadataPreferences.settings.value
        if (!s.enabled || !s.allowedOnlineLookup || s.posterMode == PosterMode.DISABLED || (!s.videoPosters && !s.folderPosters)) return Result.success()

        services.artwork.ensureLoaded()
        val records = services.sceneMetadataStore.confirmed()
        val dao = services.db.dao()
        var done = 0
        var fetched = 0
        val started = System.currentTimeMillis()
        for (record in records) {
            currentCoroutineContext().ensureActive()
            val fresh = services.sceneMetadataPreferences.settings.value
            if (!fresh.enabled || !fresh.allowedOnlineLookup || fresh.posterMode == PosterMode.DISABLED) break
            val shouldFetch = fresh.posterMode == PosterMode.AUTOMATIC || services.scenePosters.approved(record.path)
            if (shouldFetch && record.result != null) {
                val video = dao.findVideo(record.path)
                if (video != null && video.size == record.size && video.modified == record.modified &&
                    !CustomThumbs.has(services.artworkContext, video.id) &&
                    services.artwork.row(ArtworkKeys.video(video.id))?.manual != true) {
                    try {
                        val selected = JSONObject(record.result)
                        if (services.scenePosters.fetch(video, selected)) fetched++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // No image or transient error. Retain existing thumbnails and continue.
                    }
                }
            }
            done++
            if (done % 10 == 0 || done == records.size) {
                setProgress(workDataOf("done" to done, "total" to records.size, "posters" to fetched))
            }
            if (System.currentTimeMillis() - started >= TimeUnit.MINUTES.toMillis(8)) return Result.retry()
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "scene_posters"
        fun workName(id: String) = "$NAME:$id"
        fun enqueue(context: Context, libraryId: String) {
            val req = OneTimeWorkRequestBuilder<ScenePosterWorker>()
                .setInputData(workDataOf("libraryId" to libraryId))
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                workName(libraryId), ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}
