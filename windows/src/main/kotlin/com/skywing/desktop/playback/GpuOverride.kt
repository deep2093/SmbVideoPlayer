package com.skywing.desktop.playback

/**
 * SKYWING_GPU, a test-only override for the M0 laptop runs (docs/modernise/M0-RESULTS.md): NVIDIA or Intel, in any
 * case, makes SkyWing draw the video and run motion on that GPU instead of the one that drives the window's monitor.
 * Unset, or any other value, keeps the monitor's GPU. The "Graphics for video" setting (plan 5.3, M2) replaces it.
 */
internal object GpuOverride {
    fun vendor(value: String? = System.getenv("SKYWING_GPU")): String? = when (value?.trim()?.lowercase()) {
        "nvidia" -> "NVIDIA"
        "intel" -> "Intel"
        else -> null
    }
}
