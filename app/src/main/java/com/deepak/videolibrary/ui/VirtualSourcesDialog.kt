package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.smb.SmbEntry
import com.deepak.videolibrary.smb.SmbErrors
import kotlinx.coroutines.CancellationException

@Composable
internal fun VirtualSourcesDialog(vm:LibraryViewModel,onDismiss:()->Unit) {
    val root=vm.settings.rootPath.trim('/')
    var path by remember {mutableStateOf(root)}
    var selected by remember {mutableStateOf(vm.virtualSources)}
    var entries by remember {mutableStateOf(emptyList<SmbEntry>())}
    var loading by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var retry by remember {mutableIntStateOf(0)}
    LaunchedEffect(path,retry) {
        loading=true;error=null;entries=emptyList()
        try {entries=vm.virtualSourceDirectories(path)}
        catch(e:CancellationException){throw e}
        catch(e:Exception){error=SmbErrors.describe(e)}
        finally {loading=false}
    }
    fun includes(parent:String,child:String)=parent.isEmpty() || child==parent || child.startsWith("$parent/")
    fun toggle(target:String,checked:Boolean) {
        selected=if(checked) selected.filterNot {includes(target,it)}.toSet()+target else selected-target
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text("Virtual folder sources")},text={
        Column(Modifier.fillMaxWidth().heightIn(max=480.dp)) {
            Text("Selected folders include every subfolder. Open a folder to choose individual subfolders instead.")
            Text(path.ifEmpty {"Share root"},style=MaterialTheme.typography.labelLarge,modifier=Modifier.padding(vertical=8.dp))
            if(path!=root) TextButton(onClick={path=path.substringBeforeLast('/',"")}){Text("Up one folder")}
            val inherited=selected.any {it!=path && includes(it,path)}
            Row(verticalAlignment=Alignment.CenterVertically) {
                Checkbox(modifier=Modifier.semantics {contentDescription="Include current folder and subfolders"},checked=selected.any {includes(it,path)},enabled=!inherited,onCheckedChange={toggle(path,it)})
                Text("Include this folder and subfolders")
            }
            if(inherited) Text("Included by a selected parent. Unselect that parent to choose individual subfolders.",style=MaterialTheme.typography.bodySmall)
            if(loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}){Text("Retry")}}
            LazyColumn(Modifier.weight(1f,false)) {
                items(entries,key={it.name}) {entry ->
                    val child=listOf(path,entry.name).filter {it.isNotEmpty()}.joinToString("/")
                    val covered=selected.any {it!=child && includes(it,child)}
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(checked=selected.any {includes(it,child)},enabled=!covered,onCheckedChange={toggle(child,it)})
                        TextButton(onClick={path=child},modifier=Modifier.weight(1f)){Text(entry.name+"  ›")}
                    }
                }
                if(!loading && error==null && entries.isEmpty()) item {Text("No subfolders.")}
            }
            Text("${selected.size} selected sources",style=MaterialTheme.typography.labelMedium)
            if(selected.isNotEmpty()) TextButton(onClick={selected=emptySet()}) {Text("Clear selection")}
        }
    },confirmButton={TextButton(onClick={vm.updateVirtualSources(selected);onDismiss()}){Text("Save sources")}},
    dismissButton={TextButton(onClick=onDismiss){Text("Cancel")}})
}
