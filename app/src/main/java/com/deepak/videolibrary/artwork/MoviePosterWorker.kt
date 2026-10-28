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
import com.deepak.videolibrary.artwork.online.ScenePosterSearch
import com.deepak.videolibrary.metadata.SceneMetadataClient
import com.deepak.videolibrary.metadata.SceneMetadataSettings
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
 * Finds full movie posters on ThePornDB for the movie folders inside your Original folders (the subfolders of a folder you
 * added): first by the folder's own name, then (when that finds nothing certain) by the names of the video files inside it. Only an
 * unambiguous match is applied; folders with a cover you chose yourself are never touched. Reads only the app's own
 * index, never the drive.
 *
 * A run can be limited to one folder and everything below it ("scope", started from the folder's menu). The provider
 * you prefer in Settings is asked first and the other one only when the first finds nothing certain. "kind" is
 * "movie" (ThePornDB movie posters, StashDB scene pictures as the fallback) or "scene" (scene pictures only).
 */
class MoviePosterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val started = System.currentTimeMillis()
        val services = ServiceLocator.get(applicationContext)
        if (inputData.getString("libraryId") != services.libraryId) return Result.failure()
        val options = services.sceneMetadataPreferences.settings.value
        if (!options.enabled || !options.allowedOnlineLookup) return Result.success()
        val providers = options.sceneProviders().filter { services.providerKeys.get(it) != null }
        if (providers.isEmpty()) return Result.success()
        val scope = inputData.getString("scope").orEmpty().trim('/')
        val force = inputData.getBoolean("force", false)
        val kind = if (inputData.getString("kind") == KIND_SCENE) KIND_SCENE else KIND_MOVIE
        val client = SceneMetadataClient(services.providerKeys)

        val saved = services.settings.load()
        val serverKey = saved.host.trim().lowercase(java.util.Locale.ROOT) + "/" + saved.share.trim().lowercase(java.util.Locale.ROOT)
        val roots = com.deepak.videolibrary.data.OriginalRootStore(services.artworkContext).load(serverKey)
        if (roots.isEmpty()) return Result.success()
        services.artwork.ensureLoaded()
        val movies = services.moviePosters
        val targets = OriginalFolderPaths.movieFolders(roots, services.db.dao().allParentDirs())
            .filter { path -> OriginalFolderPaths.inScope(path, scope) }
            .filter { path ->
                val identity = ArtworkKeys.originalFolder(path)
                if (force && movies.fileFor(identity) == null) movies.reset(identity)
                services.artwork.effectiveRow(identity)?.manual != true &&
                    movies.fileFor(identity) == null && !movies.checkedRecently(identity) &&
                    !movies.hasReview(identity) && !movies.isRejected(identity)
            }
        if (targets.isEmpty()) return Result.success()

        val done = AtomicInteger(0)
        val stop = AtomicReference<ProviderFailure?>(null)
        val lanes = Semaphore(PARALLEL)
        setProgress(workDataOf("done" to 0, "total" to targets.size))
        for (batch in targets.chunked(BATCH_SIZE)) {
            if (stop.get() != null) break
            if (System.currentTimeMillis() - started >= RUN_BUDGET_MS) {
                continueLater(applicationContext, services.libraryId, scope, kind)
                return Result.success()
            }
            coroutineScope {
                batch.map { path ->
                    async {
                        lanes.withPermit {
                            if (stop.get() != null) return@withPermit
                            val identity = ArtworkKeys.originalFolder(path)
                            try {
                                when (val found = findFor(path, serverKey, providers, kind, client, services)) {
                                    is Found.Sure -> {
                                        val url = found.movie.posterUrl ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
                                        val image = ArtworkHttp.downloadImage(url, POSTER_WIDTH)
                                        try { movies.save(identity, image, found.movie.id, found.movie.title) } finally { image.recycle() }
                                    }
                                    // Not certain: kept for you to choose, or reject, in the review pop-up.
                                    is Found.Maybe -> movies.saveSuggestions(identity, path.substringAfterLast('/'), found.movies)
                                    Found.None -> movies.markChecked(identity, error = false)
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

    private sealed interface Found {
        class Sure(val movie: MovieCandidate) : Found
        class Maybe(val movies: List<MovieCandidate>) : Found
        object None : Found
    }

    /** Providers whose key was refused during this run: skipped for the rest of it (the other provider carries on). */
    private val refused = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private suspend fun searchFolder(provider: String, name: String, kind: String, apiKey: String, client: SceneMetadataClient): List<MovieCandidate> =
        if (provider == SceneMetadataSettings.TPDB && kind == KIND_MOVIE) TpdbMovies.search(apiKey, name, byFileName = false)
        else ScenePosterSearch.search(client, provider, name, asFileName = false)

    private suspend fun searchFile(provider: String, name: String, kind: String, apiKey: String, client: SceneMetadataClient): List<MovieCandidate> =
        if (provider == SceneMetadataSettings.TPDB && kind == KIND_MOVIE) TpdbMovies.search(apiKey, name, byFileName = true)
        else ScenePosterSearch.search(client, provider, name, asFileName = true)

    /**
     * For each provider in your order: the folder's name first, then up to [MAX_FILES] file names from the folder itself
     * (or below it, if it holds none). A certain match ends the search; otherwise the next provider is tried, and what
     * looked plausible is kept for review.
     */
    private suspend fun findFor(path: String, serverKey: String, providers: List<String>, kind: String, client: SceneMetadataClient, services: ServiceLocator): Found {
        val folderName = path.substringAfterLast('/')
        val state = services.db.libraryState()
        val direct = state.videoList(LibraryQueries.videos(LibraryScope(serverKey, directory = path, subtree = false)))
        val files = direct.ifEmpty { state.videoList(LibraryQueries.videos(LibraryScope(serverKey, directory = path, subtree = true))) }
        var maybe = emptyList<MovieCandidate>()
        var answered = false
        for (provider in providers) {
            if (provider in refused) continue
            val apiKey = services.providerKeys.get(provider) ?: continue
            try {
                val byName = searchFolder(provider, folderName, kind, apiKey, client)
                answered = true
                TpdbMovies.confidentForFolder(folderName, byName)?.let { return Found.Sure(it) }
                if (maybe.isEmpty()) maybe = TpdbMovies.plausible(folderName, byName)
                for (video in files.take(MAX_FILES)) {
                    val parsed = searchFile(provider, video.name, kind, apiKey, client)
                    TpdbMovies.confidentForFile(video.name, parsed)?.let { return Found.Sure(it) }
                    if (maybe.isEmpty()) maybe = TpdbMovies.plausible(video.name, parsed, fileName = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                when (e.failure) {
                    ProviderFailure.UNAUTHORIZED, ProviderFailure.FORBIDDEN, ProviderFailure.NO_KEY -> refused += provider
                    // Slow down or offline: affects every folder, so the run stops and tries again later.
                    ProviderFailure.RATE_LIMITED, ProviderFailure.NETWORK -> throw e
                    // This provider could not answer for this folder: the other one still gets its turn.
                    else -> Unit
                }
            }
        }
        if (!answered && providers.all { it in refused }) throw ProviderException(ProviderFailure.UNAUTHORIZED)
        return if (maybe.isNotEmpty()) Found.Maybe(maybe) else Found.None
    }

    companion object {
        private const val NAME = "movie_posters"
        const val KIND_MOVIE = "movie"
        const val KIND_SCENE = "scene"
        private const val PARALLEL = 2
        private const val BATCH_SIZE = 10
        private const val MAX_FILES = 3
        private const val PACE_MS = 400L
        private const val POSTER_WIDTH = 1000
        private val RUN_BUDGET_MS = TimeUnit.MINUTES.toMillis(8)

        fun workName(libraryId: String) = "$NAME:$libraryId"

        private fun request(libraryId: String, scope: String, force: Boolean, kind: String) = OneTimeWorkRequestBuilder<MoviePosterWorker>()
            .setInputData(workDataOf("libraryId" to libraryId, "scope" to scope, "force" to force, "kind" to kind))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
            .build()

        /** Starts a lookup, or queues one behind the lookup in progress. */
        fun enqueue(context: Context, libraryId: String, scope: String = "", force: Boolean = false, kind: String = KIND_MOVIE) {
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(workName(libraryId), ExistingWorkPolicy.APPEND_OR_REPLACE, request(libraryId, scope, force, kind))
        }

        /** The rest of a run that ran out of time: the same folder and kind, without forcing again. */
        private fun continueLater(context: Context, libraryId: String, scope: String, kind: String) =
            enqueue(context, libraryId, scope, force = false, kind = kind)
    }
}
