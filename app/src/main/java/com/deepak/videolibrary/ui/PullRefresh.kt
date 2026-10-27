package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.deepak.videolibrary.scan.ScanMode
import kotlinx.coroutines.delay

/**
 * Pull down on a library page to refresh the whole library from the drive (the same as Refresh library). This is the
 * only way a folder page reads the drive again: pages never re-check it on their own, so the drive can sleep.
 * The spinner shows briefly; the scan's own progress bar then shows how far it is.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryPullRefresh(vm: LibraryViewModel, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    var requested by remember { mutableStateOf(false) }
    LaunchedEffect(requested) {
        if (requested) {
            delay(900)
            requested = false
        }
    }
    PullToRefreshBox(
        isRefreshing = requested,
        onRefresh = {
            requested = true
            vm.startScan(ScanMode.REFRESH)
        },
        modifier = modifier,
        content = content,
    )
}
