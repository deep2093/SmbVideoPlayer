package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Screen state that should survive moving between pages for as long as the app is open (but not a restart):
 * which Settings sections are open and where the Settings page was scrolled to.
 */
class UiSession {
    /** Open/closed state of expandable parts, by name. */
    val flags = mutableStateMapOf<String, Boolean>()

    /** The open Settings section, or null when all are closed. */
    var settingsSection by mutableStateOf<String?>(null)

    /** Settings scroll position in pixels. */
    var settingsScroll = 0

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
