package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screen state that should survive moving between pages for as long as the app is open (but not a restart):
 * which Settings page is selected and where the Settings index was scrolled to.
 */
class UiSession {
    /** Open/closed state of expandable parts, by name. */
    val flags = mutableStateMapOf<String, Boolean>()
    /** Only one expanded card per Settings page, even across nested artwork groups. */
    private val accordion = mutableStateMapOf<String, String>()

    fun accordionOpen(page: String, id: String, initiallyOpen: Boolean = false): Boolean {
        val selected = accordion[page]
        return selected == id || (selected == null && initiallyOpen)
    }
    fun setAccordionOpen(page: String, id: String, expanded: Boolean) {
        accordion[page] = if (expanded) id else ""
    }

    /** Selected Settings page, or null for the Settings index. */
    var settingsSection by mutableStateOf<String?>(null)

    /** Settings scroll position in pixels. */
    var settingsScroll = 0
    /** Preserve metadata review across the normal full-screen player replacing Settings composition. */
    var metadataReviewOpen by mutableStateOf(false)

    /** The What's new notes, opened from Settings. */
    var whatsNewOpen by mutableStateOf(false)

    /** Goes up each time Settings > Review all is tapped; the review pop-up opens even if it was put off. */
    var reviewRequest by mutableStateOf(0)
    var metadataReviewIndex = 0
    var metadataReviewOffset = 0
    var movieReviewIndex = 0
    var movieReviewOffset = 0


    /** Folders being selected on a folder page, or null. */
    var folderSelection by mutableStateOf<FolderSelection?>(null)

    /** Goes up by one each time the current section's button is tapped again: the page in view jumps to its top. */
    var scrollTopRequest by mutableStateOf(0)

    fun flag(key: String, default: Boolean = false): Boolean = flags[key] ?: default

    fun setFlag(key: String, value: Boolean) {
        flags[key] = value
    }

    /** A flag usable with `by`: `var open by ui.flagState("x")`. */
    fun flagState(key: String, default: Boolean = false): FlagState = FlagState(this, key, default)

    class FlagState(private val ui: UiSession, private val key: String, private val default: Boolean) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): Boolean = ui.flag(key, default)
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) = ui.setFlag(key, value)
    }
}

/**
 * The actions every page shows at its top right edge, after its own buttons (the download button). Pages that draw
 * their own title row call this last so the download button is always in the corner.
 */
val LocalTopBarEnd = compositionLocalOf<@Composable () -> Unit> { {} }
