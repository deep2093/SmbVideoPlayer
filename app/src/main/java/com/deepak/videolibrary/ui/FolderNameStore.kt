package com.deepak.videolibrary.ui

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/** Names the user gave original folders in this app. The drive keeps its own names. Saved per server. */
class FolderNameStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("folder_names", Context.MODE_PRIVATE)
    // Changes made while the app runs, so screens update at once (null = name removed).
    private val changed = mutableStateMapOf<String, String?>()

    private fun key(server: String, path: String) = "$server|${path.trim('/')}"

    fun get(server: String, path: String): String? {
        val k = key(server, path)
        return if (changed.containsKey(k)) changed[k] else prefs.getString(k, null)
    }

    fun set(server: String, path: String, name: String?) {
        val k = key(server, path)
        changed[k] = name
        if (name == null) prefs.edit().remove(k).apply() else prefs.edit().putString(k, name).apply()
    }
}
