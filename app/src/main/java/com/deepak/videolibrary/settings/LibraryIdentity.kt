package com.deepak.videolibrary.settings

import java.security.MessageDigest
import java.util.Locale

/** A library includes the account and starting folder, not just the share name. */
object LibraryIdentity {
    fun of(s: SmbSettings): String {
        val values = listOf(s.host.trim().lowercase(Locale.ROOT), s.share.trim().lowercase(Locale.ROOT),
            s.domain.trim().lowercase(Locale.ROOT), s.username.trim().lowercase(Locale.ROOT), SettingsStore.normalizeRoot(s.rootPath))
        return MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u0000").toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
