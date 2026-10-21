package com.deepak.videolibrary.ui

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

/** The Google Images results page for [query] (image search, safe for the app to open in its own browser view). */
internal fun googleImagesUrl(query: String): String =
    "https://www.google.com/search?tbm=isch&q=" + URLEncoder.encode(query.trim(), "UTF-8")

/**
 * Google Images inside Change cover, without any API: the search box holds the file or folder name, results open
 * in a small browser view, and holding a picture offers it as the cover. Only what you search is sent, to Google.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun GoogleImagesTab(
    query: String,
    onQuery: (String) -> Unit,
    choices: List<String>,
    enabled: Boolean,
    onPicture: (String) -> Unit,
) {
    var loaded by remember { mutableStateOf<String?>(null) }
    var offered by remember { mutableStateOf<String?>(null) }
    Text(
        "Search Google Images here, then hold a picture to use it. For a sharper cover, tap a result first and hold the larger picture. What you search is sent to Google.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text("Search") },
            maxLines = 2,
            shape = RoundedCornerShape(20.dp),
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { onQuery("") }) { Icon(Icons.Filled.Close, contentDescription = "Clear") } }
            } else {
                null
            },
            modifier = Modifier.weight(1f),
        )
        Button(onClick = { loaded = googleImagesUrl(query) }, enabled = enabled && query.isNotBlank()) { Text("Search") }
    }
    if (choices.size > 1) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { choice ->
                SuggestionChip(onClick = { onQuery(choice); loaded = googleImagesUrl(choice) }, label = { Text(choice, maxLines = 1) })
            }
        }
    }
    val url = loaded
    if (url != null) {
        GoogleWebView(url, Modifier.fillMaxWidth().height(380.dp).clip(RoundedCornerShape(14.dp))) { address -> offered = address }
    }
    offered?.let { address ->
        AlertDialog(
            onDismissRequest = { offered = null },
            title = { Text("Use this picture?") },
            text = { Text("It will be saved in the app as this item's cover. Make sure you may use it.") },
            confirmButton = {
                TextButton(onClick = {
                    offered = null
                    onPicture(address)
                }) { Text("Use picture") }
            },
            dismissButton = { TextButton(onClick = { offered = null }) { Text("Cancel") } },
        )
    }
}

/** A plain browser view. Holding a picture reports its address (https or an embedded data: picture). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GoogleWebView(url: String, modifier: Modifier, onImage: (String) -> Unit) {
    var view by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            view?.apply {
                stopLoading()
                destroy()
            }
            view = null
        }
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false)
                // Links open in this view; only web pages, never other apps or files.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                        val scheme = request.url.scheme?.lowercase()
                        return scheme != "https" && scheme != "http"
                    }
                }
                setOnLongClickListener {
                    val hit = hitTestResult
                    when (hit.type) {
                        WebView.HitTestResult.IMAGE_TYPE -> {
                            hit.extra?.let(onImage)
                            true
                        }
                        WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                            // A picture inside a link: ask for the picture's own address.
                            val message = Handler(Looper.getMainLooper()) { msg ->
                                (msg.data.getString("url") ?: msg.data.getString("src") ?: (msg.obj as? String) ?: hit.extra)?.let(onImage)
                                true
                            }.obtainMessage()
                            requestImageRef(message)
                            true
                        }
                        else -> false
                    }
                }
                view = this
                tag = url
                loadUrl(url)
            }
        },
        update = { web ->
            if (web.tag != url) {
                web.tag = url
                web.loadUrl(url)
            }
        },
    )
}
