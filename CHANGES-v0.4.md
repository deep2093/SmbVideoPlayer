# VideoLibrary v0.4 — search and adjustable grids

- Videos screen: existing filename search preserved. Query filters the indexed SQLite library, without re-scanning SMB.
- Virtual Folders screen: added case-insensitive search for folder names.
- Videos and Virtual Folders: independent interactive thumbnail-size sliders (160–440 dp).
- Default tile width increased for tablets (300 dp videos, 320 dp folders), reducing the number of columns and making artwork and names easier to read. Phones default to 180/190 dp.
- Tile-size choices saved in local device preferences and persist across app restarts.
- All v0.3 player, SMB, indexing, cache and thumbnail features preserved.

Note: No local Android SDK is available for compiling/real-device verification. Use GitHub Actions and send the first build failure log if one occurs.
