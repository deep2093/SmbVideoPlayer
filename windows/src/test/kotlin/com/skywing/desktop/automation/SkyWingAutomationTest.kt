package com.skywing.desktop.automation

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests that drive the packaged SkyWing test image through Java Access Bridge, as a screen reader would.
 * Each test starts its own app on temporary data (see [SkyWingTestApp]) and closes it afterwards.
 *
 * They open real windows, so they run only with SKYWING_TEST_AUTOMATION=true, after `createDistributable`.
 */
class SkyWingAutomationTest {
    @Before
    fun requireOptIn() {
        assumeTrue("Set SKYWING_TEST_AUTOMATION=true to run UI automation", System.getenv("SKYWING_TEST_AUTOMATION") == "true")
    }

    @Test
    fun navigatesFromFoldersToPlaybackSettingsAndBack() = SkyWingTestApp.launch("navigation").use { app ->
        assertTrue(app.find("Folders", TAB).selected, "SkyWing opens on Folders")
        app.find("Add", BUTTON)

        app.activate("Settings", TAB)
        app.waitUntil("Settings to be selected") { s -> ControlQuery.single(s.nodes, "Settings", TAB).selected }
        app.activate("Playback", BUTTON)
        app.find("Back to Settings", BUTTON)

        val switches = app.withScreen { s -> s.nodes.filter { it.roleName == CHECK_BOX } }
        assertEquals(PLAYBACK_SWITCHES, switches.map { it.label }.toSet(), "Playback switches: $switches")
        switches.forEach { switch ->
            assertTrue(switch.enabled && switch.focusable, "$switch can be reached and used")
            assertEquals(listOf("click"), switch.actions, "$switch has one click action")
            assertEquals(if (switch.checked) "1" else "0", switch.value, "$switch value matches its checked state")
            assertEquals("", switch.name, "$switch is labelled by description, so NVDA does not call it read only")
        }

        app.activate("Back to Settings", BUTTON)
        app.find("Appearance", BUTTON)
        app.activate("Folders", TAB)
        app.waitUntil("Folders to be selected") { s -> ControlQuery.single(s.nodes, "Folders", TAB).selected }
        app.find("Add", BUTTON)
        assertTrue(app.isAbsent("Back to Settings", BUTTON), "Playback settings are closed")
    }

    @Test
    fun playbackSwitchesToggleAndPersistOnlyInIsolatedPreferences() {
        val registryBefore = registry(PLAYBACK_KEY)
        val seeded = SkyWingTestApp.launch("switches") { root, _ ->
            root.node(PLAYBACK_NODE).apply { putBoolean(LOWER, false); putBoolean(VRR, false) }
        }
        // The data stays for a relaunch; delete it whatever happens, even when a step fails.
        try {
            try {
                seeded.openPlayback()
                seeded.toggle(LOWER_LABEL, to = true)
                seeded.toggle(VRR_LABEL, to = true)
                seeded.waitForPreference(LOWER, true)
                seeded.waitForPreference(VRR, true)
            } finally {
                seeded.close(keepData = true)
            }

            SkyWingTestApp.launch("switches-relaunch", dataDir = seeded.dataDir).use { app ->
                app.openPlayback()
                assertTrue(app.find(LOWER_LABEL, CHECK_BOX).checked, "the change survived a restart")
                assertTrue(app.find(VRR_LABEL, CHECK_BOX).checked, "the change survived a restart")
                app.toggle(LOWER_LABEL, to = false)
                app.toggle(VRR_LABEL, to = false)
                app.waitForPreference(LOWER, false)
                app.waitForPreference(VRR, false)
            }
            assertEquals(registryBefore, registry(PLAYBACK_KEY), "the user's registry preferences were not touched")
        } finally {
            seeded.close()
        }
    }

    @Test
    fun folderPickerCheckBoxesRefuseUnsafeActionsAndAddTheTickedFolder() {
        val registryBefore = registry(LOCAL_FOLDERS_KEY)
        lateinit var library: File
        SkyWingTestApp.launch("folder-picker") { root, dir ->
            library = dir.resolve("Library").toFile()
            File(library, "Movies").mkdirs()
            File(library, "Movies/clip.mp4").writeBytes(ByteArray(0))
            root.node(LOCAL_FOLDERS_NODE).apply { putInt("count", 1); put("folder.0", library.absolutePath) }
        }.use { app ->
            app.activate("Add", BUTTON)
            app.activate("Add drive folders", LABEL)
            // The menu item (focusable) and the dialog title (not focusable) share a label while the menu fades out.
            // Wait until the dialog is open and the menu item has gone, so nothing below can match the menu.
            app.waitUntil("the folder picker to open and the Add menu to close") { s ->
                val same = s.nodes.filter { ControlQuery.matches(it, "Add drive folders", LABEL) }
                same.size == 1 && !same[0].focusable && s.nodes.any { ControlQuery.matches(it, "Cancel", BUTTON) }
            }
            val dialog = app.find("Add drive folders", LABEL).parentPath
            val inDialog: (JabNode) -> Boolean = { it.path.startsWith("$dialog/") }

            // Two "Add" buttons now exist: the toolbar's, behind the dialog, and the dialog's. Neither is guessed.
            val ambiguous = assertFailsWith<AssertionError> { app.activate("Add", BUTTON) }
            assertTrue("Ambiguous: 2" in ambiguous.message!!, ambiguous.message)
            assertFalse(app.find("Add", BUTTON, where = inDialog).enabled, "Add waits for a ticked folder")
            val refused = assertFailsWith<AssertionError> { app.activate("Add", BUTTON, where = inDialog) }
            assertTrue("disabled" in refused.message!!, refused.message)
            assertTrue(app.find("0 folders chosen", LABEL).enabled)

            val root = library.absolutePath
            assertFalse(app.find(root, CHECK_BOX).checked)
            app.toggle(root, to = true)
            app.find("1 folder chosen", LABEL)
            assertTrue(app.find("Add", BUTTON, where = inDialog).enabled, "Add is enabled once a folder is ticked")

            app.toggle(SUBFOLDERS_LABEL, to = false)
            app.toggle(SUBFOLDERS_LABEL, to = true)

            app.activate("Add", BUTTON, where = inDialog)
            app.waitUntil("the folder picker to close") { s -> s.nodes.none { ControlQuery.matches(it, "Add drive folders", LABEL) } }
            app.find("Library", LABEL, where = { it.actions.isNotEmpty() })
            app.find("Add", BUTTON)
        }
        assertEquals(registryBefore, registry(LOCAL_FOLDERS_KEY), "the user's registry preferences were not touched")
    }

    /** B13: the Google Images browser (JCEF, about 150 MB) loads only when that tab of Change cover opens. */
    @Test
    fun theGoogleImagesBrowserIsNotLoadedAtStartOrOnOtherPages() = SkyWingTestApp.launch("no-browser").use { app ->
        app.find("Add", BUTTON)
        listOf("Videos", "Settings", "Folders").forEach { page ->
            app.activate(page, TAB)
            app.waitUntil("$page to be selected") { s -> ControlQuery.single(s.nodes, page, TAB).selected }
        }
        Thread.sleep(2_000)
        val modules = app.loadedModules()
        assertTrue("jvm.dll" in modules, "the module list is SkyWing's own: $modules")
        assertEquals(emptySet(), modules intersect BROWSER_DLLS, "browser DLLs loaded without the Google Images tab")
        val unpacked = app.dataDir.resolve("LocalAppData").resolve("SkyWing").resolve("browser").toFile()
        assertFalse(unpacked.exists(), "the browser was unpacked without the Google Images tab")
    }

    private fun SkyWingTestApp.openPlayback() {
        activate("Settings", TAB)
        activate("Playback", BUTTON)
        find("Back to Settings", BUTTON)
    }

    /** Toggles a check box once and waits for its new state. The action is never repeated. */
    private fun SkyWingTestApp.toggle(label: String, to: Boolean) {
        assertEquals(!to, find(label, CHECK_BOX).checked, "'$label' starts ${if (to) "unchecked" else "checked"}")
        activate(label, CHECK_BOX)
        waitUntil("'$label' to become ${if (to) "checked" else "unchecked"}") { s -> ControlQuery.single(s.nodes, label, CHECK_BOX).checked == to }
    }

    private fun SkyWingTestApp.waitForPreference(key: String, value: Boolean) =
        waitUntil("$key=$value in ${preferencesFile.fileName}") { preferences().node(PLAYBACK_NODE).get(key, null) == value.toString() }

    /** The user's real java.util.prefs registry key, as text, to prove tests leave it alone. */
    private fun registry(key: String): String {
        val process = ProcessBuilder("reg", "query", key, "/s").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return text.trim()
    }

    private companion object {
        const val TAB = "page tab"
        const val BUTTON = "push button"
        const val CHECK_BOX = "check box"
        const val LABEL = "label"
        val BROWSER_DLLS = setOf("jcef.dll", "libcef.dll", "chrome_elf.dll")

        const val PLAYBACK_NODE = "com/skywing/desktop/playback"
        const val LOCAL_FOLDERS_NODE = "com/skywing/desktop/local-library-folders"
        const val PLAYBACK_KEY = """HKCU\Software\JavaSoft\Prefs\com\skywing\desktop\playback"""
        const val LOCAL_FOLDERS_KEY = """HKCU\Software\JavaSoft\Prefs\com\skywing\desktop\local-library-folders"""
        const val LOWER = "motion.lowerAutomatically"
        const val VRR = "motion.vrr"
        const val LOWER_LABEL = "Lower automatically when needed"
        const val VRR_LABEL = "Let VRR control refresh (fullscreen)"
        const val SUBFOLDERS_LABEL = "Include sub folders"
        val PLAYBACK_SWITCHES = setOf("Motion smoothing", VRR_LABEL, LOWER_LABEL)
    }
}

/** The matching rule itself, without a window. */
class ControlQueryTest {
    private data class Control(override val name: String, override val description: String, override val roleName: String, override val enabled: Boolean = true) : ControlView

    private val controls = listOf(
        Control("Add", "", "push button"),
        Control("", "Motion smoothing", "check box"),
        Control("Motion smoothing", "", "label"),
        Control("Frame rate", "", "label"),
        Control("Frame rate", "", "label", enabled = false),
        Control("Seek", "Playback position", "label"),
    )

    @Test fun matchesNameAndRole() = assertEquals(controls[0], ControlQuery.single(controls, "Add", "push button"))

    @Test fun fallsBackToDescriptionOnlyWhenTheNameIsEmpty() {
        assertEquals(controls[1], ControlQuery.single(controls, "Motion smoothing", "check box"))
        assertFailsWith<AssertionError> { ControlQuery.single(controls, "Playback position", "label") }
    }

    @Test fun roleIsRequired() {
        assertEquals(controls[2], ControlQuery.single(controls, "Motion smoothing", "label"))
        val missing = assertFailsWith<AssertionError> { ControlQuery.single(controls, "Add", "check box") }
        assertTrue("No check box labelled 'Add'" in missing.message!!, missing.message)
    }

    @Test fun ambiguousMatchesAreRefusedUnlessNarrowed() {
        val error = assertFailsWith<AmbiguousMatchError> { ControlQuery.single(controls, "Frame rate", "label") }
        assertTrue("Ambiguous: 2" in error.message!!, error.message)
        assertEquals(controls[3], ControlQuery.single(controls, "Frame rate", "label") { it.enabled })
    }
}
