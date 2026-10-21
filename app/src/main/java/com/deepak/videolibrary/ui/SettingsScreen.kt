package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
                            .clip(RoundedCornerShape(14.dp))
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

private class SettingsSection(val id: String, val title: String, val description: String)

private val SETTINGS_SECTIONS = listOf(
    // Alphabetical.
    SettingsSection("Appearance", "Appearance", "Theme, navigation and grid layout"),
    SettingsSection("Artwork", "Artwork", "Covers, collages and performer pictures"),
    SettingsSection("Connection", "Connection", "Server, account and saved password"),
    SettingsSection("Diagnostics", "Diagnostics", "Playback reports and troubleshooting"),
    SettingsSection("Downloads", "Downloads", "How many files download at a time"),
    SettingsSection("Library", "Library", "Hidden files and folders, grouping, backup"),
    SettingsSection("Playback", "Playback", "Brightness and volume gestures"),
    SettingsSection("Sync", "Sync & thumbnails", "Library scan, automatic sync and thumbnails"),
    SettingsSection("VPN", "VPN", "WireGuard for remote access to your server"),
)

/** One settings group: a card whose content drops down in place when the header is tapped. */
@Composable
private fun SettingsAccordion(
    title: String,
    description: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "settings_chevron",
    )
    val cardShape = RoundedCornerShape(22.dp)
    Card(
        modifier = Modifier.fillMaxWidth().themedOutline(cardShape),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) "Collapse $title" else "Expand $title",
                        onClick = onToggle,
                    ).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeIn(tween(300)),
                exit = shrinkVertically(animationSpec = tween(300, easing = FastOutSlowInEasing)) + fadeOut(tween(150)),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = content,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: LibraryViewModel) {
    // The open section (one at a time) and the scroll position are kept while the app is open, so coming back
    // from another page shows Settings as you left it.
    var section by vm.ui::settingsSection
    if (vm.ui.flag("settings.hidden")) {
        HiddenItemsScreen(vm) { vm.ui.setFlag("settings.hidden", false) }
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
    var advancedConnection by vm.ui.flagState("settings.advancedConnection")
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

    val scroll = remember { androidx.compose.foundation.ScrollState(vm.ui.settingsScroll) }
    androidx.compose.runtime.DisposableEffect(scroll) { onDispose { vm.ui.settingsScroll = scroll.value } }
    var layoutExpanded by vm.ui.flagState("settings.layout")
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        Column(
            Modifier
                .widthIn(max = 920.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(
                    scroll,
                ).padding(16.dp)
                .padding(bottom = if (floatingPadding(vm)) 100.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                LocalTopBarEnd.current()
            }
            SETTINGS_SECTIONS.forEach { entry ->
                SettingsAccordion(
                    title = entry.title,
                    description = entry.description,
                    expanded = section == entry.id,
                    onToggle = { section = if (section == entry.id) null else entry.id },
                ) {
                    when (entry.id) {
                        "Connection" -> {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillTextField(
                                    value = host,
                                    onValueChange = { host = it },
                                    label = stringResource(R.string.ui_server_address_b067928),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                    modifier = Modifier.weight(1f),
                                )
                                PillTextField(
                                    value = share,
                                    onValueChange = { share = it },
                                    label = stringResource(R.string.ui_share_name_b12a8c1),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            Text(
                                stringResource(R.string.ui_enter_your_server_address_and_share_name_ebe83d7),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            PillTextField(
                                value = root,
                                onValueChange = { root = it },
                                label = stringResource(R.string.ui_starting_folder_optional_9d0e002),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                PillTextField(
                                    value = user,
                                    onValueChange = { user = it },
                                    label = "Username",
                                    modifier = Modifier.weight(1f),
                                )
                                PillTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    label = stringResource(R.string.ui_password_8be3c94),
                                    visualTransformation = PasswordVisualTransformation(),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                    modifier = Modifier
                                        .weight(1f)
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
                            }
                            Text(
                                "Leave the username blank for guest access.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            Row(
                                Modifier.fillMaxWidth().clickable { savePassword = !savePassword },
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                                Text(
                                    stringResource(R.string.ui_save_password_encrypted_with_the_android_k_b38c859),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            if (!savePassword) {
                                Text(
                                    stringResource(R.string.ui_without_a_saved_password_background_scans__92f74da),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                            }
                            TextButton(onClick = { advancedConnection = !advancedConnection }) {
                                Text(stringResource(R.string.ui_advanced_connection_options_0ea75a8))
                            }
                            AnimatedVisibility(
                                visible = advancedConnection,
                                enter = expandVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeIn(tween(250)),
                                exit = shrinkVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    PillTextField(
                                        value = domain,
                                        onValueChange = { domain = it },
                                        label = stringResource(R.string.ui_domain_optional_a897f4b),
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(stringResource(R.string.ui_require_smb_signing_5168a83), Modifier.weight(1f))
                                        Switch(checked = requireSigning, onCheckedChange = { requireSigning = it })
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text(stringResource(R.string.ui_require_smb_encryption_02c67d4), Modifier.weight(1f))
                                        Switch(checked = requireEncryption, onCheckedChange = { requireEncryption = it })
                                    }
                                    Text(
                                        stringResource(R.string.ui_required_security_must_be_supported_by_you_01f1aae),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { vm.saveSettings(current()) },
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(stringResource(R.string.ui_save_efc007a)) }
                                OutlinedButton(
                                    onClick = { vm.testConnection(current()) },
                                    shape = CircleShape,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                ) { Text(stringResource(R.string.ui_test_connection_ccf66f0)) }
                            }
                            when (val t = vm.testState) {
                                TestState.Idle -> Unit
                                TestState.Running -> LinearProgressIndicator(Modifier.fillMaxWidth())
                                is TestState.Success -> Text(t.message, color = MaterialTheme.colorScheme.primary)
                                is TestState.Failure -> Text(t.message, color = MaterialTheme.colorScheme.error)
                            }
                        }
                        "VPN" -> VpnSettings()
                        "Downloads" -> {
                            ParallelDownloadsSetting(vm)
                            Text(
                                "1 is the default. More files at once use more of your server and network, and some drives slow down with several readers. A new choice applies to the next file that starts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        "Sync" -> SyncAndThumbnailsSettings(vm)
                        "Artwork" -> ArtworkSettingsSection(vm)
                        "Appearance" -> {
                            ThemeSettings()
                            Text(stringResource(R.string.ui_navigation_position_9c04a08), style = MaterialTheme.typography.titleMedium)
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = vm.navigationPosition == "SIDEBAR", onClick = { vm.updateNavigationPosition("SIDEBAR") })
                                TextButton(onClick = { vm.updateNavigationPosition("SIDEBAR") }) { Text(stringResource(R.string.ui_sidebar_f5171c9)) }
                                RadioButton(selected = vm.navigationPosition == "FLOATING", onClick = { vm.updateNavigationPosition("FLOATING") })
                                TextButton(onClick = { vm.updateNavigationPosition("FLOATING") }) { Text(stringResource(R.string.ui_floating_bottom_bar_b162779)) }
                            }
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth().clickable { layoutExpanded = !layoutExpanded },
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        Text(stringResource(R.string.ui_library_layout_ffd5af3), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                        Text(if (layoutExpanded) "▲" else "▼", color = MaterialTheme.colorScheme.primary)
                                    }
                                    if (layoutExpanded) {
                                        Text(
                                            stringResource(R.string.ui_choose_the_number_of_columns_thumbnails_ke_5c42c7f),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                            Text(stringResource(R.string.ui_show_videos_as_a_list_a89e29d), modifier = Modifier.weight(1f))
                                            Switch(checked = vm.videoListMode, onCheckedChange = { vm.toggleVideoLayout() })
                                        }
                                        GridPresetSetting("Folders grid", vm.folderGridPreset, vm::updateFolderGridPreset)
                                        GridPresetSetting("Videos grid", vm.videoGridPreset, vm::updateVideoGridPreset)
                                    }
                                }
                            }
                        }
                        "Playback" -> {
                            // Shown directly: no second dropdown inside the Playback section.
                            Text(stringResource(R.string.ui_player_gestures_1c6f88a), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.ui_volume_is_controlled_from_the_right_side_b_3c74369),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.ui_brightness_and_volume_swipe_54fb5b9))
                                    Text(stringResource(R.string.ui_left_brightness_right_volume_927c237), style = MaterialTheme.typography.labelSmall)
                                }
                                Switch(checked = swipeGesturesEnabled, onCheckedChange = {
                                    swipeGesturesEnabled = it
                                    gesturePrefs
                                        .edit()
                                        .putBoolean("volume_gesture_enabled", it)
                                        .putBoolean("brightness_gesture_enabled", it)
                                        .apply()
                                })
                            }
                        }
                        "Library" -> {
                            LibraryBackupActions(vm)
                            var showGrouping by vm.ui.flagState("settings.grouping")
                            TextButton(onClick = { showGrouping = !showGrouping }) { Text(stringResource(R.string.ui_automatic_virtual_folder_grouping_2cca974)) }
                            if (showGrouping) {
                                Text(stringResource(R.string.ui_changing_this_refreshes_automatic_groups_m_fdbe674), style = MaterialTheme.typography.bodySmall)
                                com.deepak.videolibrary.scan.GroupingRule.entries.forEach { rule ->
                                    val label = when (rule) {
                                        com.deepak.videolibrary.scan.GroupingRule.COMMA_OR_HYPHEN -> "Before comma or hyphen"
                                        com.deepak.videolibrary.scan.GroupingRule.COMMA_ONLY -> "Before comma"
                                        com.deepak.videolibrary.scan.GroupingRule.FULL_NAME -> "Full cleaned filename"
                                    }
                                    Row(
                                        Modifier.fillMaxWidth().selectable(selected = vm.groupingRule == rule, role = Role.RadioButton, onClick = { vm.updateGrouping(rule) }),
                                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    ) {
                                        RadioButton(vm.groupingRule == rule, onClick = null)
                                        Text(label, modifier = Modifier.padding(start = 8.dp))
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Hide system folders in Files")
                                    Text(
                                        "\$RECYCLE.BIN, System Volume Information, .TPDLNA and other hidden or system folders",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Switch(checked = vm.hideSystemFolders, onCheckedChange = vm::updateHideSystemFolders)
                            }
                            OutlinedButton(onClick = { vm.ui.setFlag("settings.hidden", true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_unhide_files_and_folders_64749bf)) }
                        }
                        "Diagnostics" -> DiagnosticsSettings()
                        else -> Unit
                    }
                }
            }
        }
    }
}
