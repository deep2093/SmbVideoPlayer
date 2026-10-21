package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.artwork.ArtworkSettings
import kotlinx.coroutines.CancellationException

/** The outcome of searching the enabled providers for a name. */
sealed class SearchOutcome {
    data class Found(val candidates: List<PerformerCandidate>, val failures: List<Pair<String, ProviderFailure>>) : SearchOutcome()
    data class Failed(val failure: ProviderFailure) : SearchOutcome()
}

/** Searches the enabled providers in the user's order. Providers without a key are skipped. */
class PerformerSearch(
    private val providers: List<PerformerProvider>,
    private val keys: ProviderKeys,
) {
    fun configured(settings: ArtworkSettings): List<PerformerProvider> =
        settings.enabledProviders().mapNotNull { id -> providers.firstOrNull { it.id == id } }.filter { keys.has(it.id) }

    /** Searches one provider with its stored key. Throws [ProviderException]. */
    suspend fun searchOne(provider: PerformerProvider, query: String, settings: ArtworkSettings): List<PerformerCandidate> {
        val key = keys.get(provider.id) ?: throw ProviderException(ProviderFailure.NO_KEY)
        return provider.search(query, key, settings.customEndpoints[provider.id])
    }

    suspend fun search(query: String, settings: ArtworkSettings): SearchOutcome {
        val active = configured(settings)
        if (active.isEmpty()) return SearchOutcome.Failed(ProviderFailure.NO_KEY)
        val found = ArrayList<PerformerCandidate>()
        val failures = ArrayList<Pair<String, ProviderFailure>>()
        for (provider in active) {
            val key = keys.get(provider.id)
            if (key == null) {
                failures += provider.displayName to ProviderFailure.NO_KEY
                continue
            }
            try {
                found += provider.search(query, key, settings.customEndpoints[provider.id])
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderException) {
                failures += provider.displayName to e.failure
            }
        }
        if (found.isEmpty() && failures.size == active.size) return SearchOutcome.Failed(failures.first().second)
        return SearchOutcome.Found(found, failures)
    }

    companion object {
        fun default(keys: ProviderKeys) = PerformerSearch(listOf(ThePornDbProvider(), StashDbProvider()), keys)
    }
}
