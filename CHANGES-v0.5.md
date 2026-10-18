# VideoLibrary v0.5 - source changes (NOT device-verified)

## Added
- Volume swipe on right, brightness swipe on left, pinch zoom and reset zoom in the player.
- Optional Picture-in-Picture toggle saved per device. Requires Android PiP support.
- Bounded SMB random-access read reconnect attempts at original byte offset; Media3 error retry and manual Retry button.
- Persistent virtual-folder management via Room migration 1->2: create, rename, merge, and assign existing videos. No SMB files are modified.
- Continue Watching strip using locally saved playback positions.
- Rounded artwork cards and basic layout polish.
- Guard against manual and periodic scans corrupting one another in the same application process.
- More accurate refresh retry result; LAN discovery 172.16-31 restriction.

## Important limitations
- Not built here (Android SDK/Gradle dependencies not installed) and not tested on physical devices.
- Gesture detectors share the screen; touch conflicts may require further tuning after real-device testing.
- Picture-in-Picture behaviour on the user's devices and Android versions needs validation; playback is activity-scoped, not a background media service.
- SMB reconnection only retries bounded transient transport failures. It cannot recover when the share disappears permanently, credentials expire, or the file moves.
- Virtual grouping is local to the app/database; not a filesystem folder modification. Renaming a group will not automatically capture future similarly named items whose filenames derive the old group name.
- Continue Watching is a basic strip and currently shows any playback with a saved position over 15 seconds (not duration-aware).
- Audio/subtitle track pickers, external subtitle support, HDR/TrueHD/DTS compatibility guarantees, per-share discovery and metadata browsing are NOT done.
- Periodic sync is every ~12 hours, not immediate push events; Android may defer tasks.
- Automatic cache eviction/size cap is not implemented; existing thumbnail cache clear remains manual.
- Modern UI styling is incremental, not yet full Nova/VLC parity.
- Physical Galaxy S26 Ultra/Tab S11 Ultra testing must be performed by user after GitHub build.

## Build
Upload source to GitHub Actions as before. Compile errors or Android runtime exceptions should be reported with the FIRST failure trace. Install as upgrade over v0.4 to test migration retaining indexed files.
