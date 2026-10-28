package com.deepak.videolibrary.artwork

import android.content.Context
import android.graphics.Bitmap
import com.deepak.videolibrary.thumbs.ArtworkFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import com.deepak.videolibrary.artwork.online.MovieCandidate
import org.json.JSONArray
import org.json.JSONObject

/** A folder whose movie was not certain: the likely movies to choose from. */
data class MovieReviewItem(val identity: String, val name: String, val candidates: List<MovieCandidate>)

/**
 * Full movie posters found automatically for folders, kept apart from portraits, scene posters and covers you chose
 * yourself (a cover you pick always wins). Also remembers folders that found no movie, so they are not searched again
 * on every run.
 */
class MoviePosterCache(context: Context, libraryId: String) {
    private val root = File(context.filesDir, "libraries-movie-posters/$libraryId").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("movie-posters-$libraryId", Context.MODE_PRIVATE)
    private val signals = MutableStateFlow(0)
    val revision = signals.asStateFlow()

    private val reviewState = MutableStateFlow(readReview())

    /** Folders waiting for you to choose their movie, or reject them. */
    val reviewQueue = reviewState.asStateFlow()

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    fun fileFor(identity: String): File? {
        val name = prefs.getString("poster:" + digest(identity), null) ?: return null
        if (!name.matches(Regex("[a-f0-9-]+[.]jpg"))) return null
        return File(root, name).takeIf { it.isFile }
    }

    /** The movie title behind a folder's poster, for showing where it came from. */
    fun titleFor(identity: String): String? = prefs.getString("title:" + digest(identity), null)

    fun save(identity: String, image: Bitmap, movieId: String, title: String) {
        val target = File(root, digest(identity) + "-" + digest(movieId) + ".jpg")
        val previous = fileFor(identity)
        ArtworkFiles.publish(target) { output -> check(image.compress(Bitmap.CompressFormat.JPEG, 88, output)) }
        prefs.edit()
            .putString("poster:" + digest(identity), target.name)
            .putString("title:" + digest(identity), title)
            .remove("checked:" + digest(identity))
            .remove("review:" + digest(identity))
            .remove("rejected:" + digest(identity))
            .apply()
        if (previous != null && previous != target) previous.delete()
        reviewState.value = readReview()
        signals.update { it + 1 }
    }

    private fun readReview(): List<MovieReviewItem> = prefs.all.entries
        .filter { it.key.startsWith("review:") }
        .mapNotNull { (_, value) ->
            runCatching {
                val json = JSONObject(value as String)
                val list = json.getJSONArray("candidates")
                MovieReviewItem(
                    json.getString("identity"), json.getString("name"),
                    (0 until list.length()).map { i ->
                        val c = list.getJSONObject(i)
                        MovieCandidate(
                            c.getString("id"), c.getString("title"), c.optString("year").takeIf { it.isNotEmpty() },
                            c.optString("site").takeIf { it.isNotEmpty() },
                            c.optString("poster").takeIf { it.isNotEmpty() }, c.optString("preview").takeIf { it.isNotEmpty() },
                        )
                    },
                )
            }.getOrNull()
        }
        .sortedBy { it.name.lowercase() }

    fun hasReview(identity: String): Boolean = prefs.contains("review:" + digest(identity))

    /** Keeps the likely movies for a folder so you can choose one (or reject them all) later. */
    fun saveSuggestions(identity: String, name: String, candidates: List<MovieCandidate>) {
        val array = JSONArray()
        candidates.forEach { c ->
            array.put(JSONObject().put("id", c.id).put("title", c.title).put("year", c.year ?: "").put("site", c.site ?: "")
                .put("poster", c.posterUrl ?: "").put("preview", c.previewUrl ?: ""))
        }
        val json = JSONObject().put("identity", identity).put("name", name).put("candidates", array)
        prefs.edit().putString("review:" + digest(identity), json.toString()).apply()
        reviewState.value = readReview()
    }

    /** "None of these": the folder is not suggested again. Choosing its poster in Change cover still works. */
    fun reject(identity: String) {
        prefs.edit().remove("review:" + digest(identity)).putBoolean("rejected:" + digest(identity), true).apply()
        reviewState.value = readReview()
    }

    fun isRejected(identity: String): Boolean = prefs.getBoolean("rejected:" + digest(identity), false)

    /** Remembers that this folder was looked up without a usable result ([error] = something failed). */
    fun markChecked(identity: String, error: Boolean) {
        prefs.edit().putString("checked:" + digest(identity), (if (error) "E" else "N") + System.currentTimeMillis()).apply()
    }

    /** True while a recent "no movie found" (7 days) or failure (24 hours) should not be searched again. */
    fun checkedRecently(identity: String, now: Long = System.currentTimeMillis()): Boolean {
        val value = prefs.getString("checked:" + digest(identity), null) ?: return false
        val at = value.drop(1).toLongOrNull() ?: return false
        val wait = if (value.startsWith("E")) TimeUnit.HOURS.toMillis(24) else TimeUnit.DAYS.toMillis(7)
        return now - at < wait
    }

    /** Forgets "checked" and any waiting suggestions for one folder, so a lookup you start by hand tries it again. A folder you rejected stays rejected. */
    fun reset(identity: String) {
        prefs.edit().remove("checked:" + digest(identity)).apply()
        reviewState.value = readReview()
    }

    /** Lets every folder be searched again (keeps the posters already found). */
    fun forgetChecks() {
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("checked:") }.forEach { edit.remove(it) }
        edit.apply()
    }

    /** Deletes the downloaded movie posters and what was remembered about them. */
    @Synchronized
    fun clear(): Int {
        val removed = root.listFiles().orEmpty().count { it.isFile && it.name.endsWith(".jpg") && it.delete() }
        prefs.edit().clear().apply()
        reviewState.value = readReview()
        signals.update { it + 1 }
        return removed
    }
}
