package com.deepak.videolibrary.metadata

import android.content.Context
import android.graphics.Bitmap
import com.deepak.videolibrary.artwork.online.ArtworkHttp
import com.deepak.videolibrary.artwork.online.ImageUrlPolicy
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.thumbs.ArtworkFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Optional scene posters kept separately from thumbnails and existing artwork overrides. */
class ScenePosterCache(context: Context, private val libraryId: String) {
    private val root = File(context.filesDir, "libraries-scene-posters/$libraryId").apply { mkdirs() }
    private val preferences = context.getSharedPreferences("scene-poster-approved-$libraryId", Context.MODE_PRIVATE)
    private val known = ConcurrentHashMap<String, File>()
    private val signals = MutableStateFlow(0)
    val revision = signals.asStateFlow()

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { "%02x".format(it) }

    private fun key(video: VideoEntity) = digest(video.relativePath) + "-${video.size}-${video.modified}"
    private fun cacheKey(video: VideoEntity) = "poster:" + key(video)

    fun fileFor(video: VideoEntity): File? {
        val k = cacheKey(video)
        known[k]?.let { if (it.isFile) return it else known.remove(k) }
        val name = preferences.getString(k, null) ?: return null
        // Only image names created by this app may be loaded.
        if (!name.matches(Regex("[a-z0-9-]+[.]jpg"))) return null
        return File(root, name).takeIf { it.isFile }?.also { known[k] = it }
    }

    fun approved(path: String): Boolean = preferences.getBoolean(digest(path), false)

    fun markApproved(path: String) {
        preferences.edit().putBoolean(digest(path), true).apply()
    }

    /** Clear only downloaded automatic scene posters and approval flags for this library. */
    @Synchronized
    fun clearDownloaded(): Int {
        val items = root.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".jpg") }
        val removed = items.count { it.delete() }
        preferences.edit().clear().apply()
        known.clear()
        signals.update { it + 1 }
        return removed
    }

    /** Save only if a remote image is valid. Does not update existing artwork or SMB data. */
    suspend fun fetch(video: VideoEntity, selected: JSONObject): Boolean {
        val scene = selected.optJSONObject("scene") ?: return false
        val provider = selected.optString("provider")
        val sceneId = scene.optString("id")
        if (provider.isBlank() || sceneId.isBlank()) return false
        val images = scene.optJSONArray("images") ?: return false
        val imageUrl = (0 until images.length())
            .mapNotNull { images.optJSONObject(it)?.optString("url") }
            .firstOrNull { ImageUrlPolicy.isAllowed(it) } ?: return false
        // The scene id changes the file name. This prevents stale decoded images when a
        // previous suggestion is rejected and the user confirms a different scene.
        val target = File(root, key(video) + "-" + digest(provider + ":" + sceneId) + ".jpg")
        val indexKey = cacheKey(video)
        val previous = fileFor(video)
        if (previous == target && target.isFile) return true
        // An image that failed recently is not downloaded again on every run.
        val failKey = "failed:" + target.name
        val failedAt = preferences.getLong(failKey, 0L)
        if (failedAt > 0L && System.currentTimeMillis() - failedAt < FAILURE_COOLDOWN_MS) return false
        val bitmap = try {
            ArtworkHttp.downloadImage(imageUrl, 720)
        } catch (e: com.deepak.videolibrary.artwork.online.ProviderException) {
            if (e.failure == com.deepak.videolibrary.artwork.online.ProviderFailure.BAD_RESPONSE ||
                e.failure == com.deepak.videolibrary.artwork.online.ProviderFailure.FORBIDDEN) {
                // Missing, refused or unreadable picture: remember it. Network and server errors are retried.
                preferences.edit().putLong(failKey, System.currentTimeMillis()).apply()
            }
            throw e
        }
        return try {
            ArtworkFiles.publish(target) { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 87, output))
            }
            // Durable index is updated ONLY after a valid image has been saved.
            preferences.edit().putString(indexKey, target.name).remove(failKey).apply()
            known[indexKey] = target
            if (previous != null && previous != target) previous.delete()
            signals.update { it + 1 }
            true
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val FAILURE_COOLDOWN_MS = java.util.concurrent.TimeUnit.HOURS.toMillis(24)
    }
}
