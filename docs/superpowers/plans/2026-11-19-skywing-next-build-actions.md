# SkyWing PC Next Build Action Plan

> **For agentic workers:** Use `superpowers:executing-plans` to implement this plan task by task when the user requests implementation. Checkboxes track future work; none imply completion.

**Goal:** Resolve the reviewed playback/fullscreen risks and improve Windows desktop usability while preserving SkyWing's themes and existing player architecture.

**Architecture:** Keep Kotlin/Compose Desktop, embedded libmpv, JNA and the stable AWT video surface. Make focused changes around playback coordination, navigation and shared controls; preserve the existing fullscreen owner.

**Tech stack:** Kotlin/JVM 17, Compose Desktop, coroutines, JNA/Win32, libmpv, VapourSynth/open-svpflow, Gradle and JUnit.

**Spec:** This file consolidates the playback/fullscreen code review and desktop-ui-design review in this chat on 19 November 2026 (Australia/Sydney). It is a future-work checklist, not authorization to implement or build now.

**Review baseline:** `feature/windows-motion-engine`, commit `f64d409baf2a9603fe40d283aadb98b0ef214e89`. The reviews were source-based: the five bug findings were not reproduced in a running player, and no current live UI, rendered contrast or DPI behaviour was certified. Recheck the checkout and findings before implementation; older CI screenshots are not evidence of the current appearance.

## Global constraints

- This task creates this action file only. Do not implement, build, package, install or run the app now.
- Future changes are Windows-only. Leave Android code, Android Gradle files and Android workflows unchanged.
- Read `AGENTS.md`, `windows/README.md`, `windows/FULLSCREEN-REGRESSION.md` and `windows/MOTION-ENGINE.md` before implementation.
- Use `skywing-debugging` for defects and `desktop-ui-design` plus `skywing-desktop-design` for UI work. Use `skywing-release-qa` for later release verification.
- Preserve Canvas/HWND identity across navigation and fullscreen. Showing controls must not recreate or resize the video surface in fullscreen.
- Preserve stored preferences, per-file behaviour, source provenance, payload hashes, legal notices and MSI upgrade identity.
- Do not overwrite personal skills or touch the existing untracked `work/` directory.
- Treat regression tests as required for confirmed defects. Record reproduction, failing assertion, focused fix and passing result.
- Do not equate generated-frame throughput, drop counters or display refresh with measured physical presentation, visual quality, HDR or VRR activation.

## Order of work

1. Reconfirm the baseline and reproduce the five findings.
2. Fix playback coordination and lifecycle issues (B1-B4), then fullscreen mouse navigation (B5).
3. Improve shared control accessibility and narrow-window layouts (U1-U2).
4. Simplify player controls, navigation and settings (U3-U5).
5. Refine readability, visual hierarchy and state feedback (U6-U8).
6. Run the later verification gates and document remaining hardware-specific acceptance.

Each item should be independently reviewable. Implement only confirmed defects; if evidence disproves a finding, record why and retain any useful regression coverage. Do not mark a later enhancement complete merely because its design is documented.

## Review focus

- Native work stalls while the user presses Esc or closes the window: the UI must remain responsive (B1).
- The user closes between periodic progress saves: final progress must be captured before player destruction (B2).
- The same filename is reopened quickly or loads slowly: resume must belong to the correct load session (B3).
- A monitor or refresh-rate change invalidates smoothing: restore ordinary playback and report the real state (B4).
- Input originates in an owned fullscreen overlay: navigation must match input over the video (B5).

## Playback and fullscreen bug backlog

### B1 — P1: Native player work can block the UI

**Evidence:** `EmbeddedMpv.updateSmoothing()` holds the same object monitor acquired by property reads and commands. `Main.kt` invokes these from UI polling and input handlers. Slow filter preparation/reconfiguration can make the EDT wait for that monitor.

**Files:** `windows/src/main/kotlin/com/skywing/desktop/Main.kt`, `playback/EmbeddedMpv.kt` under the same package; existing `FullscreenWindowTest.kt` and playback tests under `windows/src/test/kotlin/com/skywing/desktop/`.

- [ ] Reproduce deterministically with a controllably blocked player operation; measure EDT responsiveness independently of native completion.
- [ ] Add a regression that holds reconfiguration pending and asserts a queued EDT task and fullscreen exit complete before releasing it.
- [ ] Route blocking native commands, reads, load and teardown through a serialized worker. Publish immutable playback snapshots to the UI; input handlers enqueue work without waiting for the player monitor.
- [ ] Keep fullscreen window operations on the EDT and reject new playback work after close begins. Preserve native-call serialization and destruction ordering.
- [ ] Verify F/Esc, navigation and close remain responsive while native work is delayed. Report separately if native destruction itself cannot complete; do not claim that a worker can recover a permanently hung native library.

### B2 — P2: Closing races the final progress save

**Evidence:** The window close handler starts `embedded.close()` while a separate `LaunchedEffect(currentFile, closing)` attempts to read/save progress. Reads may see an already-destroyed player, or application exit may cancel persistence.

**Files:** `Main.kt`, `WindowsLibraryData.kt` (`WindowsPlaybackHistoryStore`); add focused lifecycle tests and extend `FullscreenWindowTest.kt`.

- [ ] Add a regression with progress newer than the periodic save and assert the persisted value is captured before destruction and exit.
- [ ] Implement an explicit close sequence: stop accepting input, capture a coherent file/session progress snapshot, persist it, destroy mpv, then dispose/exit.
- [ ] Ensure a persistence error is reported and still permits orderly player teardown and exit.
- [ ] Verify X and Alt+F4 in normal/fullscreen playback, including an active motion filter. Confirm no orphaned process or audio and no duplicate destructive close.

### B3 — P2: Delayed resume can seek a later session

**Evidence:** `Main.kt: openVideo()` delays 1.2 seconds and validates only the file path. Reopening the same path can satisfy an earlier request's guard; slow loading can also make the one-shot seek too early.

**Files:** `Main.kt`, `playback/EmbeddedMpv.kt`; add focused load/resume coordination tests.

- [ ] Add failing tests for resume A -> stop -> reopen A before 1.2 seconds, resume A -> open B, delayed readiness beyond 1.2 seconds, and close with resume pending.
- [ ] Give every load a unique generation and tie readiness and resume to that generation. Cancel/invalidate pending resume on stop, replacement and close.
- [ ] Replace the fixed delay with verified selected-session file readiness. Apply resume once, validate the position against the loaded duration, and handle load failure without seeking another session.
- [ ] Assert that a stale request issues no seek and the matching ready session receives exactly one resume seek.

### B4 — P2: Motion remains active when no rate fits the display

**Evidence:** `EmbeddedMpv.updateMotion()` returns "No smoothing rate fits this display" without ending the existing session. An example is 60-fps motion playback moved from a 144-Hz display to 60 Hz. The old filter can remain installed while the returned status defaults to inactive; this branch also bypasses heartbeat monitoring.

**Files:** `playback/EmbeddedMpv.kt`, `MotionEngine.kt`, `MotionSettings.kt`; extend `MotionEngineTest.kt` and add orchestration coverage around `updateMotion()`.

- [ ] Add a regression that changes supported rates during an active session and asserts filter removal plus restoration of owned decoder/sync/interpolation settings.
- [ ] End motion when no valid target remains. Preserve unrelated filters and accurately report restoration failures.
- [ ] Check adjacent early returns for missing/changed source information so UI state cannot silently disagree with an installed filter.
- [ ] Test a lower but still valid target, an unknown refresh rate, and restoration-command failure. Exercise a real monitor/refresh change later where hardware permits.

### B5 — P2: Mouse Back ignores fullscreen overlay windows

**Evidence:** `Main.kt` accepts mouse button 4 only when the event's window ancestor is the main frame. Fullscreen title/controls are separate owned dialogs; `FullscreenChrome.kt` already contains `belongsToPlayer()` for recognizing them.

**Files:** `Main.kt`, `FullscreenChrome.kt`; extend `FullscreenWindowTest.kt`.

- [ ] Dispatch mouse Back over the video, title overlay and controls overlay; require the same navigation and pause behaviour.
- [ ] Reuse the player-window ownership check for mouse navigation and exclude unrelated windows/dialogs.
- [ ] Assert fullscreen cleanup and unchanged Canvas/HWND identity. Ensure one physical Back action navigates only once.

## Desktop UI action backlog

### U1 — Highest UI priority: Accessible and discoverable controls

**Files:** `SkyUi.kt`, `SkyIcons.kt`, shared menus and player controls.

- [ ] Connect `SkyIconButton.description` to accessible naming and hover/focus tooltips; it is currently unused. Give custom actionable controls appropriate roles, selected/toggled states and disabled states.
- [ ] Add a consistent visible keyboard focus indicator and predictable Tab/Shift+Tab order. Support Enter/Space activation and Shift+F10 context menus where applicable.
- [ ] Verify screen-reader names with Narrator, rather than assuming Compose semantics are exposed correctly on Windows.
- [ ] Include shortcuts in tooltips and menus. Do not let player shortcuts fire while editing text or adjusting a focused control.

### U2 — Highest UI priority: Responsive spacing and command layout

**Files:** `VideosScreen.kt`, `VideoViews.kt` (`FolderHeader`), `FoldersScreen.kt`, `FilesScreen.kt`, `SettingsScreen.kt`, `Main.kt`.

- [ ] Split page title/search and page commands into predictable rows. Keep the main action visible and move secondary commands into an overflow menu as space decreases.
- [ ] Make fixed-width searches, the sidebar and paired 250-dp theme previews adapt to available width. Preserve readable titles and reachable controls in half-screen Snap layouts.
- [ ] Standardize shared spacing tokens around 4/8/12/16/24/32 logical units, with justified optical adjustments.
- [ ] Verify long paths, long titles, large text, compact windows and 100/125/150/200% scaling. Do not approve from the default 1280x800 layout alone.

### U3 — Player controls and status hierarchy

**Files:** `PlayerPage.kt`, `Main.kt`, `SmoothingDialog.kt`, `FullscreenChrome.kt`, playback track/property interfaces.

- [ ] Keep the timeline and primary transport controls prominent; group volume/mute/fullscreen separately from track and advanced settings.
- [ ] Replace blind Audio/Subtitles cycling with named menus, checked selections and a clearly labelled "Subtitles off" item.
- [ ] Add a volume slider with a readable value; preserve existing keyboard controls.
- [ ] Keep motion toggle/status concise. Put the detailed report in "Playback information…" and separate user-facing errors from routine load-command timing.
- [ ] Design playback-speed, aspect/zoom and a richer queue menu as follow-on enhancements; do not imply those capabilities already exist.
- [ ] Preserve fullscreen auto-hide, cursor restoration, non-focus-stealing overlays and the stable video surface. Exercise menus, dragging, pause, Alt-Tab and Esc dismissal together.

### U4 — Navigation and command discoverability

**Files:** `Main.kt`, `VideoViews.kt`, `FoldersScreen.kt`, `FilesScreen.kt`, `VideosScreen.kt`.

- [ ] Rename the sidebar's "Files" destination to "Sources" wherever it means source management; explain the distinction between collections/folders and all videos.
- [ ] Add clickable breadcrumbs to nested folders, consistent Back handling and a collapsible sidebar at narrow widths.
- [ ] Make the existing pause-on-leaving-player behaviour clear. Preserve Now Playing as a reliable way back to the loaded video.
- [ ] Replace the Videos sort button's cycling behaviour with an explicit menu and checked current sort.
- [ ] Provide a compact command menu structure: File (Open video…, Sources…, Exit), Playback (transport, tracks, motion and information), View (layout, sidebar, fullscreen), Help (shortcuts, About). Reflect availability and current selections.

### U5 — Desktop settings navigation

**Files:** `SettingsScreen.kt`, `SmoothingDialog.kt`, `ArtworkSettingsPage.kt`.

- [ ] Show persistent categories beside the selected settings page on wide windows; retain a single-column/back pattern when narrow.
- [ ] Add settings search and keep related options grouped. Constrain long explanatory text to a comfortable reading width.
- [ ] Use responsive theme previews and consistent setting-row spacing, labels, descriptions and switch alignment.
- [ ] Distinguish saved playback defaults from controls/status for the currently loaded video.

### U6 — Readability and visual consistency

**Files:** `SkyUi.kt`, `SkyTheme.kt`, `AdaptiveTheme.kt`, `PlayerPage.kt`.

- [ ] Evaluate 14-sp card titles and 12-13-sp metadata against the current 13/11-sp text. Allow two title lines where useful and reveal full names on hover/focus.
- [ ] Measure text/control contrast over final composited surfaces in every theme, especially adaptive artwork backgrounds. Do not claim a contrast failure from source alpha values alone.
- [ ] Reduce competing bright glass edges and excessive capsule emphasis. Use a small consistent radius set and reserve strong accent treatment for primary actions and selected states.
- [ ] Keep the dark viewing surface and SkyWing's existing theme identity. Review hover, focus, pressed, selected and disabled states together.

### U7 — Empty, loading and failure states

**Files:** `VideosScreen.kt`, `FilesScreen.kt`, `FoldersScreen.kt`, `SkyUi.kt`.

- [ ] Distinguish empty library, no search/filter matches, initial loading, unreachable source and scan failure.
- [ ] Pair states with relevant actions: Add source, Clear filters or Retry. Keep unavailable content separate from a successful empty result.
- [ ] Preserve browsing context while scans update data; keep loading and errors legible without relying on colour alone.

### U8 — Session restoration and keyboard contract

**Files:** `Main.kt`, `FolderOrganizer.kt`, preference/history stores and shared command handlers.

- [ ] Persist window bounds/normal restore bounds and maximized state; recover visibly if a monitor disappears. Restore the last library location, sort and view choices.
- [ ] Preserve user preferences and per-file progress. Start with playback stopped; do not restore fullscreen, open menus, drag state or transient selection.
- [ ] For first launch, provide an actionable empty library with Add source/Open video, using existing theme defaults.
- [ ] Implement and document the following keyboard contract, checking existing handlers before adding bindings:

| Shortcut | Intended action and scope |
|---|---|
| Ctrl+O | Open video… |
| Ctrl+F | Focus the current page's search, when present |
| Alt+Left | Back within the application |
| Ctrl+, | Open Settings |
| Space / K | Play/pause in player context; preserve focused-control/text editing behaviour |
| Left / Right | Preserve existing player seek amounts: back 5 s / forward 10 s |
| Up / Down | Player volume; preserve focused menu/slider navigation |
| M | Mute in player context |
| F | Toggle fullscreen in player context; preserve held-key debounce |
| Esc | Dismiss the active dialog/menu first; otherwise leave fullscreen |
| Shift+F10 | Context menu for the focused library item |
| Alt+F4 | Normal window close; preserve OS ownership |

## Verification gates — run only during later implementation

The existing fullscreen test opens media directly through `EmbeddedMpv`, bypassing application resume/history handling. The production-process close test opens no media. Extend these paths; their current coverage cannot establish B2/B3 or close-during-motion correctness.

- [ ] For B1-B5, record the smallest reproduction and run a failing regression before the focused fix, then a passing regression afterward. Use isolated preference/temp directories.
- [ ] Add transition failure/rollback coverage for borderless fullscreen, including restored decorations, visibility, bounds and retry behaviour. Record mixed-DPI and monitor-removal cases that require hardware.
- [ ] Run focused tests for the changed boundary, then the Windows suite using JDK 17:

```powershell
# FUTURE COMMANDS ONLY — not executed when creating this plan.
.\gradlew.bat -p windows test --no-daemon
```

- [ ] Run required native tests with `SKYWING_TEST_MPV=true` and `SKYWING_TEST_MPV_DIR` pointing to the pinned DLL directory, following `windows/FULLSCREEN-REGRESSION.md`. Missing prerequisites must be reported explicitly.
- [ ] Visually verify the latest build: library/source/settings/player pages; empty/loading/error/populated states; all themes; keyboard focus and Narrator; long filenames/UNC paths; narrow/Snap/maximized windows and DPI scaling.
- [ ] Exercise local and SMB playback, pause/seek/mute/tracks, repeated F/Esc, held F, navigation, Alt-Tab, monitor changes, close/Alt-F4 and no lingering process/audio. Verify the video HWND survives navigation/fullscreen transitions.
- [ ] If producing an installer later, run release QA and packaging checks; preserve native/legal files and MSI identity:

```powershell
# FUTURE RELEASE COMMAND ONLY — not executed when creating this plan.
.\gradlew.bat -p windows packageExe packageMsi --no-daemon
```

- [ ] Report changed files, executed commands/results, untested cases, hardware limitations and remaining acceptance work. Physical HDR/VRR/audio-sync quality requires separate device evidence.

## Design references

- [Fluent layout and spacing](https://fluent2.microsoft.design/layout)
- [Windows navigation basics](https://learn.microsoft.com/en-us/windows/apps/design/basics/navigation-basics)
- [Windows keyboard interactions](https://learn.microsoft.com/en-us/windows/apps/develop/input/keyboard-interactions)

## Completion record

- Planning deliverable: this Markdown file only.
- Application implementation: not started by this task.
- Builds, packaging, installation and runtime tests: not run by this task.
- Next action: review this backlog and request implementation when ready; refresh the baseline before changing code.
