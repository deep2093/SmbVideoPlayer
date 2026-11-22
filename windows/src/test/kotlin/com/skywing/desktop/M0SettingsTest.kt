package com.skywing.desktop

import com.skywing.desktop.automation.M0Settings
import com.skywing.desktop.playback.MotionQuality
import com.skywing.testsupport.IsolatedPreferencesFactory

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The M0 harness ([com.skywing.desktop.automation.M0Run]) refuses a setup that would break the M0 rules before
 * SkyWing starts: results never go into the repository or OneDrive, and the clip is never on a network drive.
 * No window is opened; the files are temporary.
 */
class M0SettingsTest {
    private val temp: Path = Files.createTempDirectory("skywing m0 settings ")
    private val image: Path = temp.resolve("SkyWing").createDirectories().also { it.resolve("SkyWing.exe").createFile() }
    private val clip: Path = temp.resolve("clip.mp4").createFile()

    private fun environment(vararg changes: Pair<String, String?>): Map<String, String> {
        val values = mutableMapOf(
            "SKYWING_TEST_IMAGE" to image.toString(),
            "SKYWING_M0_CLIP" to clip.toString(),
            "SKYWING_M0_OUT" to temp.resolve("results").toString(),
            "SKYWING_M0_LABEL" to "laptop-run-a",
            "SKYWING_M0_VRR" to "true",
            "SKYWING_M0_POWER" to "ac",
        )
        for ((name, value) in changes) if (value == null) values.remove(name) else values[name] = value
        return values
    }

    private fun refusal(vararg changes: Pair<String, String?>): String =
        assertFailsWith<IllegalArgumentException> { M0Settings.fromEnvironment(environment(*changes)) }.message.orEmpty()

    @AfterTest
    fun deleteTemporaryFiles() {
        temp.toFile().deleteRecursively()
    }

    @Test
    fun aLocalSetupIsAcceptedWithTheTimesOfSection2() {
        val settings = M0Settings.fromEnvironment(environment())
        assertEquals(clip, settings.clip)
        assertEquals(60, settings.warmupSeconds)
        assertEquals(600, settings.windowSeconds)
        assertEquals(true, settings.vrr)
        assertTrue(settings.onAc)
        assertEquals(MotionQuality.BALANCED, settings.quality)
    }

    @Test
    fun aRunCanAskForAnotherQualityByItsLabelOrName() {
        assertEquals(MotionQuality.FAST, M0Settings.fromEnvironment(environment("SKYWING_M0_QUALITY" to "Fast")).quality)
        assertEquals(MotionQuality.FAST, M0Settings.fromEnvironment(environment("SKYWING_M0_QUALITY" to "FAST")).quality)
        assertEquals(MotionQuality.BALANCED, M0Settings.fromEnvironment(environment("SKYWING_M0_QUALITY" to "balanced")).quality)
    }

    @Test
    fun vrrAutoLeavesTheChoiceToSkyWing() {
        assertNull(M0Settings.fromEnvironment(environment("SKYWING_M0_VRR" to "auto")).vrr)
        assertEquals(false, M0Settings.fromEnvironment(environment("SKYWING_M0_VRR" to "false")).vrr)
    }

    @Test
    fun theSeedGivesEveryVideoClassTheRunsQualityAndWritesVrrOnlyWhenChosen() {
        fun seeded(vararg changes: Pair<String, String?>) =
            IsolatedPreferencesFactory.openUserRoot(temp.resolve("preferences ${changes.size}.properties")).also { root ->
                M0Settings.fromEnvironment(environment(*changes)).seed(root)
            }.node("com/skywing/desktop/playback")

        val fast = seeded("SKYWING_M0_QUALITY" to "Fast", "SKYWING_M0_VRR" to "auto")
        assertEquals("MOTION", fast.get("smoothing", null))
        for (key in listOf("1080-sdr", "1080-hdr", "1440-sdr", "1440-hdr", "4k-sdr", "4k-hdr")) {
            assertEquals("FAST", fast.get("motion.$key.quality", null), key)
            assertEquals(0, fast.getInt("motion.$key.stop", -1), key)
        }
        assertNull(fast.get("motion.vrr", null), "auto leaves SkyWing's own VRR detection in charge")
        assertEquals("false", fast.get("motion.lowerAutomatically", null))
        assertEquals("true", seeded().get("motion.vrr", null))
    }

    @Test
    fun aClipOnANetworkShareIsRefusedWithoutOpeningIt() {
        assertContains(refusal("SKYWING_M0_CLIP" to """\\nas.invalid\videos\clip.mp4"""), "network")
    }

    @Test
    fun resultsInsideTheCheckoutOrOneDriveAreRefused() {
        assertContains(refusal("SKYWING_M0_OUT" to Path.of("build", "m0-results").toAbsolutePath().toString()), "checkout")
        val oneDrive = temp.resolve("OneDrive")
        assertContains(refusal("OneDrive" to oneDrive.toString(), "SKYWING_M0_OUT" to oneDrive.resolve("m0").toString()), "OneDrive")
    }

    @Test
    fun missingOrInvalidSettingsAreRefused() {
        assertContains(refusal("SKYWING_TEST_IMAGE" to null), "SKYWING_TEST_IMAGE")
        assertContains(refusal("SKYWING_VS_SHARE_FRAMES" to "0"), "SKYWING_VS_SHARE_FRAMES")
        assertContains(refusal("SKYWING_M0_LABEL" to "run a"), "SKYWING_M0_LABEL")
        assertContains(refusal("SKYWING_M0_VRR" to "yes"), "SKYWING_M0_VRR")
        assertContains(refusal("SKYWING_M0_QUALITY" to "quick"), "SKYWING_M0_QUALITY")
        assertContains(refusal("SKYWING_M0_POWER" to "plugged"), "SKYWING_M0_POWER")
        assertContains(refusal("SKYWING_M0_WINDOW" to "ten"), "SKYWING_M0_WINDOW")
    }
}
