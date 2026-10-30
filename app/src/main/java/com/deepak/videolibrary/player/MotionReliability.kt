package com.deepak.videolibrary.player

/** Current-pair measurements, including blocks whose matching was ambiguous. */
internal object MotionReliability {
    data class Decision(val holdFrame: Boolean, val sceneCut: Boolean)

    fun decide(blocks: Int, untrusted: Int, highResidual: Int, strength: Int): Decision {
        if (blocks <= 0) return Decision(true, false)
        // Mean luma error > 0.18 over most blocks is a conservative cut/flash guard.
        // This is a heuristic, not a claim that every editorial cut is detected.
        val cut = highResidual.toDouble() / blocks > 0.6
        val limit = doubleArrayOf(0.02, 0.07, 0.2)[strength.coerceIn(0, 2)]
        return Decision(cut || untrusted.toDouble() / blocks > limit, cut)
    }
}
