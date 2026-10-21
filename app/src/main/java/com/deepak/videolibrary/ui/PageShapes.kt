package com.deepak.videolibrary.ui

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Crop169
import androidx.compose.material.icons.filled.CropPortrait
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf

/**
 * Card shape per page: every card on one page has the same shape, either landscape (16:9, the default) or taller
 * (3:4). Each folder page (and each top-level folder list) has its own choice. Saved per server.
 */
class PageShapeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("page_shapes", Context.MODE_PRIVATE)
    private val changed = mutableStateMapOf<String, Boolean>()

    fun isTall(key: String): Boolean = changed[key] ?: prefs.getBoolean(key, false)

    fun setTall(key: String, tall: Boolean) {
        changed[key] = tall
        if (tall) prefs.edit().putBoolean(key, true).apply() else prefs.edit().remove(key).apply()
    }
}

/** Page names for card shapes. */
object Pages {
    const val CUSTOM = "custom"
    const val VIRTUAL = "virtual"
    const val VIDEOS = "videos"
    fun original(path: String) = "original:$path"
    fun virtualFolder(name: String) = "in-virtual:$name"
    fun customFolder(id: Long) = "in-custom:$id"
    fun ownFolder(id: String) = "in-own:$id"
    fun drivePick(path: String) = "in-drive:$path"
    fun explorer(path: String) = "explorer:$path"
}

/** Width / height of the cards on [page]. */
@Composable
internal fun cardAspect(vm: LibraryViewModel, page: String): Float =
    if (vm.isTallPage(page)) com.deepak.videolibrary.artwork.CoverCrop.TALL_ASPECT else com.deepak.videolibrary.artwork.CoverCrop.CARD_ASPECT

/** Switches the cards of [page] between landscape and taller. Shown in the page's top bar. */
@Composable
internal fun CardShapeButton(vm: LibraryViewModel, page: String) {
    val tall = vm.isTallPage(page)
    IconAction(
        if (tall) Icons.Filled.CropPortrait else Icons.Filled.Crop169,
        if (tall) "Cards on this page: taller (3:4). Tap for landscape" else "Cards on this page: landscape. Tap for taller (3:4)",
        onClick = { vm.setTallPage(page, !tall) },
    )
}
