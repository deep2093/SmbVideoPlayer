package com.deepak.videolibrary.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Local, case-insensitive filtering of just the opened folder level. */
@Composable
internal fun FolderLevelSearch(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        singleLine = true,
        placeholder = { androidx.compose.material3.Text("Search in this folder") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotBlank()) IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Default.Close, contentDescription = "Clear search")
            }
        },
    )
}
