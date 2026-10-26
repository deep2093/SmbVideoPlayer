package com.skywing.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.friwi.jcefmaven.CefAppBuilder
import org.cef.CefApp
import org.cef.CefClient
import org.cef.browser.CefBrowser
import java.awt.BorderLayout
import java.io.File
import javax.swing.JPanel

/** One Chromium runtime for Google Images; its native files live in the user's writable app-data directory. */
private object GoogleBrowserRuntime {
    @Volatile private var app: CefApp? = null

    @Synchronized fun get(): CefApp {
        app?.let { return it }
        val base = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() } ?: System.getProperty("user.home")
        val builder = CefAppBuilder()
        builder.setInstallDir(File(base, "SkyWing/browser"))
        builder.getCefSettings().windowless_rendering_enabled = false
        val created = builder.build()
        app = created
        Runtime.getRuntime().addShutdownHook(Thread({ created.dispose() }, "skywing-browser-shutdown"))
        return created
    }
}

private class GoogleBrowserSession(val client: CefClient, val browser: CefBrowser) {
    fun close() {
        browser.close(true)
        client.dispose()
    }
}

/** Google search stays in the cover window; a copied image still enters the existing cover positioning flow. */
@Composable
internal fun EmbeddedGoogleImages(url: String) {
    val theme = LocalSkyTheme.current
    val ready by produceState<Result<CefApp>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { GoogleBrowserRuntime.get() } }
    }
    var session by remember { mutableStateOf<GoogleBrowserSession?>(null) }
    val latestSession by rememberUpdatedState(session)
    DisposableEffect(Unit) { onDispose { latestSession?.close() } }
    when {
        ready == null -> Box(Modifier.fillMaxWidth().height(350.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = theme.accent)
        }
        ready!!.isFailure -> Text("The in-app browser could not start: ${ready!!.exceptionOrNull()?.message ?: "Unknown error"}", color = theme.muted)
        else -> {
            val app = ready!!.getOrThrow()
            SwingPanel(
                modifier = Modifier.fillMaxWidth().height(350.dp),
                factory = {
                    val client = app.createClient()
                    val browser = client.createBrowser(url, false, false)
                    session = GoogleBrowserSession(client, browser)
                    JPanel(BorderLayout()).apply { add(browser.uiComponent, BorderLayout.CENTER) }
                },
            )
            LaunchedEffect(url, session) { session?.browser?.loadURL(url) }
        }
    }
}
