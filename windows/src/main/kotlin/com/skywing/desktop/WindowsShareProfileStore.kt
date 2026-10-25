package com.skywing.desktop

import java.util.prefs.Preferences

internal data class WindowsShareProfile(
    val name: String,
    val primaryAddress: String,
    val alternateAddress: String,
)

internal class WindowsShareProfileStore {
    private val preferences = Preferences.userNodeForPackage(WindowsShareProfileStore::class.java)
        .node("smb-share-profiles")

    @Synchronized
    fun load(): List<WindowsShareProfile> {
        val count = preferences.getInt("count", 0).coerceIn(0, MAX_PROFILES)
        return (0 until count).mapNotNull { index ->
            val name = preferences.get("name." + index, null)
            val primary = preferences.get("primary." + index, null)
            val alternate = preferences.get("alternate." + index, "") ?: ""
            if (name.isNullOrBlank() || primary.isNullOrBlank()) null
            else WindowsShareProfile(name, primary, alternate)
        }
    }

    @Synchronized
    fun save(profile: WindowsShareProfile): List<WindowsShareProfile> {
        require(profile.name.isNotBlank()) { "Enter a profile name" }
        require(profile.primaryAddress.isNotBlank()) { "Enter a network path" }
        val profiles = load().filterNot { it.name.equals(profile.name, ignoreCase = true) } + profile
        require(profiles.size <= MAX_PROFILES) { "You can save up to " + MAX_PROFILES + " share profiles" }
        write(profiles)
        return profiles
    }

    @Synchronized
    fun delete(name: String): List<WindowsShareProfile> {
        val profiles = load().filterNot { it.name == name }
        write(profiles)
        return profiles
    }

    private fun write(profiles: List<WindowsShareProfile>) {
        val oldCount = preferences.getInt("count", 0).coerceIn(0, MAX_PROFILES)
        repeat(maxOf(oldCount, profiles.size)) { index ->
            preferences.remove("name." + index)
            preferences.remove("primary." + index)
            preferences.remove("alternate." + index)
        }
        profiles.forEachIndexed { index, profile ->
            preferences.put("name." + index, profile.name)
            preferences.put("primary." + index, profile.primaryAddress)
            preferences.put("alternate." + index, profile.alternateAddress)
        }
        preferences.putInt("count", profiles.size)
        preferences.flush()
    }

    companion object {
        private const val MAX_PROFILES = 20
    }
}
