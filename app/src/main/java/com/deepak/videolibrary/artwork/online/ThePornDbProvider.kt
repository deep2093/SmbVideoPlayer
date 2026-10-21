package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.artwork.ProviderIds
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * ThePornDB, through its published REST API (https://api.theporndb.net/docs): authenticated performer search
 * with the user's own API token. Image use is subject to the service's rules and the user's own account.
 */
class ThePornDbProvider : PerformerProvider {
    override val id = ProviderIds.THE_PORN_DB
    override val displayName = "ThePornDB"

    override suspend fun search(query: String, apiKey: String, endpoint: String?): List<PerformerCandidate> {
        if (apiKey.isBlank()) throw ProviderException(ProviderFailure.NO_KEY)
        val base = EndpointPolicy.normalize(endpoint) ?: BASE
        val url = "$base/performers?q=${URLEncoder.encode(query, "UTF-8")}&per_page=10"
        val body = ArtworkHttp.getJson(
            url,
            mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
        )
        return parse(body)
    }

    companion object {
        private const val BASE = "https://api.theporndb.net"

        internal fun parse(body: String): List<PerformerCandidate> {
            val data: JSONArray = try {
                JSONObject(body).optJSONArray("data") ?: return emptyList()
            } catch (e: Exception) {
                throw ProviderException(ProviderFailure.BAD_RESPONSE, e)
            }
            val out = ArrayList<PerformerCandidate>()
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val name = item.optString("name").trim()
                val performerId = item.optString("id").ifBlank { item.optString("_id") }
                if (name.isEmpty() || performerId.isBlank()) continue
                val image = firstUrl(item.optString("image"), posterUrl(item), item.optString("thumbnail"), item.optString("face"))
                val preview = firstUrl(item.optString("thumbnail"), item.optString("face"), item.optString("image"))
                out += PerformerCandidate(
                    providerId = ProviderIds.THE_PORN_DB,
                    providerName = "ThePornDB",
                    performerId = performerId,
                    name = name,
                    disambiguation = item.optString("disambiguation").takeIf { it.isNotBlank() && it != "null" },
                    imageUrl = image,
                    previewUrl = preview ?: image,
                )
            }
            return out
        }

        private fun posterUrl(item: JSONObject): String? {
            val posters = item.optJSONArray("posters") ?: return null
            for (i in 0 until posters.length()) {
                val poster = posters.optJSONObject(i)
                val url = poster?.optString("url")
                if (!url.isNullOrBlank()) return url
            }
            return null
        }

        /** The first address the app is willing to download. */
        private fun firstUrl(vararg candidates: String?): String? =
            candidates.firstOrNull { ImageUrlPolicy.looksUsable(it) }
    }
}
