# v0.9 Search and layout update

- Replaced persistent folder/video search fields with top-right search icons, showing a search field on demand. Closing search clears the filter.
- Removed viewport-driven, shallow grid cards. Artwork has a consistent 16:10 aspect ratio, so large-tablet grids show taller thumbnails similar to VLC.
- Kept existing independent 3x3 through 5x5 column presets in Settings. Rows are visual targets, not a fixed total on the scrollable grid.
- Improved SMB port 445 probing with a fallback unbound network socket and clearer diagnostics. Discovery remains best-effort: routers can hide shares, reject scanning, or expose SMB on different subnets. SMB share enumeration is not implemented.

Source only; GitHub/Android compilation and real-device tests are required. Do not assume LAN discovery works until verified.
