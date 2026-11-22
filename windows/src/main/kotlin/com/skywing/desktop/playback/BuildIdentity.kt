package com.skywing.desktop.playback

import java.util.Properties

/**
 * Which build this is, as `generateBuildInfo` in build.gradle.kts recorded it in skywing-build.properties: the commit,
 * whether windows/ had uncommitted changes, the libmpv runtime staged for it (stock, or frame-sharing when
 * MPV-PREVIEW-MANIFEST.json was staged with it) and whether it contains ed072bf, the watchdog fix that keeps the latest
 * motion heartbeat across missed polls. The playback report shows it, and CI names the installers after it.
 */
internal data class BuildIdentity(
    val version: String,
    val commit: String,
    val localChanges: Boolean,
    val runtime: String,
    val watchdogFix: String,
) {
    /**
     * The playback report line. [shareFrames] is SKYWING_VS_SHARE_FRAMES: the frame-sharing libmpv shares its output
     * planes only while it is unset or 1, so any other value is the original output copy used for A/B tests.
     */
    fun reportLine(shareFrames: String? = System.getenv("SKYWING_VS_SHARE_FRAMES")): String {
        val changes = if (localChanges) " with local changes" else ""
        val sharing = when {
            runtime != "frame-sharing" -> ""
            shareFrames == null || shareFrames == "1" -> ", sharing on"
            else -> ", sharing off (SKYWING_VS_SHARE_FRAMES=$shareFrames)"
        }
        return "Build: commit $commit$changes; runtime $runtime$sharing; watchdog fix ed072bf $watchdogFix"
    }

    companion object {
        /** This build; every value is unknown when the app runs without the generated resource. */
        val current: BuildIdentity by lazy {
            val text = BuildIdentity::class.java.getResourceAsStream("/skywing-build.properties")?.use { it.readBytes().decodeToString() }
            parse(text.orEmpty())
        }

        fun parse(text: String): BuildIdentity {
            val values = Properties().apply { load(text.reader()) }
            fun value(key: String, missing: String = "unknown") = values.getProperty(key)?.takeIf { it.isNotBlank() } ?: missing
            return BuildIdentity(
                version = value("version", missing = "development"),
                commit = value("commit"),
                localChanges = values.getProperty("localChanges").toBoolean(),
                runtime = value("runtime"),
                watchdogFix = value("watchdogFix"),
            )
        }
    }
}
