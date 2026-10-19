package com.deepak.videolibrary.ui

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Scrolls [state] to the top when [key] changes while this composable is in the composition.
 *
 * The first value is treated as already seen, so a screen that re-enters the composition
 * (coming back from a folder, a tab switch or the player) keeps its saved scroll position.
 */
@Composable
internal fun ScrollToTopOnChange(key: Any?, state: LazyGridState) {
    var seen by remember { mutableStateOf(key) }
    LaunchedEffect(key) {
        if (seen != key) {
            seen = key
            state.scrollToItem(0)
        }
    }
}
