package com.skywing.desktop.artwork

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A folder a lookup may find a portrait for: its cover key and the name to search. */
internal data class LookupTarget(val key: String, val label: String)

/**
 * Looks up performer portraits for folders, like the Android PortraitFetcher and its background worker. It only runs
 * when the user turned internet portraits on for that kind of folder and gave consent. A folder whose cover you chose,
 * or that already has a portrait, is never looked up again, so pictures are never overwritten.
 */
internal class PortraitLookup(private val repo: ArtworkRepository) {
    sealed class Outcome {
        object Applied : Outcome()
        object Ambiguous : Outcome()
        object NoMatch : Outcome()
        object Skipped : Outcome()
        object Suggested : Outcome()
        object ItemError : Outcome()
        data class Failed(val failure: ProviderFailure) : Outcome()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** (done, total) while a lookup runs, otherwise null. */
    var progress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    /** Starts a lookup over [targets] (built when it starts). [forced] (Look up portraits now) also retries earlier "no match" results. */
    fun start(forced: Boolean, targets: () -> List<LookupTarget>) {
        val s = repo.settings
        if (!s.enabled || !s.adultConsent) return
        if (!forced && !s.autoFetchOnScan) return
        if (job?.isActive == true && !forced) return
        job?.cancel()
        job = scope.launch {
            val since = System.currentTimeMillis()
            val list = targets().filter { wanted(it, forced, since) }.distinctBy { it.key }
            if (list.isEmpty()) {
                repo.preferences.lastNote = "Last lookup: nothing new to look up."
                return@launch
            }
            var applied = 0
            var unclear = 0
            progress = 0 to list.size
            try {
                for ((index, target) in list.withIndex()) {
                    val outcome = try {
                        autoFetch(target.key, target.label)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Outcome.ItemError
                    }
                    when (outcome) {
                        is Outcome.Failed -> {
                            repo.preferences.lastNote = "Stopped: ${outcome.failure.message}"
                            return@launch
                        }
                        Outcome.Applied -> applied++
                        Outcome.Ambiguous, Outcome.Suggested -> unclear++
                        else -> Unit
                    }
                    progress = index + 1 to list.size
                    // Gentle pace: well under StashDB's rate limit.
                    delay(PACE_MS)
                }
                repo.preferences.lastNote = "Last lookup: $applied applied" + if (unclear > 0) ", $unclear waiting in Review suggestions." else "."
            } finally {
                progress = null
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Folders with a manual cover or a portrait are never looked up; earlier results are only retried when forced. */
    private fun wanted(target: LookupTarget, forced: Boolean, since: Long): Boolean {
        if (!repo.settings.portraitLookupWanted(ArtworkKeys.scopeOf(target.key))) return false
        val row = repo.effectiveRow(target.key)
        if (row?.manual == true) return false
        if (row?.imageName != null) return false
        return row == null || (forced && row.updatedAt < since)
    }

    suspend fun autoFetch(key: String, label: String): Outcome {
        val settings = repo.settings
        val scope = ArtworkKeys.scopeOf(key)
        if (!settings.portraitLookupWanted(scope)) return Outcome.Skipped
        if (repo.row(key)?.manual == true) return Outcome.Skipped
        val query = settings.searchTerm(scope, label)
        if (query.isEmpty()) {
            repo.recordLookup(key, ArtworkOverride.STATUS_NO_MATCH)
            return Outcome.NoMatch
        }
        val apiKey = repo.keys.get() ?: return Outcome.Failed(ProviderFailure.NO_KEY)
        val found = try {
            StashDb.search(query, apiKey, settings.customEndpoint.ifBlank { null })
        } catch (e: ProviderException) {
            if (e.failure != ProviderFailure.BAD_RESPONSE) return Outcome.Failed(e.failure)
            repo.recordLookup(key, ArtworkOverride.STATUS_ERROR)
            return Outcome.ItemError
        }
        if (settings.selectionFor(scope) == PortraitSelection.AUTOMATIC) {
            when (val result = AutoMatch.evaluate(query, found)) {
                is AutoMatch.Result.Match -> return apply(key, result.candidate)
                AutoMatch.Result.Ambiguous -> {
                    repo.saveSuggestions(key, AutoMatch.ranked(query, found))
                    return Outcome.Ambiguous
                }
                AutoMatch.Result.NoMatch -> Unit
            }
        }
        // Manual selection (or no confident match): keep what was found for the user to pick from, best first.
        val ranked = AutoMatch.ranked(query, found)
        repo.saveSuggestions(key, ranked)
        return if (ranked.any { !it.imageUrl.isNullOrBlank() }) Outcome.Suggested else Outcome.NoMatch
    }

    /** Applies [candidate]'s photo to [key] (Review suggestions, or a confident automatic match). */
    suspend fun apply(key: String, candidate: PerformerCandidate): Outcome {
        val url = candidate.imageUrl ?: return Outcome.ItemError.also { repo.recordLookup(key, ArtworkOverride.STATUS_ERROR) }
        val image = try {
            Pictures.decode(ArtworkHttp.downloadBytes(url, EndpointPolicy.hostOf(repo.settings.customEndpoint))) ?: throw ProviderException(ProviderFailure.BAD_RESPONSE)
        } catch (e: ProviderException) {
            if (e.failure != ProviderFailure.BAD_RESPONSE) return Outcome.Failed(e.failure)
            repo.recordLookup(key, ArtworkOverride.STATUS_ERROR)
            return Outcome.ItemError
        }
        return try {
            repo.saveImage(key, ArtworkMode.PORTRAIT, image, manual = false, candidate = candidate, focusY = 0.25f)
            Outcome.Applied
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.ItemError
        } finally {
            image.close()
        }
    }

    private companion object {
        const val PACE_MS = 1_200L
    }
}
