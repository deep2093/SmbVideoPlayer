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
import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.MovieCandidate
import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import com.deepak.videolibrary.artwork.online.TpdbMovies
import com.deepak.videolibrary.data.LibraryQueries
import com.deepak.videolibrary.data.LibraryScope
import com.deepak.videolibrary.metadata.FolderCoverChoice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Finds full movie posters on ThePornDB for Original folders whose cover is set to "Full movie poster": first by the
 * folder's own name, then (when that finds nothing certain) by the names of the video files inside it. Only an
 * unambiguous match is applied; folders with a cover you chose yourself are never touched. Reads only the app's own
 * index, never the drive.
 */
class MoviePosterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val services = ServiceLocator.get(applicationContext)
        if (inputData.getString("libraryId") != services.libraryId) return Result.failure()
        val options = services.sceneMetadataPreferences.settings.value
        if (!options.enabled || !options.allowedOnlineLookup || options.originalFolderCover != FolderCoverChoice.MOVIE) return Result.success()
        val apiKey = services.providerKeys.get(ProviderIds.TPDB) ?: return Result.success()

        val saved = services.settings.load()
        val serverKey = saved.host.trim().lowercase(java.util.Locale.ROOT) + "/" + saved.share.trim().lowercase(java.util.Locale.ROOT)
        val roots = com.deepak.videolibrary.data.OriginalRootStore(services.artworkContext).load(serverKey)
        if (roots.isEmpty()) return Result.success()
        services.artwork.ensureLoaded()
        val movies = services.moviePosters
        val targets = OriginalFolderPaths.within(roots, services.db.dao().allParentDirs()).filter { path ->
            val identity = ArtworkKeys.originalFolder(path)
            services.artwork.effectiveRow(identity)?.manual != true &&
                movies.fileFor(identity) == null && !movies.checkedRecently(identity)
        }
        if (targets.isEmpty()) return Result.success()

        val done = AtomicInteger(0)
        val stop = AtomicReference<ProviderFailure?>(null)
        val lanes = Semaphore(PARALLEL)
        setProgress(workDataOf("done" to 0, "total" to targets.size))
        for (batch in targets.chunked(BATCH_SIZE)) {
            if (stop.get() != null) break
            if (System.currentTimeMillis() - started >= RUN_BUDGET_MS) {
                continueLater(applicationContext, services.libraryId)
                return Result.success()
            }
            coroutineScope {
                batch.map { path ->
                    async {
                        lanes.withPermit {
                            if (stop.get() != null) return@withPermit
                            val identity = ArtworkKeys.originalFolder(path)
                            try {
                                val found = findFor(path, serverKey, apiKey, services)
                                if (found == null) {
                                    movies.markChecked(identity, error = false)
                                } else {
                                    val url = found.posterUrl ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
                                    val image = ArtworkHttp.downloadImage(url, POSTER_WIDTH)
                                    try { movies.save(identity, image, found.id, found.title) } finally { image.recycle() }
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: ProviderException) {
                                when (e.failure) {
                                    // These affect every folder: stop now and try the run again later.
                                    ProviderFailure.RATE_LIMITED, ProviderFailure.UNAUTHORIZED, ProviderFailure.FORBIDDEN,
                                    ProviderFailure.NETWORK, ProviderFailure.NO_KEY -> stop.compareAndSet(null, e.failure)
                                    else -> movies.markChecked(identity, error = true)
                                }
                            } catch (_: Exception) {
                                movies.markChecked(identity, error = true)
                            }
                            done.incrementAndGet()
                            delay(PACE_MS)
                        }
                    }
                }.awaitAll()
            }
            setProgress(workDataOf("done" to done.get(), "total" to targets.size))
        }
        return when (stop.get()) {
            null -> Result.success()
            ProviderFailure.RATE_LIMITED, ProviderFailure.NETWORK -> Result.retry()
            else -> Result.failure()
        }
    }

    /** Folder name first; then up to [MAX_FILES] file names from the folder itself (or below it, if it holds none). */
    private suspend fun findFor(path: String, serverKey: String, apiKey: String, services: ServiceLocator): MovieCandidate? {
        val folderName = path.substringAfterLast('/')
        TpdbMovies.confidentForFolder(folderName, TpdbMovies.search(apiKey, folderName, byFileName = false))?.let { return it }
        val state = services.db.libraryState()
        val direct = state.videoList(LibraryQueries.videos(LibraryScope(serverKey, directory = path, subtree = false)))
        val files = direct.ifEmpty { state.videoList(LibraryQueries.videos(LibraryScope(serverKey, directory = path, subtree = true))) }
        for (video in files.take(MAX_FILES)) {
            TpdbMovies.confidentForFile(video.name, TpdbMovies.search(apiKey, video.name, byFileName = true))?.let { return it }
        }
        return null
    }

    companion object {
        private const val NAME = "movie_posters"
        private const val PARALLEL = 2
        private const val BATCH_SIZE = 10
        private const val MAX_FILES = 3
        private const val PACE_MS = 400L
        private const val POSTER_WIDTH = 1000
        private val RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        fun workName(libraryId: String) = "$NAME:$libraryId"

        private fun request(libraryId: String) = OneTimeWorkRequestBuilder<MoviePosterWorker>()
            .setInputData(workDataOf("libraryId" to libraryId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()

        /** Starts a lookup, or queues one behind the lookup in progress. */
        fun enqueue(context: Context, libraryId: String) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(libraryId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(libraryId))
        }

        private fun continueLater(context: Context, libraryId: String) = enqueue(context, libraryId)
    }
}
