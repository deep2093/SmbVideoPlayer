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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import com.deepak.videolibrary.scan.ScanMode
import kotlinx.coroutines.delay

/**
 * Pull down on a library page to look for missing scenes, posters and portraits again and apply what is found. It does
 * not scan the drive for new files: the Refresh button does that, so pages never re-check the drive on their own and
 * it can sleep. The spinner shows briefly; the lookup's own progress bar then shows how far it is.
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
            vm.lookUpMissing()
        },
        modifier = modifier,
        content = content,
    )
}

/** The Refresh button on every library page: scans the drive for new, changed and removed files. */
@Composable
internal fun RefreshLibraryAction(vm: LibraryViewModel) {
    IconAction(Icons.Filled.Refresh, "Refresh library", onClick = { vm.startScan(ScanMode.REFRESH) })
}
