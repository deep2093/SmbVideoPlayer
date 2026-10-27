package com.deepak.videolibrary.ui

import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/** Two-finger pinch switches saved layout steps; one-finger scrolling is unaffected. */
internal fun Modifier.pinchLibraryGrid(vm: LibraryViewModel, folders: Boolean): Modifier =
    pointerInput(vm, folders) {
        var cumulativeZoom = 1f
        detectTransformGestures(panZoomLock = true) { _, _, zoom, _ ->
            if (zoom <= 0f || !zoom.isFinite()) return@detectTransformGestures
            cumulativeZoom *= zoom
            if (cumulativeZoom >= 1.20f) {
                vm.pinchLibraryLayout(folders, -1)
                cumulativeZoom = 1f
            } else if (cumulativeZoom <= 0.83f) {
                vm.pinchLibraryLayout(folders, 1)
                cumulativeZoom = 1f
            }
        }
    }
