# SkyWing Windows x64 (foundation preview)

This is a deliberately separate Windows-only Kotlin/Compose Desktop Gradle build.
It starts from `feature/skywing-brand-and-glass` and **does not change** the Android `app/` module, Android Gradle settings or CI jobs.

## Build on Windows 10/11 x64
1. Install JDK 17 and ensure `java -version` works.
2. In the repo root: `gradlew.bat -p windows run` (or inside `windows`: `..\gradlew.bat -p . run`).
3. For an EXE: `gradlew.bat -p windows packageExe`.
4. For an MSI: `gradlew.bat -p windows packageMsi`.
   A local package is numbered 0.1.1 (CI uses 0.1.<run number>). Packaging stops if this PC has a newer SkyWing
   installed; see `TESTING-POLICY.md`, section 4, for `GITHUB_RUN_NUMBER` and `-Pskywing.allowLowerVersion=true`.

Windows packaging requires a Windows host and suitable system tooling. No pre-built Windows executable has been produced or tested yet. 
## What the Windows app looks like now
The Windows UI follows the Android app: the same four SkyWing glass themes (Settings > Appearance), a glass sidebar
(Folders, Videos, Files, Settings), cover cards with real thumbnails, Custom / Virtual / Original folders, Continue
watching, selection with Add to folder, Settings pages that slide in, and Android-style player controls under the video.
Thumbnails are grabbed with a short, windowless libmpv run and cached in `%LOCALAPPDATA%\SkyWing\library\thumbs`.
Network shares use the current Windows sign-in (no SMB library yet).

See [the full Windows x64 roadmap](../docs/WINDOWS-X64-PROJECT-PLAN.md) for the stages, HDR/Dolby limits and acceptance gates.

## If the window draws wrongly
SkyWing draws its interface with Skia on Direct3D 12, and Skia falls back to OpenGL or software drawing by itself
when Direct3D can't start. If a graphics driver starts Direct3D but draws the window wrongly, set the environment
variable `SKIKO_RENDER_API` to `OPENGL` (or `SOFTWARE`, which is slower) before SkyWing starts. Skia reads it at
start. The video itself is drawn by libmpv and doesn't change.
