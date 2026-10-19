package com.deepak.videolibrary.data

import androidx.room.withTransaction
import com.deepak.videolibrary.smb.*
import kotlinx.coroutines.*
import java.util.Locale

/** Live directory selection is independent of whether videos have already been indexed. */
class FolderCollectionRepository(private val db: LibraryDatabase, private val profileProvider: () -> SmbProfile) {
    private fun valid(path: String) = com.deepak.videolibrary.settings.SettingsStore.normalizeRoot(path) == path
    private fun visible(entries: List<SmbEntry>) = entries.filter { it.isDirectory && !it.isReparsePoint && !it.name.startsWith('.') }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
    suspend fun children(path: String): List<SmbEntry> = withContext(Dispatchers.IO) {
        val profile=profileProvider(); require(valid(path))
        SmbConnection(profile).use { connection -> connection.open(); visible(connection.list(path)) }
    }
    suspend fun descendants(path: String): Set<String> = withContext(Dispatchers.IO) {
        val profile=profileProvider(); require(valid(path))
        val selected=linkedSetOf<String>(); val pending=ArrayDeque<Pair<String,Int>>()
        pending.add(path to 0)
        SmbConnection(profile).use { connection ->
            connection.open()
            while(pending.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val(parent,depth)=pending.removeFirst()
                require(depth < 128 && selected.size <= 10000) { "Choose a smaller folder tree" }
                visible(connection.list(parent)).forEach { entry ->
                    val child=listOf(parent,entry.name).filter(String::isNotBlank).joinToString("/")
                    if(selected.add(child))pending.add(child to depth+1)
                }
            }
        }
        selected
    }
    suspend fun add(paths: Set<String>,recursive: Boolean,key: String) {
        val profile=profileProvider()
        require(paths.isNotEmpty() && paths.all { valid(it) })
        val dao=db.libraryState()
        db.withTransaction {
            paths.sorted().forEach { path ->
                if(dao.sourceFolder(key,path,recursive)==null) {
                    val leaf=path.substringAfterLast('/').ifBlank { profile.share }
                    var name=leaf; var suffix=2
                    while(dao.matchNameExists(key,name))name="$leaf (${suffix++})"
                    dao.createMatch(MatchFolderEntity(connectionKey=key,name=name,term="",sourcePath=path,includeSubfolders=recursive))
                }
            }
        }
    }
}
