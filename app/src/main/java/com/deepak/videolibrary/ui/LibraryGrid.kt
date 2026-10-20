package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import kotlin.math.min

/** Keep the saved density as a ceiling; a phone never gets five unreadable thumbnails. */
@Composable
internal fun libraryColumns(preset: String, floating: Boolean): Int {
    val width = LocalConfiguration.current.screenWidthDp
    val sidebar = if (width >= 700 && !floating) 204 else 0
    val available = (width - sidebar - 32).coerceAtLeast(1)
    return min(LibraryLayout.columns(preset), ((available + 12) / 160).coerceAtLeast(1))
}
