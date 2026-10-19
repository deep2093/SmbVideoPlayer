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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock

/** Durable operations shared by every screen. No whole-share refresh after a single edit. */
class TransferWorker(context: Context, params: WorkerParameters): CoroutineWorker(context,params) {
    override suspend fun doWork(): Result {
        val source=inputData.getString("source") ?: return Result.failure()
        val destination=inputData.getString("destination") ?: ""
        val operation=inputData.getString("operation") ?: return Result.failure()
        val directory=inputData.getBoolean("directory",false)
        val key=inputData.getString("namespace") ?: return Result.failure()
        val services=ServiceLocator.get(applicationContext)
        val profile=services.settings.loadProfile() ?: return Result.failure(workDataOf("message" to "Save a usable SMB profile before starting a background transfer."))
        val currentKey=profile.host.trim().lowercase(java.util.Locale.ROOT)+"/"+profile.share.trim().lowercase(java.util.Locale.ROOT)
        if(key!=currentKey) return Result.failure(workDataOf("message" to "Server settings changed. Start this operation again on the intended server."))
        return try {
            // Do not silently run large copies as an ordinary time-limited worker.
            setForeground(WorkNotifications.info(applicationContext,4,"${operation.lowercase().replaceFirstChar { it.titlecase() }} in progress"))
            val state=services.db.libraryState()
            val context=currentCoroutineContext()
            services.scanRepository.scanMutex.withLock { withContext(Dispatchers.IO) {
                SmbConnection(profile).use { conn ->
                    conn.open()
                    when(operation) {
                        "COPY" -> {
                            var bytes=0L;var last=0L
                            conn.copyPath(source,destination,directory,progress={ n ->
                                bytes+=n
                                val now=android.os.SystemClock.elapsedRealtime()
                                if(now-last>500) { setProgressAsync(workDataOf("bytes" to bytes,"message" to "Copying"));last=now }
                            },cancelled={isStopped || !context.isActive},stagingId=id.toString())
                            // Index only the copied destination, not the whole share.
                            val paths=ArrayDeque<String>()
                            if(directory) paths.add(destination) else paths.add(destination.substringBeforeLast('/',""))
                            while(paths.isNotEmpty()) {
                                val parent=paths.removeFirst()
                                for(entry in conn.list(parent)) {
                                    val path=listOf(parent,entry.name).filter(String::isNotBlank).joinToString("/")
                                    if(!directory && path!=destination) continue
                                    if(entry.isDirectory) { if(!entry.name.startsWith('.')) paths.add(path);continue }
                                    if(!isPlayable(entry) || services.db.dao().findVideo(path)!=null) continue
                                    services.db.dao().insertVideo(VideoEntity(relativePath=path,name=entry.name,parentDir=parent,size=entry.size,modified=entry.modified,groupKey=GroupKey.fromFileName(entry.name),lastSeenSession=0,addedAt=System.currentTimeMillis()))
                                }
                            }
                            ThumbnailWorker.enqueue(applicationContext)
                        }
                        "MOVE" -> {
                            conn.renamePath(source,destination,directory)
                            services.db.withTransaction {
                                for(video in state.under(source)) {
                                    val path=destination+video.relativePath.removePrefix(source)
                                    val name=path.substringAfterLast('/')
                                    state.moveVideo(video.id,path,path.substringBeforeLast('/',""),name,GroupKey.fromFileName(name))
                                }
                                state.moveProgress(key,source,destination)
                                state.moveHidden(key,source,destination)
                            }
                        }
                        "DELETE" -> {
                            val rows=state.under(source)
                            conn.deletePath(source,directory)
                            services.db.withTransaction {state.deletePath(source);state.deleteProgress(key,source);state.deleteHidden(key,source)}
                            rows.forEach { ThumbStore.thumbFile(applicationContext,it.id).delete() }
                        }
                        else -> return@withContext
                    }
                }
            }
            }
            Result.success(workDataOf("finishedAt" to System.currentTimeMillis(),"message" to "${operation.lowercase().replaceFirstChar { it.titlecase() }} completed"))
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) {
            val message=when(e) {
                is IllegalArgumentException -> when(e.message) {"Destination already exists"->"Destination already exists; choose another folder or name.";"Cannot copy a folder into itself"->"Cannot copy a folder into itself.";else->"Invalid source or destination."}
                else -> SmbErrors.describe(e)
            }
            Result.failure(workDataOf("finishedAt" to System.currentTimeMillis(),"message" to message))
        }
    }
    companion object {
        const val UNIQUE_NAME="file_transfers"
        fun enqueue(context: Context, operation: String, source: String, destination: String, directory: Boolean, key: String) {
            val request=OneTimeWorkRequestBuilder<TransferWorker>().setInputData(workDataOf("operation" to operation,"source" to source,"destination" to destination,"directory" to directory,"namespace" to key))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME,ExistingWorkPolicy.APPEND_OR_REPLACE,request)
        }
    }
}
