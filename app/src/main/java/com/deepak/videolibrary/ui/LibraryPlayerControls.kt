package com.deepak.videolibrary.ui

import androidx.compose.runtime.Composable

/** Stable entry point for existing engine callers and accessibility tests. */
@Composable
internal fun LibraryPlayerControls(
    title: String,
    position: Long,
    duration: Long,
    isPlaying: Boolean,
    canPlay: Boolean = true,
    canPrevious: Boolean = false,
    canNext: Boolean = false,
    queueLabel: String? = null,
    aspectLabel: String? = null,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    onTracks: () -> Unit,
    onOptions: () -> Unit,
    onAspect: () -> Unit = {},
    onRotate: (() -> Unit)? = null,
    onPip: (() -> Unit)? = null,
    onQueue: (() -> Unit)? = null,
    onInteraction: () -> Unit = {},
) {
    SkyWingPlayerChrome(
        title = title, position = position, duration = duration, isPlaying = isPlaying,
        canPlay = canPlay, canPrevious = canPrevious, canNext = canNext,
        queueLabel = queueLabel, aspectLabel = aspectLabel,
        onBack = onBack, onTogglePlayback = onTogglePlayback, onSeek = onSeek,
        onPrevious = onPrevious, onNext = onNext, onTracks = onTracks, onOptions = onOptions,
        onAspect = onAspect, onRotate = onRotate, onPip = onPip, onQueue = onQueue,
        onInteraction = onInteraction,
    )
}
