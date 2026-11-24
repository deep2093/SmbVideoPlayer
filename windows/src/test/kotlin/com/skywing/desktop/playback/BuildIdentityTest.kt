package com.skywing.desktop.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuildIdentityTest {
    private val frameSharingBuild = """
        version=0.1.120
        commit=3bf3d33
        localChanges=false
        runtime=frame-sharing
        watchdogFix=included
    """.trimIndent()

    @Test fun reportLineNamesTheCommitRuntimeAndWatchdogFix() {
        assertEquals(
            "Build: commit 3bf3d33; runtime frame-sharing, sharing on; watchdog fix ed072bf included",
            BuildIdentity.parse(frameSharingBuild).reportLine(shareFrames = null),
        )
    }

    @Test fun reportLineShowsWhenFrameSharingIsSwitchedOff() {
        // The frame-sharing libmpv shares its output planes only while SKYWING_VS_SHARE_FRAMES is unset or 1.
        val identity = BuildIdentity.parse(frameSharingBuild)
        assertEquals(
            "Build: commit 3bf3d33; runtime frame-sharing, sharing on; watchdog fix ed072bf included",
            identity.reportLine(shareFrames = "1"),
        )
        assertEquals(
            "Build: commit 3bf3d33; runtime frame-sharing, sharing off (SKYWING_VS_SHARE_FRAMES=0); watchdog fix ed072bf included",
            identity.reportLine(shareFrames = "0"),
        )
    }

    @Test fun reportLineMarksLocalChangesAndTheStockRuntime() {
        val text = frameSharingBuild.replace("localChanges=false", "localChanges=true")
            .replace("runtime=frame-sharing", "runtime=stock").replace("watchdogFix=included", "watchdogFix=missing")
        // Stock libmpv has no frame sharing, so the switch does not apply to it.
        assertEquals(
            "Build: commit 3bf3d33 with local changes; runtime stock; watchdog fix ed072bf missing",
            BuildIdentity.parse(text).reportLine(shareFrames = "0"),
        )
    }

    @Test fun missingValuesAreReportedAsUnknown() {
        val identity = BuildIdentity.parse("")
        assertEquals("development", identity.version)
        assertEquals("Build: commit unknown; runtime unknown; watchdog fix ed072bf unknown", identity.reportLine(shareFrames = null))
    }

    @Test fun theBuildWritesTheIdentityThisAppReads() {
        // generateBuildInfo in build.gradle.kts writes skywing-build.properties into the app's resources.
        val text = assertNotNull(javaClass.getResourceAsStream("/skywing-build.properties"), "skywing-build.properties is missing")
            .use { it.readBytes().decodeToString() }
        val identity = BuildIdentity.parse(text)
        assertTrue(Regex("""0\.1\.\d+""").matches(identity.version), identity.version)
        assertTrue(Regex("""[0-9a-f]{7}""").matches(identity.commit) || identity.commit == "unknown", identity.commit)
        assertTrue(identity.runtime in setOf("frame-sharing", "stock", "none"), identity.runtime)
        assertTrue(identity.watchdogFix in setOf("included", "missing", "unknown"), identity.watchdogFix)
        assertEquals(identity, BuildIdentity.current)
    }
}
