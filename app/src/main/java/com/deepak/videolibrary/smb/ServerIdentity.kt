package com.deepak.videolibrary.smb

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale
import java.util.UUID

/**
 * Remembers which SMB server answers on the main address, so the app never sends a login to a different device that
 * happens to answer on one of the alternate addresses.
 *
 * Every SMB server reports a fixed server ID (its GUID) before any login is sent. The ID from the main address is
 * always accepted and remembered (a replaced router or NAS simply updates it). An alternate address is only used when
 * it reports the same ID. Until an ID is known (the main address has never answered), the first server that logs in
 * successfully is remembered. Servers that report an all-zero ID cannot be told apart and are not checked.
 */
object ServerIdentity {
    private const val PREFS = "smb_server_identity"
    private val ZERO = UUID(0L, 0L)

    @Volatile private var prefs: SharedPreferences? = null
    private val memory = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Called once per process before any connection (ServiceLocator does it). Safe to call repeatedly. */
    fun init(context: Context) {
        if (prefs == null) prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun key(mainHost: String) = mainHost.trim().lowercase(Locale.ROOT)

    internal fun known(mainHost: String): String? =
        memory[key(mainHost)] ?: prefs?.getString(key(mainHost), null)?.also { memory[key(mainHost)] = it }

    /** True when [guid] identifies a server (not null or all zeros). */
    internal fun usable(guid: UUID?): Boolean = guid != null && guid != ZERO

    /**
     * Throws before any login is sent when [target] is an alternate address and reports a different server than the
     * one remembered for [mainHost].
     */
    fun check(mainHost: String, target: String, guid: UUID?) {
        if (target.trim().equals(mainHost.trim(), ignoreCase = true)) return
        if (!usable(guid)) return
        val pinned = known(mainHost) ?: return
        if (!pinned.equals(guid.toString(), ignoreCase = true)) throw SmbIdentityException()
    }

    /** Remembers [guid] after a successful login: always for the main address, otherwise only if nothing is known yet. */
    fun learn(mainHost: String, target: String, guid: UUID?) {
        if (!usable(guid)) return
        val main = target.trim().equals(mainHost.trim(), ignoreCase = true)
        if (!main && known(mainHost) != null) return
        val value = guid.toString()
        if (memory[key(mainHost)] == value) return
        memory[key(mainHost)] = value
        prefs?.edit()?.putString(key(mainHost), value)?.apply()
    }

    /** Forgets everything (tests). */
    internal fun clearForTest() {
        memory.clear()
        prefs = null
    }
}

/** An alternate address answered, but it is a different device from the remembered server. */
class SmbIdentityException : java.io.IOException("Alternate address belongs to a different server")
