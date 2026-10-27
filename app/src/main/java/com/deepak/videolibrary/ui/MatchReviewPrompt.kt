package com.deepak.videolibrary.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Asks you to decide the scene matches that were not clear enough to apply by themselves: when the app opens, and again
 * whenever new ones turn up while you are using it (after the lookup has gone quiet, never while a video is playing).
 * "Review now" opens the same one-file-at-a-time review as Settings > Scene metadata. "Later" asks again only when
 * more matches are waiting than the last time you were asked.
 */
@Composable
internal fun MatchReviewPrompt(vm: LibraryViewModel, canShow: Boolean, onPreviewVideo: (VideoEntity) -> Unit) {
    val context = LocalContext.current
    val service = remember(context) { ServiceLocator.get(context) }
    val changes by service.sceneMetadataStore.revision.collectAsStateWithLifecycle()
    var waiting by remember { mutableIntStateOf(0) }
    var askedAt by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(changes) {
        // A lookup saves many results in a row; count once it has gone quiet.
        if (changes > 0) delay(4_000)
        val count = withContext(Dispatchers.IO) { service.sceneMetadataStore.reviewCount() }
        waiting = count
        askedAt = minOf(askedAt, count)
    }

    if (canShow && !vm.ui.metadataReviewOpen && waiting > askedAt) {
        AlertDialog(
            onDismissRequest = { askedAt = waiting },
            title = { Text("Matches need your review") },
            text = {
                Text(
                    (if (waiting == 1) "1 video has" else "$waiting videos have") +
                        " scene matches that were not clear enough to apply automatically. Everything else was applied already.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askedAt = waiting
                    vm.ui.metadataReviewOpen = true
                }) { Text("Review now") }
            },
            dismissButton = { TextButton(onClick = { askedAt = waiting }) { Text("Later") } },
        )
    }
    // The review window is shown here (not inside Settings) so the pop-up works from any page. It hides while a
    // video plays and returns at the same file afterwards.
    if (canShow && vm.ui.metadataReviewOpen) {
        SceneMetadataReviewDialog(
            service = service,
            vm = vm,
            onPreviewVideo = onPreviewVideo,
            onDismiss = { vm.ui.metadataReviewOpen = false },
        )
    }
}
