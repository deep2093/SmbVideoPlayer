package com.deepak.videolibrary.files

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.scan.GroupKey
import com.deepak.videolibrary.scan.WorkNotifications
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.thumbs.ThumbStore
import com.deepak.videolibrary.thumbs.ThumbnailWorker
import com.deepak.videolibrary.ui.isPlayable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Durable operations shared by every screen. No whole-share refresh after a single edit. */
class TransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val destination = inputData.getString("destination") ?: ""
        val operation = inputData.getString("operation") ?: return Result.failure()
        val directory = inputData.getBoolean("directory", false)
        val key = inputData.getString("namespace") ?: return Result.failure()
        val services = ServiceLocator.get(applicationContext)
        if (inputData.getString("libraryId")?.let { it != services.libraryId } == true) return Result.failure()
        val profile =
            services.settings.loadProfile()
                ?: return Result.failure(workDataOf("message" to "Save a usable SMB profile before starting a background transfer."))
        val currentKey = profile.host.trim().lowercase(java.util.Locale.ROOT) + "/" + profile.share.trim().lowercase(java.util.Locale.ROOT)
        if (key !=
            currentKey
        ) {
            return Result.failure(
                workDataOf(
                    "message" to "Server settings changed. Start this operation again on the intended server.",
                ),
            )
        }
        return try {
            // Do not silently run large copies as an ordinary time-limited worker.
            setForeground(
                WorkNotifications.info(applicationContext, 4, "${operation.lowercase().replaceFirstChar { it.titlecase() }} in progress"),
            )
            val state = services.db.libraryState()
            val context = currentCoroutineContext()
            services.scanRepository.scanMutex.withLock {
                withContext(Dispatchers.IO) {
                    SmbConnection(profile).use { conn ->
                        conn.open()
                        val operationId = id.toString()
                        var journal = state.journal(operationId)
                        if (journal != null) {
                            require(journal.operation == operation && journal.source == source && journal.destination == destination && journal.directory == directory)
                        }
                        if (journal?.phase == "DONE") return@withContext
                        if (journal == null) {
                            val remoteId = if (operation == "COPY") 0L else conn.identity(source)
                            journal = com.deepak.videolibrary.data.TransferJournal(operationId, operation, source, destination, directory, "PREPARED", remoteId)
                            state.putJournal(journal)
                        }
                        val entry = journal
                        var remoteDone = entry.phase == "REMOTE_DONE"
                        if (!remoteDone) {
                            when (operation) {
                                "COPY" -> {
                                    if (entry.phase == "READY" && conn.exists(destination)) {
                                        require(conn.identity(destination) == entry.remoteIdentity) { "Destination already exists" }
                                    } else {
                                        var bytes = 0L
                                        var last = 0L
                                        conn.copyPath(source, destination, directory, progress = { n ->
                                            bytes += n
                                            val now = android.os.SystemClock.elapsedRealtime()
                                            if (now - last > 500) {
                                                setProgressAsync(workDataOf("bytes" to bytes, "message" to "Copying")); last = now
                                            }
                                        }, cancelled = { isStopped || !context.isActive }, stagingId = operationId, onReady = { remoteId ->
                                            kotlinx.coroutines.runBlocking { state.putJournal(entry.copy(phase = "READY", remoteIdentity = remoteId)) }
                                        })
                                    }
                                }
                                "MOVE" -> {
                                    if (conn.exists(source)) {
                                        require(conn.identity(source) == entry.remoteIdentity) { "Source changed since operation started" }
                                        conn.renamePath(source, destination, directory)
                                    } else {
                                        require(conn.exists(destination) && conn.identity(destination) == entry.remoteIdentity) { "Could not reconcile moved file" }
                                    }
                                }
                                "DELETE" -> if (conn.exists(source)) {
                                    require(conn.identity(source) == entry.remoteIdentity) { "Source changed since operation started" }
                                    conn.deletePath(source, directory)
                                }
                                else -> error("Unknown file operation")
                            }
                            state.journalPhase(operationId, "REMOTE_DONE")
                            remoteDone = true
                        }
                        if (remoteDone) {
                            when (operation) {
                                "COPY" -> {
                                    // Repeatable indexing: published destination belongs to this operation.
                                    val paths = ArrayDeque<Pair<String, Int>>()
                                    if (directory) paths.add(destination to 0) else paths.add(destination.substringBeforeLast('/', "") to 0)
                                    while (paths.isNotEmpty()) {
                                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        val (parent, depth) = paths.removeFirst()
                                        require(depth <= 128) { "Maximum folder depth exceeded" }
                                        for (entryFile in conn.list(parent)) {
                                            val path = listOf(parent, entryFile.name).filter(String::isNotBlank).joinToString("/")
                                            if (!directory && path != destination) continue
                                            if (entryFile.isReparsePoint) continue
                                            if (entryFile.isDirectory) {
                                                if (!entryFile.name.startsWith('.')) paths.add(path to depth + 1)
                                                continue
                                            }
                                            if (!isPlayable(entryFile) || services.db.dao().findVideo(path) != null) continue
                                            services.db.dao().insertVideo(VideoEntity(relativePath = path, name = entryFile.name, parentDir = parent,
                                                size = entryFile.size, modified = entryFile.modified, groupKey = GroupKey.fromFileName(entryFile.name),
                                                lastSeenSession = 0, addedAt = System.currentTimeMillis()))
                                        }
                                    }
                                    state.journalPhase(operationId, "DONE")
                                    ThumbnailWorker.enqueue(applicationContext)
                                }
                                "MOVE" -> services.db.withTransaction {
                                    for (video in state.under(source)) {
                                        val path = destination + video.relativePath.removePrefix(source)
                                        val name = path.substringAfterLast('/')
                                        state.moveVideo(video.id, path, path.substringBeforeLast('/', ""), name, GroupKey.fromFileName(name))
                                    }
                                    state.moveProgress(key, source, destination)
                                    state.moveHidden(key, source, destination)
                                    state.moveSourceFolders(key, source, destination)
                                    state.journalPhase(operationId, "DONE")
                                }
                                "DELETE" -> {
                                    val rows = state.under(source)
                                    services.db.withTransaction {
                                        state.deletePath(source); state.deleteProgress(key, source); state.deleteHidden(key, source)
                                        state.journalPhase(operationId, "DONE")
                                    }
                                    rows.forEach { ThumbStore.thumbFile(services.artworkContext, it.id).delete() }
                                }
                            }
                        }
                    }
                }
            }
            Result.success(
                workDataOf(
                    "finishedAt" to System.currentTimeMillis(),
                    "message" to "${operation.lowercase().replaceFirstChar { it.titlecase() }} completed",
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (SmbErrors.isTransient(e) && runAttemptCount < 5) return Result.retry()
            val message = when (e) {
                is IllegalArgumentException -> when (e.message) {
                    "Destination already exists" -> "Destination already exists; choose another folder or name."
                    "Cannot copy a folder into itself" -> "Cannot copy a folder into itself."
                    else -> "Invalid source or destination."
                }
                else -> SmbErrors.describe(e)
            }
            Result.failure(workDataOf("finishedAt" to System.currentTimeMillis(), "message" to message))
        }
    }
    companion object {
        const val UNIQUE_NAME = "file_transfers"
        fun enqueue(context: Context, operation: String, source: String, destination: String, directory: Boolean, key: String) {
            val request = OneTimeWorkRequestBuilder<TransferWorker>()
                .setInputData(
                    workDataOf(
                        "operation" to operation,
                        "libraryId" to ServiceLocator.get(context).libraryId,
                        "source" to source,
                        "destination" to destination,
                        "directory" to directory,
                        "namespace" to key,
                    ),
                ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
