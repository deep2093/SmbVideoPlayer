package com.deepak.videolibrary.data

import android.content.Context
import org.json.JSONArray

/**
 * The drive folders you added to the Original folders section, per server. The section shows only these; inside
 * each one its real sub-folders appear exactly as they are on the drive. Nothing on the drive changes.
 */
class OriginalRootStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("original_roots", Context.MODE_PRIVATE)

    @Synchronized
    fun load(key: String): List<String> = decode(prefs.getString(key, null))

    @Synchronized
    fun save(key: String, roots: List<String>) {
        prefs.edit().putString(key, encode(roots)).apply()
    }

    companion object {
        const val MAX_ROOTS = 100

        /** Trimmed, de-duplicated, and without folders already covered by another added folder. */
        fun normalize(roots: Collection<String>): List<String> {
            val clean = roots.map { it.trim().trim('/') }.filter { it.isNotEmpty() }.distinct()
            return clean.filter { r -> clean.none { other -> other != r && r.startsWith("$other/") } }
                .sortedBy { it.substringAfterLast('/').lowercase(java.util.Locale.ROOT) }
                .take(MAX_ROOTS)
        }

        internal fun encode(roots: List<String>): String = JSONArray(roots).toString()

        internal fun decode(text: String?): List<String> {
            if (text.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(text)
                (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotBlank() } }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
