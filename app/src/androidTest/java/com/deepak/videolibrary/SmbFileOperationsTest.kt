package com.deepak.videolibrary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbProfile
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.InterruptedIOException
import java.security.MessageDigest

/** Opt-in only: this host/share is created on the disposable CI runner. */
@RunWith(AndroidJUnit4::class)
class SmbFileOperationsTest {
    @Test fun copyRenameDeleteAndCancelOnDisposableShare() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smbFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Persist only the disposable fixture credentials, so the subsequent UI worker
        // also exercises normal Keystore-backed authentication after a process restart.
        SettingsStore(
            context,
        ).save(
            SmbSettings(host = "10.0.2.2", share = "fixtures", username = "vlfixture", password = "ci-fixture-only", savePassword = true),
        )
        SmbConnection(SmbProfile("10.0.2.2", "fixtures", "", "vlfixture", "ci-fixture-only")).use { conn ->
            conn.open()
            val source = "Movies/Tom Cruise - Fixture.mp4"
            val copy = "Destination/Copied.mp4"
            val renamed = "Destination/Renamed.mp4"
            fun digest(path: String): ByteArray = conn.openFile(path).use { f ->
                f.inputStream.use { stream ->
                    val digest = MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(65536)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0)break
                        digest.update(buffer, 0, n)
                    }
                    digest.digest()
                }
            }
            val original = digest(source)
            var copiedBytes = 0L
            conn.copyPath(source, copy, false, progress = { copiedBytes += it })
            assertEquals(2097152L, copiedBytes)
            assertArrayEquals(original, digest(copy))
            try {
                conn.copyPath(source, copy, false)
                fail("Must not overwrite an existing destination")
            } catch (
                _: IllegalArgumentException,
            ) {
            }
            conn.renamePath(copy, renamed)
            assertArrayEquals(original, digest(renamed))
            conn.deletePath(renamed, false)
            assertTrue(conn.list("Destination").isEmpty())
            var cancel = false
            try {
                conn.copyPath(source, "Destination/Cancelled.mp4", false, progress = { cancel = true }, cancelled = { cancel })
                fail("Copy must cancel")
            } catch (
                _: InterruptedIOException,
            ) {
            }
            assertTrue("No final or staging file after cancellation", conn.list("Destination").isEmpty())
            conn.copyPath("Movies", "Destination/Folder", true)
            assertArrayEquals(original, digest("Destination/Folder/Tom Cruise - Fixture.mp4"))
            conn.renamePath("Destination/Folder", "Destination/MovedFolder", true)
            conn.deletePath("Destination/MovedFolder", true)
            assertTrue(conn.list("Destination").isEmpty())
            assertArrayEquals(original, digest(source))
        }
    }
}
