package com.deepak.videolibrary.artwork

import android.content.Context
import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.AutoMatch
import com.deepak.videolibrary.artwork.online.NetworkPolicy
import com.deepak.videolibrary.artwork.online.PerformerCandidate
import com.deepak.videolibrary.artwork.online.PerformerSearch
import com.deepak.videolibrary.artwork.online.ProviderException
import com.deepak.videolibrary.artwork.online.ProviderFailure
import kotlinx.coroutines.CancellationException

/**
 * Looks up and applies a performer portrait for one folder without asking, but only when the user turned this
 * on for that kind of item and exactly one confident match exists. Anything unclear is left for the user to
 * resolve in the "Change cover" dialog. Only the cleaned label is sent to providers.
 */
class PortraitFetcher(
    private val context: Context,
    private val repository: ArtworkRepository,
    private val search: PerformerSearch,
) {
    sealed class Outcome {
        object Applied : Outcome()
        object Ambiguous : Outcome()
        object NoMatch : Outcome()
        object Skipped : Outcome()

        /** Something went wrong with this one item only (an unreadable picture, a bad response); carry on. */
        object ItemError : Outcome()

        /** A problem that affects every lookup (key rejected, rate limit, offline): stop the run. */
        data class Failed(val failure: ProviderFailure) : Outcome()
    }

    suspend fun autoFetch(identity: String, label: String): Outcome {
        val settings = repository.settings.value
        val scope = ArtworkKeys.scopeOf(identity)
        if (!settings.enabled || !settings.portraitsAllowedFor(scope)) return Outcome.Skipped
        val existing = repository.row(identity)
        if (existing != null && existing.manual) return Outcome.Skipped
        if (!NetworkPolicy.allowed(context, settings)) return Outcome.Failed(ProviderFailure.BLOCKED_BY_SETTINGS)

        val query = settings.searchTerm(scope, label)
        if (query.isEmpty()) {
            repository.recordLookup(identity, ArtworkOverride.STATUS_NO_MATCH)
            return Outcome.NoMatch
        }
        val providers = search.configured(settings)
        if (providers.isEmpty()) return Outcome.Failed(ProviderFailure.NO_KEY)

        for (provider in providers) {
            val candidates: List<PerformerCandidate> = try {
                search.searchOne(provider, query, settings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                if (isFatal(e.failure)) return Outcome.Failed(e.failure)
                // This provider answered with something unreadable for this name: try the next one.
                continue
            }
            when (val result = AutoMatch.evaluate(query, candidates)) {
                is AutoMatch.Result.Match -> return apply(identity, result.candidate)
                AutoMatch.Result.Ambiguous -> {
                    repository.recordLookup(identity, ArtworkOverride.STATUS_AMBIGUOUS)
                    return Outcome.Ambiguous
                }
                AutoMatch.Result.NoMatch -> Unit
            }
        }
        repository.recordLookup(identity, ArtworkOverride.STATUS_NO_MATCH)
        return Outcome.NoMatch
    }

    private suspend fun apply(identity: String, candidate: PerformerCandidate): Outcome {
        val url = candidate.imageUrl ?: return Outcome.ItemError.also { repository.recordLookup(identity, ArtworkOverride.STATUS_ERROR) }
        val bitmap = try {
            ArtworkHttp.downloadImage(url, DOWNLOAD_WIDTH, com.deepak.videolibrary.artwork.online.EndpointPolicy.hostsOf(repository.settings.value.customEndpoints.values))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ProviderException) {
            if (isFatal(e.failure)) return Outcome.Failed(e.failure)
            repository.recordLookup(identity, ArtworkOverride.STATUS_ERROR)
            return Outcome.ItemError
        }
        try {
            repository.saveImage(
                key = identity,
                mode = ArtworkMode.PORTRAIT,
                image = bitmap,
                manual = false,
                providerId = candidate.providerId,
                performerId = candidate.performerId,
                performerName = candidate.name,
                focusY = 0.25f,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Storage problem: leave the item for a later run.
            return Outcome.ItemError
        } finally {
            bitmap.recycle()
        }
        return Outcome.Applied
    }

    private fun isFatal(failure: ProviderFailure) = failure != ProviderFailure.BAD_RESPONSE

    private companion object {
        const val DOWNLOAD_WIDTH = 1280
    }
}
