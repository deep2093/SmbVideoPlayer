package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.VideoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Asks you to decide the scene matches, performer portraits and movie posters that were not clear enough to apply by themselves, in one
 * pop-up: when the app opens, and again whenever new ones turn up while you are using it (after the lookup has gone
 * quiet, never while a video is playing). Each button opens the same review as in Settings. "Later" asks again only
 * when more are waiting than the last time you were asked.
 */
@Composable
internal fun MatchReviewPrompt(vm: LibraryViewModel, canShow: Boolean, onPreviewVideo: (VideoEntity) -> Unit) {
    val context = LocalContext.current
    val service = remember(context) { ServiceLocator.get(context) }
    val changes by service.sceneMetadataStore.revision.collectAsStateWithLifecycle()
    val portraits by vm.portraitReview.collectAsStateWithLifecycle()
    val movies by vm.movieReview.collectAsStateWithLifecycle()
    var movieReviewOpen by remember { mutableStateOf(false) }
    var forced by remember { mutableStateOf(false) }
    var scenesWaiting by remember { mutableIntStateOf(0) }
    var portraitReviewOpen by remember { mutableStateOf(false) }
    var askedAt by rememberSaveable { mutableIntStateOf(0) }
    val waiting = scenesWaiting + portraits.size + movies.size
    // Settings > Review all asks for this window even after Later.
    var seenRequest by remember { mutableIntStateOf(vm.ui.reviewRequest) }
    LaunchedEffect(vm.ui.reviewRequest) {
        if (vm.ui.reviewRequest != seenRequest) {
            seenRequest = vm.ui.reviewRequest
            forced = true
        }
    }

    // The pop-up comes once the lookups have finished, not while they are still running: wait until none of them has
    // been running for a few seconds.
    val manager = remember(context) { androidx.work.WorkManager.getInstance(context) }
    val busy by remember(manager, service.libraryId) {
        val id = service.libraryId
        kotlinx.coroutines.flow.combine(
            listOf(
                manager.getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.scan.ScanWorker.name(id)),
                manager.getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.metadata.SceneMetadataWorker.workName(id)),
                manager.getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.metadata.ScenePosterWorker.workName(id)),
                manager.getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.artwork.PortraitFetchWorker.workName(id)),
                manager.getWorkInfosForUniqueWorkFlow(com.deepak.videolibrary.artwork.MoviePosterWorker.workName(id)),
            ),
        ) { lists -> lists.any { infos -> infos.any { it.state == androidx.work.WorkInfo.State.RUNNING } } }
    }.collectAsStateWithLifecycle(initialValue = false)
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(busy) {
        settled = false
        if (!busy) {
            delay(5_000)
            settled = true
        }
    }

    LaunchedEffect(changes, settled) {
        scenesWaiting = withContext(Dispatchers.IO) { service.sceneMetadataStore.reviewCount() }
    }
    LaunchedEffect(waiting) { askedAt = minOf(askedAt, waiting) }

    if (canShow && !vm.ui.metadataReviewOpen && !portraitReviewOpen && !movieReviewOpen && (forced || (settled && waiting > askedAt))) {
        AlertDialog(
            onDismissRequest = { askedAt = waiting; forced = false },
            title = { Text("Matches need your review") },
            text = {
                Text(
                    if (waiting == 0) "Nothing is waiting. Matches that are clear are applied automatically."
                    else listOfNotNull(
                        scenesWaiting.takeIf { it > 0 }?.let { if (it == 1) "1 scene match" else "$it scene matches" },
                        portraits.size.takeIf { it > 0 }?.let { if (it == 1) "1 performer portrait" else "$it performer portraits" },
                        movies.size.takeIf { it > 0 }?.let { if (it == 1) "1 movie poster" else "$it movie posters" },
                    ).joinToString(", ") + " were not clear enough to apply automatically. Everything else was applied already.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    if (scenesWaiting > 0) TextButton(onClick = {
                        askedAt = waiting; forced = false
                        vm.ui.metadataReviewOpen = true
                    }) { Text("Review scenes") }
                    if (portraits.isNotEmpty()) TextButton(onClick = {
                        askedAt = waiting; forced = false
                        portraitReviewOpen = true
                    }) { Text("Review portraits") }
                    if (movies.isNotEmpty()) TextButton(onClick = {
                        askedAt = waiting; forced = false
                        movieReviewOpen = true
                    }) { Text("Review movie posters") }
                }
            },
            dismissButton = { TextButton(onClick = { askedAt = waiting; forced = false }) { Text(if (waiting == 0) "Close" else "Later") } },
        )
    }
    if (canShow && portraitReviewOpen) ReviewPortraitsDialog(vm, onDismiss = { portraitReviewOpen = false })
    if (canShow && movieReviewOpen) ReviewMoviesDialog(vm, onPreviewVideo = onPreviewVideo, onDismiss = { movieReviewOpen = false })
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
