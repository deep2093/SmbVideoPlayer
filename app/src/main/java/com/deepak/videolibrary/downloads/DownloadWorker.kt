package com.deepak.videolibrary.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.settings.LibraryIdentity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream

/**
 * Copies queued files from the server into the device's Download/Veyra folder, keeping folder structure.
 * One file at a time by default, up to four at once when the user chooses (each over its own connection); the rest
 * wait in the queue. Progress is saved as it goes, so the Downloads screen shows a live bar and an interrupted file
 * continues where it stopped. Nothing on the server is changed.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val dao = DownloadStore.dao(context)

    override suspend fun doWork(): Result {
        val services = ServiceLocator.get(applicationContext)
        val saved = services.settings.load()
        if (saved.host.isBlank() || saved.share.isBlank()) return Result.success()
        // Files of another saved server wait until that server is selected again.
        if (LibraryIdentity.of(saved) != services.libraryId) return Result.retry()
        val libraryId = services.libraryId
        dao.requeueInterrupted(libraryId)
        if (dao.queuedCount(libraryId) == 0) return Result.success()

        runCatching { setForeground(foreground("Preparing downloads", 0, 0)) }
        // Use the WireGuard tunnel when the server is only reachable through it (same rules as the app's own
        // automatic connect), and keep it up until the downloads finish.
        val vpn = com.deepak.videolibrary.vpn.WireGuardManager.get(applicationContext)
        try {
            vpn.autoConnect(respectBackoff = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Carry on: the server may be reachable directly.
        }
        val hold = vpn.beginPlayback()
        val profile = services.settings.toProfile(saved)
        val running = java.util.concurrent.ConcurrentHashMap<Long, LongArray>()
        var retryLater = false
        try {
            kotlinx.coroutines.coroutineScope {
                val finished = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                val jobs = HashMap<Long, kotlinx.coroutines.Job>()
                var lastNotice = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    jobs.entries.removeAll { !it.value.isActive }
                    // The number of files at a time is read each round, so a new setting applies to the next file.
                    val limit = DownloadPrefs.parallel(applicationContext)
                    while (!retryLater && jobs.size < limit) {
                        val item = dao.nextQueued(libraryId) ?: break
                        if (dao.markRunning(item.id) == 0) continue
                        running[item.id] = longArrayOf(item.bytesDone, item.size)
                        jobs[item.id] = launch {
                            try {
                                if (runOne(profile, item, running) == Outcome.RETRY_LATER) {
                                    retryLater = true
                                }
                            } finally {
                                running.remove(item.id)
                                finished.trySend(Unit)
                            }
                        }
                    }
                    if (jobs.isEmpty()) break
                    val now = System.currentTimeMillis()
                    if (now - lastNotice >= 1_000) {
                        val totals = running.values.toList()
                        val done = totals.sumOf { it[0] }
                        val size = totals.sumOf { it[1] }
                        val title = if (jobs.size == 1) dao.get(jobs.keys.first())?.name ?: "1 file" else "${jobs.size} files"
                        runCatching { setForeground(foreground(title, done, size)) }
                        lastNotice = now
                    }
                    // Wake when a file finishes, or after a moment to pick up new files or a changed setting.
                    kotlinx.coroutines.withTimeoutOrNull(1_000) { finished.receive() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return if (SmbErrors.isTransient(e) && runAttemptCount < 20) Result.retry() else Result.failure()
        } finally {
            hold.release()
        }
        return if (retryLater) Result.retry() else Result.success()
    }

    private enum class Outcome { DONE, RETRY_LATER }

    /** Copies one file over its own connection, recording the result on the item. */
    private suspend fun runOne(profile: com.deepak.videolibrary.smb.SmbProfile, item: DownloadItem, running: MutableMap<Long, LongArray>): Outcome {
        val connection = SmbConnection(profile)
        try {
            withContext(Dispatchers.IO) { connection.open() }
        } catch (e: Exception) {
            // Could not reach the server at all: the file waits in the queue.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { connection.close() }
                if (dao.status(item.id) == DownloadItem.RUNNING) dao.setStatus(item.id, DownloadItem.QUEUED, null)
            }
            if (e is CancellationException) throw e
            if (SmbErrors.isTransient(e) && runAttemptCount < MAX_ATTEMPTS) return Outcome.RETRY_LATER
            throw e
        }
        try {
            copy(connection, item) { done, size -> running[item.id] = longArrayOf(done, size) }
        } catch (e: CancellationException) {
            // The whole run was stopped (system or user): leave the item to continue next time.
            withContext(kotlinx.coroutines.NonCancellable) {
                if (dao.status(item.id) == DownloadItem.RUNNING) dao.setStatus(item.id, DownloadItem.QUEUED, null)
            }
            throw e
        } catch (e: StoppedByUser) {
            discard(dao.get(item.id)?.uri)
        } catch (e: DeviceProblem) {
            // Problems on this device (full, not allowed) are not fixed by retrying.
            dao.setStatus(item.id, DownloadItem.FAILED, e.message)
            discard(dao.get(item.id)?.uri)
        } catch (e: Exception) {
            if (SmbErrors.isTransient(e) && runAttemptCount < MAX_ATTEMPTS) {
                // Network dropped: try again later from where it stopped.
                dao.setStatus(item.id, DownloadItem.QUEUED, null)
                return Outcome.RETRY_LATER
            }
            dao.setStatus(item.id, DownloadItem.FAILED, describe(e))
            discard(dao.get(item.id)?.uri)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { runCatching { connection.close() } }
        }
        return Outcome.DONE
    }

    private class StoppedByUser : Exception()

    /** A problem on the device side (not the network), with a message ready to show. */
    private class DeviceProblem(message: String) : Exception(message)

    private fun describe(e: Exception): String = when (e) {
        is SecurityException -> "Android did not allow saving to the Download folder."
        else -> SmbErrors.describe(e)
    }

    /** Runs a device-side step, turning its failures into [DeviceProblem] so they are not retried as network errors. */
    private inline fun <T> onDevice(message: String, block: () -> T): T = try {
        block()
    } catch (e: SecurityException) {
        throw DeviceProblem("Android did not allow saving to the Download folder.")
    } catch (e: IOException) {
        throw DeviceProblem(message)
    } catch (e: IllegalStateException) {
        throw DeviceProblem(message)
    }

    private suspend fun copy(connection: SmbConnection, item: DownloadItem, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val resolver = applicationContext.contentResolver
        connection.openFile(item.sourcePath).use { file ->
            val size = file.fileInformation.standardInformation.endOfFile
            // Continue an interrupted file when the device copy matches what was saved; otherwise start over.
            var uri: Uri? = item.uri?.let(Uri::parse)
            var start = 0L
            if (uri != null) {
                val existing = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull()
                if (existing != null && existing == item.bytesDone && existing <= size) {
                    start = existing
                } else {
                    discard(item.uri)
                    uri = null
                }
            }
            if (size - start > freeBytes()) throw DeviceProblem("Not enough free space on this device.")
            if (uri == null) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, DownloadPaths.cleanSegment(item.name))
                    put(MediaStore.MediaColumns.MIME_TYPE, DownloadPaths.mimeType(item.name))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DownloadPaths.relativePath(item.targetFolder))
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                uri = onDevice("Could not create the file in the Download folder.") {
                    resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                } ?: throw DeviceProblem("Could not create the file in the Download folder.")
            }
            dao.setTarget(item.id, size, uri.toString())
            dao.setProgress(item.id, start)
            val target: Uri = uri
            val out: OutputStream = onDevice("Could not open the file in the Download folder.") {
                resolver.openOutputStream(target, if (start > 0) "wa" else "w")
            } ?: throw DeviceProblem("Could not open the file in the Download folder.")
            out.use { output ->
                val buffer = ByteArray(CHUNK)
                var position = start
                var lastSave = System.currentTimeMillis()
                while (position < size) {
                    currentCoroutineContext().ensureActive()
                    val n = file.read(buffer, position, 0, minOf(CHUNK.toLong(), size - position).toInt())
                    if (n <= 0) break
                    onDevice("Could not write to this device. It may be full.") { output.write(buffer, 0, n) }
                    position += n
                    val now = System.currentTimeMillis()
                    if (now - lastSave >= SAVE_EVERY_MS) {
                        onDevice("Could not write to this device. It may be full.") { output.flush() }
                        dao.setProgress(item.id, position)
                        onProgress(position, size)
                        lastSave = now
                        // Cancelled from the Downloads screen?
                        if (dao.status(item.id) != DownloadItem.RUNNING) throw StoppedByUser()
                    }
                }
                onDevice("Could not write to this device. It may be full.") { output.flush() }
                if (position < size) throw IOException("The file ended early on the server")
                dao.setProgress(item.id, position)
            }
            // Visible to other apps only once complete.
            resolver.update(target, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            dao.setStatus(item.id, DownloadItem.DONE, null)
        }
    }

    /** Deletes an unfinished device copy. */
    private fun discard(uri: String?) {
        if (uri == null) return
        runCatching { applicationContext.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    private fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private fun foreground(title: String, done: Long, total: Long): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        private const val UNIQUE = "downloads"
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 7
        private const val CHUNK = 256 * 1024
        private const val SAVE_EVERY_MS = 400L
        private const val MAX_ATTEMPTS = 20

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            // A run already going picks up new files itself; this one follows it in case it was finishing.
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
