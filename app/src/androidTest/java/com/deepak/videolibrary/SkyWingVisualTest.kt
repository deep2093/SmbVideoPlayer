package com.deepak.videolibrary

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.deepak.videolibrary.data.VideoEntity
import com.deepak.videolibrary.settings.SettingsStore
import com.deepak.videolibrary.thumbs.ThumbStore
import com.deepak.videolibrary.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in screenshots of real composables with isolated synthetic library data, not marketing mockups.
 * The player image is explicitly a chrome preview, not evidence of video decoding or HDR performance.
 */
class SkyWingVisualTest {
    @get:Rule val rule = createComposeRule()

    @Test fun captureFourThemesAcrossRealSurfaces() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("skywingVisual") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val services = ServiceLocator(app, "skywing-visual-${UUID.randomUUID()}", SettingsStore(app))
        val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        val vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = LibraryViewModel(app, services) as T
        })[LibraryViewModel::class.java]
        val prefs = app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
        val prior = prefs.getString("theme", "CHARCOAL")
        val priorReduction = prefs.getBoolean("reduce_transparency", false)
        val theme = mutableStateOf("LIQUID")
        val player = mutableStateOf(false)
        val showing = mutableStateOf(true)
        val output = File(app.getExternalFilesDir(null), "skywing-visual").apply { mkdirs() }
        val device = args.getString("visualDevice", "phone")
        try {
            ThumbStore.thumbDir(services.artworkContext).mkdirs()
            repeat(12) { i ->
                val title = listOf("Mountain", "Ocean", "Night Sky")[i % 3]
                val id = services.db.dao().insertVideo(VideoEntity(
                    relativePath = "Demo/$title - ${i + 1}.mp4", name = "$title - ${i + 1}.mp4",
                    parentDir = "Demo", size = 100_000, modified = 1, groupKey = title,
                    thumbStatus = "DONE", lastSeenSession = 1, addedAt = 1,
                ))
                val bitmap = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(listOf(0xFF244A66.toInt(), 0xFF356879.toInt(), 0xFF332E69.toInt())[i % 3])
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.WHITE; textSize = 26f }
                canvas.drawCircle(230f, 115f, 60f, paint.apply { alpha = 100 })
                paint.alpha = 255
                canvas.drawText(title, 22f, 380f, paint)
                ThumbStore.thumbFile(services.artworkContext, id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                bitmap.recycle()
            }
            val set = vm.createVirtualSet("Demo library")!!
            vm.setVirtualSetMembers(set, listOf("Demo"), emptyList())
            vm.openVirtualSetId = set
            rule.setContent {
                if (showing.value) key(theme.value, player.value) {
                    VideoLibraryTheme {
                        if (player.value) Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF377C95), Color(0xFF09233B))))) {
                            LibraryPlayerControls(title = "SkyWing controls preview", position = 85_000, duration = 360_000,
                                isPlaying = true, canNext = true, queueLabel = "1 / 12", aspectLabel = "Fit",
                                onBack = {}, onTogglePlayback = {}, onSeek = {}, onTracks = {}, onOptions = {},
                                onRotate = {}, onPip = {}, onQueue = {})
                        } else AppRoot(vm)
                    }
                }
            }
            fun capture(name: String) {
                rule.waitForIdle()
                rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
                    File(output, "$device-${theme.value}-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                }
            }
            skyWingGlassThemes.forEach { palette ->
                rule.runOnIdle {
                    prefs.edit().putString("theme", palette.id).putBoolean("reduce_transparency", false).commit()
                    theme.value = palette.id
                    player.value = false
                }
                rule.waitForIdle()
                capture("folders")
                listOf("Videos", "Files", "Settings").forEach { label ->
                    rule.onAllNodesWithText(label).onLast().performClick()
                    capture(label.lowercase())
                }
                rule.runOnIdle { player.value = true }
                capture("player-chrome-preview")
            }
        } finally {
            rule.runOnIdle { showing.value = false }
            rule.waitForIdle()
            prefs.edit().putString("theme", prior).putBoolean("reduce_transparency", priorReduction).commit()
            owner.viewModelStore.clear()
            services.db.close()
        }
    }
}
