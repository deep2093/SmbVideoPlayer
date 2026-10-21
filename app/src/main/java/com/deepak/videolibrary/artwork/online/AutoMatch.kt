package com.deepak.videolibrary.artwork.online

/** Decides whether provider results are certain enough to apply without asking. */
object AutoMatch {
    sealed class Result {
        data class Match(val candidate: PerformerCandidate) : Result()

        /** More than one person fits: the user must choose. */
        object Ambiguous : Result()

        object NoMatch : Result()
    }

    /**
     * Applies automatically only when exactly one candidate has the very same name (ignoring case, accents and
     * punctuation), it has a picture, nobody else shares the name, and no disambiguation hints at another person
     * of that name. Anything else is shown to the user.
     */
    /** Candidates ordered for showing to the user: exact name matches first, then names containing the search. */
    fun ranked(query: String, candidates: List<PerformerCandidate>): List<PerformerCandidate> {
        val wanted = NameCleaner.normalize(query)
        fun score(c: PerformerCandidate): Int {
            val name = NameCleaner.normalize(c.name)
            return when {
                name == wanted -> 0
                name.startsWith(wanted) || wanted.startsWith(name) -> 1
                name.contains(wanted) || wanted.contains(name) -> 2
                else -> 3
            }
        }
        return candidates.withIndex().sortedWith(compareBy({ score(it.value) }, { it.index })).map { it.value }
    }

    fun evaluate(query: String, candidates: List<PerformerCandidate>): Result {
        val wanted = NameCleaner.normalize(query)
        if (wanted.length < 3) return Result.NoMatch
        val exact = candidates.filter { NameCleaner.normalize(it.name) == wanted }
        return when {
            exact.isEmpty() -> Result.NoMatch
            exact.size > 1 -> Result.Ambiguous
            exact[0].imageUrl.isNullOrBlank() -> Result.NoMatch
            !exact[0].disambiguation.isNullOrBlank() -> Result.Ambiguous
            else -> Result.Match(exact[0])
        }
    }
}
