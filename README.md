# Video Library (Android)

Personal SMB video player with an indexed library, virtual folders, original folders, live File Explorer and thumbnail collages. Development previews are reviewed in draft PR #2; do not merge before device testing.

## Install previews as updates

Download **VideoLibrary-preview-apk** from a successful Android APK workflow run, extract `update.apk`, and install **Video Library Preview**. Keep the previous Preview installed to preserve its library, thumbnails and settings. This package is separate from the original Video Library app. The dedicated preview signing key is a public, non-production test identity and must remain stable. Never use it for production or regenerate it for each build.

## Library and file actions

- Folders contains swipeable **Custom folders / Virtual folders / Original folders** tabs. Originals initially lists indexed folders containing visible videos, including nested folders; empty folders are omitted.
- Search matches text anywhere in names, ignoring case. Play all creates a queue from matching visible videos. File Explorer searches the live share recursively.
- Long-press a video for Select / Select all, cut, copy, paste, rename, delete, details or hide. Selection supports multiple files and assignment into a virtual folder. Long-press a virtual folder for Merge into. Virtual names and merges change library organisation; original rename, cut, copy and delete change the SMB share.
- The clipboard works across sections. Paste opens an original-folder destination chooser. Background jobs serialize with scans, refuse overwrite, and report progress/cancellation. Copies publish from a unique staging path only after completion; interrupted copies restart rather than resuming at a byte offset.
- Hidden items remain in File Explorer and can be restored individually or together under **Settings → Unhide files and folders**.
- Scan lists failed thumbnail filenames until successfully retried or deleted. Each opens a reason, individual retry and a shortcut to its File Explorer folder. Decoder failures report available information without claiming an exact cause Android did not supply.
- The themed tablet sidebar starts expanded, collapses during vertical scrolling and expands on navigation clicks. Settings also offers a floating bottom bar with live backdrop blur on supported Android versions and a translucent fallback on older devices. App chrome follows the selected theme.

## Automatic collections and file navigation

Virtual folders automatically group indexed filenames using the text before the first comma or hyphen. The source-selection switch/picker has been removed; existing thumbnails and manual assignments remain. Original folder cards independently shorten the folder's leaf name at the first comma/hyphen and show only that label and item count. Physical paths stay unchanged and remain available through Details.

Settings offers five persistent themes: Dark grey (default), AMOLED black, Midnight blue, Forest and Plum.
Custom folders match their name anywhere in indexed video filenames, ignoring case. A Peter collection matches both `Peter - Movie.mp4` and `Movie with Peter.mp4`; newly indexed matches appear automatically. Collections overlap without rewriting existing virtual assignments. They support thumbnails, search, Play all, selection, shared cut/copy/paste, rename, hide, details and removal. Merge assigns current matches into a virtual folder while keeping the automatic collection. Deleting a collection keeps originals; videos inside retain their individual permanent-delete actions.

Original folders and File Explorer show search from a top-right icon. Failed-thumbnail shortcuts open the containing folder, scroll to the actual file and highlight it in the live listing. File Explorer omits the previous network-address and original-file warning banners.

## Playback and storage

SMB playback uses 1 MiB read-ahead and a bounded Media3 buffer for high-bitrate 4K files. Playback keeps the screen awake, handles audio focus and disconnected headphones, supports zoom/reset, playlists and optional PiP. History uses a database with throttled saves and imports previous preview preferences. Renaming or moving indexed files in this app preserves IDs, artwork and virtual assignments; external filesystem changes still require refresh. A schema migration preserves the library.

Videos load in pages and sort in SQL; folder summaries aggregate in SQL. Full queues are materialized only for explicit Play all / selection actions. This improves scalability but does not establish a particular refresh rate on a real tablet.

WMV/ASF queues use a bundled VLC compatibility player through a seekable, read-only Android proxy descriptor backed by SMB. Files are streamed rather than downloaded. Credentials remain in the existing SMB connection, not a playback URL. WMV retains the previous automatic decoder selection. The experimental software-default and startup-recovery changes were reverted at the user's request. The compatibility player supports queue advance, resume, seek, speed, embedded audio/subtitle tracks, zoom/reset, focus, headphone unplug, keep-awake and optional PiP. Normal queues retain Media3 playback. Protected/damaged files are not guaranteed to play.

## Build and validation

Use JDK 17, Gradle 8.11.1, Android platform 36 and build tools 35.0.0. GitHub Actions runs unit tests, lint, the search benchmark, signed builds, a real previous-preview upgrade and Android 10 / Android 15 emulators. Instrumentation covers hiding, sorting, paging, progress, rename identity and thumbnail failures. A disposable Samba share covers copy integrity, cancellation/staging cleanup, no overwrite, directory operations and tablet UI copy/paste/hide/restore. Tests never access the user's NAS.

Hardware decoding, 4K throughput, actual user WMV files, PiP, headphone behavior and smooth scrolling still need target-device testing. See `PLAYBACK-TESTING.md`. WorkManager depends on Android background restrictions; unattended authenticated operations require an available saved profile/password.
