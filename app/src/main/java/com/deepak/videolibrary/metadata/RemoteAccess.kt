package com.deepak.videolibrary.metadata

import android.content.Context
import com.deepak.videolibrary.vpn.VpnState
import com.deepak.videolibrary.vpn.WireGuardManager

/**
 * Remote access = the VPN tunnel to the drive is up. While it is, nothing heavy starts by itself:
 * - scene, poster and portrait lookups run only when you ask (pull down to refresh, "Fetch scenes/posters", Settings);
 * - library scans run only from Refresh or when you add folders (the 12-hour refresh and the periodic sync wait);
 * - thumbnails and video lengths (which read the video files) wait until you are home, or until you start thumbnails
 *   yourself in Settings.
 */
object RemoteAccess {
    fun active(context: Context): Boolean =
        runCatching { WireGuardManager.get(context.applicationContext).state.value is VpnState.Connected }.getOrDefault(false)
}
