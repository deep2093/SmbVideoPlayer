package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.deepak.videolibrary.ServiceLocator
import com.deepak.videolibrary.metadata.SceneMetadataClient
import com.deepak.videolibrary.metadata.SceneMetadataSettings
import com.deepak.videolibrary.metadata.SceneMetadataWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** Separate Settings page. Artwork defaults, manually chosen covers and virtual folders are untouched. */
@Composable
internal fun SceneMetadataSettingsSection(vm: LibraryViewModel) {
    val context = LocalContext.current
    val service = remember(vm, context) { ServiceLocator.get(context) }
    val prefs = service.sceneMetadataPreferences
    val options by prefs.settings.collectAsStateWithLifecycle()
    val keyRevision by vm.providerKeyRevision.collectAsStateWithLifecycle()
    var consentOpen by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    Text("Scene metadata", style = MaterialTheme.typography.titleMedium)
    Text(
        "Fetch scene titles, studios, performers, dates and available artwork. Metadata is stored separately and never renames SMB files, changes virtual folders or replaces your existing covers.",
        style = MaterialTheme.typography.bodySmall,
    )
    SwitchRow("Enable online metadata", null, options.enabled) { enabled ->
        prefs.update { it.copy(enabled = enabled) }
        if (!enabled) SceneMetadataWorker.cancel(context, service.libraryId)
    }

    if (options.enabled) {
        SwitchRow(
            "Allow sending search terms and fingerprints",
            "Cleaned filenames, OSHash and optionally pHash can identify scenes. No SMB path or account credentials are sent.",
            options.allowedOnlineLookup,
        ) { allow ->
            if (allow) consentOpen = true
            else {
                prefs.update { it.copy(allowedOnlineLookup = false) }
                SceneMetadataWorker.cancel(context, service.libraryId)
            }
        }

        SettingsLabel("Search order")
        listOf(SceneMetadataSettings.STASHDB, SceneMetadataSettings.TPDB).forEach { id ->
            val name = if (id == SceneMetadataSettings.STASHDB) "StashDB" else "ThePornDB"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.RadioButton(
                    selected = options.primary == id,
                    onClick = { prefs.update { it.copy(primary = id) } },
                )
                TextButton(onClick = { prefs.update { it.copy(primary = id) } }) { Text("Search $name first") }
            }
        }
        Text("The second provider is searched if the first returns no match.", style = MaterialTheme.typography.bodySmall)

        SettingsDivider()
        SettingsLabel("Your API keys")
        SceneKeyEntry(vm, SceneMetadataSettings.STASHDB, "StashDB", keyRevision)
        SceneKeyEntry(vm, SceneMetadataSettings.TPDB, "ThePornDB", keyRevision)
        Text("Each key is encrypted using Android Keystore and is never included in a build or sent to the other provider.", style = MaterialTheme.typography.bodySmall)

        SettingsDivider()
        SwitchRow("Fetch automatically after scans", "Runs as a separate background worker on unmetered connections with battery safeguards.", options.automatic) {
            prefs.update { old -> old.copy(automatic = it) }
        }
        SwitchRow("Use video pHash matching", "Slower on SMB. Experimental Android compatibility; proposed pHash matches always require review.", options.phash) {
            prefs.update { old -> old.copy(phash = it) }
        }
        SwitchRow("Review uncertain matches", "Filename matches and multiple results wait for your confirmation.", options.reviewUncertain) {
            prefs.update { old -> old.copy(reviewUncertain = it) }
        }

        SettingsDivider()
        Button(onClick = {
            if (!options.allowedOnlineLookup) status = "Allow online lookups before starting."
            else {
                SceneMetadataWorker.enqueue(context, service.libraryId, manual = true)
                status = "Metadata lookup queued. Results are cached locally."
            }
        }, enabled = options.allowedOnlineLookup && (
            vm.providerHasKey(SceneMetadataSettings.STASHDB) || vm.providerHasKey(SceneMetadataSettings.TPDB)
        ), modifier = Modifier.fillMaxWidth()) { Text("Fetch metadata now") }

        OutlinedButton(onClick = { reviewOpen = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Review scene matches")
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    if (consentOpen) AlertDialog(
        onDismissRequest = { consentOpen = false },
        title = { Text("Enable online scene identification?") },
        text = { Text("This will send cleaned video titles and optional file fingerprints to StashDB and ThePornDB to find metadata. Hashes can uniquely identify content. Your SMB server address, credentials and full file paths will not be sent.") },
        confirmButton = { TextButton(onClick = {
            consentOpen = false
            prefs.update { it.copy(allowedOnlineLookup = true) }
        }) { Text("Allow lookups") } },
        dismissButton = { TextButton(onClick = { consentOpen = false }) { Text("Cancel") } },
    )
    if (reviewOpen) SceneMetadataReviewDialog(service, onDismiss = { reviewOpen = false })
}

@Composable
private fun SceneKeyEntry(vm: LibraryViewModel, provider: String, label: String, revision: Int) {
    val context = LocalContext.current
    val service = remember(context) { ServiceLocator.get(context) }
    var key by remember(provider) { mutableStateOf("") }
    var result by remember(provider) { mutableStateOf<String?>(null) }
    var testing by remember(provider) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val saved = remember(provider, revision) { vm.providerHasKey(provider) }
    Text(label, style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth(),
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        label = { Text(if (saved) "Key saved. Type to replace." else "API key") },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            if (vm.saveProviderKey(provider, key)) { key = ""; result = "Key saved securely." }
            else result = "Couldn't save key. Check its format and device Keystore."
        }, enabled = key.isNotBlank()) { Text("Save key") }
        TextButton(onClick = {
            testing = true
            scope.launch {
                result = try {
                    val ok = withContext(Dispatchers.IO) { SceneMetadataClient(service.providerKeys).test(provider) }
                    if (ok) "Connected successfully." else "The provider did not confirm this key."
                } catch (e: CancellationException) { throw e
                } catch (_: Exception) { "Connection failed. Check this key and network access." }
                testing = false
            }
        }, enabled = saved && !testing) { Text(if (testing) "Testing…" else "Test") }
        if (saved) TextButton(onClick = { vm.clearProviderKey(provider); result = "Key removed." }) { Text("Remove") }
    }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SceneMetadataReviewDialog(service: ServiceLocator, onDismiss: () -> Unit) {
    var refresh by remember { mutableIntStateOf(0) }
    val records = remember(refresh) { service.sceneMetadataStore.review(60) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review scene matches") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
            ) {
                if (records.isEmpty()) item { Text("No uncertain matches are waiting.") }
                items(records.size) { i ->
                    val record = records[i]
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(record.path.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall)
                        val suggestions = runCatching { JSONArray(record.suggestions ?: "[]") }.getOrDefault(JSONArray())
                        for (index in 0 until suggestions.length()) {
                            val proposed = suggestions.optJSONObject(index) ?: continue
                            val scene = proposed.optJSONObject("scene") ?: continue
                            Text(scene.optString("title", "Unknown"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${proposed.optString("provider")} • ${proposed.optString("method")}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = {
                                service.sceneMetadataStore.approve(record.path, proposed.toString())
                                refresh++
                            }) { Text("Confirm this scene") }
                        }
                        TextButton(onClick = {
                            service.sceneMetadataStore.dismiss(record.path)
                            refresh++
                        }) { Text("Dismiss suggestions") }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { refresh++ }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
