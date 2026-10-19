package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.smb.SmbConnection
import com.deepak.videolibrary.smb.SmbErrors
import com.deepak.videolibrary.smb.SmbProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/** One action surface for library videos, real folders, virtual folders and Explorer. */
@Composable
internal fun FileActionsMenu(expanded: Boolean, dismiss:()->Unit, vm: LibraryViewModel,
    path: String, directory: Boolean=false, virtual: Boolean=false, onPlay:(()->Unit)?=null,
    onPlayFromStart:(()->Unit)?=null, details: String=path, onRemoveContinue:(()->Unit)?=null,
    selectionScope:String="videos") {
    var rename by remember(path) { mutableStateOf(false) }
    var delete by remember(path) { mutableStateOf(false) }
    var info by remember(path) { mutableStateOf(false) }
    var merge by remember(path) { mutableStateOf(false) }
    var mergeName by remember(path) { mutableStateOf("") }
    var name by remember(path) { mutableStateOf(if(virtual) path else path.substringAfterLast('/')) }
    DropdownMenu(expanded=expanded,onDismissRequest=dismiss) {
        if(onPlay!=null) DropdownMenuItem(text={Text("Play")},onClick={dismiss();onPlay()})
        if(onPlayFromStart!=null) DropdownMenuItem(text={Text("Play from start")},onClick={dismiss();onPlayFromStart()})
        if(!directory && !virtual) {
            DropdownMenuItem(text={Text("Select")},onClick={dismiss();vm.toggleSelected(path,selectionScope)})
            DropdownMenuItem(text={Text("Select all")},onClick={dismiss();vm.selectAll(selectionScope)})
        }
        if(virtual) DropdownMenuItem(text={Text("Merge into…")},onClick={dismiss();merge=true})
        DropdownMenuItem(text={Text("Cut")},onClick={dismiss();if(virtual) vm.stageGroup(path,true) else vm.stageExplorer(path,directory,true)})
        DropdownMenuItem(text={Text("Copy")},onClick={dismiss();if(virtual) vm.stageGroup(path,false) else vm.stageExplorer(path,directory,false)})
        DropdownMenuItem(text={Text("Paste…")},enabled=vm.explorerClipboard!=null,onClick={dismiss();vm.pasteDestination=if(virtual) vm.settings.rootPath else if(directory) path else path.substringBeforeLast('/',"")})
        DropdownMenuItem(text={Text("Rename")},onClick={dismiss();rename=true})
        DropdownMenuItem(text={Text("Delete…")},onClick={dismiss();delete=true})
        DropdownMenuItem(text={Text("Details")},onClick={dismiss();info=true})
        DropdownMenuItem(text={Text("Remove / Hide")},onClick={dismiss();if(virtual) vm.hideGroup(path) else vm.hidePath(path,directory)})
        if(onRemoveContinue!=null) DropdownMenuItem(text={Text("Remove from Continue Watching")},onClick={dismiss();onRemoveContinue()})
    }
    if(merge) AlertDialog(onDismissRequest={merge=false},title={Text("Merge virtual folder")},
        text={Column {Text("Move this folder's video assignments into another virtual folder.");OutlinedTextField(mergeName,{mergeName=it},label={Text("Destination virtual folder")})}},
        confirmButton={TextButton(enabled=mergeName.isNotBlank(),onClick={vm.mergeFolders(path,mergeName);merge=false}){Text("Merge")}},dismissButton={TextButton(onClick={merge=false}){Text("Cancel")}})
    if(rename) AlertDialog(onDismissRequest={rename=false},title={Text(if(virtual) "Rename virtual folder" else "Rename original SMB item")},
        text={Column {Text(if(virtual) "This changes library organisation only." else "This changes the name on your network drive.");OutlinedTextField(name,{name=it},label={Text("New name")})}},
        confirmButton={TextButton(enabled=name.isNotBlank() && '/' !in name && '\\' !in name,onClick={
            if(virtual) vm.renameFolder(path,name) else vm.renameOriginal(path,name,directory);rename=false
        }){Text("Rename")}},dismissButton={TextButton(onClick={rename=false}){Text("Cancel")}})
    if(delete) AlertDialog(onDismissRequest={delete=false},title={Text("Delete originals from SMB?")},
        text={Text(if(virtual) "Permanently delete the visible videos in virtual folder '$path' from your network drive? This cannot be undone. Hide keeps the files instead."
            else "Permanently delete '${path.substringAfterLast('/')}'${if(directory) " and everything inside" else ""} from your network drive? This cannot be undone.")},
        confirmButton={TextButton(onClick={if(virtual) vm.deleteGroupFiles(path) else vm.deleteOriginal(path,directory);delete=false}){Text("Delete permanently")}},
        dismissButton={TextButton(onClick={delete=false}){Text("Cancel")}})
    if(info) AlertDialog(onDismissRequest={info=false},title={Text("Details")},text={Text(details)},confirmButton={TextButton(onClick={info=false}){Text("Close")}})
}

@Composable
internal fun FileClipboardBar(vm: LibraryViewModel) {
    SelectionBar(vm)
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val active=transfers.firstOrNull { it.state==WorkInfo.State.RUNNING } ?: transfers.firstOrNull { it.state==WorkInfo.State.ENQUEUED }
    vm.explorerClipboard?.let { clip ->
        Row(Modifier.fillMaxWidth().padding(8.dp),verticalAlignment=Alignment.CenterVertically) {
            Text("${if(clip.third) "Cut" else "Copied"}: ${if(vm.clipboardBatch.size>1) "${vm.clipboardBatch.size} videos" else clip.first.substringAfterLast('/')}",modifier=Modifier.weight(1f),maxLines=1)
            TextButton(onClick={vm.pasteDestination=vm.settings.rootPath}){Text("Paste…")}
            TextButton(onClick=vm::clearClipboard){Text("Clear")}
        }
    }
    if(active!=null) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically) {
            val bytes=active.progress.getLong("bytes",0)
            Text(if(bytes>0) "Copying: ${bytes/1048576} MB" else "File operation queued / running",modifier=Modifier.weight(1f))
            TextButton(onClick={vm.cancelTransfer(active.id)}){Text("Cancel")}
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val recent=transfers.filter { it.state.isFinished }.maxByOrNull { it.outputData.getLong("finishedAt",0) }
    recent?.outputData?.getString("message")?.let { Text(it,Modifier.padding(horizontal=8.dp),color=if(recent.state==WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
    if(vm.pasteDestination!=null) PasteDestinationDialog(vm)
}

@Composable
private fun PasteDestinationDialog(vm: LibraryViewModel) {
    var path by remember { mutableStateOf(vm.pasteDestination ?: "") }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val s=vm.settings
    LaunchedEffect(path) {
        busy=true;error=null
        try { folders=withContext(Dispatchers.IO) {
            SmbConnection(SmbProfile(s.host,s.share,s.rootPath,s.username,s.password)).use { conn ->
                conn.open();conn.list(path).filter {it.isDirectory}.map {it.name}.sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        } } catch(e:CancellationException){throw e} catch(e:Exception){error=SmbErrors.describe(e)} finally{busy=false}
    }
    AlertDialog(onDismissRequest={vm.pasteDestination=null},title={Text("Choose an original destination folder")},
        text={Column {
            Text(path.ifEmpty { "/ (share root)" })
            TextButton(enabled=path.isNotEmpty(),onClick={path=path.substringBeforeLast('/',"")}){Text("← Up")}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            LazyColumn(Modifier.heightIn(max=320.dp)) {items(folders){name->Text(name,Modifier.fillMaxWidth().clickable{path=listOf(path,name).filter(String::isNotBlank).joinToString("/")}.padding(14.dp))}}
        }},confirmButton={TextButton(enabled=!busy && error==null,onClick={vm.pasteInto(path);vm.pasteDestination=null}){Text("Paste here")}},
        dismissButton={TextButton(onClick={vm.pasteDestination=null}){Text("Cancel")}})
}

@Composable
private fun SelectionBar(vm:LibraryViewModel) {
    if(vm.selectedPaths.isEmpty())return
    var merge by remember {mutableStateOf(false)}
    var name by remember {mutableStateOf("")}
    BackHandler {vm.clearSelection()}
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            Text("${vm.selectedPaths.size} selected",Modifier.weight(1f))
            TextButton(onClick={vm.selectAll(vm.selectionScope)}){Text("Select all")}
            TextButton(onClick=vm::clearSelection){Text("Done")}
        }
        Row {
            TextButton(onClick={vm.stageSelected(true)}){Text("Cut")}
            TextButton(onClick={vm.stageSelected(false)}){Text("Copy")}
            TextButton(enabled=vm.explorerClipboard!=null,onClick={vm.pasteDestination=vm.settings.rootPath}){Text("Paste…")}
            TextButton(onClick={merge=true}){Text("Merge into…")}
        }
    }
    if(merge) AlertDialog(onDismissRequest={merge=false},title={Text("Merge selected videos")},
        text={Column {Text("Assign selected videos to an existing or new virtual folder. Original files keep their locations.");OutlinedTextField(name,{name=it},label={Text("Destination virtual folder")})}},
        confirmButton={TextButton(enabled=name.isNotBlank(),onClick={vm.mergeSelected(name);merge=false}){Text("Merge")}},dismissButton={TextButton(onClick={merge=false}){Text("Cancel")}})
}

@Composable
internal fun HiddenItemsScreen(vm: LibraryViewModel, back:()->Unit) {
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    BackHandler(onBack=back)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick=back){Text("← Settings")}
            Text("Hidden files and folders",style=MaterialTheme.typography.titleLarge,modifier=Modifier.weight(1f))
        }
        Text("Hidden items stay on your SMB drive and remain visible in File Explorer.")
        TextButton(enabled=hidden.isNotEmpty(),onClick=vm::unhideAll){Text("Unhide all (${hidden.size})")}
        if(hidden.isEmpty()) Text("No hidden items.")
        LazyColumn {items(hidden,key={"${it.kind}:${it.target}"}){item->
            Row(Modifier.fillMaxWidth().padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)){Text(item.label);Text("${if(item.kind=="MATCH") "Custom folder" else if(item.kind=="GROUP") "Virtual folder" else if(item.kind=="DIRECTORY") "Original folder" else "File"} · ${item.target}",style=MaterialTheme.typography.bodySmall)}
                TextButton(onClick={vm.unhide(item)}){Text("Unhide")}
            }
        }}
    }
}

@Composable
internal fun ThumbnailFailures(vm:LibraryViewModel) {
    val failures by vm.failedThumbnails.collectAsStateWithLifecycle()
    var selected by remember {mutableStateOf<VideoEntity?>(null)}
    if(failures.isNotEmpty()) {
        Text("Failed thumbnails (${failures.size})",style=MaterialTheme.typography.titleMedium)
        Text("Tap a filename for the reason. These stay listed until successfully retried or deleted.",style=MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=320.dp)) {
            items(failures,key={it.id}){video->TextButton(onClick={selected=video},modifier=Modifier.fillMaxWidth()){Text(video.name,modifier=Modifier.fillMaxWidth())}}
        }
    }
    selected?.let {video->
        AlertDialog(onDismissRequest={selected=null},title={Text(video.name)},
            text={Column {Text(video.thumbError ?: "This thumbnail failed in an earlier build. Retry it to record a reason if Android provides one.");Text(video.relativePath,style=MaterialTheme.typography.bodySmall)}},
            confirmButton={TextButton(onClick={vm.openFailureFolder(video.relativePath);selected=null}){Text("Open folder in File Explorer")}},
            dismissButton={Row {TextButton(onClick={vm.retryThumbnail(video.id);selected=null}){Text("Retry this file")};TextButton(onClick={selected=null}){Text("Close")}}})
    }
}
