package com.deepak.videolibrary.scan

import androidx.room.withTransaction
import com.deepak.videolibrary.data.LibraryDatabase
import com.deepak.videolibrary.data.PendingDirEntity
import com.deepak.videolibrary.data.ScanErrorEntity
import com.deepak.videolibrary.data.ScanSessionEntity
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ScanMode { INITIAL, REFRESH, RESUME, RETRY }

object ScanStatus {
    const val RUNNING = "RUNNING"
    const val INTERRUPTED = "INTERRUPTED"
    const val FAILED = "FAILED"
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
}

private val VIDEO_EXTENSIONS = setOf(
    "mkv",
    "mp4",
    "m4v",
    "avi",
    "mov",
    "wmv",
    "asf",
    "flv",
    "webm",
    "ts",
    "m2ts",
    "mts",
    "mpg",
    "mpeg",
    "3gp",
    "ogv",
    "vob",
    "divx",
)

private val SKIPPED_NAMES = setOf("\$RECYCLE.BIN", "System Volume Information")

private fun isVideo(name: String): Boolean {
    val dot = name.lastIndexOf('.')
    return dot > 0 && name.substring(dot + 1).lowercase() in VIDEO_EXTENSIONS
}

/** Skips dot-files (including macOS "._" resource forks) and system folders. */
private fun isSkipped(name: String): Boolean =
    name.startsWith(".") || name.isBlank() || '/' in name || '\\' in name || SKIPPED_NAMES.any { it.equals(name, ignoreCase = true) }

/**
 * Prepares scan sessions and runs them. The directory queue lives in the database, so a scan that
 * is killed at any moment can continue from where it stopped.
 */
class ScanRepository(private val db: LibraryDatabase, private val owners: android.content.SharedPreferences? = null) {
    val scanMutex = kotlinx.coroutines.sync.Mutex()

    private val dao = db.dao()

    /**
     * Runs one scan. Returns true if the scan walked the whole queue, false if it could not start
     * or had to stop early (it can be resumed). Cancellation marks the session INTERRUPTED and rethrows.
     */
    suspend fun run(mode: ScanMode, profile: SmbProfile, owner: String? = null): Boolean = scanMutex.withLock {
        dao.markRunningInterrupted()
        val latest=dao.latestSession()
        val sameRequest=owner!=null && owners?.getLong(owner,-1L)==latest?.id
        if(sameRequest && latest?.status in setOf(ScanStatus.COMPLETED,ScanStatus.COMPLETED_WITH_ERRORS)) return@withLock true
        val effective=if(sameRequest && mode in setOf(ScanMode.INITIAL,ScanMode.REFRESH) && dao.pendingCountNow()>0) ScanMode.RESUME else mode
        val session=prepare(effective,profile) ?: return@withLock false
        if(owner!=null && owners!=null) withContext(Dispatchers.IO) {
            val editor=owners.edit().putLong(owner,session.id)
            owners.all.entries.sortedBy { (it.value as? Long) ?: 0L }.dropLast(63).forEach { if(it.key!=owner)editor.remove(it.key) }
            check(editor.commit()) { "Could not persist scan request" }
        }
        ScanEngine(db,profile,session).run()
    }

    suspend fun runPeriodic(profile: SmbProfile): Boolean = scanMutex.withLock {
        dao.markRunningInterrupted()
        val mode = if (dao.latestResumableSession() != null && dao.pendingCountNow() > 0) ScanMode.RESUME else ScanMode.REFRESH
        val session = prepare(mode, profile) ?: return@withLock false
        ScanEngine(db, profile, session).run()
    }

    private suspend fun prepare(mode: ScanMode, profile: SmbProfile): ScanSessionEntity? {
        val now = System.currentTimeMillis()
        return when (mode) {
            ScanMode.INITIAL, ScanMode.REFRESH -> db.withTransaction {
                dao.clearPending()
                val id = dao.insertSession(
                    ScanSessionEntity(mode = mode.name, status = ScanStatus.RUNNING, startedAt = now),
                )
                val key = profile.host.trim().lowercase(java.util.Locale.ROOT) + "/" + profile.share.trim().lowercase(java.util.Locale.ROOT)
                val roots = (listOf(profile.rootPath) + db.libraryState().savedCollections(key).mapNotNull { it.sourcePath }).distinct()
                val minimalRoots = roots.filter { candidate -> roots.none { other -> other != candidate && (other.isEmpty() || candidate.startsWith("$other/")) } }
                dao.insertPending(minimalRoots.map { PendingDirEntity(it) })
                dao.getSession(id)
            }

            ScanMode.RESUME -> db.withTransaction {
                val s = dao.latestResumableSession() ?: return@withTransaction null
                if (dao.pendingCountNow() == 0) return@withTransaction null
                dao.setSessionStatus(s.id, ScanStatus.RUNNING, null)
                dao.getSession(s.id)
            }

            ScanMode.RETRY -> db.withTransaction {
                val s = dao.latestSession() ?: return@withTransaction null
                val dirs = dao.errorDirPaths(s.id)
                if (dirs.isEmpty()) return@withTransaction null
                dao.clearDirErrors(s.id)
                dao.insertPending(dirs.map { PendingDirEntity(it) })
                dao.setSessionStatus(s.id, ScanStatus.RUNNING, null)
                dao.refreshCounters(s.id)
                dao.getSession(s.id)
            }
        }
    }
}

class ScanEngine(
    private val db: LibraryDatabase,
    private val profile: SmbProfile,
    private val session: ScanSessionEntity,
) {
    private val dao = db.dao()
    private val sid = session.id

    suspend fun run(): Boolean {
        val conn = SmbConnection(profile)
        try {
            try {
                withContext(Dispatchers.IO) { conn.open() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Could not even connect: keep the queue so the scan can be resumed later.
                withContext(NonCancellable) {
                    recordError("", SmbErrors.describe(e), isDirectory = false)
                    dao.refreshCounters(sid)
                    dao.setSessionStatus(sid, ScanStatus.FAILED, System.currentTimeMillis())
                }
                return false
            }

            while (true) {
                currentCoroutineContext().ensureActive()
                val dir = dao.nextPending() ?: break
                scanDirectory(conn, dir)
            }

            finish()
            return true
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                dao.setSessionStatus(sid, ScanStatus.INTERRUPTED, null)
            }
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { conn.close() } }
        }
    }

    /** Lists one directory and saves the result atomically. A failure is recorded; the scan moves on. */
    private suspend fun scanDirectory(conn: SmbConnection, dir: String) {
        val entries: List<SmbEntry>
        try {
            entries = listWithRetry(conn, dir)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            db.withTransaction {
                recordError(dir, SmbErrors.describe(e), isDirectory = true)
                dao.deletePending(dir)
                dao.bumpProgress(sid, dir)
            }
            return
        }

        db.withTransaction {
            val subDirs = ArrayList<PendingDirEntity>()
            for (entry in entries) {
                if (isSkipped(entry.name)) continue
                if (entry.isReparsePoint || (entry.isDirectory && dir.count { it == '/' } >= 127)) {
                    recordError(dir, "Skipped a linked folder or the maximum folder depth.", isDirectory = true)
                    continue
                }
                val rel = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                if (entry.isDirectory) {
                    subDirs.add(PendingDirEntity(rel))
                } else if (isVideo(entry.name)) {
                    upsertVideo(rel, dir, entry)
                }
            }
            if (subDirs.isNotEmpty()) dao.insertPending(subDirs)
            dao.deletePending(dir)
            dao.bumpProgress(sid, dir)
        }
    }

    private suspend fun listWithRetry(conn: SmbConnection, dir: String): List<SmbEntry> {
        var last: Exception? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            try {
                return withContext(Dispatchers.IO) { conn.list(dir) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                if (!SmbErrors.isTransient(e) || attempt == MAX_ATTEMPTS) break
                delay(1_000L * attempt)
                try {
                    withContext(Dispatchers.IO) { conn.reconnect() }
                } catch (c: CancellationException) {
                    throw c
                } catch (ignored: Exception) {
                    // The next attempt will report the failure.
                }
            }
        }
        throw last ?: IllegalStateException("Unknown listing error")
    }

    private suspend fun upsertVideo(relativePath: String, parentDir: String, entry: SmbEntry) {
        val existing = dao.findVideo(relativePath)
        when {
            existing == null -> dao.insertVideo(
                VideoEntity(
                    relativePath = relativePath,
                    name = entry.name,
                    parentDir = parentDir,
                    size = entry.size,
                    modified = entry.modified,
                    groupKey = GroupKey.fromFileName(entry.name, profile.groupingRule),
                    lastSeenSession = sid,
                    addedAt = System.currentTimeMillis(),
                ),
            )

            existing.size != entry.size || existing.modified != entry.modified ->
                dao.updateMetadata(existing.id, entry.size, entry.modified, GroupKey.fromFileName(entry.name, profile.groupingRule), sid)
            existing.groupKey != GroupKey.fromFileName(entry.name, profile.groupingRule) ->
                dao.updateGroupKey(existing.id, GroupKey.fromFileName(entry.name, profile.groupingRule), sid)
            existing.lastSeenSession != sid -> dao.touchVideo(existing.id, sid)
        }
    }

    private suspend fun recordError(path: String, message: String, isDirectory: Boolean) {
        dao.insertError(
            ScanErrorEntity(
                sessionId = sid,
                path = path,
                message = message,
                isDirectory = isDirectory,
                time = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * Queue is empty. Removed files are only dropped after a scan that finished with no errors within this isolated library. A failed or partial scan never prunes unseen rows.
     */
    private suspend fun finish() {
        db.withTransaction {
            dao.refreshCounters(sid)
            val s = dao.getSession(sid)
            val errors = s?.errorCount ?: 0
            val found = s?.videosFound ?: 0
            if (errors == 0) {
                dao.deleteNotSeen(sid)
            }
            val status = if (errors == 0) ScanStatus.COMPLETED else ScanStatus.COMPLETED_WITH_ERRORS
            dao.setSessionStatus(sid, status, System.currentTimeMillis())
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
