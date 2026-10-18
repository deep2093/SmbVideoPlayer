# Library interface redesign

This is an independent Compose implementation inspired by familiar VLC for Android
interaction patterns. No VideoLAN source, logos, cone icons, artwork, or other assets
have been copied. The app continues to use its existing Media3 player and SMB stack.

## References reviewed

- Supplied tutorial: https://www.youtube.com/watch?v=AhoyvF9QJPk
  (Android Video, Browse/local network, bottom navigation and More/settings screens).
  The tutorial's Video screen has no indexed media; it is not a complete playback specification.
- Official Video Explorer documentation:
  https://docs.videolan.me/vlc-user/android/3.X/en/video/video_explorer.html
- Current official Android source mirror, reviewed for behavior only:
  https://github.com/videolan/vlc-android/blob/master/application/vlc-android/res/menu/activity_option.xml
  and `bottom_navigation.xml` in the same directory.
- Licensing reference: https://github.com/videolan/vlc-android#license
  VLC Android's GPL licensing does not make its source/assets unrestricted for reuse.

## Result

- Black surfaces and the user's red accent, compact headers, simple thumbnail tiles,
  visible per-video overflow actions, and icon-based playback controls.
- Independent, persistent folder/video list or grid display. Grid density remains
  inside the expandable Library layout settings. Both 2×2 and 2×3 now validate and
  persist, and the row target changes thumbnail height within usable bounds.
- Video sorting by name, added date, modified date, or size; repeat a sort to reverse
  it. Play all follows the displayed order. Titles and file size are always available;
  duration and watched progress appear once playback has discovered duration.
- Continue watching is part of the scrollable library on both tabs. The visible
  overflow menu and long press both expose removal without deleting a network file.
  Completed videos are excluded consistently. Playing a dismissed video makes it
  eligible again.
- Existing virtual folders, filename search, assignment, rename/merge, SMB browser,
  copy/move/rename/delete confirmations, scan/sync, saved credentials, resume,
  track selection, PiP, gestures and expandable settings remain available.

## Validation and limits

Run `gradle testDebugUnitTest assembleDebug` with JDK 17, Gradle 8.9 and Android SDK
35, or use the existing Android APK GitHub Actions workflow. Presentation tests cover
both two-column presets, continue-watching boundary cases and deterministic sorting.

The development machine has no Gradle/JDK/Android SDK on PATH. The pushed commit's
GitHub Actions result is the build authority. Physical phone/tablet checks still need
the user's SMB server: resume/seek, subtitles, large libraries, IME visibility, original
file operations, gestures and PiP. No VLC-only features such as casting, audio-library
indexing or subtitle downloading are advertised as implemented.
