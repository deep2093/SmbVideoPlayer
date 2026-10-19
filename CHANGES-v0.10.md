# v0.10 Stability, structure and speed

Source only. Nothing here has been compiled or run on a device; GitHub Actions is the build
authority and the first run must be checked before installing.

## User-visible
- Virtual folders, Custom folders and Original folders keep their scroll position when you open a
  folder and come back, return from the player or switch tabs. Original folders remembers one
  position per folder level; entering a folder starts at its top. Changing a search still jumps to the top.
- The tablet sidebar is fixed (always expanded). The collapse button and collapse-on-scroll are gone.
- Scan screen reorganised into Library scan, Thumbnails, Automatic sync and Scan errors cards with
  counter tiles and wrapping action buttons. "Clear thumbnail cache" is now "Clear cache".

## Performance
- Thumbnails are generated over two parallel SMB connections (`ThumbnailGenerator.WORKERS`).
- The Media3 sample buffer is sized to the device (32 MB low-RAM, 64 MB, or 96 MB on devices with a
  256 MB+ heap class) instead of a fixed 96 MB.
- A hand-written baseline profile (`app/src/main/baseline-prof.txt`) is compiled into release builds.

## Reliability and build
- Room schema export is on (`app/schemas`). Commit the generated `4.json` after the first build.
  `MigrationTest` checks the schemas and skips itself until a schema file is present.
- `ScrollRestorationTest` guards the scroll behaviour with a Compose UI test.
- The release build type is now shrunk (R8 + resource shrinking). It is unsigned unless
  `releaseStoreFile`, `releaseStorePassword`, `releaseKeyAlias`, `releaseKeyPassword` (Gradle
  properties) or `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` (environment) are provided. It is never signed with the preview key.
  The preview and optimized variants keep the same package name, signing key and version-code rule,
  so they still update in place.

## Code structure
- `Screens.kt` (1,000+ lines) is split into `AppRoot.kt`, `FoldersScreen.kt`, `VideosScreen.kt`,
  `ScanScreen.kt`, `SettingsScreen.kt` and `ScrollRestoration.kt`.
- `.editorconfig` added and all Kotlin sources formatted with ktlint 1.5.0
  (`ktlint -F "app/src/**/*.kt"`). Formatting changes whitespace and line breaks only.

## Not changed on purpose
- Dependency versions (Compose BOM 2024.12.01, Media3 1.5.1, smbj 0.13.0, Room 2.6.1): upgrade in a
  separate change that can be built and tested, one library at a time.
- `targetSdk` stays at 35.
- `LibraryViewModel` is not split yet, and Play all still loads the whole queue.
