package com.skywing.desktop

import com.skywing.testsupport.IsolatedPreferencesFactory
import java.io.File
import java.util.prefs.Preferences
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * App code that runs inside the test JVM must never see the user's SkyWing data. build.gradle.kts gives the test JVM
 * file-backed preferences and its own LOCALAPPDATA and APPDATA; this fails if that set-up is lost.
 */
class TestIsolationTest {
    @Test fun preferencesAreFileBackedNotTheRegistry() {
        val root = Preferences.userRoot()
        assertTrue(root.javaClass.name.startsWith(IsolatedPreferencesFactory::class.java.name),
            "Tests would use the real registry preferences: ${root.javaClass.name}")
    }

    @Test fun localAndRoamingAppDataAreRedirected() {
        val real = System.getProperty("skywing.test.realLocalAppData").orEmpty()
        val local = System.getenv("LOCALAPPDATA").orEmpty()
        assertTrue(local.isNotBlank() && File(local).canonicalPath.contains("test-user-data"), "LOCALAPPDATA is not redirected: $local")
        assertNotEquals(File(real).canonicalPath, File(local).canonicalPath)
        assertTrue(System.getenv("APPDATA").orEmpty().contains("test-user-data"), "APPDATA is not redirected")
    }
}
