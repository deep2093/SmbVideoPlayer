package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** True when the screen is wider than it is tall. */
@Composable
internal fun isLandscape(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp > config.screenHeightDp
}

/** Columns for folder pages ([folders]) or video pages, from Settings > App layout for the current orientation. */
@Composable
internal fun libraryColumns(vm: LibraryViewModel, folders: Boolean): Int =
    libraryColumns(vm.gridPreset(folders, isLandscape()), floatingPadding(vm))

/** Keep the saved density as a ceiling; a phone never gets five unreadable thumbnails. */
@Composable
internal fun libraryColumns(preset: String, floating: Boolean): Int {
    val width = LocalConfiguration.current.screenWidthDp
    val sidebar = if (width >= 700 && !floating) 204 else 0
    val available = (width - sidebar - 32).coerceAtLeast(1)
    return LibraryLayout.fittedColumns(preset, available)
}
