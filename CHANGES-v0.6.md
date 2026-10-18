# VideoLibrary v0.6 — fullscreen playback and folder queues

Changes relative to v0.5.1:

- Fullscreen playback requests immersive Android status/navigation bars and restores the prior flags when the player closes.
- Playback controls become a floating overlay; they initially show, auto-hide after two seconds, and toggle with a single tap. Controls reappear at end of the playlist.
- Progress bar overlays the video near the bottom with elapsed and total time (updated every second); dragging the seek bar seeks upon release.
- Playback next and folder queue indicator. In virtual folder, **Play all** builds a sequential Media3 playlist from currently shown videos; tapping a folder video starts there and automatically advances to following videos.
- Virtual-folder management is now under a top **Organize** toggle. Each folder has a contextual menu containing Rename and Merge only in organize mode.
- Keeps existing left/right double-tap seeking, hold-to-speed, volume/brightness swipe, pinch zoom, PiP option, fit/crop/stretch and SMB retry handling.
- Version bumped to 0.6.0.

## Constraints / testing

This is source for GitHub Actions, **not a locally compiled or tested APK**. Verify compilation on GitHub and gestures on physical Samsung devices. Gesture recognizers may conflict on some devices; verify tap/double-tap, drag and pinch combinations. Immersive mode relies on Android system UI flags; Android versions and gesture navigation can transiently reveal system bars. Folder playback order follows the current indexed/filtered grid ordering, not a separate sortable playlist. More advanced VLC-style seek previews, chapters, track selection and cinematic UI remain outside this update.

Install over your existing APK to preserve the local index and custom folders; do not uninstall or clear app storage.
