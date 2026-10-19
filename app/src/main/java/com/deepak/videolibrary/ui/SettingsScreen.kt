package com.deepak.videolibrary.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.deepak.videolibrary.settings.SmbSettings
import kotlinx.coroutines.launch

private val GRID_PRESETS = LibraryLayout.presets

/** Four equally sized choices, with no incomplete last row or screen-height coupling. */
@Composable
private fun GridPresetSetting(label: String, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        GRID_PRESETS.chunked(2).forEach { options ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected == option) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                            ).selectable(selected = selected == option, role = Role.RadioButton, onClick = { onSelect(option) })
                            .padding(horizontal = 8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == option, onClick = null)
                        Text("$option columns", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    var section by rememberSaveable { mutableStateOf<String?>(null) }
    var hiddenPage by rememberSaveable { mutableStateOf(false) }
    if (hiddenPage) {
        HiddenItemsScreen(vm) { hiddenPage = false }
        return
    }

    val saved = vm.settings
    var host by remember { mutableStateOf(saved.host) }
    var share by remember { mutableStateOf(saved.share) }
    var root by remember { mutableStateOf(saved.rootPath) }
    var user by remember { mutableStateOf(saved.username) }
    var password by remember { mutableStateOf(saved.password) }
    var domain by remember { mutableStateOf(saved.domain) }
    var requireSigning by remember { mutableStateOf(saved.requireSigning) }
    var requireEncryption by remember { mutableStateOf(saved.requireEncryption) }
    var advancedConnection by rememberSaveable { mutableStateOf(false) }
    var savePassword by remember { mutableStateOf(saved.savePassword) }
    val context = LocalContext.current
    val gesturePrefs = remember(context) { context.getSharedPreferences("player_prefs", android.content.Context.MODE_PRIVATE) }
    var swipeGesturesEnabled by remember {
        mutableStateOf(
            gesturePrefs.getBoolean("volume_gesture_enabled", true) &&
                gesturePrefs.getBoolean("brightness_gesture_enabled", true),
        )
    }
    val keyboardScope = rememberCoroutineScope()
    val passwordBringIntoView = remember { BringIntoViewRequester() }
    fun current() = SmbSettings(
        host = host,
        share = share,
        rootPath = root,
        username = user,
        password = password,
        savePassword = savePassword,
        domain = domain,
        requireSigning = requireSigning,
        requireEncryption = requireEncryption,
    )

    BackHandler(enabled = section != null) { section = null }
    val scroll = rememberScrollState()
    LaunchedEffect(section) { scroll.scrollTo(0) }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
    Column(
        Modifier
            .widthIn(max = 760.dp)
            .fillMaxSize()
            .imePadding()
            .verticalScroll(
                scroll,
            ).padding(16.dp)
            .padding(bottom = if (floatingPadding(vm))100.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (section != null) TextButton(onClick = { section = null }) { Text("← All settings") }
        Text(section ?: "Settings", style = MaterialTheme.typography.headlineMedium)
        if (section == null) {
            listOf(
                "Connection" to "Server, account and saved password",
                "Appearance" to "Theme, navigation and grid layout",
                "Playback" to "Brightness and volume gestures",
                "Library" to "Hidden files and folders",
                "Diagnostics" to "Playback reports and troubleshooting",
            ).forEach { (title, description) ->
                Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { section = title }) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("›", style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        if (section == "Library") {
            LibraryBackupActions(vm)
            var showGrouping by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { showGrouping = !showGrouping }) { Text("Automatic virtual-folder grouping") }
            if (showGrouping) {
                Text("Changing this refreshes automatic groups. Manual assignments stay in place.", style = MaterialTheme.typography.bodySmall)
                com.deepak.videolibrary.scan.GroupingRule.entries.forEach { rule ->
                    val label = when(rule) {
                        com.deepak.videolibrary.scan.GroupingRule.COMMA_OR_HYPHEN -> "Before comma or hyphen"
                        com.deepak.videolibrary.scan.GroupingRule.COMMA_ONLY -> "Before comma"
                        com.deepak.videolibrary.scan.GroupingRule.FULL_NAME -> "Full cleaned filename"
                    }
                    Row(Modifier.fillMaxWidth().selectable(selected = vm.groupingRule == rule, role = Role.RadioButton, onClick = { vm.updateGrouping(rule) }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(vm.groupingRule == rule, onClick = null); Text(label)
                    }
                }
            }
            OutlinedButton(onClick = { hiddenPage = true }, modifier = Modifier.fillMaxWidth()) { Text("Unhide files and folders") }
        }
        if (section == "Appearance") {
            ThemeSettings()
            Text("Navigation position", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                RadioButton(selected = vm.navigationPosition == "SIDEBAR", onClick = { vm.updateNavigationPosition("SIDEBAR") })
                TextButton(onClick = { vm.updateNavigationPosition("SIDEBAR") }) { Text("Sidebar") }
                RadioButton(selected = vm.navigationPosition == "FLOATING", onClick = { vm.updateNavigationPosition("FLOATING") })
                TextButton(onClick = { vm.updateNavigationPosition("FLOATING") }) { Text("Floating bottom bar") }
            }
        }
        if (section == "Diagnostics") DiagnosticsSettings()
        var gesturesExpanded by rememberSaveable { mutableStateOf(false) }
        var layoutExpanded by rememberSaveable { mutableStateOf(false) }
        if (section == "Playback") {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { gesturesExpanded = !gesturesExpanded },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Player gestures", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (gesturesExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                    }
                    if (gesturesExpanded) {
                        Text(
                            "Volume is controlled from the right side; brightness from the left.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Brightness and volume swipe")
                                Text("Left: brightness · Right: volume", style = MaterialTheme.typography.labelSmall)
                            }
                            androidx.compose.material3.Switch(checked = swipeGesturesEnabled, onCheckedChange = {
                                swipeGesturesEnabled = it
                                gesturePrefs
                                    .edit()
                                    .putBoolean("volume_gesture_enabled", it)
                                    .putBoolean("brightness_gesture_enabled", it)
                                    .apply()
                            })
                        }
                    }
                }
            }
        }
        if (section == "Appearance") {
            Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { layoutExpanded = !layoutExpanded },
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text("Library layout", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (layoutExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                    }
                    if (layoutExpanded) {
                        Text(
                            "Choose the number of columns. Thumbnails keep their 16:9 shape in every layout.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("Show videos as a list", modifier = Modifier.weight(1f))
                            androidx.compose.material3.Switch(checked = vm.videoListMode, onCheckedChange = { vm.toggleVideoLayout() })
                        }
                        GridPresetSetting("Folders grid", vm.folderGridPreset, vm::updateFolderGridPreset)
                        GridPresetSetting("Videos grid", vm.videoGridPreset, vm::updateVideoGridPreset)
                    }
                }
            }
        }
        if (section == "Connection") {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text("Server address") },
                placeholder = { Text("e.g. 192.168.71.250") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Enter your server address and share name.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = share,
                onValueChange = { share = it },
                label = { Text("Share name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = root,
                onValueChange = { root = it },
                label = { Text("Starting folder (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username (blank for guest)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(passwordBringIntoView)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            keyboardScope.launch {
                                kotlinx.coroutines.delay(250)
                                passwordBringIntoView.bringIntoView()
                            }
                        }
                    },
            )
            Row(
                Modifier.fillMaxWidth().clickable { savePassword = !savePassword },
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                Text(
                    "Save password (encrypted with the Android Keystore)",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            if (!savePassword) {
                Text(
                    "Without a saved password, background scans only work while the app has been opened since it last started.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            TextButton(onClick = { advancedConnection = !advancedConnection }) { Text("Advanced connection options") }
            if (advancedConnection) {
                OutlinedTextField(value = domain, onValueChange = {
                    domain = it
                }, label = { Text("Domain (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Require SMB signing", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = requireSigning, onCheckedChange = { requireSigning = it })
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("Require SMB encryption", Modifier.weight(1f))
                    androidx.compose.material3.Switch(checked = requireEncryption, onCheckedChange = { requireEncryption = it })
                }
                Text(
                    "Required security must be supported by your server. Encryption requires SMB3 and can affect throughput.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveSettings(current()) }) { Text("Save") }
                OutlinedButton(onClick = { vm.testConnection(current()) }) { Text("Test connection") }
            }

            when (val t = vm.testState) {
                TestState.Idle -> Unit
                TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is TestState.Success -> Text(t.message, color = MaterialTheme.colorScheme.primary)
                is TestState.Failure -> Text(t.message, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    }
}
