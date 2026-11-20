package com.deepak.videolibrary.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The locked screen of a player: swallows every touch, and a tap shows the Unlock button for a few seconds.
 * [hint] changes whenever the button should show again (each tap, and when the screen is locked).
 */
@Composable
internal fun BoxScope.PlayerLockOverlay(hint: Int, onTap: () -> Unit, onUnlock: () -> Unit) {
    var hintVisible by remember { mutableStateOf(true) }
    LaunchedEffect(hint) {
        hintVisible = true
        delay(3_000)
        hintVisible = false
    }
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                onTap()
            }
        },
    )
    androidx.compose.animation.AnimatedVisibility(
        visible = hintVisible,
        modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 28.dp),
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
    ) {
        FilledTonalButton(onClick = onUnlock) {
            Icon(Icons.Filled.LockOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Unlock")
        }
    }
}
