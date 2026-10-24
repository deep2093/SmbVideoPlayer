# SkyWing implementation and review notes

This branch implements the supplied SkyWing handoff on top of `aa47bb0`.

## What changes

- Display name is SkyWing / SkyWing Preview in the launcher, in-app headers, diagnostics, APK artifacts and current README. The intro now uses the wing/play mark.
- Light and dark adaptive icon backgrounds, gradient wing/play foreground, monochrome themed icon, round resources and legacy density icons. The adaptive mark is scaled into the central 66dp safe region of a 108dp layer. No manual launcher aliases are enabled. Launcher colors follow the system; the in-app theme is independent.
- Four actual app themes: Aurora Glass Dark (`LIQUID`), Aurora Glass Light (`SKYWING_AURORA_LIGHT`), Crystal Glass Dark (`SKYWING_CRYSTAL_DARK`), Crystal Glass Light (`LIQUID_GLASS`). Every other theme remains available. Appearance includes selectable native palette/dock previews. Preferences retain the same file and key.
- The existing content/wallpaper hierarchy, themed cards and Haze 1.6.1 floating dock use the new palettes. Glass themes show the dock on phones and a translucent sidebar when the tablet sidebar layout is selected. The stored navigation preference is retained.
- Reduce transparency replaces panels with opaque tints and disables dock backdrop blur. Existing Compose animation scale handling remains in place.
- `LibraryPlayerControls` keeps its existing contract and forwards to shared `SkyWingPlayerChrome` for both Media3 and LibVLC. Glass title/back/action chips, central play/pause and skip controls, floating timeline and action dock replace the old VLC-style presentation. Both engines expose queue and direct PiP actions. Media3's existing brightness/volume feedback becomes transient side rails.
- Player overlays use dark translucent tints even in light app themes, with no video frame capture or blur. The engines, data sources, gesture recognizers, decoding, track dialogs and auto-hide ownership remain in place. Playback state is not created by the chrome.
- New downloads go to `Download/SkyWing`. Existing completed and in-progress items keep their saved MediaStore URIs. Enqueue checks for an accessible completed file in the same server/source/destination before downloading it again. No files are moved, renamed or deleted during the brand change.

## Compatibility boundaries

Application ID `com.deepak.videolibrary`, `.preview` suffix, Kotlin package, theme IDs, database filenames/schema versions, preference identities, authorities and existing signing configurations stay unchanged. Historic changelogs and technical identifiers such as `VideoLibraryTheme`, Android style IDs, baseline profile symbols and package paths retain their old spelling deliberately. The legacy download path remains documented only for compatibility.

All existing original/custom/virtual folders, artwork, accounts, history, VPN configuration, scans and file actions remain owned by their existing implementations. This change does not add new metadata categories or a replacement library model.

## Key files

- `Theme.kt`, `SkyWingThemes.kt`, `SkyWingThemeChoices.kt`: catalog, persistence, color mapping and Appearance.
- `FloatingLibraryNavigation.kt`, `SkyWingGlassSurface.kt`, `AppRoot.kt`: material and responsive navigation.
- `LibraryPlayerControls.kt`, `SkyWingPlayerChrome.kt`, `SkyWingQueueDialog.kt`: shared playback presentation.
- `player/PlayerScreen.kt`, `ui/VlcPlayerScreen.kt`, `MainActivity.kt`: existing engine callback integration and PiP overlay visibility.
- `LaunchIntro.kt`, `res/drawable*`, `res/mipmap*`, `app/build.gradle.kts`: branding and icons.
- `DownloadPaths.kt`, `DownloadStore.kt`, `LibraryViewModel.kt`: non-destructive download compatibility.
- `.github/workflows/android.yml`, `benchmark.yml`: consistent artifact names; PR validation uses the existing pipeline.

## Validation

The initial remote Kotlin build found a composable accidentally inserted in VLC's BackHandler. That was corrected before handoff. Current definitive status is the latest run attached to PR #4, not an earlier failed run.

Local XML parsing and `git diff --check` pass. Local Android builds cannot start because the environment lacks the Android SDK and cannot download Gradle. GitHub Actions runs the real Kotlin/JUnit, lint, debug APK, instrumentation APK, optimized/R8 APK and previous-preview upgrade checks.

Added checks cover catalog uniqueness and old IDs, all four live persisted Appearance choices, system day/night icon resolution, shared player callbacks and unknown duration, and existing download URI lookup boundaries.

`SkyWingVisualTest` is an opt-in capture of the real app composables with an isolated synthetic indexed library. The Android 15 device job captures Folders, Videos, Files, Settings, Connection, Appearance and shared player controls in all four themes on phone and tablet sizes. The player controls now use the earlier gradient layout and outlined play button; queue and PiP actions remain. Player images are controls previews on a synthetic background, not proof of Media3/LibVLC decoding. Files may show a disconnected state when no fixture connection is configured. The existing SMB fixture/playback checks remain separate.

## Device acceptance still required

Not tested on the user's physical device/server: a 10-minute 4K SMB stream, HDR, actual WMV files, remote WireGuard, dropped frames/battery, headset/audio focus, physical launcher masks and system light/dark icon refresh. Emulator tests cannot establish those results. Inspect bright/dark scenes, large fonts, landscape, PiP, track switching, gestures, resumes and queue boundaries in both engines before merging. Existing VLC gesture capabilities are preserved; the new side-rail display is driven by Media3's existing brightness/volume recognizer.

No exact pixel match to conceptual reference artwork or measured playback performance is claimed. The real screenshot artifacts and test results are the evidence to review. Keep this as a draft until build, visual and device review are satisfactory.
