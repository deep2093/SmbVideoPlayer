# VideoLibrary v0.8 — Layout Presets

- Replaced the Videos and Folders thumbnail-size sliders with six fixed grid presets: **3×3, 3×4, 4×3, 4×4, 5×4, 5×5**.
- Moved both layout controls to **Settings → Library layout**; they no longer appear above the grids.
- Each screen has an independent choice, saved locally across restarts.
- Presets specify **columns × target visible rows**. The grids remain scrollable; row count is a visual target, and screen size, available space, Android font scaling, and item minimum height may change the exact number of rows visible at once.
- Default tablet choice: Folders 3×3, Videos 4×3. Phone: 3×4 for both.
- No database migration or rescan required. Your SMB, player, thumbnail cache, and virtual folder records are unchanged.

**Not verified:** Android compilation and device appearance. Build the ZIP with GitHub Actions before installing, and test 5×5 on both tablet and phone (small screens will scroll).
