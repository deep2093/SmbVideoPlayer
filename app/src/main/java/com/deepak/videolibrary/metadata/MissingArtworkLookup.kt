package com.deepak.videolibrary.metadata

import android.content.Context
import com.deepak.videolibrary.ServiceLocator

/**
 * Starts the lookups for what is still missing: performer portraits, scene matches with their posters, and movie
 * posters. Only the features you switched on run. With [recheck], items that earlier found nothing (or whose picture
 * failed to download) are tried again; without it, only new or changed items are looked up.
 */
object MissingArtworkLookup {
    /**
     * Returns false when nothing is switched on, so there was nothing to start. Over remote access (VPN up) it does
     * nothing unless [manual], i.e. unless you asked for the lookup yourself.
     */
    fun start(context: Context, services: ServiceLocator, recheck: Boolean, manual: Boolean = false): Boolean {
        if (!manual && RemoteAccess.active(context)) return false
        val libraryId = services.libraryId
        var started = false
        val art = services.artwork.settings.value
        if (art.enabled && art.adultConsent && art.portraitsOnFolders) {
            com.deepak.videolibrary.artwork.PortraitFetchWorker.enqueue(context, libraryId, force = recheck)
            started = true
        }
        val scenes = services.sceneMetadataPreferences.settings.value
        if (scenes.enabled && scenes.allowedOnlineLookup) {
            if (recheck) {
                services.sceneMetadataStore.forgetMisses()
                services.scenePosters.forgetFailures()
            }
            SceneMetadataWorker.enqueue(context, libraryId)
            // Movie posters for the movie folders inside Original folders.
            if (scenes.moviePosters) {
                if (recheck) services.moviePosters.forgetChecks()
                com.deepak.videolibrary.artwork.MoviePosterWorker.enqueue(context, libraryId)
            }
            started = true
        }
        return started
    }
}
