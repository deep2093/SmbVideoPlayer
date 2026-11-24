package com.skywing.desktop

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.awt.ComposeWindow
import com.skywing.desktop.playback.MotionQuality
import com.skywing.desktop.playback.MotionSettings
import com.skywing.desktop.playback.SmoothingCapabilities
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStatus
import org.junit.Assume.assumeTrue
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.util.concurrent.FutureTask
import javax.accessibility.AccessibleComponent
import javax.accessibility.AccessibleRole
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Real Compose windows read through javax.accessibility, the data Java Access Bridge sends to screen readers and
 * automation. Covers names, descriptions, roles, values, checked states and keyboard input as JAB clients see them.
 */
class AccessBridgeTreeTest {
    private fun <T> edt(block: () -> T): T = FutureTask(block).also { SwingUtilities.invokeAndWait(it) }
        .let { task -> try { task.get() } catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e } }

    private fun withWindow(content: @Composable () -> Unit, test: (ComposeWindow) -> Unit) {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val window = edt { ComposeWindow().apply { setContent { Column { content() } }; setSize(900, 900); isVisible = true } }
        try { test(window) } finally { edt { window.dispose() } }
    }

    /** Waits for Compose to publish the control, then requires exactly one match. */
    private fun find(window: ComposeWindow, label: String, role: AccessibleRole, where: (AccessibleControl) -> Boolean = { true }): AccessibleControl {
        val end = System.nanoTime() + 10_000_000_000
        while (true) {
            val result = runCatching { edt { AccessibleControls.find(window, label, role, where) } }
            if (result.isSuccess || System.nanoTime() > end) return result.getOrThrow()
            Thread.sleep(100)
        }
    }

    private fun eventually(what: String, condition: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000
        while (!condition()) { check(System.nanoTime() < end) { "Timed out waiting for $what" }; Thread.sleep(50) }
    }

    /** Focuses the control as JAB's requestFocus does, then delivers a key press and release to the window. */
    private fun press(window: ComposeWindow, control: AccessibleControl, key: Int, char: Char = KeyEvent.CHAR_UNDEFINED) {
        edt { (control.context as AccessibleComponent).requestFocus() }
        Thread.sleep(200)
        edt {
            val target = window.focusOwner ?: deepest(window.contentPane)
            val now = System.currentTimeMillis()
            target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_PRESSED, now, 0, key, char, KeyEvent.KEY_LOCATION_STANDARD))
            if (char != KeyEvent.CHAR_UNDEFINED) target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_TYPED, now, 0, KeyEvent.VK_UNDEFINED, char))
            target.dispatchEvent(KeyEvent(target, KeyEvent.KEY_RELEASED, now, 0, key, char, KeyEvent.KEY_LOCATION_STANDARD))
        }
    }

    private fun deepest(c: Component): Component = (c as? Container)?.components?.firstOrNull()?.let(::deepest) ?: c

    @Test fun switchIsADescribedCheckBoxWithoutReadOnlyText() = withWindow({
        var on by remember { mutableStateOf(false) }
        SwitchRow("Show hidden files", "Detail", on) { on = it }
    }) { window ->
        val box = find(window, "Show hidden files", AccessibleRole.CHECK_BOX)
        assertEquals("", box.name)
        assertEquals("Show hidden files", box.description)
        assertFalse(box.hasTextInterface, "a text interface makes NVDA announce the switch as read only")
        assertFalse(box.checked)
        edt { AccessibleControls.activate(box) }
        eventually("checked after JAB click") { find(window, "Show hidden files", AccessibleRole.CHECK_BOX).checked }
        press(window, find(window, "Show hidden files", AccessibleRole.CHECK_BOX), KeyEvent.VK_SPACE, ' ')
        eventually("unchecked after Space") { !find(window, "Show hidden files", AccessibleRole.CHECK_BOX).checked }
    }

    @Test fun disabledSwitchReportsStateAndIsNeverActivated() = withWindow({
        SkySwitch(true, {}, "Motion smoothing", enabled = false)
    }) { window ->
        val box = find(window, "Motion smoothing", AccessibleRole.CHECK_BOX)
        assertTrue(box.checked)
        assertFalse(box.enabled)
        assertFailsWith<IllegalStateException> { edt { AccessibleControls.activate(box) } }
    }

    @Test fun folderCheckBoxIsDescribedByItsFolder() = withWindow({
        var ticked by remember { mutableStateOf(false) }
        PickRow(SkyIcon.Folder, "Movies", ticked, enabled = true, onCheck = { ticked = it }, onOpen = null)
    }) { window ->
        val box = find(window, "Movies", AccessibleRole.CHECK_BOX)
        assertEquals("Movies", box.description)
        assertFalse(box.hasTextInterface)
        edt { AccessibleControls.activate(box) }
        eventually("checked") { find(window, "Movies", AccessibleRole.CHECK_BOX).checked }
    }

    /**
     * Sliders keep text naming: description naming let NVDA read purpose and value on focus, but it did not announce
     * value changes, and Material3 1.8.2 sliders take no arrow-key input. JAB sends them as labels with a value.
     */
    @Test fun seekSliderIsANamedLabelWithItsValue() = withWindow({
        var seek by remember { mutableStateOf(25f) }
        PlayerControls(
            position = seek / 100.0 * 120.0, duration = 120.0, seekFraction = seek, paused = true, muted = false,
            canPrevious = false, canNext = false, status = "", smoothingMode = SmoothingMode.OFF, smoothingActive = false,
            onSmoothingToggle = {}, onSmoothingOptions = {}, onSeekChange = { seek = it }, onSeekFinished = {},
            onTogglePause = {}, onStop = {}, onBack10 = {}, onForward10 = {}, onPrevious = {}, onNext = {}, onAudio = {},
            onSubtitles = {}, onSubtitlesOff = {}, onVolumeDown = {}, onVolumeUp = {}, onMute = {},
            onFullscreen = {}, onOpenFile = {},
        )
    }) { window ->
        val seek = find(window, "Seek", AccessibleRole.LABEL) { it.value != null }
        assertEquals("Seek", seek.name)
        assertEquals(25.0, seek.value?.toDouble())
        assertTrue(seek.enabled)
    }

    @Test fun motionSlidersAreToldApartFromCaptionsByTheirValue() = withWindow({
        SmoothingOptions(
            SmoothingMode.MOTION,
            SmoothingStatus(
                capabilities = SmoothingCapabilities(displayHz = 120.0, sourceFps = 24000 / 1001.0, width = 1920, height = 1080),
                settings = MotionSettings(stop = 0, quality = MotionQuality.BALANCED),
            ),
        ) {}
    }) { window ->
        // The visible "Frame rate" caption and the slider share label and role: refuse, then narrow by value.
        val frameRate = find(window, "Frame rate", AccessibleRole.LABEL) { it.value != null }
        val ambiguous = assertFailsWith<AssertionError> { edt { AccessibleControls.find(window, "Frame rate", AccessibleRole.LABEL) } }
        assertTrue("Ambiguous: 2" in ambiguous.message.orEmpty(), ambiguous.message)
        assertEquals(0.0, frameRate.value?.toDouble())
        assertEquals(MotionQuality.BALANCED.ordinal.toDouble(), find(window, "Quality", AccessibleRole.LABEL) { it.value != null }.value?.toDouble())
        assertTrue(find(window, "Motion smoothing", AccessibleRole.CHECK_BOX).checked)
    }

    @Test fun buttonsAndTabsKeepTheirNames() = withWindow({
        NavRow("Folders", SkyIcon.Folder, selected = true) {}
        SkyIconButton(SkyIcon.Play, "Play", {})
    }) { window ->
        assertTrue(find(window, "Folders", AccessibleRole.PAGE_TAB).let { it.name == "Folders" && it.selected })
        assertEquals("Play", find(window, "Play", AccessibleRole.PUSH_BUTTON).name)
    }

    @Test fun ambiguousOrMissingMatchesAreRefusedNotGuessed() = withWindow({
        var clicks by remember { mutableStateOf(0) }
        SkyButton("Add", { clicks++ })
        SkyButton("Add", { clicks++ })
        SkySwitch(false, {}, "Repeat")
    }) { window ->
        find(window, "Repeat", AccessibleRole.CHECK_BOX)
        val ambiguous = assertFailsWith<AssertionError> { edt { AccessibleControls.find(window, "Add", AccessibleRole.PUSH_BUTTON) } }
        assertTrue("Ambiguous: 2" in ambiguous.message.orEmpty(), ambiguous.message)
        assertFailsWith<AssertionError> { edt { AccessibleControls.find(window, "Repeat", AccessibleRole.PUSH_BUTTON) } }
    }
}
