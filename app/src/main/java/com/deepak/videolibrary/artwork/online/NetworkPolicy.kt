package com.deepak.videolibrary.artwork.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.deepak.videolibrary.artwork.ArtworkSettings

/** Applies the user's "Wi-Fi only" and "metered downloads" choices to artwork downloads. */
object NetworkPolicy {
    fun allowed(context: Context, settings: ArtworkSettings): Boolean {
        val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        if (metered && !settings.allowMetered) return false
        if (settings.wifiOnly && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return false
        return true
    }
}
