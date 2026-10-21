package com.deepak.videolibrary.artwork

/** Where the picture for an item comes from. */
enum class ArtworkSource {
    /** A stored image (portrait or picked from the phone). */
    IMAGE,

    /** The frame the user picked by hand (videos). */
    CUSTOM_FRAME,

    /** The automatically generated frame (videos). */
    FRAME,

    /** Folder: the first video's frame. */
    FIRST_FRAME,

    /** Folder: a collage of frames. */
    COLLAGE,

    /** Whatever the app showed before this feature existed. */
    LEGACY,

    /** Deliberately no cover. */
    NONE,
}

data class ArtworkDecision(val source: ArtworkSource, val imageName: String? = null)

/**
 * Chooses the cover source for an item. Order, as specified:
 * the user's override for this item, then the frame the user picked, then the default for this kind of
 * item (an inherited portrait is only used when portraits are on for that kind of item), then the
 * existing thumbnail. With the master switch off nothing here applies.
 */
object ArtworkResolver {
    /**
     * @param own the stored row for this item (a folder, or a video)
     * @param group for a video: the stored row of the folder it belongs to, whose portrait it may inherit
     * @param hasCustomFrame a frame was picked by hand for this video
     */
    fun decide(
        scope: ArtworkScope,
        own: ArtworkOverride?,
        group: ArtworkOverride?,
        hasCustomFrame: Boolean,
        settings: ArtworkSettings,
    ): ArtworkDecision {
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

        if (!scope.isFolder && hasCustomFrame) return ArtworkDecision(ArtworkSource.CUSTOM_FRAME)

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
