package com.deepak.videolibrary.artwork

import android.content.Context
import com.deepak.videolibrary.artwork.online.EndpointPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The artwork settings, kept in SharedPreferences (one file per library) and exposed as a flow. */
class ArtworkPreferences(context: Context, libraryId: String) {
    private val prefs = context.applicationContext.getSharedPreferences("artwork-settings-$libraryId", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())
    val settings: StateFlow<ArtworkSettings> = state.asStateFlow()

    /** A short note about the last background lookup (shown in Settings), or null. Never holds keys or paths. */
    var lastBackgroundNote: String?
        get() = prefs.getString("last_background_note", null)
        set(value) {
            prefs.edit().putString("last_background_note", value).apply()
        }

    @Synchronized
    fun update(change: (ArtworkSettings) -> ArtworkSettings) {
        val next = sanitize(change(state.value))
        write(next)
        state.value = next
    }

    private fun read(): ArtworkSettings {
        val d = ArtworkSettings()
        fun mode(key: String, scope: ArtworkScope, fallback: ArtworkMode): ArtworkMode =
            runCatching { ArtworkMode.valueOf(prefs.getString(key, fallback.name)!!) }.getOrDefault(fallback)
                .takeIf { it in scope.defaultModes } ?: fallback
        val order = prefs.getString("provider_order", null)?.split(',')?.filter { it.isNotBlank() }
            ?.let { saved -> sanitizeOrder(saved) } ?: d.providerOrder
        return ArtworkSettings(
            enabled = prefs.getBoolean("enabled", d.enabled),
            videoMode = mode("video_mode", ArtworkScope.VIDEO, d.videoMode),
            smbFolderMode = mode("smb_mode", ArtworkScope.SMB_FOLDER, d.smbFolderMode),
            virtualFolderMode = mode("virtual_mode", ArtworkScope.VIRTUAL_FOLDER, d.virtualFolderMode),
            adultConsent = prefs.getBoolean("adult_consent", d.adultConsent),
            portraitsOnFolders = prefs.getBoolean("portraits_folders", d.portraitsOnFolders),
            portraitsOnVideos = prefs.getBoolean("portraits_videos", d.portraitsOnVideos),
            autoFetchOnScan = prefs.getBoolean("auto_fetch", d.autoFetchOnScan),
            wifiOnly = prefs.getBoolean("wifi_only", d.wifiOnly),
            allowMetered = prefs.getBoolean("allow_metered", d.allowMetered),
            collageTiles = prefs.getInt("collage_tiles", d.collageTiles).coerceIn(2, 6),
            providerOrder = order,
            disabledProviders = prefs.getStringSet("disabled_providers", emptySet())?.toSet() ?: emptySet(),
            videoUseFirstName = prefs.getBoolean("video_first_name", d.videoUseFirstName),
            folderUseWholeName = prefs.getBoolean("folder_whole_name", d.folderUseWholeName),
            smbFolderSelection = selection("smb_selection"),
            virtualFolderSelection = selection("virtual_selection"),
            customEndpoints = known.mapNotNull { id -> prefs.getString("endpoint_$id", null)?.takeIf { it.isNotBlank() }?.let { id to it } }.toMap(),
        )
    }

    private fun selection(key: String): PortraitSelection =
        runCatching { PortraitSelection.valueOf(prefs.getString(key, PortraitSelection.AUTOMATIC.name)!!) }.getOrDefault(PortraitSelection.AUTOMATIC)

    private fun write(s: ArtworkSettings) {
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putString("video_mode", s.videoMode.name)
            .putString("smb_mode", s.smbFolderMode.name)
            .putString("virtual_mode", s.virtualFolderMode.name)
            .putBoolean("adult_consent", s.adultConsent)
            .putBoolean("portraits_folders", s.portraitsOnFolders)
            .putBoolean("portraits_videos", s.portraitsOnVideos)
            .putBoolean("auto_fetch", s.autoFetchOnScan)
            .putBoolean("wifi_only", s.wifiOnly)
            .putBoolean("allow_metered", s.allowMetered)
            .putInt("collage_tiles", s.collageTiles)
            .putString("provider_order", s.providerOrder.joinToString(","))
            .putStringSet("disabled_providers", s.disabledProviders)
            .putBoolean("video_first_name", s.videoUseFirstName)
            .putBoolean("folder_whole_name", s.folderUseWholeName)
            .putString("smb_selection", s.smbFolderSelection.name)
            .putString("virtual_selection", s.virtualFolderSelection.name)
            .remove("endpoint_${ProviderIds.RETIRED_THE_PORN_DB}")
            .also { e -> known.forEach { id -> if (s.customEndpoints[id].isNullOrBlank()) e.remove("endpoint_$id") else e.putString("endpoint_$id", s.customEndpoints[id]) } }
            .apply()
    }

    companion object {
        private val known = listOf(ProviderIds.STASH_DB)

        internal fun sanitizeOrder(saved: List<String>): List<String> =
            saved.filter { it in known }.distinct().let { kept -> kept + known.filter { it !in kept } }

        /** Keeps the settings consistent: portraits and auto-fetch never stay on without consent. */
        internal fun sanitize(s: ArtworkSettings): ArtworkSettings {
            val consent = s.adultConsent
            return s.copy(
                portraitsOnFolders = s.portraitsOnFolders && consent,
                portraitsOnVideos = s.portraitsOnVideos && consent,
                autoFetchOnScan = s.autoFetchOnScan && consent && (s.portraitsOnFolders || s.portraitsOnVideos),
                collageTiles = s.collageTiles.coerceIn(2, 6),
                providerOrder = sanitizeOrder(s.providerOrder),
                customEndpoints = s.customEndpoints.filter { (id, url) -> id in known && EndpointPolicy.isAllowed(url) },
            )
        }
    }
}
