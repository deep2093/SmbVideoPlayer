package com.deepak.videolibrary.metadata

import android.content.Context
import com.deepak.videolibrary.vpn.VpnState
import com.deepak.videolibrary.vpn.WireGuardManager

/**
 * Remote access = the VPN tunnel to the drive is up. While it is, scene, poster and portrait lookups never start by
 * themselves (after a scan, a periodic sync or a settings change); they run only when you ask for them: pull down to
 * refresh, "Fetch scenes/posters" on a folder, or the buttons in Settings.
 */
object RemoteAccess {
    fun active(context: Context): Boolean =
        runCatching { WireGuardManager.get(context.applicationContext).state.value is VpnState.Connected }.getOrDefault(false)
}
