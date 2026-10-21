package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One action surface for library videos, real folders, virtual folders and Explorer. */
@Composable
internal fun FileActionsMenu(
    expanded: Boolean,
    dismiss: () -> Unit,
    vm: LibraryViewModel,
    path: String,
    directory: Boolean = false,
    virtual: Boolean = false,
    onPlay: (() -> Unit)? = null,
    onPlayFromStart: (() -> Unit)? = null,
    details: String = path,
    onRemoveContinue: (() -> Unit)? = null,
    selectionScope: String = "videos",
    onChangeThumbnail: (() -> Unit)? = null,
    onChangeCover: (() -> Unit)? = null,
) {
    var rename by remember(path) { mutableStateOf(false) }
    var delete by remember(path) { mutableStateOf(false) }
    var info by remember(path) { mutableStateOf(false) }
    var merge by remember(path) { mutableStateOf(false) }
    var mergeName by remember(path) { mutableStateOf("") }
    var name by remember(path) { mutableStateOf(if (virtual) path else path.substringAfterLast('/')) }
    DropdownMenu(expanded = expanded, onDismissRequest = dismiss, shape = RoundedCornerShape(16.dp)) {
        if (onPlay != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_play_5d12bd5)) }, onClick = {
                dismiss()
                onPlay()
            })
        }
        if (onPlayFromStart != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_play_from_start_c052f7a)) }, onClick = {
                dismiss()
                onPlayFromStart()
            })
        }
        if (onChangeThumbnail != null) {
            DropdownMenuItem(text = { Text("Change thumbnail") }, onClick = {
                dismiss()
                onChangeThumbnail()
            })
        }
        if (onChangeCover != null) {
            DropdownMenuItem(text = { Text("Change cover") }, onClick = {
                dismiss()
                onChangeCover()
            })
        }
        if (!directory && !virtual) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_select_8598222)) }, onClick = {
                dismiss()
                vm.toggleSelected(path, selectionScope)
            })
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_select_all_913afff)) }, onClick = {
                dismiss()
                vm.selectAll(selectionScope)
            })
        }
        if (virtual) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_merge_into_56f0e1a)) }, onClick = {
                dismiss()
                merge = true
            })
        }
        DropdownMenuItem(text = { Text("Download") }, onClick = {
            dismiss()
            when {
                virtual -> vm.downloadVirtualFolder(path)
                directory -> vm.downloadDirectory(path)
                else -> vm.downloadFiles(listOf(path))
            }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_cut_38d13bd)) }, onClick = {
            dismiss()
            if (virtual) vm.stageGroup(path, true) else vm.stageExplorer(path, directory, true)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_copy_af74f7c)) }, onClick = {
            dismiss()
            if (virtual) vm.stageGroup(path, false) else vm.stageExplorer(path, directory, false)
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_paste_9db2f69)) }, enabled = vm.explorerClipboard != null, onClick = {
            dismiss()
            vm.pasteDestination =
                if (virtual) {
                    vm.settings.rootPath
                } else if (directory) {
                    path
                } else {
                    path.substringBeforeLast('/', "")
                }
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_rename_d3f4cb8)) }, onClick = {
            dismiss()
            rename = true
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_delete_2695739)) }, onClick = {
            dismiss()
            delete = true
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_details_dc3decb)) }, onClick = {
            dismiss()
            info = true
        })
        DropdownMenuItem(text = { Text(stringResource(R.string.ui_remove_hide_3c3e1da)) }, onClick = {
            dismiss()
            if (virtual) vm.hideGroup(path) else vm.hidePath(path, directory)
        })
        if (onRemoveContinue != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ui_remove_from_continue_watching_b589f73)) }, onClick = {
                dismiss()
                onRemoveContinue()
            })
        }
    }
    if (merge) {
        AlertDialog(
            onDismissRequest = { merge = false },
            title = { Text(stringResource(R.string.ui_merge_virtual_folder_9d8da68)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_move_this_folder_s_video_assignments_into__61204ac))
                    PillTextField(mergeName, { mergeName = it }, label = stringResource(R.string.ui_destination_virtual_folder_fda9d5d), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = mergeName.isNotBlank(), onClick = {
                    vm.mergeFolders(path, mergeName)
                    merge = false
                }) { Text(stringResource(R.string.ui_merge_ea8f0d0)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    merge =
                        false
                }) { Text(stringResource(R.string.ui_cancel_77dfd21)) }
            },
        )
    }
    if (rename) {
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text(if (virtual) "Rename virtual folder" else "Rename original SMB item") },
            text = {
                Column {
                    Text(if (virtual) "This changes library organisation only." else "This changes the name on your network drive.")
                    PillTextField(name, {
                        name =
                            it
                    }, label = stringResource(R.string.ui_new_name_9e627c7), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && '/' !in name && '\\' !in name, onClick = {
                    if (virtual) vm.renameFolder(path, name) else vm.renameOriginal(path, name, directory)
                    rename = false
                }) { Text(stringResource(R.string.ui_rename_d3f4cb8)) }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
    if (delete) {
        AlertDialog(
            onDismissRequest = { delete = false },
            title = { Text(stringResource(R.string.ui_delete_originals_from_smb_fa4c321)) },
            text = {
                Text(
                    if (virtual) {
                        "Permanently delete the visible videos in virtual folder '$path' from your network drive? This cannot be undone. Hide keeps the files instead."
                    } else {
                        "Permanently delete '${path.substringAfterLast(
                            '/',
                        )}'${if (directory) " and everything inside" else ""} from your network drive? This cannot be undone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (virtual) vm.deleteGroupFiles(path) else vm.deleteOriginal(path, directory)
                    delete = false
                }) { Text(stringResource(R.string.ui_delete_permanently_e63b515)) }
            },
            dismissButton = { TextButton(onClick = { delete = false }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
        )
    }
    if (info) {
        AlertDialog(onDismissRequest = { info = false }, title = { Text(stringResource(R.string.ui_details_dc3decb)) }, text = { Text(details) }, confirmButton = {
            TextButton(onClick = {
                info =
                    false
            }) { Text(stringResource(R.string.ui_close_bbfa773)) }
        })
    }
}

@Composable
internal fun FileClipboardBar(vm: LibraryViewModel) {
    SelectionBar(vm)
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val active = transfers.firstOrNull {
        it.state == WorkInfo.State.RUNNING
    } ?: transfers.firstOrNull { it.state == WorkInfo.State.ENQUEUED }
    vm.explorerClipboard?.let { clip ->
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${if (clip.third) "Cut" else "Copied"}: ${if (vm.clipboardBatch.size > 1) {
                    "${vm.clipboardBatch.size} videos"
                } else {
                    clip.first
                        .substringAfterLast(
                            '/',
                        )
                }}",
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            TextButton(onClick = { vm.pasteDestination = vm.settings.rootPath }) { Text(stringResource(R.string.ui_paste_9db2f69)) }
            TextButton(onClick = vm::clearClipboard) { Text(stringResource(R.string.ui_clear_719ea39)) }
        }
    }
    if (active != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val bytes = active.progress.getLong("bytes", 0)
            Text(if (bytes > 0) "Copying: ${bytes / 1048576} MB" else "File operation queued / running", modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.cancelTransfer(active.id) }) { Text(stringResource(R.string.ui_cancel_77dfd21)) }
        }
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    val recent = transfers.filter { it.state.isFinished }.maxByOrNull { it.outputData.getLong("finishedAt", 0) }
    // A finished operation's message shows briefly, then goes away; it never reappears on a later launch.
    val finishedAt = recent?.outputData?.getLong("finishedAt", 0L) ?: 0L
    val showFor = if (recent?.state == WorkInfo.State.FAILED) FAILED_MESSAGE_MS else DONE_MESSAGE_MS
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(finishedAt, showFor) {
        now = System.currentTimeMillis()
        val left = finishedAt + showFor - now
        if (left > 0) {
            kotlinx.coroutines.delay(left)
            now = System.currentTimeMillis()
        }
    }
    recent?.outputData?.getString("message")?.takeIf { finishedAt > 0 && now - finishedAt in 0 until showFor }?.let {
        Text(
            it,
            Modifier.padding(horizontal = 8.dp),
            color = if (recent.state ==
                WorkInfo.State.FAILED
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
    if (vm.pasteDestination != null) PasteDestinationDialog(vm)
}

private const val DONE_MESSAGE_MS = 5_000L
private const val FAILED_MESSAGE_MS = 15_000L

@Composable
private fun PasteDestinationDialog(vm: LibraryViewModel) {
    var path by remember { mutableStateOf(vm.pasteDestination ?: "") }
    var folders by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val s = vm.settings
    LaunchedEffect(path) {
        busy = true
        error = null
        try {
            folders = withContext(Dispatchers.IO) {
                SmbConnection(
                    SmbProfile(s.host, s.share, s.rootPath, s.username, s.password, s.domain, s.requireSigning, s.requireEncryption),
                ).use { conn ->
                    conn.open()
                    conn
                        .list(path)
                        .filter { it.isDirectory }
                        .map { it.name }
                        .sortedWith(String.CASE_INSENSITIVE_ORDER)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = SmbErrors.describe(e)
        } finally {
            busy = false
        }
    }
    AlertDialog(
        onDismissRequest = { vm.pasteDestination = null },
        title = { Text(stringResource(R.string.ui_choose_an_original_destination_folder_4667196)) },
        text = {
            Column {
                Text(path.ifEmpty { "/ (share root)" })
                TextButton(enabled = path.isNotEmpty(), onClick = { path = path.substringBeforeLast('/', "") }) { Text(stringResource(R.string.ui_up_2c97c0b)) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(folders) { name ->
                        Text(
                            name,
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    path =
                                        listOf(path, name).filter(String::isNotBlank).joinToString("/")
                                }.padding(14.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && error == null, onClick = {
                vm.pasteInto(path)
                vm.pasteDestination = null
            }) { Text(stringResource(R.string.ui_paste_here_731b577)) }
        },
        dismissButton = { TextButton(onClick = { vm.pasteDestination = null }) { Text(stringResource(R.string.ui_cancel_77dfd21)) } },
    )
}

@Composable
private fun SelectionBar(vm: LibraryViewModel) {
    // Videos show their own icon header while selecting.
    if (vm.selectedPaths.isEmpty() || vm.selectionScope == "videos") return
    var merge by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    BackHandler { vm.clearSelection() }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${vm.selectedPaths.size} selected", Modifier.weight(1f))
            TextButton(onClick = { vm.selectAll(vm.selectionScope) }) { Text(stringResource(R.string.ui_select_all_913afff)) }
            TextButton(onClick = vm::clearSelection) { Text(stringResource(R.string.ui_done_e9b450d)) }
        }
        Row {
            TextButton(onClick = { vm.downloadFiles(vm.selectedPaths); vm.clearSelection() }) { Text("Download") }
            TextButton(onClick = { vm.stageSelected(true) }) { Text(stringResource(R.string.ui_cut_38d13bd)) }
            TextButton(onClick = { vm.stageSelected(false) }) { Text(stringResource(R.string.ui_copy_af74f7c)) }
            TextButton(enabled = vm.explorerClipboard != null, onClick = { vm.pasteDestination = vm.settings.rootPath }) { Text(stringResource(R.string.ui_paste_9db2f69)) }
            TextButton(onClick = { merge = true }) { Text(stringResource(R.string.ui_merge_into_56f0e1a)) }
        }
    }
    if (merge) {
        AlertDialog(
            onDismissRequest = { merge = false },
            title = { Text(stringResource(R.string.ui_merge_selected_videos_e03e58e)) },
            text = {
                Column {
                    Text(stringResource(R.string.ui_assign_selected_videos_to_an_existing_or_n_b820db7))
                    PillTextField(name, {
                        name =
                            it
                    }, label = stringResource(R.string.ui_destination_virtual_folder_fda9d5d), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    vm.mergeSelected(name)
                    merge = false
                }) { Text(stringResource(R.string.ui_merge_ea8f0d0)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    merge =
                        false
                }) { Text(stringResource(R.string.ui_cancel_77dfd21)) }
            },
        )
    }
}

@Composable
internal fun HiddenItemsScreen(vm: LibraryViewModel, back: () -> Unit) {
    val hidden by vm.hiddenItems.collectAsStateWithLifecycle()
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text(stringResource(R.string.ui_settings_861b68c)) }
            Text(stringResource(R.string.ui_hidden_files_and_folders_28d2aec), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.ui_hidden_items_stay_on_your_smb_drive_and_re_26d930b))
        TextButton(enabled = hidden.isNotEmpty(), onClick = vm::unhideAll) { Text("Unhide all (${hidden.size})") }
        if (hidden.isEmpty()) Text(stringResource(R.string.ui_no_hidden_items_b5145e2))
        LazyColumn {
            items(hidden, key = { "${it.kind}:${it.target}" }) { item ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.label)
                        Text(
                            "${if (item.kind == "MATCH") {
                                "Custom folder"
                            } else if (item.kind == "GROUP") {
                                "Virtual folder"
                            } else if (item.kind == "DIRECTORY") {
                                "Original folder"
                            } else {
                                "File"
                            }} · ${item.target}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { vm.unhide(item) }) { Text(stringResource(R.string.ui_unhide_37f0297)) }
                }
            }
        }
    }
}

@Composable
internal fun ThumbnailFailures(vm: LibraryViewModel) {
    val failures by vm.failedThumbnails.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<VideoEntity?>(null) }
    if (failures.isNotEmpty()) {
        Text("Failed thumbnails (${failures.size})", style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.ui_tap_a_filename_for_the_reason_these_stay_l_cb2e815),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            items(failures, key = {
                it.id
            }) { video ->
                TextButton(onClick = {
                    selected = video
                }, modifier = Modifier.fillMaxWidth()) { Text(video.name, modifier = Modifier.fillMaxWidth()) }
            }
        }
    }
    selected?.let { video ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(video.name) },
            text = {
                Column {
                    Text(
                        video.thumbError
                            ?: "This thumbnail failed in an earlier build. Retry it to record a reason if Android provides one.",
                    )
                    Text(video.relativePath, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.openFailureFolder(video.relativePath)
                    selected = null
                }) { Text(stringResource(R.string.ui_open_folder_in_file_explorer_4aa3f2c)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.retryThumbnail(video.id)
                        selected = null
                    }) { Text(stringResource(R.string.ui_retry_this_file_a7c5877)) }
                    TextButton(onClick = {
                        selected =
                            null
                    }) { Text(stringResource(R.string.ui_close_bbfa773)) }
                }
            },
        )
    }
}
