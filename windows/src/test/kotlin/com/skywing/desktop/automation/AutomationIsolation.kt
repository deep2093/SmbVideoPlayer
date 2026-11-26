package com.skywing.desktop.automation

import java.io.File

/** The isolation check the screen tests share: what the user's real SkyWing has, and what SkyWing leaves in the temp folder. */
internal object AutomationIsolation {
    private val THUMBNAIL_PLAYER_FOLDER = Regex("skywing-thumb[0-9]+")

    /** The image folders of SkyWing's thumbnail players in the temp folder the launched SkyWing uses (TMP, as Windows picks it). */
    fun thumbnailPlayerFolders(): Set<String> {
        val temp = (System.getenv("TMP") ?: System.getenv("TEMP"))?.let(::File) ?: return emptySet()
        return temp.listFiles { f -> f.isDirectory && THUMBNAIL_PLAYER_FOLDER.matches(f.name) }?.map { it.name }?.toSet().orEmpty()
    }

    /** The user's real SkyWing preferences (registry) and data folder, as text, to compare before and after. */
    fun realUserState(): String {
        val process = ProcessBuilder("reg", "query", """HKCU\Software\JavaSoft\Prefs\com\skywing""", "/s").redirectErrorStream(true).start()
        val registry = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        // The test JVM's LOCALAPPDATA is redirected (build.gradle.kts); the real folder comes in a property.
        val real = System.getProperty("skywing.test.realLocalAppData")?.takeIf { it.isNotBlank() } ?: System.getenv("LOCALAPPDATA").orEmpty()
        val data = File(real, "SkyWing/library")
        val files = data.listFiles()?.sortedBy { it.name }?.joinToString("\n") { "${it.name} ${it.length()} ${it.lastModified()}" } ?: "none"
        return "$registry\n$files"
    }
}
