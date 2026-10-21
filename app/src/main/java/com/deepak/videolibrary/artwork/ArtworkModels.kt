package com.deepak.videolibrary.artwork

/** The three kinds of item that can have a cover. Each has its own default and its own overrides. */
enum class ArtworkScope(val label: String) {
    VIDEO("Videos"),
    SMB_FOLDER("SMB folders"),
    VIRTUAL_FOLDER("Virtual folders"),
    ;

    val isFolder: Boolean get() = this != VIDEO

    /** The modes offered as a default for this kind of item. */
    val defaultModes: List<ArtworkMode>
        get() = if (isFolder) {
            listOf(ArtworkMode.EXISTING, ArtworkMode.FIRST_FRAME, ArtworkMode.COLLAGE, ArtworkMode.PORTRAIT, ArtworkMode.CUSTOM)
        } else {
            listOf(ArtworkMode.EXISTING, ArtworkMode.FRAME, ArtworkMode.PORTRAIT, ArtworkMode.CUSTOM)
        }
}

enum class ArtworkMode(val label: String) {
    /** What the app has always shown. */
    EXISTING("Existing"),

    /** Video: the automatically generated frame (ignores a hand-picked frame). */
    FRAME("Auto video frame"),

    /** Folder: the first video's frame. */
    FIRST_FRAME("First video frame"),

    /** Folder: several video frames in one picture. */
    COLLAGE("Video collage"),

    /** An internet performer portrait chosen for this item. */
    PORTRAIT("Internet performer portrait"),

    /** An image the user chose for this item. */
    CUSTOM("Custom per-item"),

    /** No cover at all (neutral placeholder). */
    NONE("No cover"),
}

/** How internet portraits are chosen for a kind of folder. */
enum class PortraitSelection(val label: String, val detail: String) {
    AUTOMATIC("Automatic", "Applies a picture only when exactly one confident match is found; anything unclear waits for you in Review suggestions."),
    MANUAL("Manual", "Finds suggestions in the background but applies nothing until you pick one in Review suggestions."),
    CUSTOM("Custom", "Nothing is searched in the background. Covers change only when you search or choose an image yourself in Change cover."),
}

/** Identity strings. They match the ones the folder screens already use for covers. */
object ArtworkKeys {
    fun video(id: Long) = "video:$id"
    fun virtualFolder(name: String) = "virtual:$name"
    fun matchFolder(id: Long) = "match:$id"
    fun originalFolder(path: String) = "original:$path"

    /** A Virtual folder (made by the user) and one of its generated subfolders. */
    fun virtualSet(id: String) = "vset:$id"
    fun virtualSub(setId: String, identity: String) = "vset:$setId:$identity"

    fun scopeOf(key: String): ArtworkScope = when {
        key.startsWith("video:") -> ArtworkScope.VIDEO
        key.startsWith("original:") -> ArtworkScope.SMB_FOLDER
        else -> ArtworkScope.VIRTUAL_FOLDER
    }

    /** The label of a folder key (the name or path after the prefix). */
    fun labelOf(key: String): String = key.substringAfter(':')
}

/** Everything the user can configure. Off by default: with [enabled] false the app looks exactly as before. */
data class ArtworkSettings(
    val enabled: Boolean = false,
    val videoMode: ArtworkMode = ArtworkMode.EXISTING,
    val smbFolderMode: ArtworkMode = ArtworkMode.EXISTING,
    val virtualFolderMode: ArtworkMode = ArtworkMode.EXISTING,
    /** The user accepted that performer names are sent to the providers they enabled. */
    val adultConsent: Boolean = false,
    val portraitsOnFolders: Boolean = false,
    val portraitsOnVideos: Boolean = false,
    val autoFetchOnScan: Boolean = false,
    val wifiOnly: Boolean = true,
    val allowMetered: Boolean = false,
    val collageTiles: Int = 4,
    val providerOrder: List<String> = listOf(ProviderIds.STASH_DB),
    val disabledProviders: Set<String> = emptySet(),
    /** Video file names: search with only the part before the first "," or "-". */
    val videoUseFirstName: Boolean = true,
    /** Folders: search with the whole folder name. */
    val folderUseWholeName: Boolean = true,
    /** Optional own API address per provider (HTTPS), for self-hosted or mirrored services. */
    val customEndpoints: Map<String, String> = emptyMap(),
    /** How portraits are chosen for SMB folders and for virtual (and custom) folders, independently. */
    val smbFolderSelection: PortraitSelection = PortraitSelection.AUTOMATIC,
    val virtualFolderSelection: PortraitSelection = PortraitSelection.AUTOMATIC,
) {
    /** The portrait selection rule for a folder kind (videos follow their virtual folder). */
    fun selectionFor(scope: ArtworkScope): PortraitSelection =
        if (scope == ArtworkScope.SMB_FOLDER) smbFolderSelection else virtualFolderSelection

    fun withSelection(scope: ArtworkScope, selection: PortraitSelection): ArtworkSettings =
        if (scope == ArtworkScope.SMB_FOLDER) copy(smbFolderSelection = selection) else copy(virtualFolderSelection = selection)

    fun modeFor(scope: ArtworkScope): ArtworkMode = when (scope) {
        ArtworkScope.VIDEO -> videoMode
        ArtworkScope.SMB_FOLDER -> smbFolderMode
        ArtworkScope.VIRTUAL_FOLDER -> virtualFolderMode
    }

    fun withMode(scope: ArtworkScope, mode: ArtworkMode): ArtworkSettings = when (scope) {
        ArtworkScope.VIDEO -> copy(videoMode = mode)
        ArtworkScope.SMB_FOLDER -> copy(smbFolderMode = mode)
        ArtworkScope.VIRTUAL_FOLDER -> copy(virtualFolderMode = mode)
    }

    /** Internet portraits may be used for this kind of item only when the user turned that on explicitly. */
    fun portraitsAllowedFor(scope: ArtworkScope): Boolean =
        adultConsent && if (scope == ArtworkScope.VIDEO) portraitsOnVideos else portraitsOnFolders

    /**
     * Whether portraits may be looked up for a folder of this kind: its own default is the portrait and portraits are
     * on for folders, or (virtual folders) videos use portraits and inherit them from their folder. Never in Custom
     * mode, where the user searches only by hand.
     */
    fun portraitLookupWanted(scope: ArtworkScope): Boolean {
        if (!enabled || !scope.isFolder || selectionFor(scope) == PortraitSelection.CUSTOM) return false
        val forFolder = portraitsAllowedFor(scope) && modeFor(scope) == ArtworkMode.PORTRAIT
        val forVideos = scope == ArtworkScope.VIRTUAL_FOLDER && portraitsAllowedFor(ArtworkScope.VIDEO) && videoMode == ArtworkMode.PORTRAIT
        return forFolder || forVideos
    }

    fun enabledProviders(): List<String> = providerOrder.filter { it !in disabledProviders }

    /** The search term for a label of this kind of item, following the user's naming switches. */
    fun searchTerm(scope: ArtworkScope, label: String): String =
        if (scope == ArtworkScope.VIDEO) {
            if (videoUseFirstName) com.deepak.videolibrary.artwork.online.NameCleaner.forVideo(label)
            else com.deepak.videolibrary.artwork.online.NameCleaner.clean(label)
        } else {
            com.deepak.videolibrary.artwork.online.NameCleaner.forFolder(label, folderUseWholeName)
        }
}

object ProviderIds {
    const val STASH_DB = "stashdb"

    /** Removed provider (paid API). Only used to delete what an older version stored for it. */
    internal const val RETIRED_THE_PORN_DB = "theporndb"
}

/** What is stored for one item. [manual] true means the user chose it by hand and it beats every default. */
data class ArtworkOverride(
    val key: String,
    val mode: ArtworkMode,
    val manual: Boolean,
    val imageName: String? = null,
    val providerId: String? = null,
    val performerId: String? = null,
    val performerName: String? = null,
    /** OK, NO_MATCH, AMBIGUOUS or ERROR: the outcome of an automatic lookup, so it is not repeated forever. */
    val lookupStatus: String = STATUS_OK,
    val updatedAt: Long = 0,
) {
    companion object {
        const val STATUS_OK = "OK"
        const val STATUS_NO_MATCH = "NO_MATCH"
        const val STATUS_AMBIGUOUS = "AMBIGUOUS"
        const val STATUS_ERROR = "ERROR"

        /** Suggestions were found and wait for the user to pick one. */
        const val STATUS_REVIEW = "REVIEW"
    }
}
