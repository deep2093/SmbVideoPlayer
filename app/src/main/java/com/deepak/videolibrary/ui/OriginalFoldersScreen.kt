package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.data.DirectorySummary
import com.deepak.videolibrary.scan.ScanMode
import java.io.File

@Composable
fun OriginalFoldersScreen(vm: LibraryViewModel,onPlay:(VideoEntity,List<VideoEntity>)->Unit) {
    val children by vm.originalDirectories.collectAsStateWithLifecycle()
    val videos=vm.originalPages.collectAsLazyPagingItems()
    val count by vm.originalResultsCount.collectAsStateWithLifecycle()
    val root=vm.settings.rootPath.trim('/')
    val path=vm.originalPath.takeIf {isWithinFolder(it,root)} ?: root
    val query=vm.originalQuery
    fun up(){vm.originalQuery="";vm.originalPath=path.substringBeforeLast('/',"").takeIf {isWithinFolder(it,root)} ?: root}
    BackHandler(enabled=query.isNotEmpty() || path!=root){if(query.isNotEmpty())vm.originalQuery="" else up()}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically){Text("Original folders",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f));TextButton(onClick={vm.startScan(ScanMode.REFRESH)}){Text("Refresh index")}}
        Row(verticalAlignment=Alignment.CenterVertically){TextButton(enabled=path!=root,onClick=::up){Text("← Up")};Text(path.ifEmpty {"/ (share root)"},Modifier.weight(1f),maxLines=2,overflow=TextOverflow.Ellipsis)}
        OutlinedTextField(query,{vm.originalQuery=it},singleLine=true,label={Text("Search folder and video names")},modifier=Modifier.fillMaxWidth(),trailingIcon={if(query.isNotEmpty())TextButton(onClick={vm.originalQuery=""}){Text("Clear")}})
        Row(verticalAlignment=Alignment.CenterVertically){Text("$count videos including subfolders",Modifier.weight(1f));TextButton(enabled=count>0,onClick={vm.playLibrary("originals",onPlay=onPlay)}){Text("Play all")}}
        LazyVerticalGrid(columns=GridCells.Fixed(if(vm.folderListMode)1 else LibraryLayout.columns(vm.folderGridPreset)),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp),modifier=Modifier.weight(1f)){
            if(count==0 && children.isEmpty())item(span={GridItemSpan(maxLineSpan)}){Text(if(query.isBlank())"No visible indexed videos here. Scan your library or check Hidden items in Settings." else "No matching visible folders or videos.")}
            items(children,key={"folder:${it.path}"}){folder->OriginalFolderCard(vm,folder){vm.originalQuery="";vm.originalPath=folder.path}}
            items(count=videos.itemCount,key=videos.itemKey {"video:${it.relativePath}"}){index->videos[index]?.let {video->VideoCard(video,onClick={vm.playLibrary("originals",video,onPlay)},vm=vm,listMode=vm.folderListMode)}}
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OriginalFolderCard(vm:LibraryViewModel,folder:DirectorySummary,onClick:()->Unit){
    val key=vm.coverKey("original:${folder.path}",folder.count,folder.thumbsDone)
    var cover by remember(key){mutableStateOf(vm.cachedCover(key))}
    var menu by remember(folder.path){mutableStateOf(false)}
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    LaunchedEffect(key,hidden){cover=vm.originalCover(folder.path,key)}
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick=onClick,onLongClick={menu=true})){
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f/9f),contentAlignment=Alignment.Center){if(cover!=null)FileImage(cover,Modifier.fillMaxSize())else Icon(Icons.Filled.Folder,null,Modifier.size(48.dp))}
                Column(Modifier.padding(10.dp)){Text(folder.path.substringAfterLast('/'),maxLines=2,overflow=TextOverflow.Ellipsis);Text("${folder.count} videos",style=MaterialTheme.typography.bodySmall)}
            }
        }
        FileActionsMenu(menu,{menu=false},vm,folder.path,directory=true,details="${folder.path}\n${folder.count} visible indexed videos\nOriginal SMB folder")
    }
}
