package com.deepak.videolibrary.ui

import androidx.compose.ui.graphics.Color

/**
 * SkyWing glass theme catalog for the EXISTING Theme.kt architecture.
 *
 * Integration:
 * 1) Place this file beside Theme.kt in com.deepak.videolibrary.ui.
 * 2) In Theme.kt's `libraryThemes = listOf(...)`, replace the old LIQUID and
 *    LIQUID_GLASS entries with `*skyWingGlassThemes.toTypedArray(),`.
 * 3) Preserve those two IDs; they're previously saved in SharedPreferences.
 * 4) Retain every other theme. No database migration is necessary.
 *
 * The four selectable themes are two distinct MATERIALS with dark/light variants.
 * Compatible with the existing LibraryTheme, WallpaperGlow and VideoLibraryTheme.
 */
val skyWingGlassThemes: List<LibraryTheme> = listOf(
    LibraryTheme(
        id = "LIQUID", // Existing Aurora Glass ID: never break previous selections.
        label = "Aurora Glass · Dark",
        background = Color(0xFF070D26),
        surface = Color(0x7A111C46),
        card = Color(0x3D86A8F9),
        accent = Color(0xFF7FCDFF),
        glass = true,
        wallpaper = listOf(Color(0xFF050B22), Color(0xFF192562), Color(0xFF271458), Color(0xFF071B39)),
        glows = listOf(
            WallpaperGlow(0.00f, 0.08f, 0.60f, Color(0x8B243FBD)),
            WallpaperGlow(0.98f, 0.22f, 0.70f, Color(0x804E28B8)),
            WallpaperGlow(0.48f, 0.95f, 0.65f, Color(0x5520C6E9)),
        ),
        glassTint = Color(0x9A10173A),
        menu = Color(0xFF15234C),
        light = false,
    ),
    LibraryTheme(
        id = "SKYWING_AURORA_LIGHT",
        label = "Aurora Glass · Light",
        background = Color(0xFFF0F4FE),
        surface = Color(0xBCE9F5FF),
        card = Color(0xA6FFFFFF),
        accent = Color(0xFF1468EF),
        glass = true,
        wallpaper = listOf(Color(0xFFEDF6FF), Color(0xFFE6EEFF), Color(0xFFF5EAFD), Color(0xFFDBF7FC)),
        glows = listOf(
            WallpaperGlow(0.10f, 0.00f, 0.58f, Color(0x9996DFFF)),
            WallpaperGlow(0.92f, 0.62f, 0.58f, Color(0x80C3A8FF)),
            WallpaperGlow(0.25f, 0.93f, 0.55f, Color(0x66A8F0FF)),
        ),
        glassTint = Color(0x98E9F4FF),
        menu = Color(0xFFF1F5FF),
        light = true,
    ),
    LibraryTheme(
        id = "SKYWING_CRYSTAL_DARK",
        label = "Crystal Glass · Dark",
        background = Color(0xFF101B28),
        surface = Color(0x7C172C3C),
        card = Color(0x3C8CACB9),
        accent = Color(0xFF96CFFF),
        glass = true,
        wallpaper = listOf(Color(0xFF0A1622), Color(0xFF1E3548), Color(0xFF101C2A), Color(0xFF293F51)),
        glows = listOf(
            WallpaperGlow(0.62f, 0.16f, 0.76f, Color(0x557DAAC8)),
            WallpaperGlow(0.08f, 0.92f, 0.75f, Color(0x4D4B7DA0)),
        ),
        glassTint = Color(0xA21A2A3D),
        menu = Color(0xFF203348),
        light = false,
    ),
    LibraryTheme(
        id = "LIQUID_GLASS", // Existing Transparent ID becomes Crystal Glass Light.
        label = "Crystal Glass · Light",
        background = Color(0xFFF6F9FC),
        surface = Color(0xB8FFFFFF),
        card = Color(0xACFFFFFF),
        accent = Color(0xFF2774D6),
        glass = true,
        wallpaper = listOf(Color(0xFFFAFCFE), Color(0xFFEBF3F9), Color(0xFFF6F5F2), Color(0xFFE8F2F7)),
        glows = listOf(
            WallpaperGlow(0.88f, 0.05f, 0.70f, Color(0x66C8E9FB)),
            WallpaperGlow(0.12f, 0.75f, 0.78f, Color(0x669ECBDD)),
            WallpaperGlow(0.65f, 1.00f, 0.68f, Color(0x55E8D4C4)),
        ),
        glassTint = Color(0xA8F4FAFF),
        menu = Color(0xFFF6F9FC),
        light = true,
    ),
)

/** Helpful for deciding whether UI uses restrained Crystal or vibrant Aurora optics. */
fun LibraryTheme.skyWingGlassFamily(): String? = when (id) {
    "LIQUID", "SKYWING_AURORA_LIGHT" -> "AURORA"
    "SKYWING_CRYSTAL_DARK", "LIQUID_GLASS" -> "CRYSTAL"
    else -> null
}
