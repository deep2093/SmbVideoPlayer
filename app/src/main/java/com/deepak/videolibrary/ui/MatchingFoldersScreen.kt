package com.deepak.videolibrary.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.data.*

@Composable
internal fun MatchingFoldersScreen(vm:LibraryViewModel,onOpen:()->Unit,onPlay:(VideoEntity,List<VideoEntity>)->Unit) {
    val folders by vm.matchingFolders.collectAsStateWithLifecycle()
    val query by vm.matchFolderQuery.collectAsStateWithLifecycle()
    var search by remember {mutableStateOf(false)}
    var creating by remember {mutableStateOf(false)}
    var name by remember {mutableStateOf("")}
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("Custom folders",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
            IconButton(onClick={search=!search;if(!search)vm.matchFolderQuery.value=""}){Icon(Icons.Default.Search,"Search custom folders")}
            TextButton(onClick={name="";creating=true}){Text("+ Create")}
        }
        Text("Name a folder Peter to collect every indexed video with Peter anywhere in its filename. New matching videos appear automatically.",style=MaterialTheme.typography.bodySmall)
        vm.organizerError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        if(search)OutlinedTextField(query,{vm.matchFolderQuery.value=it},label={Text("Search custom folders")},modifier=Modifier.fillMaxWidth(),singleLine=true)
        LazyVerticalGrid(modifier=Modifier.weight(1f),columns=GridCells.Fixed(if(vm.folderListMode)1 else LibraryLayout.columns(vm.folderGridPreset)),horizontalArrangement=Arrangement.spacedBy(10.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(top=16.dp,bottom=if(floatingPadding(vm))112.dp else 16.dp)) {
            items(folders,key={it.folder.id}){row->MatchingFolderCard(vm,row,onOpen,onPlay)}
        }
    }
    if(creating)AlertDialog(onDismissRequest={creating=false},title={Text("Create automatic custom folder")},
        text={Column {Text("The folder name is matched anywhere in video filenames, ignoring case. Original files keep their locations.");OutlinedTextField(name,{name=it},label={Text("Folder name / filename match")},singleLine=true)}},
        confirmButton={TextButton(enabled=name.isNotBlank(),onClick={vm.createMatchingFolder(name);creating=false}){Text("Create")}},dismissButton={TextButton(onClick={creating=false}){Text("Cancel")}})
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MatchingFolderCard(vm:LibraryViewModel,row:MatchFolderRow,onOpen:()->Unit,onPlay:(VideoEntity,List<VideoEntity>)->Unit) {
    val folder=row.folder
    val key=vm.coverKey("match:${folder.id}:${folder.term}",row.count,row.thumbsDone,row.lastModified)
    var cover by remember(key){mutableStateOf(vm.cachedCover(key))}
    LaunchedEffect(key){cover=vm.matchingCover(folder,key)}
    var menu by remember {mutableStateOf(false)}
    var dialog by remember {mutableStateOf("")}
    var name by remember {mutableStateOf("")}
    fun open(){vm.groupFilter.value=null;vm.matchFolderFilter.value=folder;vm.query.value="";onOpen()}
    Box {
        Card(Modifier.fillMaxWidth().combinedClickable(onClick=::open,onLongClick={menu=true})) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f/9f),contentAlignment=Alignment.Center){if(cover!=null)FileImage(cover,Modifier.fillMaxSize()) else Icon(Icons.Default.Folder,null,Modifier.size(48.dp))}
                Row(Modifier.padding(10.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(folder.name,maxLines=2,overflow=TextOverflow.Ellipsis);Text("${row.count} matching videos",style=MaterialTheme.typography.bodySmall)};IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,"Options for ${folder.name}")}}
            }
        }
        DropdownMenu(menu,{menu=false}) {
            DropdownMenuItem(text={Text("Play all")},onClick={menu=false;vm.matchFolderFilter.value=folder;vm.groupFilter.value=null;vm.query.value="";vm.playLibrary("videos",onPlay=onPlay)})
            DropdownMenuItem(text={Text("Select all")},onClick={menu=false;open();vm.selectAll("videos")})
            DropdownMenuItem(text={Text("Cut")},onClick={menu=false;vm.stageMatchingFolder(folder,true)})
            DropdownMenuItem(text={Text("Copy")},onClick={menu=false;vm.stageMatchingFolder(folder,false)})
            DropdownMenuItem(text={Text("Paste…")},enabled=vm.explorerClipboard!=null,onClick={menu=false;vm.pasteDestination=vm.settings.rootPath})
            DropdownMenuItem(text={Text("Rename")},onClick={menu=false;name=folder.name;dialog="Rename"})
            DropdownMenuItem(text={Text("Merge into…")},onClick={menu=false;name="";dialog="Merge"})
            DropdownMenuItem(text={Text("Details")},onClick={menu=false;dialog="Details"})
            DropdownMenuItem(text={Text("Remove / Hide")},onClick={menu=false;vm.hideMatchingFolder(folder)})
            DropdownMenuItem(text={Text("Delete custom folder…")},onClick={menu=false;dialog="Delete"})
        }
    }
    if(dialog.isNotEmpty())AlertDialog(onDismissRequest={dialog=""},title={Text("$dialog ${folder.name}")},
        text={Column {
            Text(when(dialog){"Rename"->"Renaming also changes the automatic filename match.";"Merge"->"Assign matching videos to a destination virtual folder. This automatic collection remains available.";"Delete"->"Remove this automatic collection? Original files and existing virtual assignments are kept.";else->"${row.count} visible videos with '${folder.term}' anywhere in the filename, ignoring case. Membership updates automatically."})
            if(dialog=="Rename" || dialog=="Merge")OutlinedTextField(name,{name=it},singleLine=true,label={Text(if(dialog=="Merge")"Destination virtual folder" else "Name / filename match")})
        }},confirmButton={TextButton(enabled=dialog !in listOf("Rename","Merge") || name.isNotBlank(),onClick={when(dialog){"Rename"->vm.renameMatchingFolder(folder,name);"Merge"->vm.mergeMatchingFolder(folder,name);"Delete"->vm.deleteMatchingFolder(folder)};dialog=""}){Text(if(dialog=="Details")"Close" else dialog)}},dismissButton={if(dialog!="Details")TextButton(onClick={dialog=""}){Text("Cancel")}})
}
