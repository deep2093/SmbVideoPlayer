package com.skywing.desktop.artwork

/** Each folder kind has its own cover default and portrait selection. */
internal enum class ArtworkScope(val label: String) {
    VIDEO("Videos"),
    SMB_FOLDER("Drive folders"),
    VIRTUAL_FOLDER("Virtual folders"),
    CUSTOM_FOLDER("Custom folders"),
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

internal enum class ArtworkMode(val label: String) {
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
internal enum class PortraitSelection(val label: String, val detail: String) {
    AUTOMATIC("Automatic", "Applies a picture only when exactly one confident match is found; anything unclear waits for you in Review suggestions."),
    MANUAL("Manual", "Finds suggestions in the background but applies nothing until you pick one in Review suggestions."),
    CUSTOM("Custom", "Nothing is searched in the background. Covers change only when you search or choose an image yourself in Change cover."),
}

/**
 * Identity strings of covers. Videos are keyed by their library path (lower case), so a cover chosen for a video
 * comes back by itself when the same file returns to the library.
 */
internal object ArtworkKeys {
    fun video(path: String) = "video:" + path.lowercase()

    /** A group of videos with one name (inside a Custom folder, or library wide). */
    fun virtualFolder(identity: String) = "virtual:$identity"
    fun matchFolder(id: String) = "match:$id"
    fun ownFolder(id: String) = "own:$id"
    fun originalFolder(path: String) = "original:" + path.trimEnd('\\').lowercase()

    /** A Virtual folder made by the user and one of its generated subfolders. */
    fun virtualSet(id: String) = "vset:$id"
    fun virtualSub(setId: String, identity: String) = "vset:$setId:$identity"

    fun scopeOf(key: String): ArtworkScope = when {
        key.startsWith("video:") -> ArtworkScope.VIDEO
        key.startsWith("original:") -> ArtworkScope.SMB_FOLDER
        key.startsWith("own:") || key.startsWith("match:") -> ArtworkScope.CUSTOM_FOLDER
        else -> ArtworkScope.VIRTUAL_FOLDER
    }
}

/** Everything the user can configure in Settings > Artwork. */
internal data class ArtworkSettings(
    val enabled: Boolean = true,
    val videoMode: ArtworkMode = ArtworkMode.EXISTING,
    val smbFolderMode: ArtworkMode = ArtworkMode.EXISTING,
    val virtualFolderMode: ArtworkMode = ArtworkMode.EXISTING,
    val customFolderMode: ArtworkMode = ArtworkMode.EXISTING,
    /** The user accepted that performer names are sent to StashDB. */
    val adultConsent: Boolean = false,
    val portraitsOnFolders: Boolean = false,
    val portraitsOnVideos: Boolean = false,
    val autoFetchOnScan: Boolean = false,
    val collageTiles: Int = 4,
    /** Video file names: search with only the part before the first "," or "-". */
    val videoUseFirstName: Boolean = true,
    /** Folders: search with the whole folder name. */
    val folderUseWholeName: Boolean = true,
    /** Optional own StashDB-compatible API address (HTTPS). */
    val customEndpoint: String = "",
    val smbFolderSelection: PortraitSelection = PortraitSelection.AUTOMATIC,
    val virtualFolderSelection: PortraitSelection = PortraitSelection.AUTOMATIC,
    val customFolderSelection: PortraitSelection = PortraitSelection.AUTOMATIC,
) {
    fun selectionFor(scope: ArtworkScope): PortraitSelection = when (scope) {
        ArtworkScope.SMB_FOLDER -> smbFolderSelection
        ArtworkScope.CUSTOM_FOLDER -> customFolderSelection
        else -> virtualFolderSelection
    }

    fun withSelection(scope: ArtworkScope, selection: PortraitSelection): ArtworkSettings =
        when (scope) {
            ArtworkScope.SMB_FOLDER -> copy(smbFolderSelection = selection)
            ArtworkScope.CUSTOM_FOLDER -> copy(customFolderSelection = selection)
            else -> copy(virtualFolderSelection = selection)
        }

    fun modeFor(scope: ArtworkScope): ArtworkMode = when (scope) {
        ArtworkScope.VIDEO -> videoMode
        ArtworkScope.SMB_FOLDER -> smbFolderMode
        ArtworkScope.VIRTUAL_FOLDER -> virtualFolderMode
        ArtworkScope.CUSTOM_FOLDER -> customFolderMode
    }

    fun withMode(scope: ArtworkScope, mode: ArtworkMode): ArtworkSettings = when (scope) {
        ArtworkScope.VIDEO -> copy(videoMode = mode)
        ArtworkScope.SMB_FOLDER -> copy(smbFolderMode = mode)
        ArtworkScope.VIRTUAL_FOLDER -> copy(virtualFolderMode = mode)
        ArtworkScope.CUSTOM_FOLDER -> copy(customFolderMode = mode)
    }

    /** Internet portraits may be used for this kind of item only when the user turned that on explicitly. */
    fun portraitsAllowedFor(scope: ArtworkScope): Boolean =
        adultConsent && if (scope == ArtworkScope.VIDEO) portraitsOnVideos else portraitsOnFolders

    /**
     * Whether portraits may be looked up for a folder of this kind: its default is the portrait and portraits are on
     * for folders, or (virtual folders) videos use portraits and inherit them from their folder. Never in Custom
     * selection, where the user searches only by hand.
     */
    fun portraitLookupWanted(scope: ArtworkScope): Boolean {
        if (!enabled || !scope.isFolder || selectionFor(scope) == PortraitSelection.CUSTOM) return false
        val forFolder = portraitsAllowedFor(scope) && modeFor(scope) == ArtworkMode.PORTRAIT
        val forVideos = scope == ArtworkScope.VIRTUAL_FOLDER && portraitsAllowedFor(ArtworkScope.VIDEO) && videoMode == ArtworkMode.PORTRAIT
        return forFolder || forVideos
    }

    /** The search term for a label of this kind of item, following the naming switches. */
    fun searchTerm(scope: ArtworkScope, label: String): String =
        if (scope == ArtworkScope.VIDEO) {
            if (videoUseFirstName) NameCleaner.forVideo(label) else NameCleaner.clean(label)
        } else {
            NameCleaner.forFolder(label, folderUseWholeName)
        }

    /** Keeps the settings consistent: portraits and auto lookups never stay on without consent. */
    fun sanitized(): ArtworkSettings = copy(
        portraitsOnFolders = portraitsOnFolders && adultConsent,
        portraitsOnVideos = portraitsOnVideos && adultConsent,
        autoFetchOnScan = autoFetchOnScan && adultConsent && (portraitsOnFolders || portraitsOnVideos),
        collageTiles = collageTiles.coerceIn(2, 6),
        customEndpoint = customEndpoint.trim().takeIf { EndpointPolicy.isAllowed(it) } ?: "",
        videoMode = videoMode.takeIf { it in ArtworkScope.VIDEO.defaultModes } ?: ArtworkMode.EXISTING,
        smbFolderMode = smbFolderMode.takeIf { it in ArtworkScope.SMB_FOLDER.defaultModes } ?: ArtworkMode.EXISTING,
        virtualFolderMode = virtualFolderMode.takeIf { it in ArtworkScope.VIRTUAL_FOLDER.defaultModes } ?: ArtworkMode.EXISTING,
        customFolderMode = customFolderMode.takeIf { it in ArtworkScope.CUSTOM_FOLDER.defaultModes } ?: ArtworkMode.EXISTING,
    )
}

/** What is stored for one item. [manual] true means the user chose it by hand and it beats every default. */
internal data class ArtworkOverride(
    val key: String,
    val mode: ArtworkMode,
    val manual: Boolean,
    val imageName: String? = null,
    val providerId: String? = null,
    val performerId: String? = null,
    val performerName: String? = null,
    /** OK, NO_MATCH, AMBIGUOUS, ERROR or REVIEW: the outcome of an automatic lookup, so it is not repeated forever. */
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

/** Where the picture for an item comes from. */
internal enum class ArtworkSource { IMAGE, FRAME, FIRST_FRAME, COLLAGE, LEGACY, NONE }

internal data class ArtworkDecision(val source: ArtworkSource, val imageName: String? = null)

/**
 * Chooses the cover source for an item, in the Android order: the user's choice for this item, then the default for
 * this kind of item (an inherited portrait only when portraits are on for that kind), then the existing thumbnail.
 */
internal object ArtworkResolver {
    /**
     * @param own the stored row for this item (a folder, or a video)
     * @param group for a video: the stored row of the folder it belongs to, whose portrait it may inherit
     */
    fun decide(scope: ArtworkScope, own: ArtworkOverride?, group: ArtworkOverride?, settings: ArtworkSettings): ArtworkDecision {
        if (!settings.enabled) return ArtworkDecision(ArtworkSource.LEGACY)

        if (own != null && own.manual) {
            when (own.mode) {
                ArtworkMode.NONE -> return ArtworkDecision(ArtworkSource.NONE)
                ArtworkMode.PORTRAIT, ArtworkMode.CUSTOM ->
                    if (own.imageName != null) return ArtworkDecision(ArtworkSource.IMAGE, own.imageName)
                ArtworkMode.FIRST_FRAME -> if (scope.isFolder) return ArtworkDecision(ArtworkSource.FIRST_FRAME)
                ArtworkMode.COLLAGE -> if (scope.isFolder) return ArtworkDecision(ArtworkSource.COLLAGE)
                ArtworkMode.FRAME -> if (!scope.isFolder) return ArtworkDecision(ArtworkSource.FRAME)
                ArtworkMode.EXISTING -> return ArtworkDecision(ArtworkSource.LEGACY)
            }
        }

        return when (settings.modeFor(scope)) {
            ArtworkMode.PORTRAIT -> {
                // Videos inherit the portrait of their folder; folders use their own.
                val row = if (scope.isFolder) own else group
                if (settings.portraitsAllowedFor(scope) && row != null && !row.manual && row.imageName != null) {
                    ArtworkDecision(ArtworkSource.IMAGE, row.imageName)
                } else {
                    ArtworkDecision(ArtworkSource.LEGACY)
                }
            }
            ArtworkMode.FIRST_FRAME -> if (scope.isFolder) ArtworkDecision(ArtworkSource.FIRST_FRAME) else ArtworkDecision(ArtworkSource.LEGACY)
            ArtworkMode.COLLAGE -> if (scope.isFolder) ArtworkDecision(ArtworkSource.COLLAGE) else ArtworkDecision(ArtworkSource.LEGACY)
            ArtworkMode.FRAME -> if (scope.isFolder) ArtworkDecision(ArtworkSource.LEGACY) else ArtworkDecision(ArtworkSource.FRAME)
            else -> ArtworkDecision(ArtworkSource.LEGACY)
        }
    }
}
