package com.deepak.videolibrary.artwork.online

import com.deepak.videolibrary.metadata.SceneMetadataClient

/**
 * Scene search results (StashDB, or ThePornDB's stash-box endpoint) shown as poster candidates, so a folder can get a
 * scene picture through the same match and review steps as a movie poster. Ids carry the provider so two services
 * never clash.
 */
internal object ScenePosterSearch {
    /** [asFileName] strips an extension first (a video file name); a folder name is searched as it is. */
    suspend fun search(client: SceneMetadataClient, provider: String, text: String, asFileName: Boolean): List<MovieCandidate> {
        val matches = if (asFileName) client.search(provider, text) else client.searchText(provider, text)
        val out = ArrayList<MovieCandidate>()
        for (m in matches) {
            val images = m.scene.optJSONArray("images")
            var url: String? = null
            if (images != null) {
                for (i in 0 until images.length()) {
                    val candidate = images.optJSONObject(i)?.optString("url").orEmpty()
                    if (ImageUrlPolicy.looksUsable(candidate)) { url = candidate; break }
                }
            }
            if (url == null) continue
            out += MovieCandidate(
                id = provider + ":" + m.id,
                title = m.title,
                year = m.scene.optString("date").takeIf { it.length >= 4 && it != "null" }?.take(4),
                site = m.scene.optJSONObject("studio")?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
                posterUrl = url,
                previewUrl = url,
            )
        }
        return out
    }
}
