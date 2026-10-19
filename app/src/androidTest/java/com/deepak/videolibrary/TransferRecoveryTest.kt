package com.deepak.videolibrary

import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.deepak.videolibrary.data.*
import com.deepak.videolibrary.files.TransferWorker
import com.deepak.videolibrary.settings.SmbSettings
import com.deepak.videolibrary.smb.SmbConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Recreate the worker after remote publication but before its Room transaction. */
class TransferRecoveryTest {
    @Test fun moveRecoveryPreservesAssignmentAndDoesNotRenameAgain() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("smbFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ServiceLocator.get(context).settings.save(SmbSettings(host="10.0.2.2",share="fixtures",username="vlfixture",password="ci-fixture-only",savePassword=true))
        val services = ServiceLocator.get(context)
        val state = services.db.libraryState()
        val id = UUID.randomUUID()
        val source = "Destination/Recovery-source.mp4"
        val destination = "Destination/Recovery-destination.mp4"
        val profile = services.settings.toProfile(services.settings.load())
        withContext(Dispatchers.IO) {
            SmbConnection(profile).use { conn ->
                conn.open()
                try {
                    conn.copyPath("Movies/Tom Cruise - Fixture.mp4",source,false)
                    val remoteId = conn.identity(source)
                    services.db.dao().insertVideo(VideoEntity(relativePath=source,name="Recovery-source.mp4",parentDir="Destination",size=2097152,modified=1,groupKey="A",customGroup="Recovery group",lastSeenSession=0,addedAt=1))
                    state.putProgress(PlaybackStateEntity("10.0.2.2/fixtures",source,30000,120000))
                    state.putJournal(TransferJournal(id.toString(),"MOVE",source,destination,false,"PREPARED",remoteId))
                    conn.renamePath(source,destination)
                    val data = workDataOf("operation" to "MOVE","source" to source,"destination" to destination,"directory" to false,"namespace" to "10.0.2.2/fixtures","libraryId" to services.libraryId)
                    suspend fun runWorker() = TestListenableWorkerBuilder<TransferWorker>(context).setId(id).setInputData(data).build().doWork()
                    assertTrue(runWorker() is ListenableWorker.Result.Success)
                    assertNull(services.db.dao().findVideo(source))
                    assertEquals("Recovery group",services.db.dao().findVideo(destination)!!.customGroup)
                    assertEquals(30000L,state.progress("10.0.2.2/fixtures",destination)!!.positionMs)
                    assertEquals("DONE",state.journal(id.toString())!!.phase)
                    assertTrue(runWorker() is ListenableWorker.Result.Success)
                    assertEquals(remoteId,conn.identity(destination))
                } finally {
                    if(conn.exists(source))conn.deletePath(source,false)
                    if(conn.exists(destination))conn.deletePath(destination,false)
                    state.deletePath(source);state.deletePath(destination)
                    state.deleteProgress("10.0.2.2/fixtures",destination)
                }
            }
        }
    }
}
