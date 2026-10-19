package com.deepak.videolibrary

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.scan.*
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Separate in-memory index: never touches the upgrade/UI test's saved library. */
@RunWith(AndroidJUnit4::class)
class ScanResumeTest {
    @Test fun periodicScanResumesPendingQueueInsteadOfReplacingSession() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smbFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).build()
        try {
            val dao = db.dao()
            val id = dao.insertSession(ScanSessionEntity(mode = "REFRESH", status = ScanStatus.INTERRUPTED, startedAt = 1))
            dao.insertPending(listOf(PendingDirEntity("Movies")))
            val repo = ScanRepository(db)
            val profile = SmbProfile("10.0.2.2", "fixtures", "Movies", "vlfixture", "ci-fixture-only")
            assertTrue(repo.runPeriodic(profile))
            assertEquals(id, dao.latestSession()!!.id)
            assertEquals(ScanStatus.COMPLETED, dao.latestSession()!!.status)
            assertEquals(0, dao.pendingCountNow())
            assertNotNull(dao.findVideo("Movies/Tom Cruise - Fixture.mp4"))
            assertFalse(repo.run(ScanMode.RESUME, profile))
            assertFalse(repo.run(ScanMode.RETRY, profile))
        } finally {
            db.close()
        }
    }
}
