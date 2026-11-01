package com.deepak.videolibrary.ui

import android.content.Context

/**
 * The saved result of the GPU quality check ("<highest level that kept up>|<summary>"). It is stored once per device
 * and survives app updates; bump [KEY] only when a new engine makes the old result meaningless.
 */
internal object GpuQualityStore {
    private const val KEY = "native_gpu_levels_v2"

    private fun prefs(context: Context) = context.getSharedPreferences("player_prefs", Context.MODE_PRIVATE)

    fun read(context: Context): String? = prefs(context).getString(KEY, null)

    fun save(context: Context, result: String) {
        prefs(context).edit().putString(KEY, result).apply()
    }

    /** Clears the GPU-failed marker set by the in-playback fallback, because a fresh check has just passed. */
    fun clearFailure(context: Context) {
        val e = prefs(context).edit()
        prefs(context).all.keys.filter { it.startsWith("native_gpu_failed_") }.forEach { e.remove(it) }
        e.apply()
    }
}
