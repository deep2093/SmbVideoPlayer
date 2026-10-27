package com.deepak.videolibrary.ui

import androidx.compose.ui.res.stringResource
import com.deepak.videolibrary.R

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.settings.SmbSettings
import kotlinx.coroutines.launch

private class SettingsSection(val id: String, val title: String, val description: String)

private val SETTINGS_SECTIONS = listOf(
    // One question per section, in the order you need them: the server first, troubleshooting last.
    SettingsSection("Connection", "Connection", "Server, account and remote access"),
    SettingsSection("Sync", "Sync & scanning", "Automatic rescan and library scan"),
    SettingsSection("Artwork", "Artwork & scene metadata", "Video posters, folder portraits and matching"),
    SettingsSection("Layout", "App layout", "Folder tabs, navigation, hidden items"),
    SettingsSection("Appearance", "Appearance", "Theme"),
    SettingsSection("Diagnostics", "Backup & diagnostics", "Export, restore and reports"),
    SettingsSection("WhatsNew", "What's new", "Changes in this version"),
)

/** Settings index row. Each group opens its own page. */
@Composable
private fun SettingsPageRow(entry: SettingsSection, onOpen: () -> Unit) {
    val cardShape = RoundedCornerShape(18.dp)
    Card(
        modifier = Modifier.fillMaxWidth().themedOutline(cardShape).clickable(role = Role.Button, onClickLabel = "Open ${entry.title}", onClick = onOpen),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(entry.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(vm: LibraryViewModel, onPreviewVideo: (com.deepak.videolibrary.data.VideoEntity) -> Unit = {}) {
    // The selected page and index scroll position survive tab changes during this app session.
    var section by vm.ui::settingsSection
    if (vm.ui.flag("settings.hidden")) {
        HiddenItemsScreen(vm) { vm.ui.setFlag("settings.hidden", false) }
        return
    }
    BackHandler(enabled = section != null) { section = null }

    val saved = vm.settings
    var host by remember { mutableStateOf(saved.host) }
    var alternates by remember { mutableStateOf(saved.alternateHosts) }
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
        alternateHosts = alternates,
    )

    val scroll = remember { androidx.compose.foundation.ScrollState(vm.ui.settingsScroll) }
    androidx.compose.runtime.DisposableEffect(scroll) { onDispose { vm.ui.settingsScroll = scroll.value } }
    AnimatedContent(
        targetState = section,
        modifier = Modifier.fillMaxSize(),
        transitionSpec = {
            val opening = initialState == null && targetState != null
            slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (opening) width else -width } togetherWith
                slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { width -> if (opening) -width else width }
        },
        label = "settings_page_slide",
    ) { page ->
        val pageScroll = rememberScrollState()
        val activeScroll = if (page == null) scroll else pageScroll
        OnScrollTopRequest(vm) { activeScroll.animateScrollTo(0) }
        val header = rememberCollapsingHeader()
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().nestedScroll(header.connection), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 920.dp).fillMaxSize()) {
                CollapsingHeader(header, resetKey = page) {
                    Row(Modifier.fillMaxWidth().padding(start = if (page == null) 16.dp else 8.dp, end = 16.dp, top = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (page != null) {
                            IconButton(onClick = { section = null }) {
                                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to Settings")
                            }
                        }
                        Text(
                            if (page == null) "Settings" else SETTINGS_SECTIONS.first { it.id == page }.title,
                            style = MaterialTheme.typography.headlineMedium,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Column(
                    Modifier.fillMaxSize().imePadding().verticalScroll(activeScroll)
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .padding(bottom = if (floatingPadding(vm)) 100.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (page == null) {
                        SETTINGS_SECTIONS.forEach { entry ->
                            SettingsPageRow(entry) { if (entry.id == "WhatsNew") vm.ui.whatsNewOpen = true else section = entry.id }
                        }
                    } else {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (page) {
                        "Connection" -> {
                            SettingsStaticCard("Server & account") {
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
                            // A router that moves the drive between addresses: the app tries these when the main one is silent.
                            PillTextField(
                                value = alternates,
                                onValueChange = { alternates = it },
                                label = "Other addresses (optional)",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Tried when the server address doesn't answer, e.g. 192.168.71.249. Separate several with commas.",
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
                            SettingsDivider()
                            // Remote access is part of reaching the server, so it lives here, folded away until needed.
                            SettingsAccordion(vm, "Connection", "vpn", "Remote access (VPN)",
                                "WireGuard, for reaching your server away from home") { VpnSettings() }
                            }
                        }
                        "Sync" -> SyncAndThumbnailsSettings(vm, scan = true, thumbnails = false)
                        "Artwork" -> {
                            SceneMetadataSettingsSection(vm, onPreviewVideo = onPreviewVideo)
                            ArtworkSettingsSection(vm)
                            SettingsAccordion(
                                vm, "Artwork", "thumbnail-tools",
                                "Thumbnail generation & reset",
                                "Generate, retry, clear cache and reset manually chosen artwork",
                            ) {
                                SyncAndThumbnailsSettings(vm, scan = false, thumbnails = true)
                                ResetChoicesSetting(vm)
                            }
                        }
                        "Layout" -> {
                            SettingsStaticCard("Visible folder sections") {
                                FolderTabsSetting(vm)
                            }
                            SettingsStaticCard("Navigation") {
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                RadioButton(selected = vm.navigationPosition == "SIDEBAR", onClick = { vm.updateNavigationPosition("SIDEBAR") })
                                TextButton(onClick = { vm.updateNavigationPosition("SIDEBAR") }) { Text(stringResource(R.string.ui_sidebar_f5171c9)) }
                                RadioButton(selected = vm.navigationPosition == "FLOATING", onClick = { vm.updateNavigationPosition("FLOATING") })
                                TextButton(onClick = { vm.updateNavigationPosition("FLOATING") }) { Text(stringResource(R.string.ui_floating_bottom_bar_b162779)) }
                            }
                            }
                            SettingsStaticCard("Folder pages") {
                                GridSizeSetting(vm, folders = true)
                            }
                            SettingsStaticCard("Video pages") {
                                GridSizeSetting(vm, folders = false)
                            }
                            SettingsStaticCard("Hidden files and folders") {
                                OutlinedButton(onClick = { vm.ui.setFlag("settings.hidden", true) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ui_unhide_files_and_folders_64749bf)) }
                            }
                        }
                        "Appearance" -> SettingsStaticCard("Themes & readability") { ThemeSettings() }
                        "Diagnostics" -> {
                            SettingsStaticCard("Library backup & restore") {
                                LibraryBackupActions(vm)
                            }
                            SettingsStaticCard("Performance & diagnostics") {
                                DiagnosticsSettings()
                            }
                        }
                        else -> Unit
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Resets of what you chose by hand, kept apart: covers and portraits (artwork), and thumbnails you picked for
 * videos. Each asks first. Nothing on your drive changes.
 */
@Composable
private fun ResetChoicesSetting(vm: LibraryViewModel) {
    var confirm by remember { mutableStateOf<String?>(null) }
    ScanSection("Reset your choices") {
        Text(
            "Covers, portraits and thumbnails you chose stay saved, even for folders and videos you hide, take out or delete, and come back when they return. Reset them here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { confirm = "artwork" }, modifier = Modifier.weight(1f)) { Text("Reset artwork") }
            OutlinedButton(onClick = { confirm = "thumbnails" }, modifier = Modifier.weight(1f)) { Text("Reset thumbnails") }
        }
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "artwork") "Reset all artwork?" else "Reset all thumbnails?") },
            text = {
                Text(
                    if (what == "artwork") {
                        "Every cover, crop and portrait you chose or the app found is removed, including saved ones. Folders and videos go back to their automatic covers. Thumbnails you picked for videos are kept."
                    } else {
                        "Every thumbnail you picked for a video is removed, and the automatic thumbnail shows again. Covers and portraits are kept."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "artwork") vm.resetAllArtwork() else vm.resetAllCustomThumbnails()
                    confirm = null
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

/** A small heading inside a section. */
@Composable
internal fun SettingsLabel(text: String) {
    // One strong visual hierarchy shared by *all* Settings pages.
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Box(
            Modifier.padding(end = 10.dp).width(4.dp).height(23.dp)
                .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(8.dp)),
        )
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** A thin line between the parts of a section (instead of boxes inside boxes). */
@Composable
internal fun SettingsDivider() {
    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** One setting on one line: its name (and an optional short note) on the left, the switch on the right. */
@Composable
internal fun SwitchRow(title: String, note: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A row that opens more settings in place. */
@Composable
private fun ExpandRow(title: String, note: String, open: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = if (open) "Close $title" else "Open $title",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (open) 90f else 0f),
        )
    }
}

/** Grid or list, and how many columns, chosen separately for portrait and landscape. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun GridSizeSetting(vm: LibraryViewModel, folders: Boolean) {
    val list = if (folders) vm.folderListMode else vm.videoListMode
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (folders) "Show folders as a list" else "Show videos as a list",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            Switch(checked = list, onCheckedChange = { if (folders) vm.chooseFolderListMode(it) else vm.chooseVideoListMode(it) })
        }
        if (!list) {
            for (landscape in listOf(false, true)) {
                Text(
                    if (landscape) "Columns in landscape" else "Columns in portrait",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryLayout.presets.forEach { preset ->
                        FilterChip(
                            selected = vm.gridPreset(folders, landscape) == preset,
                            onClick = { vm.updateGridPreset(folders, landscape, preset) },
                            label = { Text(preset) },
                        )
                    }
                }
            }
            Text(
                "A narrow screen may show fewer columns than chosen so cards stay readable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
