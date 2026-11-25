package com.skywing.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.platform.testTag
import com.skywing.desktop.playback.MotionQuality
import com.skywing.desktop.playback.MotionSettings
import com.skywing.desktop.playback.SmoothingCapabilities
import com.skywing.desktop.playback.SmoothingMode
import com.skywing.desktop.playback.SmoothingStatus
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals

/** Accessible names, roles and states that Java Access Bridge receives from Compose semantics. */
@OptIn(ExperimentalTestApi::class)
class AccessibilitySemanticsTest {
    @get:Rule val compose = createComposeRule()

    private fun role(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)
    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private val noDescription = SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    /** The Java Access Bridge name, as Compose Desktop 1.8 derives it: editable text, else the first text entry. */
    private fun named(name: String) = SemanticsMatcher("accessible name '$name'") {
        (it.config.getOrNull(SemanticsProperties.EditableText) ?: it.config.getOrNull(SemanticsProperties.Text)?.firstOrNull())?.text == name
    }

    /**
     * Switches and check boxes are labelled by description and carry no text semantics: text gives Java Access
     * Bridge a text interface, which NVDA announces as "read only".
     */
    private fun described(label: String) = SemanticsMatcher("described as '$label' without text") {
        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf(label) &&
            it.config.getOrNull(SemanticsProperties.Text) == null && it.config.getOrNull(SemanticsProperties.EditableText) == null
    }
    private fun slider(name: String) = named(name) and SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    @Test fun iconButtonUsesItsDescriptionAsAButtonName() {
        var clicks = 0
        compose.setContent { SkyIconButton(SkyIcon.Play, "Play", { clicks++ }) }
        compose.onNode(named("Play")).assert(role(Role.Button)).assert(noDescription).assertHasClickAction().performClick()
        assertEquals(1, clicks)
    }

    @Test fun anActorCardWithoutAPhotoIsNamedByTheActorNotTheInitials() {
        compose.setContent {
            FolderCard("Ava Stone", "2 videos", null, emptyList(), {}, actor = true, initials = actorInitials("Ava Stone"))
        }
        compose.onNode(role(Role.Button) and named("Ava Stone")).assertHasClickAction()
        compose.onAllNodes(role(Role.Button) and named("AS")).assertCountEquals(0)
    }

    @Test fun iconButtonKeepsKeyboardFocusAndEnterActivation() {
        var clicks = 0
        compose.setContent { SkyIconButton(SkyIcon.Stop, "Stop (S)", { clicks++ }) }
        val button = compose.onNode(named("Stop (S)"))
        button.requestFocus().assertIsFocused()
        button.performKeyInput { pressKey(Key.Enter) }
        compose.waitForIdle()
        assertEquals(1, clicks)
    }

    @Test fun textButtonIsAButtonWithoutADuplicateDescription() {
        compose.setContent { SkyButton("Done", {}) }
        compose.onNodeWithText("Done").assert(role(Role.Button)).assert(noDescription)
    }

    @Test fun navigationRowsAreTabsWithSelectedState() {
        var picked = ""
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                NavRow("Folders", SkyIcon.Folder, selected = true) { picked = "Folders" }
                NavRow("Videos", SkyIcon.VideoLibrary, selected = false) { picked = "Videos" }
            }
        }
        compose.onNodeWithText("Folders").assert(role(Role.Tab)).assertIsSelected()
        compose.onNodeWithText("Videos").assert(role(Role.Tab)).assertIsNotSelected().performClick()
        assertEquals("Videos", picked)
    }

    @Test fun chipsExposeTabRoleAndSelectedState() {
        compose.setContent { SkyChipBar(listOf("All", "Recent"), selected = 1, onSelect = {}) }
        compose.onNodeWithText("Recent").assert(role(Role.Tab)).assertIsSelected()
        compose.onNodeWithText("All").assert(role(Role.Tab)).assertIsNotSelected()
    }

    @Test fun settingsSwitchIsDescribedByItsTitle() {
        var value = false
        compose.setContent { SwitchRow("Show hidden files", "Detail", checked = false) { value = it } }
        compose.onNode(isToggleable() and described("Show hidden files"))
            .assert(role(Role.Checkbox)).assertIsOff().performClick()
        assertEquals(true, value)
        assertEquals(1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
    }

    /** The checkbox-role switch must look, size and respond exactly like the Material switch it wraps. */
    @Test fun skySwitchMatchesMaterialSwitchSizeClickAndKeys() {
        var material by androidx.compose.runtime.mutableStateOf(false)
        var sky by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            androidx.compose.foundation.layout.Row {
                androidx.compose.material3.Switch(material, { material = it }, Modifier.testTag("material"))
                SkySwitch(sky, { sky = it }, "Sky", Modifier.testTag("sky"))
            }
        }
        val m = compose.onNodeWithTag("material")
        val s = compose.onNodeWithTag("sky").assert(role(Role.Checkbox)).assert(described("Sky"))
        assertEquals(m.fetchSemanticsNode().size, s.fetchSemanticsNode().size)
        s.performClick(); compose.waitForIdle()
        assertEquals(true, sky)
        listOf(Key.Spacebar, Key.Enter).forEach { key ->
            val before = material to sky
            m.requestFocus(); m.performKeyInput { pressKey(key) }
            s.requestFocus().assertIsFocused(); s.performKeyInput { pressKey(key) }
            compose.waitForIdle()
            assertEquals(before.first != material, before.second != sky, "key $key")
        }
    }

    @Test fun skySwitchDrawsTheSamePixelsAsMaterialSwitch() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                listOf(false, true).forEach { checked ->
                    androidx.compose.material3.Switch(checked, {}, Modifier.testTag("m$checked"))
                    SkySwitch(checked, {}, "Sky", Modifier.testTag("s$checked"))
                }
            }
        }
        listOf(false, true).forEach { checked ->
            val m = compose.onNodeWithTag("m$checked").captureToImage().toPixelMap()
            val s = compose.onNodeWithTag("s$checked").captureToImage().toPixelMap()
            assertEquals(m.width to m.height, s.width to s.height)
            val different = (0 until m.width).flatMap { x -> (0 until m.height).filter { y -> m[x, y] != s[x, y] }.map { y -> "$x,$y" } }
            assertEquals(emptyList(), different, "checked=$checked size=${m.width}x${m.height}")
        }
    }

    @Test fun disabledSkySwitchKeepsStateButIgnoresClicks() {
        var changes = 0
        compose.setContent { SkySwitch(true, { changes++ }, "Motion smoothing", enabled = false) }
        compose.onNode(described("Motion smoothing")).assert(role(Role.Checkbox)).assertIsOn().assertIsNotEnabled().performClick()
        assertEquals(0, changes)
    }

    /** Text fields take their bridge name from what is typed, so the placeholder is exposed as the description. */
    @Test fun searchFieldIsDescribedByItsPlaceholder() {
        compose.setContent { SkyField("", {}, "Search folders", leading = SkyIcon.Search) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetText))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Search folders")))
    }

    @Test fun smoothingControlsHaveNamesAndCurrentValues() {
        val status = SmoothingStatus(
            capabilities = SmoothingCapabilities(displayHz = 120.0, sourceFps = 24000 / 1001.0, width = 1920, height = 1080),
            settings = MotionSettings(stop = 0, quality = MotionQuality.BALANCED),
        )
        compose.setContent { SmoothingOptions(SmoothingMode.MOTION, status) {} }
        compose.onNode(isToggleable() and described("Motion smoothing"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        compose.onNode(slider("Frame rate")).assert(state("47.95 fps (2×)"))
            .assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..3f, steps = 2))
        compose.onNode(slider("Quality")).assert(state("Balanced"))
    }

    @Test fun seekSliderNamesPositionAndAcceptsSetProgress() {
        var seek = -1f
        compose.setContent { controls(seekFraction = 25f, onSeekChange = { seek = it }) }
        compose.onNode(slider("Seek"))
            .assert(state("0:30 of 2:00"))
            .assertRangeInfoEquals(ProgressBarRangeInfo(25f, 0f..100f))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(50f) }
        assertEquals(50f, seek)
    }

    @Test fun playerTextControlsHaveSpecificNames() {
        var off = 0
        compose.setContent { controls(onSubtitlesOff = { off++ }) }
        compose.onNode(named("Subtitles off")).assert(role(Role.Button)).performClick()
        assertEquals(1, off)
        listOf("Next audio track", "Volume down", "Volume up", "Back 10 seconds", "Forward 10 seconds").forEach {
            compose.onNode(named(it)).assert(role(Role.Button))
        }
    }

    @Test fun folderCheckboxIsDescribedByItsFolder() {
        var checked = false
        compose.setContent { PickRow(SkyIcon.Folder, "Movies", checked = false, enabled = true, onCheck = { checked = it }, onOpen = null) }
        compose.onNode(isToggleable() and described("Movies")).assert(role(Role.Checkbox)).performClick()
        assertEquals(true, checked)
    }

    // The M3 runner's audit: Settings › App layout had two check boxes a screen reader could not name.
    @Test fun folderTabCheckboxIsDescribedByItsTab() {
        var ticked: Boolean? = null
        compose.setContent { FolderTabRow("Library", on = false, canTurnOff = true, canMoveEarlier = false, canMoveLater = false, onTick = { ticked = it }, onEarlier = {}, onLater = {}) }
        compose.onNode(isToggleable() and described("Library")).assert(role(Role.Checkbox)).performClick()
        assertEquals(true, ticked)
    }

    @Test fun settingsRowsAndThemesExposeRoles() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SettingsRow(SkyIcon.Settings, "Appearance", "Theme", {})
                ThemeChoice(skyThemes.first(), selected = true) {}
            }
        }
        compose.onNode(hasText("Appearance")).assert(role(Role.Button))
        compose.onNode(hasText(skyThemes.first().label)).assert(role(Role.RadioButton)).assertIsSelected()
    }

    @Test fun coverPositionSlidersAreNamedOnce() {
        val image = org.jetbrains.skia.Image.makeFromBitmap(org.jetbrains.skia.Bitmap().apply { allocN32Pixels(8, 8) })
        compose.setContent { androidx.compose.foundation.layout.Column { PositionStep(image, false, {}, { _, _, _ -> }) } }
        listOf("Up / down" to 0.3f, "Left / right" to 0.5f, "Zoom" to 0.5f).forEach { (name, value) ->
            compose.onNode(slider(name)).assertRangeInfoEquals(ProgressBarRangeInfo(value, 0f..1f))
            assertEquals(1, compose.onAllNodes(slider(name)).fetchSemanticsNodes().size)
        }
    }

    // M3 item 9 (M3 design brief 5.7): a card is a push button named by its title, with what is watched and its selection.
    @Test fun videoCardsAreButtonsNamedByTitleWithTheWatchedShareAndSelection() {
        val entry = WindowsMediaEntry(java.io.File(System.getProperty("java.io.tmpdir"), "Sky card test.mp4").path, "s", "local", "Videos", 1_000, 0)
        compose.setContent { VideoCard(entry, {}, progress = 0.42f, selected = true) }
        compose.onNode(named("Sky card test")).assert(role(Role.Button)).assertHasClickAction().assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("42% watched")))
    }

    @Test fun cardsOutsideSelectionSayNothingAboutSelection() {
        compose.setContent { FolderCard("Holidays", "12 videos", null, emptyList(), {}) }
        compose.onNode(named("Holidays")).assert(role(Role.Button)).assertHasClickAction()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)).assert(noDescription)
    }

    @Test fun settingsCategoriesInTwoPanesAreTabsWithTheOpenOneSelected() {
        compose.setContent {
            androidx.compose.foundation.layout.Column {
                SettingsListEntry(SkyIcon.Settings, "Appearance", selected = true) {}
                SettingsListEntry(SkyIcon.Settings, "Playback", selected = false) {}
            }
        }
        compose.onNodeWithText("Appearance").assert(role(Role.Tab)).assertIsSelected()
        compose.onNodeWithText("Playback").assert(role(Role.Tab)).assertIsNotSelected()
    }

    @androidx.compose.runtime.Composable
    private fun controls(seekFraction: Float = 0f, onSeekChange: (Float) -> Unit = {}, onSubtitlesOff: () -> Unit = {}) =
        PlayerControls(
            position = 0.0, duration = 120.0, seekFraction = seekFraction, paused = true, muted = false,
            canPrevious = false, canNext = false, status = "", smoothingMode = SmoothingMode.OFF, smoothingActive = false,
            onSmoothingToggle = {}, onSmoothingOptions = {}, onSeekChange = onSeekChange, onSeekFinished = {},
            onTogglePause = {}, onStop = {}, onBack10 = {}, onForward10 = {}, onPrevious = {}, onNext = {}, onAudio = {},
            onSubtitles = {}, onSubtitlesOff = onSubtitlesOff, onVolumeDown = {}, onVolumeUp = {}, onMute = {},
            onFullscreen = {}, onOpenFile = {},
        )
}
