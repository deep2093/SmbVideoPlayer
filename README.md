# Video Library (Android)

Personal SMB video-library Android project. Open the repository root in Android Studio, or push to GitHub and run **Actions → Android APK → Run workflow**. The workflow uploads `app-debug.apk` as an artifact.

## Changes in this update
- Added root Gradle project files so GitHub Actions/Android Studio can recognize the project.
- Added a custom launcher vector icon and manifest references.
- Filename grouping now uses the cleaned phrase before the first comma (e.g. `Roger Marting, alex.mkv` → `roger marting`) and removes a trailing numeric sequel suffix (`Avatar 1.mkv` → `avatar`). Group keys remain case-insensitive; source filenames and SMB files are not changed.
- Added periodic background refresh every 12 hours when network is available. Android can defer periodic work; it is not an exact real-time filesystem watcher.
- Back navigation from another tab returns to Videos; back from Videos uses Android's normal app-exit behavior.

## Important remaining work / verification
This ZIP is a source update, **not a verified APK build**. The current codebase still needs implementation and device testing for manual video assignment, user-created folders, moving/merging virtual folders, and a full playback screen with modern player controls. The existing project has SMB listing and thumbnail infrastructure, but does not yet contain a complete video playback UI. Do not treat 4K/HDR/Dolby Vision/TrueHD/subtitle support as verified until tested on target devices and media.

Automatic refresh runs on a 12-hour periodic schedule and requires a saved usable SMB profile for background operation. WorkManager scheduling is best-effort under Android battery/background restrictions. A manual scan is still available.

## Build
- JDK 17
- Android SDK platform 35 and build tools 35.0.0
- Gradle 8.9 (GitHub Actions workflow installs it)

The GitHub workflow builds a debug APK. It is for personal testing and is not a signed production release.
