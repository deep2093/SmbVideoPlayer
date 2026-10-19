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
- Independent, persistent folder/video list or grid display. Library layout offers
  four equally sized choices (2, 3, 4 or 5 columns) in a balanced two-by-two selector.
  Legacy columns × rows choices migrate by column count. All grid artwork keeps a
  fixed 16:9 ratio, independent of viewport height; captions reserve two title lines.
- Automatic sync lives in Scan, showing schedule, current status and last success.
  Status updates automatically; verbose cache and scheduling details are removed.
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

Use the checked-in Gradle 8.11.1 wrapper with JDK 17 and Android SDK 36. Build historical schema fixtures as described in README before assembling instrumentation tests. GitHub Actions builds signed previews, runs unit tests/lint and exercises API 29/35 with a disposable SMB share. The benchmark module generates profiles and measures cold startup separately.

v0.11 adds selectable physical folders, isolated library storage, durable transfer recovery and a simpler Scan screen. Settings uses a readable maximum width, secondary scan actions live in a menu, and failures expand only when needed.

Physical phone/tablet checks still need the user's SMB server: resume/seek, subtitles, large libraries, IME visibility, original file operations, gestures and PiP. Emulator results do not establish 4K throughput or tablet frame rates. No casting or subtitle downloading is advertised as implemented.
