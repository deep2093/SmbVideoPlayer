package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Keep the saved density as a ceiling; a phone never gets five unreadable thumbnails. */
@Composable
internal fun libraryColumns(preset: String, floating: Boolean): Int {
    val width = LocalConfiguration.current.screenWidthDp
    val sidebar = if (width >= 700 && !floating) 204 else 0
    val available = (width - sidebar - 32).coerceAtLeast(1)
    return LibraryLayout.fittedColumns(preset, available)
}
