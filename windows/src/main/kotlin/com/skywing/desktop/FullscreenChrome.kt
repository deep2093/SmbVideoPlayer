package com.skywing.desktop

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.layout.onSizeChanged
import java.awt.Dialog
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.SwingUtilities
import kotlin.math.roundToInt

/** Separate owned HWNDs draw above the heavyweight video without changing its bounds. */
@Composable
internal fun FullscreenChrome(owner: Window, fullscreen: Boolean, visible: Boolean,
                              bottom: Boolean, content: @Composable () -> Unit) {
    if (!fullscreen) {
        if (visible) content()
        return
    }
    val latestContent by rememberUpdatedState(content)
    val locals = currentCompositionLocalContext
    val overlay = remember(owner, bottom) {
        ComposeDialog(owner, Dialog.ModalityType.MODELESS).apply {
            name = if (bottom) "skywing-controls-overlay" else "skywing-title-overlay"
            isUndecorated = true
            isResizable = false
            isTransparent = true
            focusableWindowState = false
            isAutoRequestFocus = false
        }
    }
    DisposableEffect(overlay) {
        fun position() {
            val bounds = owner.bounds
            overlay.setBounds(bounds.x, if (bottom) bounds.y + bounds.height - overlay.height else bounds.y,
                bounds.width, overlay.height.coerceAtLeast(1))
        }
        overlay.setSize(owner.width, if (bottom) 300 else 64)
        overlay.compositionLocalContext = locals
        overlay.setContent {
            Column(Modifier.fillMaxWidth().onSizeChanged { size ->
                // Compose reports physical pixels; AWT window bounds use logical pixels.
                val height = (size.height / overlay.graphicsConfiguration.defaultTransform.scaleY).roundToInt().coerceAtLeast(1)
                if (height != overlay.height) SwingUtilities.invokeLater {
                    if (overlay.isDisplayable) { overlay.setSize(overlay.width, height); position() }
                }
            }) { latestContent() }
        }
        val listener = object : ComponentAdapter() {
            override fun componentMoved(e: ComponentEvent) = position()
            override fun componentResized(e: ComponentEvent) = position()
        }
        owner.addComponentListener(listener)
        position()
        onDispose { owner.removeComponentListener(listener); overlay.dispose() }
    }
    SideEffect { overlay.isVisible = visible && owner.isShowing }
}

internal fun belongsToPlayer(candidate: Window?, owner: Window): Boolean =
    candidate === owner || (candidate?.owner === owner && candidate.name?.startsWith("skywing-") == true)
