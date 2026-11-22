# SkyWing for Windows: audit, redesign and action plan (for Codex)

You are working on **SkyWing for Windows**, the PC port of my Android video player for a network drive. This document is the brief: what is wrong, why, and exactly how to fix it, in order. Read all of it before changing code. Where it says **verify**, check the real code, API documentation or a test before relying on it. If something here turns out wrong, say so plainly and propose the nearest alternative. Don't fake it.

---

## 0. Ground rules

1. **Repository:** `github.com/deep2093/SmbVideoPlayer`. The Windows app is `windows/` (Kotlin 2.1.21, Compose Multiplatform 1.8.2, JVM 17, JNA 5.14, libmpv in a native child window). **Don't change `app/` (Android).**
2. **Start from** the latest `feature/windows-motion-engine`. On 20 November that is GitHub `9f58021` (0.1.98, repo guidance) plus my local commits: `ed072bf` (watchdog fix), merge `f64d409`, and the accessibility-packaging and fullscreen-test changes (milestone M0 commits these). Work on a new branch `feature/windows-modern-pc` made from that head. Never touch `codex/adaptive-motion-interpolation`, and don't rewrite or force-push existing branches.
2a. **Repository guidance wins:** follow `AGENTS.md` and the skills in `.agents/skills/` (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review (19–20 November) and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md` before touching playback. If this brief and those documents disagree about how motion currently works, **the code and those documents win**; tell me about the difference.
2b. **Already built, don't redo** (from the efficiency review): real SDR motion smoothing at full resolution (libmpv → VapourSynth → open-svpflow); four exact frame-rate stops (2×, 2.5×, 3×, 4×; first use = closest to 60 fps; sources above 60 fps bypass); five quality presets (Fastest, Fast, Balanced, Better, Best) applied on slider release; settings remembered for six resolution/SDR-HDR classes; "offer next lower stop" on sustained drops, with automatic lowering **off by default**; rolling 60 s drop label; one bounded stall recovery; active-display GPU routing (`749b5fc`); the opt-in `SKYWING_D3D11_COMPAT` mode; the frame-sharing mpv build (about 11% less CPU, output hashes identical); transparent owned overlay controls that keep the video HWND; and a detailed playback report. Improve these; don't replace them.
3. **Don't push or open a PR until I say "push".** Commit in small steps with clear messages. CI (`.github/workflows/skywing-windows-x64.yml`) is the build authority.
4. **x64 only.** Don't add ARM or 32-bit builds.
5. **Never write to my drives or shares.** Everything the app creates lives in its own app-data folder. Don't keep the drive awake: no polling while nothing plays or scans.
6. **Keep working:** fullscreen without recreating the video window, Unicode paths, alternate drive addresses, the MSI upgrade code (in-place upgrades keep my library), the legal/notice files, the motion engine and its tests.
7. **Plain-language UI text.** Short, friendly, no jargon.
8. After each phase, tell me in a few plain sentences: what works, what to test on my PCs, and the commit IDs.

**My test machines (all must run smoothly):**

| Machine | Graphics | Screen |
|---|---|---|
| Gaming laptop | Intel Core Ultra 9 275HX + NVIDIA RTX 5070 Ti Laptop (hybrid, with a dedicated-GPU mode) | 240 Hz, G-SYNC |
| MSI Claw 8 AI+ handheld | Intel Arc 140V integrated (Lunar Lake; **verify** from the playback report) | 8-inch touchscreen, high-DPI, VRR |
| OneXPlayer 3 handheld | **Intel Arc B390** integrated (Panther Lake, Xe3) | Touchscreen, high-DPI |

The handhelds also have built-in game controllers and usually **no physical keyboard**.

**Beyond my machines:** the app must run on most Windows x64 PCs: NVIDIA, AMD and Intel, dedicated or integrated, old or new, with or without OpenCL. Section 5.8 is the compatibility matrix. A missing feature on some GPU must switch that feature off with a plain reason, never crash or block playback.

---

## 1. What I asked for

1. **Graphics chip:**
   - Use the dedicated GPU when the laptop runs in dedicated mode.
   - Behave correctly in hybrid mode.
   - Use Intel or AMD integrated graphics when there is no dedicated GPU.
   - Never fail just because a vendor is missing.
2. **Every kind of screen:** fixed refresh rate (60/120/144/165 Hz…), G-SYNC, FreeSync and other VRR screens.
3. **Speed:** the app is slow and sluggish on both handhelds. Make it as efficient as possible on every PC.
4. **Modern design:** the app and the player look old and boring. Make them look like a modern Windows 11 app.
5. **Player:**
   - Add animation when maximising the window or going fullscreen.
   - Replace the toolbar, which is outdated and sluggish.
6. **Network drives:**
   - "Add network share" is confusing.
   - It doesn't work properly when the PC is not on Ethernet.
   - There is nowhere to enter the drive's username and password.
7. **Settings:** once inside a settings page there is no clear way back to the other settings. I have to leave to another section and come back.
8. Find and fix bugs, plus your own recommendations from the last audit.
9. **Works on most GPUs**, integrated and dedicated (section 5.8).
10. **Touch keyboard:** on the handhelds, tapping an empty text box (search, drive address, username, password, rename) does nothing; the Windows touch keyboard never appears. It must appear, and the box must stay visible above it (section 8.2).
11. **Touch-friendly everywhere**, and **adaptive** for big and small windows, touch and non-touch screens (sections 6.6, 8.3 and the design boards in 6.7).
12. Go through the code again for more bugs, improvements and new features (second-pass findings B16–B27, section 11).
13. **Window size:** don't open a near-full-screen "normal" window on the handhelds; smart default, remembered (section 6.8).
14. **Extra features:** all of section 11.4 (offline browsing, sleep and wake safety, phone sync, handheld shortcut, accent colour and Mica, Wi-Fi buffering, refresh-rate matching, audio passthrough, undo, folder watching, signed installer, winget, licence audit, optional motion component).
15. **Build on the motion work already done** (efficiency review, 19–20 November): keep what works (section 0, rule 2b), ship the watchdog fix and measure on the handhelds first (M0), and fix the new findings B28–B36.

---

## 2. Audit findings (what is wrong and why)

### 2.1 Bugs

| # | Where | Problem | Fix (phase) |
|---|---|---|---|
| B1 | `Main.kt` `go(section)` returns early when `section == selected`; `SettingsScreen.kt` keeps `open` in `remember` | Clicking **Settings** in the sidebar while on a settings page does nothing, so you're stuck on that page. Esc and the mouse back button don't leave a settings page either. | P3 |
| B2 | `WindowsShareBrowser` uses the current Windows sign-in only ("No credentials are stored") | A drive that needs a username and password can't be opened at all. Recent Windows 11 also blocks guest access to many routers' USB drives. | P4 |
| B3 | `FilesScreen.kt` lines 262–268 | Three unlabelled fields in one row with a cut-off placeholder ("Name (for example"), plus "Open share" and "Save" whose difference isn't clear. Errors only say "Enter a network path." | P4 |
| B4 | `EmbeddedMpv` + `WindowsDisplayAdapter` (commit 749b5fc) | Active-display routing itself works: with everything on Intel the laptop played 4K Balanced 2× with zero drops (the old 398-drop result came from a **mixed** Intel/NVIDIA setup before this commit). What's missing: AMD adapters return `null` and fall back to "NVIDIA"; the choice is made once and not updated when the window moves to another monitor or the power source changes; and there's no way to ask for the dedicated GPU ("Performance") or the integrated one ("Power saving"). | P2 |
| B5 | `MotionRuntime.prepare` | The OpenCL device filter only knows NVIDIA. On Intel or AMD handhelds the motion engine takes whichever platform comes first. | P2 |
| B6 | `DisplayVrr` | VRR detection only works for NVIDIA, so Intel and AMD VRR screens always read "unknown". Detection can also flip from unknown to supported a second after start, which rebuilds the motion filter with a seek (a visible hitch). | P2 |
| B7 | `PlayerChrome` → `EmbeddedMpv.setCursorHidden` | Calls a synchronous `mpv_command` from the UI thread. If mpv is busy (filter rebuild, VapourSynth start), the whole UI freezes. | P1 |
| B8 | `Main.kt` line 307 `historyStore.load()`, `FoldersScreen.kt` line 264 `LocalFolderStore().load()` | Disk reads inside composition on the UI thread, repeated on every revision change. | P1 |
| B9 | `FoldersScreen.kt` lines 1018–1027 and similar | Builds maps and lists of the whole library (`library.all.map / associateBy`) during composition on the UI thread. With thousands of videos this costs tens of milliseconds per recomposition. | P1 |
| B10 | `Covers.kt` line 56, `Thumbnails.kt` line 44 | `Image.makeFromEncoded(file.readBytes())` decodes every picture at full size, even for a 200-pixel card. A 4K poster is about 33 MB once decoded. Integrated GPUs and 16 GB handhelds run out of headroom quickly. | P1 |
| B11 | `AdaptiveTheme.kt` wallpaper | Four or five full-window radial gradients, animated for 1.4 s every time the covers on screen change (which happens while scrolling). The whole window repaints every frame. That's cheap on the RTX, expensive on an integrated GPU at high resolution and 120 Hz. | P1 |
| B12 | Frame-sharing mpv (commits 0664ed0, 2bfc524) | Each shared plane holds its own VapourSynth frame reference and queued frames are released before teardown, and a 52-seek stress run passed. Still open: one **unexplained teardown timeout** right after fullscreen + seeks, which is exactly where a frame outliving the script would show up; no repeated close-under-load test; dependencies come from "whatever MSYS2 has today" (recorded in the manifest, but not pinned); 125 DLLs. | P2 |
| B13 | Compose packaging | No release/ProGuard build, no AppCDS, JVM 17, default JVM flags: slow start, more memory. JCEF (the Google Images tab) is about 150 MB and must never load unless that tab is opened (**verify** it's lazy). | P1 |
| B14 | Touch and controllers | Compose Desktop on Windows has weak touch scrolling (no fling or drag-scroll in many places; **verify** what the 1.8 touch support does). There's no game-controller support. Both handhelds are touch-and-controller devices. | P5 |
| B15 | Player chrome | Fullscreen is an instant jump. Controls show and hide with no easing. The seek bar has no hover time or preview, there's no volume slider and no speed control. | P6 |

**Second pass (new findings):**

| # | Where | Problem | Fix (phase) |
|---|---|---|---|
| B16 | `WindowsLibraryData.kt` `WindowsMediaIndex.load()` (`catch (_: Exception) { emptyList() }`) | **Data-loss risk.** If the index can't be read once (antivirus lock, half-written file, disk hiccup), `load()` returns an empty list. The next `replaceSource` then writes `previous(empty) + this source`, which **wipes every other drive and folder from the library**. | P1 |
| B17 | same file, `removeWhere` | Not `@Synchronized`, unlike `replaceSource` and `renamePath`. A scan and a hide at the same time can overwrite each other (lost update). | P1 |
| B18 | same file | Every `replaceSource`, `renamePath` and `removeWhere` re-reads the whole index from disk. | P1 |
| B19 | `replaceSource` + `LibrarySync` (`Files.walk` → `File`) | Calls `file.length()` and `file.lastModified()` for every file. Over SMB each is a network round trip: 5,000 videos ≈ 10,000 round trips per scan, slow on Wi-Fi and it keeps the drive busy. | P1 |
| B20 | `WindowsCustomFolderStore`, `WindowsPlaybackHistoryStore` (`java.util.prefs` = Windows registry) | Custom folders store **one registry value per video** (up to 100 × 5,000). History rewrites up to 400 registry values and flushes **every 5 s while playing**. Slow on handhelds, bloats the registry, and every save rewrites everything. | P1 |
| B21 | `Main.kt` ~line 446, the 500 ms `LaunchedEffect` loop | Four synchronous `mpv_get_property` calls every 500 ms on the UI thread (duration, position, pause, mute). When mpv is busy the UI stalls; between polls the time and seek bar lag by up to 0.5 s. | P1 / P6 |
| B22 | `Main.kt` ~line 368, resume | Resume is a `seekAbsolute` **1.2 s after** `loadfile`. On a fast load the first second plays from 0 and then jumps; on a slow SMB or 4K load the seek can arrive before the file is ready and the resume is lost. | P6 |
| B23 | `Main.kt` ~line 481, `KeyEventDispatcher` | Skips shortcuts only when `focusOwner is JTextComponent`. Compose text fields are never a `JTextComponent`, so typing in any Compose field on the player page (rename, track search, a dialog) triggers Space = pause, F = fullscreen, M = mute. | P6 |
| B24 | `Thumbnails.kt` (`Semaphore(3)`) + `playback/ThumbnailExtractor.kt` (`mpv_create` per picture, `hwdec=no`, scale only) | A new mpv per thumbnail and **software** decoding, three at a time. On a handheld that's three 4K HEVC software decodes in parallel: CPU pegged, fans up, UI slow. HDR videos get washed-out thumbnails (no tone mapping). | P1 |
| B25 | `LibrarySync.kt` | Compose `mutableStateOf` (`current`, `status`, `problems`) is written from the scan coroutine for every file. Thousands of state writes per second cause recomposition storms during a scan. | P1 |
| B26 | `SkyUi.kt` `SkyField` (wraps `OutlinedTextField`) | No touch-keyboard trigger and no "keep visible above the keyboard" behaviour. Root cause of ask 10. | P5 |
| B27 | `Main.kt` arrow keys | ← seeks −5 s but → seeks +10 s. Make them symmetric (±5 s; J/L ±10 s), as in 9.1. | P6 |

**Third pass (from the motion efficiency review and the 20 November change report):**

| # | Where | Problem | Fix (phase) |
|---|---|---|---|
| B28 | Watchdog, `MotionEngine.kt` | The fix for the false restart at 6m 42s (`ed072bf`) is in **no published build**; 0.1.98 lacks it. The heartbeat is still a status file written every 0.5 s and polled; one failed read caused the false restart. | M0, then P2 |
| B29 | `EmbeddedMpv.kt` line ~358 | Only `\\server` paths count as network. A mapped drive letter (`Z:\…`) is reported as "local file", so the report is wrong and any network-aware logic (buffering, offline library, sleep/wake) would be too. | P1 |
| B30 | `MotionSettings.kt` line ~29 | Unknown refresh rate falls back to a **240 Hz** ceiling. On a 60 Hz screen that allows 119.88 fps: wasted power and judder. Windows always reports the current mode (`EnumDisplaySettingsW`, `dmDisplayFrequency`). | P2 |
| B31 | Frame-rate stops on fixed-refresh screens | Stops that don't divide the refresh (e.g. 74.93 fps on a fixed 120 Hz screen) are offered without warning and will judder. | P2 |
| B32 | Fullscreen cursor | In the fullscreen tests AWT reported a hidden cursor while Windows still showed it, until the mouse moved. The test now moves the pointer first, which **may hide a real bug**: a user who stops moving the mouse over the video can keep seeing the cursor. Likely cause (**verify**): Windows only re-asks for the cursor on mouse movement (`WM_SETCURSOR`), and the hide command runs on another thread; or a hidden-but-present overlay window sits under the pointer. | P6 |
| B33 | Accessibility (`SkyUi.kt` icon button, rail rows, switches, sliders) | The shared icon button takes a description and throws it away; navigation rows show selection visually but don't report it; several switches and player sliders have no accessible name. Windows UI Automation sees **none** of the Compose controls (only the frame, caption buttons, canvases and the mpv pane); they are only visible through Java Access Bridge (now packaged). | P3 |
| B34 | Next-build action plan (`docs/superpowers/plans/2026-11-19-…`) | Five playback findings: UI blocking during synchronised native calls (see B7, B21); the final watch position racing with close; a stale delayed resume applied to a reopened file (see B22); motion staying on after a display change leaves no allowed rate; Mouse Back over fullscreen overlays not handled. | P1 / P6 |
| B35 | Release assets | The installed motion payload's patch hash differs from the checkout, and a local package builds as **0.1.1**, lower than the installed 0.1.98 (MSI upgrade rules may block it or confuse testing). A release needs matching motion assets and a clean-install + in-place-upgrade check. | M0, P7 |
| B36 | Licences | The custom mpv bundles MSYS2's FFmpeg, which MSYS2 lists as GPL-3.0-or-later; the corresponding-source and notices audit is unfinished (FFmpeg, VapourSynth, Skia, other native dependencies, open-svpflow). Fine for personal use; must be done before any public release, signing or winget. | P7 / M10 |

**Fixes for the third pass:**
- **B28:** ship the fix first (M0). Then replace the file heartbeat with an in-process channel: the VapourSynth Python script calls a tiny native callback (or writes to a shared-memory block) with the frame counter and time; the watchdog reads memory, never a file. Keep the 15 s threshold and the bounded recovery. Regression test: no restart over 30 min with periodic simulated I/O failures.
- **B29:** classify by `GetDriveTypeW(root) == DRIVE_REMOTE`, and resolve mapped letters to their UNC with `WNetGetConnectionW` for display, reports and drive matching.
- **B30:** use the current mode's refresh from `EnumDisplaySettingsW(ENUM_CURRENT_SETTINGS)` for the monitor that shows the window; only if that fails, assume **60 Hz** (safe), never 240.
- **B31:** keep the four stops (no new options). On a fixed screen, a stop that isn't a whole divisor of the refresh gets a small note, "May judder on this 120 Hz screen", and isn't picked as the first-use default if a fitting stop exists.
- **B32:** add a test that keeps the mouse **still** over the video and expects the Win32 cursor hidden within 3 s. Fix candidates (**verify** which works): after hiding, nudge with `SetCursorPos(current x, current y)` so Windows sends `WM_SETCURSOR`; make hidden overlays truly hidden (not transparent-but-present) or click-through (`WS_EX_TRANSPARENT`) with a null cursor class; send the hide command asynchronously (B7).
- **B33:** see 6.9.
- **B34:** fold into P1 (UI blocking) and P6 (close, resume, display change, Mouse Back), each with a failing regression test first, as `AGENTS.md` asks.
- **B35 / B36:** see M0 and 11.4 item 11.

**Fixes for the second pass:**
- **B16–B18 (library index):**
  - Keep one in-memory copy, loaded once, guarded by a `Mutex`. All changes go through it; write-through to disk.
  - Tell "file missing" (first run: empty library) apart from "file unreadable" (error). On unreadable: retry 3× with a short wait, then restore `media-index.bak` (written before each successful replace). **Never write** while the index is in the error state; show "Library couldn't be read. Restored the last good copy."
  - Unit test: a corrupt index must never shrink the library.
- **B19:** walk with `Files.walkFileTree` and take size and time from the `BasicFileAttributes` the listing already returns (one directory listing per folder, no per-file stat).
- **B20:** move custom folders, history and other stores to small JSON files in `%LOCALAPPDATA%\SkyWing\` with atomic writes (temp file + move). Save history on pause, seek, stop, video change and at most every 30 s while playing, and only when the position moved by more than 5 s. Migrate the registry values once on first start, then delete them.
- **B21:** `mpv_observe_property` for `time-pos`, `duration`, `pause`, `mute`, `volume`, `speed`, `demuxer-cache-time`, `chapter-list`, read on the existing mpv event thread into a `StateFlow`. The UI collects it; no UI-thread mpv calls (debug assertion, as in B7).
- **B22:** pass the resume time to `loadfile` as a per-file option (`loadfile <path> replace -1 start=<seconds>`; **verify** the argument order for the bundled mpv version). No timer.
- **B23:** a shared `TextInputFocus` state that every `SkyField` sets on focus; the dispatcher ignores single-key shortcuts while it is true (Esc still exits fullscreen).
- **B24:** one reusable thumbnail mpv per worker; `hwdec=auto-copy` (falls back to software by itself); HDR sources get tone mapping (`vf=lavfi=[zscale=t=linear:npl=100,format=gbrpf32le,tonemap=hable,zscale=t=bt709:m=bt709:r=tv,format=yuv420p]`; **verify** the bundled FFmpeg has `zscale`, else use mpv's own `tone-mapping` with a screenshot path). Workers: 1 on battery or integrated-only, 2 on handhelds plugged in, 3 on dedicated GPUs. Paused while a video plays.
- **B25:** publish scan progress through a `StateFlow`, sampled at 4 Hz.

### 2.2 Design problems

- **Mixed styles:** glass outlines, white text with halos, saturated gradients and pill fields of different heights. Nothing lines up to a grid.
- **Weak hierarchy:** page titles, section titles and body text are similar sizes. The sidebar's "SkyWing / Video library" block wastes space.
- **Small targets:** too small and dense for touch on 8-inch handhelds.
- **No hover, pressed or focus states** on most controls. That matters for mouse, keyboard and controller users.
- **The effects are both the dated part and the slow part,** so the redesign fixes speed at the same time.

---

## 3. Phases (do them in this order)

| Phase | Goal | Size |
|---|---|---|
| **M0** | Ship and measure what's already done: commit local work, a build with the watchdog fix, 10-minute Hybrid acceptance, first handheld measurements (section 3.1) | S |
| **P1** | Speed foundation: nothing heavy on the UI thread, small images, cheap background, faster start; safe library index and JSON stores; network-drive detection (B16–B20, B24, B25, B29, B34 UI blocking) | M |
| **P2** | Graphics chip and display policy (hybrid/dedicated/integrated, fixed/VRR), GPU compatibility matrix and fallbacks (5.8); close frame-sharing's last risk (5.5); in-app measuring (5.6) and cheaper-smoothing experiments (5.7) | L |
| **P3** | Modern design system + adaptive layout (6.6) + new app shell + Settings with proper navigation + accessibility (6.9); follow the design boards (6.7) | L |
| **P4** | New "Add network drive" flow with username/password, auto-find, clear errors | M |
| **P5** | Handheld support: touch keyboard (8.2), touch-friendly everywhere (8.3), controller navigation, power-aware defaults | M |
| **P6** | New player: modern controls, animations, fullscreen transition; close, resume, display-change, Mouse Back and cursor fixes (B32, B34) | L |
| **P7** | Tests, performance checks, cleanup; release assets and licence audit (B35, B36) | M |

Report after each phase. M0 comes first because the watchdog fix isn't in any build yet and nothing has been measured on the handhelds. P1 and P2 then fix "slow" and the GPU choices without changing how anything looks.

### 3.1 M0: ship and measure what's already done

1. **Commit the local work** on `feature/windows-motion-engine` in separate commits: the `jdk.accessibility` packaging change, and the fullscreen test fixture fixes (with their two new regressions). Check `git status` is clean apart from build output. **Don't push** until I say "push".
2. **Build** (after I push) with the frame-sharing runtime, and confirm in the installer and the playback report that it contains `ed072bf`, the frame-sharing DLL (`MPV-PREVIEW-MANIFEST.json`) and **matching motion assets** (the installed payload's patch hash differed from the checkout).
3. **Version check:** make sure a local package can't be installed over a newer installed build by mistake (a local build came out as 0.1.1 while 0.1.98 is installed). Use the CI version or refuse to package a lower version.
4. **Laptop acceptance (Hybrid):** 10 minutes uninterrupted, fullscreen, the same local 4K 29.97 clip, Balanced 2×, Automatic GPU (all-Intel). Then the same with Performance forced to NVIDIA if available (or `SKYWING_*` override). Record drops per minute, restarts (expect 0), graph generations, process CPU, CPU package power, temperatures and fans, and the playback report.
5. **First handheld numbers** (OneXPlayer 3 with Arc B390, then the Claw): 1080p and 4K SDR, Fast and Balanced, 2×, plugged in and on battery, 5 minutes each: drops, CPU cores, battery drain per hour where possible.
6. Write the results into `windows/docs/modernise/M0-RESULTS.md`. These numbers set the handheld defaults (5.7) and the Automatic GPU policy (5.3).

---

## 4. P1: Speed foundation

**Target:** every screen scrolls at the display's refresh rate on the MSI Claw. No frame takes over 8 ms of UI-thread work while scrolling a 5,000-video library. Cold start to first page under 2.5 s on the laptop and under 4 s on the handhelds.

### 4.1 Nothing heavy on the UI thread
- Move `historyStore.load()`, `LocalFolderStore().load()` and every `library.all`-wide `map/associateBy/filter` out of composition. Each becomes a value in the model that is produced on `Dispatchers.Default`/`IO` and exposed as `StateFlow`, then collected with `collectAsState()`.
- **Pattern:**
  ```kotlin
  // In the model, not in a composable:
  val byPath: StateFlow<Map<String, WindowsMediaEntry>> = allFlow
      .map { list -> list.associateBy { it.path.lowercase(Locale.ROOT) } }
      .flowOn(Dispatchers.Default)
      .stateIn(scope, SharingStarted.Eagerly, emptyMap())
  ```
- **Fix B7:** add an async command for UI-originated calls, and use it for the cursor, volume, pause and seek-from-UI.
  ```kotlin
  /** Never blocks the caller; mpv runs it when free. */
  fun commandAsync(vararg args: String) {
      if (closed) return
      val array = StringArray(args)
      native.mpv_command_async(context, 0L, array) // verify the JNA binding exists; add it if not
  }
  ```
- Turn on Compose compiler stability reports. Make the data classes passed to composables `@Immutable`, or pass `ImmutableList` (kotlinx.collections.immutable) so cards skip recomposition.

### 4.2 Pictures at the size they're shown
- **One image loader** for covers, thumbnails and portraits (`ui/ImageLoader.kt`):
  - Decode **at the target size**, rounded up to a power-of-two bucket, using Skia's `Codec`. **Verify** `org.jetbrains.skia.Codec.makeFromData(...).getScaledDimensions(scale)` and `readPixels`. If that's unavailable, use `Image.makeFromEncoded` followed by a scaled draw into a `Surface`, done **once** and cached.
  - Memory LRU **by bytes**, about 1/8 of the max heap, capped at 256 MB. A small disk cache of downscaled JPEGs, keyed by path + modified time + size bucket.
  - **At most 3 decodes at once** (`Dispatchers.IO.limitedParallelism(3)`). Cancel decodes for cards that have left the screen.
- **Usage:**
  ```kotlin
  val bitmap by rememberImage(file, sizePx = with(LocalDensity.current) { IntSize(cardWidth.roundToPx(), cardHeight.roundToPx()) })
  ```
- Animated covers (GIF / flip-book) play only while visible **and** the window is focused. They're paused in Battery saver mode (P5).

### 4.3 A cheap background
- **Replace the animated multi-gradient wallpaper with a static one:**
  - Draw it **once** into an `ImageBitmap` the size of the window (or half size, scaled up; it's blurry anyway).
  - Redraw it only when the window size or theme changes.
  - Adaptive themes: recompute the palette at most **once every 2 s** after scrolling stops, then cross-fade two cached bitmaps over 600 ms. Don't animate gradient parameters per frame.
- Remove per-card gradient backgrounds (`Brush.linearGradient(listOf(theme.card, theme.surface))`). Use flat theme colours from the new design system (P3).

### 4.4 Start-up and memory
- **Packaging:**
  - Add a release build with ProGuard (`compose.desktop.application.buildTypes.release.proguard`). Keep rules for JNA, JCEF and anything reflective. **Verify** the packaged app starts and plays.
  - Ship the release variant in CI.
- **JVM:**
  - Move to JDK 21 if Compose 1.8.2 and jpackage are fine with it (**verify**).
  - Add an AppCDS archive if the Compose Gradle plugin supports it for this version (**verify**).
  - Set `jvmArgs += listOf("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=8", "-Xss2m", "-XX:+UseStringDeduplication")` and measure. Remove anything that doesn't help.
- **Skia renderer:** keep the default Direct3D 12. Add a hidden fallback setting `SKYWING_RENDER_API=OPENGL|SOFTWARE` for broken drivers, passed as `-Dskiko.renderApi`.
- **JCEF:** load only when the Google Images tab opens. Free it after that dialog closes, if JCEF allows it (**verify**).

### 4.5 Measure, don't guess
- Add a hidden **performance overlay** (Ctrl+Shift+F12): UI frame time (from `withFrameNanos`), recompositions per second, image cache size, decode queue length, JVM heap.
- Include those numbers in the playback report.
- Report before/after numbers for the laptop and both handhelds.

---

## 5. P2: Graphics chip and display policy

### 5.1 Facts the policy must respect
- mpv's `d3d11-adapter` is chosen **once, when the video output starts**. Changing it means re-initialising the video output.
- With motion smoothing on, frames are **copied to system memory** for VapourSynth (`hwdec=auto-copy`). So the motion engine (OpenCL) can run on a **different** GPU from the one that draws the video, at no extra cost.
- On a hybrid laptop, the built-in screen is wired to the integrated GPU. A dedicated GPU that draws the video has its frames copied across by Windows (Optimus). That copy is usually fine for video, but costs some power.
- In dedicated/MUX mode, or on an external monitor wired to the dedicated GPU, the dedicated GPU drives the screen directly.

### 5.2 Detect the hardware (`playback/GpuInventory.kt`)
- **List every adapter** with its name, vendor (NVIDIA / AMD / Intel / Other, from the PCI vendor ID, not the name), dedicated video memory, and whether it is integrated.
  - **Preferred:** DXGI `IDXGIFactory6::EnumAdapterByGpuPreference` through a tiny native helper DLL, or JNA COM vtables (**verify** which is simpler to keep correct).
  - **Fallback:** `EnumDisplayDevicesW` plus the registry `HKLM\SYSTEM\CurrentControlSet\Control\Class\{4d36e968-e325-11ce-bfc1-08002be10318}\*` (`DriverDesc`, `ProviderName`, `HardwareInformation.qwMemorySize`).
- **Which adapter drives the window's monitor:** keep `WindowsDisplayAdapter`, but map to the vendor/adapter record (AMD included), not a "starts with" string.
- **Power:** use `GetSystemPowerStatus` (AC or battery) and the Windows power mode (Battery saver).
- **Mode labels:** "Dedicated" (the monitor is wired to the dedicated GPU), "Hybrid" (dedicated GPU present, monitor on integrated), "Integrated only", "Dedicated only".

### 5.3 The policy (pure function, unit-tested)
```kotlin
enum class GpuChoice { AUTOMATIC, POWER_SAVING, PERFORMANCE }  // Settings → Playback → "Graphics for video"

data class GpuPlan(val renderAdapter: Adapter, val motionAdapter: Adapter, val decodeOnRender: Boolean, val reason: String)

fun plan(inv: GpuInventory, monitorAdapter: Adapter, choice: GpuChoice, onBattery: Boolean, motionOn: Boolean): GpuPlan
```
| Situation | Draws the video | Motion engine | Reason |
|---|---|---|---|
| Only one GPU (handhelds) | that GPU | that GPU | Nothing to choose |
| Dedicated mode (monitor on dedicated GPU) | dedicated | dedicated | Direct path; this is today's active-display routing |
| Hybrid, **Automatic** | the monitor's GPU (integrated) | same GPU | **Today's behaviour, keep it.** Measured: all-Intel 4K Balanced 2× held 59.9 fps with zero drops, and the RTX stays near idle. Never mix devices: the old 398-drop result came from Intel drawing + NVIDIA elsewhere |
| Hybrid, Automatic, smoothing **can't keep up** on the integrated GPU (the existing "offer next lower stop" fires at 2×) | offer "Use NVIDIA for smoothing" as well as the lower stop | — | One tap; remembered per video class. Never switch silently |
| **Performance** | dedicated | dedicated | User asked. Accepts the cross-GPU copy to the screen and higher power |
| **Power saving** | integrated | integrated | User asked |
| A GPU missing or failed to start | the other GPU | same | Always fall back, never fail |

**Verify before changing Automatic:** run the M0 acceptance on the laptop in Hybrid with both Automatic (all-Intel) and Performance (all-NVIDIA), 10 minutes each, same clip; keep Automatic as is unless the numbers say otherwise.

**Applying the plan:**
- **Draws the video:** set `d3d11-adapter` to the adapter's exact description (from DXGI), not a vendor prefix. If mpv rejects it, clear it and let Windows choose; then report "Using Windows' choice of graphics".
- **Motion engine:** set `SKYWING_OPENCL_VENDOR` to `NVIDIA` / `AMD` / `Intel`. Extend the open-svpflow Windows patch to match those vendor strings (AMD's OpenCL vendor is "Advanced Micro Devices, Inc."; **verify** each).
- **When the plan changes** (power plugged or unplugged, window moved to another monitor, setting changed):
  - Apply it at the **next video**, or immediately with a re-initialised video output and resume at the same position (`vo` reinit; **verify**).
  - Never restart on every small change. Debounce for 5 s.
- **Report:** show the plan and its reason, e.g. "Hybrid · drawing on Intel Arc 140V · motion on NVIDIA RTX 5070 Ti · plugged in".

### 5.4 Display sync (fixed, G-SYNC, FreeSync, VRR)
- **Detect per monitor:**
  - Refresh rate (mpv `display-fps`, cross-checked with `EnumDisplaySettingsW`).
  - VRR capability:
    1. NVIDIA: keep the NvAPI query.
    2. AMD: ADLX `IADLXDisplayFreeSync::IsSupported` (**verify** the binding cost; skip it if heavy).
    3. Any vendor: parse the monitor's **EDID** from `HKLM\SYSTEM\CurrentControlSet\Enum\DISPLAY\<id>\<instance>\Device Parameters\EDID`. Read the *Display Range Limits* descriptor (min/max vertical Hz), the DisplayID **Adaptive-Sync** block and AMD's FreeSync VSDB. That gives a VRR range (for example 48–240 Hz) for G-SYNC Compatible and FreeSync screens on Intel, AMD and NVIDIA. Treat it as "capable", not "enabled".
- **Show:** Settings → Playback → "Screen", as "240 Hz · Variable refresh 48–240 Hz (detected)". Keep my manual override switch. Refresh this only when the monitor or mode changes (`WM_DISPLAYCHANGE`), **not** every second, which also fixes the start-up hitch in B6.
- **Frame-rate stops (already built, keep them):** the four exact stops 2× / 2.5× / 3× / 4× with first use closest to 60 fps and stops above the refresh turned off. Changes only:
  - Refresh comes from the real current mode, never a 240 Hz guess (B30).
  - On a **fixed** screen, a stop that isn't a whole divisor of the refresh gets the note "May judder on this <N> Hz screen" and isn't the first-use default when a fitting stop exists (B31). No new "Match screen" option: the four stops stay the only choices.
  - On a **VRR** screen, every stop inside the VRR range fits.
  ```kotlin
  /** Annotates the existing four stops; it never adds or removes a stop. */
  fun annotate(stops: List<MotionStop>, refreshHz: Double, vrr: VrrRange?): List<MotionStop> = stops.map { s ->
      when {
          s.fps > (vrr?.maxHz ?: refreshHz) + 0.01 -> s.copy(enabled = false, note = "Above this screen's ${refreshHz.roundToInt()} Hz")
          vrr != null && s.fps >= vrr.minHz -> s.copy(note = null)
          isWholeDivisor(refreshHz, s.fps) -> s.copy(note = null)
          else -> s.copy(note = "May judder on this ${refreshHz.roundToInt()} Hz screen")
      }
  }
  ```
- **mpv settings:**

  | Screen | `video-sync` | `interpolation` | Notes |
  |---|---|---|---|
  | Fixed, target = refresh or a divisor | `display-resample` | `no` with motion on; `yes` + `tscale=oversample` only for "Blend" (if kept) | Audio is resampled slightly to lock to the screen |
  | VRR, fullscreen | `audio` | `no` | The screen follows the frame timing. Needs fullscreen or "windowed G-SYNC/FreeSync" in the driver |
  | VRR, windowed | as fixed | | Many drivers only do VRR in fullscreen. Say so once in the status line |
  | Below the VRR minimum (for example 24 fps on a 48–240 Hz screen) | `audio` | `no` | The driver's low-framerate compensation doubles frames |

- **Tearing safety:** keep `d3d11-flip=yes` and the default sync interval. The `SKYWING_D3D11_COMPAT` trial must never become a default.

### 5.5 Keep frame sharing, and close its last risk (B12)
- **Keep** the frame-sharing mpv: it's the best measured saving (about 11% less process CPU on Intel, identical output hashes for all five presets, zero drops). `SKYWING_VS_SHARE_FRAMES=0` stays as the switch for A/B tests.
- **Close the teardown timeout before calling it stable:** a native test that, with smoothing on, does 100 × (seek, fullscreen on/off, seek) then closes the player, 10 times in a row, and must not hang or crash. If practical, run it once with page heap on (`gflags /p /enable SkyWing.exe /full`) to catch a frame used after the VapourSynth script is gone. **Verify** in VapourSynth R73 whether a held `VSFrame` keeps its core alive; if not, copy any still-referenced plane when the script is destroyed.
- **Reproducible builds:** pin the MSYS2 package versions recorded in `MPV-PREVIEW-MANIFEST.json` (fail the build if they change), or build the dependencies from pinned sources.
- **Builds:** keep today's rule (push builds on the motion branch and chosen manual builds use frame sharing; pull-request builds use stock), and always put the runtime name and sharing state in the playback report and the installer file name, so testers never have to guess from the version number.

### 5.6 Measuring inside the app (so tests don't depend on external tools)
- **Per-minute log while smoothing:** output fps, mpv `frame-drop-count`, `decoder-frame-drop-count`, `vo-delayed-frame-count`, `mistimed-frame-count`, `vsync-jitter`, `estimated-display-fps`, process CPU (cores), motion heartbeat age, graph generation, and events (seek, pause, cache wait, fullscreen, recovery). Read through observed properties (B21), write to a CSV in the app-data folder. **Verify** each property exists in the pinned mpv.
- **"Copy test report"** in the playback report: build, runtime and sharing state, GPU plan, screen and VRR state, and the per-minute table.
- **Benchmark mode** (hidden, `--bench <file> --minutes 10 --quality Balanced --stop 2x`): plays fullscreen from a fixed start time and writes the CSV, so laptop, Claw and OneXPlayer runs are comparable.

### 5.7 Making smoothing cheaper (experiments, not promises)
The profile shows the full-resolution motion search is about 70% of the analysis work, with colour (chroma) scoring a big part of it, and SkyWing Balanced uses about 2.3 CPU cores against PotPlayer's 1.3 on Intel.
1. **PotPlayer-equivalent preset (test only):** copy the exact MVTools settings from my PotPlayer AviSynth script (archive the script first) into a hidden preset, so the two players can finally be compared like for like.
2. **Luma-only scoring:** **verify** whether open-svpflow can skip chroma in the search (as MVTools' `chroma=false` does). If yes, compare CPU and pictures against Balanced; adopt only if it looks the same to me.
3. **Handheld defaults:** measure Fast vs Balanced on the Claw and the OneXPlayer 3 (5.6 benchmark). Use whichever holds 2× at 1080p without drops on battery as the handheld default.
Every experiment keeps full-resolution output (no downscale and upscale; that was ruled out).

### 5.8 GPU compatibility matrix (most integrated and dedicated GPUs)

Detect capabilities at start-up (DXGI adapter + a one-off mpv probe of `hwdec-interop` and the OpenCL platform list), cache them per driver version, and decide per feature. **Verify** every codec row against the vendor's decode tables; the rows are the expected baseline, not a promise.

| GPU family (examples) | Draws video | Hardware decode (expected) | Motion engine (OpenCL vendor string) | Default smoothing |
|---|---|---|---|---|
| NVIDIA RTX 20/30/40/50, GTX 16 (RTX 5070 Ti Laptop) | D3D11 | H.264, HEVC 10-bit; AV1 from RTX 30 | Yes ("NVIDIA Corporation") | On; up to 4K at 2.5× on RTX 40/50 |
| NVIDIA GTX 10 and older | D3D11 | H.264, HEVC 10-bit on GTX 10; no AV1 | Yes | 1080p only, Fast quality |
| AMD Radeon RX 6000/7000/9000 | D3D11 | H.264, HEVC 10-bit, AV1 (RX 6000+; **verify** RX 6500/6400) | Yes ("Advanced Micro Devices, Inc.") | On; 1440p at 2.5× |
| AMD Ryzen integrated (Radeon 680M/780M/880M/890M) | D3D11 | H.264, HEVC, AV1 | Yes | 1080p at 2.5×, Balanced |
| AMD Vega integrated (older Ryzen) | D3D11 | H.264, HEVC; no AV1 | Usually | 1080p 2×, Fast |
| **Intel Arc B390 / Arc 140V / Arc 130V** (OneXPlayer 3, Claw 8) | D3D11 | H.264, HEVC 10-bit, AV1, VVC on newest (**verify**) | Yes ("Intel(R) Corporation") | Set from the 5.7 handheld runs; start at 1080p 2×, Fast. 4K: allowed, with a note |
| Intel Arc A-series dedicated | D3D11 | H.264, HEVC, AV1 | Yes | 1440p at 2.5× |
| Intel Iris Xe (11th–14th gen) | D3D11 | H.264, HEVC 10-bit, AV1 | Yes | 1080p 2×, Fast |
| Intel UHD 6xx/7xx | D3D11 | H.264, HEVC (10-bit from Kaby Lake); no AV1 | Sometimes | Off by default; allowed for ≤1080p 2× |
| No driver / Microsoft Basic Render / remote desktop | `d3d11-warp=auto` (software) | None (`hwdec=no`) | No | Off, with "Install your graphics driver for smooth playback" |

The "Default smoothing" column only sets the first-use **quality** and the resolution above which a "may struggle on this graphics chip" note appears. The frame-rate stop keeps the existing first-use rule (closest to 60 fps), and the user's choices per video class always win.

**Rules that make this work everywhere:**
1. **Start-up probe, not vendor names:** pick features by what the probe says the device supports. Never assume from the brand.
2. **Decode fallback chain:** `d3d11va` (zero-copy) → `d3d11va-copy` → `nvdec`/`vulkan` (only if present) → software. On the first decode error for a file, drop one step and continue from the same position. Report it.
3. **Render fallback:** D3D11 with the plan's adapter → D3D11 with Windows' choice → `d3d11-warp=auto`. Never a black window.
4. **Motion engine fallback:** OpenCL on the plan's GPU → OpenCL on any other GPU → CPU path for ≤1080p at 2× (**verify** open-svpflow's CPU mode is usable; if not, hide the switch and say "Motion smoothing needs a graphics driver with OpenCL"). Some Intel and AMD integrated drivers ship OpenCL only with the full driver package: say "Update your graphics driver" when no platform is found.
5. **When smoothing can't keep up:** build on what exists ("offer next lower stop" on sustained drops; automatic lowering is a setting, **off by default**). Add a quality step to the offer ("Lower quality" before "Lower frame rate"), and on a hybrid laptop the "Use NVIDIA for smoothing" offer (5.3). When automatic lowering is on, remember the level that worked per GPU + video class so the next video starts there.
6. **HDR:** motion smoothing stays **bypassed** for 10-bit HDR and Dolby Vision until it's validated on real files with Windows HDR on and off (as `AGENTS.md` requires). For playback: if Windows HDR is on for that monitor, pass HDR through (`target-colorspace-hint=yes`; **verify** with the bundled mpv). If not, tone-map with the GPU (`tone-mapping=bt.2446a` or `spline`; integrated GPUs use the cheaper option).
7. **The playback report** lists: adapter, driver version, decode path, render path, OpenCL device, VRR range, plan reason, drops.

---

## 6. P3: Modern design system, app shell and Settings

### 6.1 Direction: "calm, cinematic Windows 11"
- **Layers, not effects.** Flat, layered surfaces with one subtle 1-pixel edge, like Windows 11. The covers supply the colour; the interface stays quiet.
- **One accent colour** per theme, used for selection, focus and progress only.
- **Typography:** Segoe UI Variable (bundled with Windows 11; fall back to Segoe UI on Windows 10).
- **Spacing:** a 4-pixel grid. Corner radius 8 for controls, 12 for cards, 16 for panels and dialogs.
- **Motion:** short and purposeful. 120–200 ms ease-out for state changes, 250 ms for page transitions, never longer than 300 ms. Respect Windows' "Animation effects" off setting (`SystemParametersInfo(SPI_GETCLIENTAREAANIMATION)`) by reducing motion to fades.
- **Remove:** text halos and outlines, gradient cards, glass outlines on every element, pill-shaped text fields.

### 6.2 Design tokens (`ui/theme/Tokens.kt`)
```kotlin
@Immutable
data class SkyColors(
    val base: Color,          // window background
    val layer1: Color,        // sidebar, top bar
    val layer2: Color,        // cards, panels
    val layer3: Color,        // menus, dialogs, hover on layer2
    val stroke: Color,        // 1 px edges (white 8% on dark / black 8% on light)
    val text: Color, val textSecondary: Color, val textDisabled: Color,
    val accent: Color, val onAccent: Color, val accentSubtle: Color, // accentSubtle = accent at 16% for selected rows
    val focus: Color,         // 2 px focus ring (keyboard / controller)
    val danger: Color, val success: Color, val warning: Color,
    val scrim: Color,         // over video: black 55%
)

val Dark = SkyColors(
    base = Color(0xFF111215), layer1 = Color(0xFF16181C), layer2 = Color(0xFF1C1E23), layer3 = Color(0xFF24272D),
    stroke = Color(0x14FFFFFF), text = Color(0xFFF3F4F6), textSecondary = Color(0xFFA9AEB8), textDisabled = Color(0xFF6B707A),
    accent = Color(0xFF6CA8FF), onAccent = Color(0xFF0B1220), accentSubtle = Color(0x296CA8FF),
    focus = Color(0xFFFFFFFF), danger = Color(0xFFFF6B6B), success = Color(0xFF4CC38A), warning = Color(0xFFFFB547),
    scrim = Color(0x8C000000),
)
// Also: Light, Midnight (blue-black), OLED (pure black base, layer1 = 0xFF0A0A0B), and Adaptive (Dark with accent from covers).

object SkySpace { val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 24.dp; val xxl = 32.dp }
object SkyRadius { val control = 8.dp; val card = 12.dp; val panel = 16.dp }
object SkyMotion {
    val fast = tween<Float>(120, easing = FastOutSlowInEasing)
    val normal = tween<Float>(200, easing = FastOutSlowInEasing)
    val page = tween<Float>(250, easing = FastOutSlowInEasing)
}

val SkyType = Typography(
    displaySmall = TextStyle(fontFamily = SegoeVariable, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp),
    titleLarge  = TextStyle(fontFamily = SegoeVariable, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = SegoeVariable, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium  = TextStyle(fontFamily = SegoeVariable, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall   = TextStyle(fontFamily = SegoeVariable, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge  = TextStyle(fontFamily = SegoeVariable, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)
```
- Map existing theme choices to these, and migrate the saved choice. Keep Adaptive, but it now only changes **the accent and one soft background tint** (see 4.3).
- **Optional, behind a setting, after P1 is measured:** a Windows 11 **Mica** backdrop (`DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE = 38, DWMSBT_MAINWINDOW = 2)`) with `base` transparent. **Verify** it works with Skia's Direct3D renderer and doesn't cost frames on the handhelds. Ship it off if in doubt.

### 6.3 Components (`ui/components/*`)
Each component has **hover, pressed, focused, disabled and selected** states and a minimum touch size of **40 dp** (44 dp in Handheld mode):
- `SkyButton` with Accent, Standard and Subtle variants.
- `SkyIconButton` with a tooltip after 600 ms.
- `SkyTextField`: label above, 36 dp tall, 8 radius, an inline error line, and a password variant with a "show" eye.
- `SkyToggle`, `SkySegmented` (replaces the pill chip bar), `SkyListRow` (icon, title, subtitle, trailing control or chevron).
- `SkyCard`: cover, title, detail, a progress bar for "continue watching", and a hover lift (scale 1.02, edge to `accent` 40%, 150 ms).
- `SkyDialog` and `SkyMenu`: `layer3`, 16 radius, a soft shadow, enter by fade + scale from 0.96 over 150 ms.
- `SkyToast` (bottom centre, 4 s), `SkyEmptyState` (icon, one sentence, one button), `SkySkeleton` (shimmer placeholders while loading; shimmer off in Battery saver).
- **Focus ring:** 2 dp `focus` colour, 2 dp outside the shape, shown only for keyboard and controller focus (track the last input type).

```kotlin
@Composable
fun SkyCard(cover: ImageBitmap?, title: String, detail: String, progress: Float?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalSkyColors.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (hovered || focused) 1.02f else 1f, SkyMotion.fast)
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }        // layer-only: no relayout
            .clip(RoundedCornerShape(SkyRadius.card))
            .background(c.layer2)
            .border(1.dp, if (hovered || focused) c.accent.copy(alpha = .4f) else c.stroke, RoundedCornerShape(SkyRadius.card))
            .hoverable(interaction).focusable(interactionSource = interaction)
            .clickable(interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            if (cover != null) Image(cover, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else SkySkeleton(Modifier.fillMaxSize())
            if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp),
                color = c.accent, trackColor = Color.Black.copy(alpha = .4f))
        }
        Column(Modifier.padding(horizontal = SkySpace.m, vertical = SkySpace.s)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        }
    }
}
```

### 6.4 App shell
- **Navigation rail:**
  - **Expanded** (window ≥ 1100 dp): 240 dp wide, icons + labels, the app mark at the top (just the icon + "SkyWing", 48 dp tall).
  - **Compact** (window < 1100 dp, or Handheld mode): 72 dp wide, icons with small labels under them.
  - Sections: Home, Folders, Videos, Files, then a divider, Now playing (with a mini cover and progress), and Settings at the bottom.
- **Top bar per page:** title (`titleLarge`) on the left. On the right: search (expands into a field, Ctrl+F), then the page's main action, then a ⋮ menu for the rest. The same idea as the Android phone toolbars: at most 3 visible actions.
- **New Home page** (recommended):
  - "Continue watching" row (from history).
  - "Recently added" row.
  - Pinned folders.
  - Uses the existing data; no new scanning.
- **Page transitions:** `AnimatedContent` with fade + 24 dp slide over 250 ms. Keep each section's scroll position when coming back (hold `LazyGridState` per section in the shell).
- **Grids:**
  - Adaptive columns (`GridCells.Adaptive(minSize = 180.dp)`, 220 dp on large screens). Always `key = {}` and `contentType = {}`. Card aspect from the existing per-page setting.
  - Smooth wheel scrolling: animate wheel deltas over 120 ms with Compose's `scrollBy` animation (**verify** whether 1.8 already smooths; if so, leave it).

### 6.5 Settings: fixes B1 and gives proper navigation
- **Two-pane layout** when the window is ≥ 900 dp wide:
  - **Left (280 dp):** a search box ("Search settings") and the category list, always visible.
  - **Right:** the selected page with a breadcrumb title, e.g. "Settings › Playback".
- **Single pane** below 900 dp: category list, then the page, with a clear **← Back** button in the header.
- **Back always works:** Esc, the mouse back button (XButton1), Alt+Left, the controller's B button and clicking **Settings** in the rail all go back. Clicking Settings while in Settings returns to the category list.
  ```kotlin
  // Main.kt: let a section handle a repeat click instead of returning early
  fun go(section: String) {
      if (section == selected) { sectionReselected(section); return }   // Settings → back to list; Folders → to root; Videos → scroll to top
      ...
  }
  ```
- Hold the open settings page in the shell's state (not `remember` inside `SettingsScreen`), so leaving and returning behaves predictably. Returning from another section opens the list, unless you were mid-edit.
- **Categories:** Appearance · Library · Network drives · Artwork · Playback · Controls (keyboard, controller) · Performance (Battery saver, renderer fallback, overlay) · About.
- **Settings search:** filter every row by its title and keywords, and jump to it with a 1-second highlight.

### 6.6 Adaptive design: window size × input type

Two independent axes decide the layout. Build them as two values in a `CompositionLocal` so every screen reads the same answer.

```kotlin
enum class WidthClass { Compact, Medium, Expanded }          // < 720 dp, 720–1199 dp, ≥ 1200 dp (window, not screen)
enum class InputMode { Mouse, Touch, Controller }            // the LAST input used; switches live

data class Adaptive(val width: WidthClass, val input: InputMode) {
    val target get() = if (input == InputMode.Mouse) 32.dp else 48.dp     // minimum hit size
    val row get() = if (input == InputMode.Mouse) 40.dp else 56.dp
    val bodySize get() = if (input == InputMode.Mouse) 14.sp else 16.sp
    val showHoverActions get() = input == InputMode.Mouse
    val focusRingAlways get() = input == InputMode.Controller
}
val LocalAdaptive = staticCompositionLocalOf { Adaptive(WidthClass.Expanded, InputMode.Mouse) }
```

**Window size (width class):**

| Width | Navigation | Library grid | Settings | Player |
|---|---|---|---|---|
| Compact < 720 dp (snapped half of a handheld, small window) | Top tabs | 2 columns | List → page with Back | Bottom panel only, secondary actions in ⋮ |
| Medium 720–1199 dp (handhelds full screen, 1024 px windows) | Rail 72 dp, icon + label | 3–4 columns | Two panes, 240 dp list | Full panel, labels hidden |
| Expanded ≥ 1200 dp (laptop, desktop monitor) | Rail 240 dp, full labels, Now playing card | 5–6 columns (max card 320 dp; more columns, not bigger cards) | Two panes, 288 dp list, search | Full panel, labels |

Recompute on window resize with a 100 ms debounce. Keep scroll position and focus across a class change.

**Input type (density):**
- **Detect:** Compose `PointerEvent.type` (`Touch`, `Mouse`, `Stylus`); **verify** Compose 1.8 on Windows reports `Touch` from `WM_POINTER`. If it reports touch as mouse, read `GetMessageExtraInfo()` (touch/pen events carry the `0xFF515700` signature) through a small JNA window-proc hook. Controller = any XInput button. Keyboard keeps the current mode.
- **Start-up default:** Touch if `GetSystemMetrics(SM_DIGITIZER)` has `NID_INTEGRATED_TOUCH` and no mouse is present, otherwise Mouse.
- **Switch rules:** change density only between gestures (never mid-scroll or mid-drag); animate the change with the standard 200 ms motion, or instantly if animations are off.
- **Mouse:** hover lifts cards 2% and shows card actions and the seek preview; right-click menus; compact rows.
- **Touch:** 48 dp targets, 56 dp rows, +1 text step, ⋮ always visible on cards (no hover), long-press = right-click menu, press tint instead of hover.
- **Controller:** touch sizes plus an always-visible focus ring (2 dp gap + 2 dp accent), button hints in a bottom bar (A select · B back · Y search · LB/RB tabs).

### 6.7 Design reference boards

Reference screens built with these tokens, in the handover folder: `design/png/` (pictures at real size), `design/html/` (open in any browser) and `design/source/` (the original board files). The live canvas is **https://claude.ai/artifact/8jtT2iRm4dKrJVjTNTA5cw** (opens in Claude). Covers and frames are coloured stand-ins marked "sample". Match the structure, sizes and tokens; exact pixels are not required.

| Board | Size | What it shows | Notes for building it |
|---|---|---|---|
| Desktop · Home | 1440 × 900 | Expanded width + mouse. 240 dp rail (Home, Folders, Videos, Files and drives, Now playing card, Settings at the bottom), 76 dp top bar with search (Ctrl F hint), Add folder, ⋮. "Continue watching" (4 wide cards, time left, progress line, hovered card lifted with accent edge) and "Recently added" (5 columns, quality badge). | Home is a new page: continue watching from history, recently added from index dates. |
| Compact · Folders | 1024 × 720 | Medium width. 72 dp rail with labels, Original / Virtual / Custom segmented control, 4-column folder cards with a 3-picture mosaic, sort menu open: "tap again to flip order" with ↑/↓ arrows. | Same sort behaviour as Android. |
| Desktop · Settings | 1440 × 900 | Two-pane settings: category list with coloured icon tiles and search, breadcrumb, **Playback** page: "Graphics for video" (Automatic / Power saving / Performance) with the live plan line, Screen row with "G-SYNC ready" and the VRR switch, Motion smoothing with the 2× / 2.5× / 3× / 4× frame-rate stops and the Fast → Best quality slider. | Fixes B1: the category list is always visible, so you can always move between pages. |
| Add network drive | 1440 × 900 | Step 2 of 3 in a dialog: found drive with response time and alternate address, Guest / Username and password switch, labelled fields, show-password button, "Remember password · saved in Windows Credential Manager", the Windows 11 guest-access note, Advanced (domain), Back / Check connection / Sign in and continue. | Section 7. |
| Handheld · Home + touch keyboard | 1280 × 800 | Medium width + touch. Top tabs, 48 dp targets, search focused with the **Windows touch keyboard docked**; the page is pushed up so the results stay visible; first result focused. | Section 8.2. The keyboard itself is Windows', drawn only for reference. |
| Handheld · Settings | 1280 × 800 | Touch + controller. Category chips (LB/RB hint), 2-column 88 dp setting tiles, focus ring on "Battery mode", footer hints A Change · B Back. Performance page: Battery mode, Graphics (Intel Arc B390), smoothing off below 30 % battery, thumbnail workers, animations, screen VRR. | Section 8.1 and 8.4. |
| Handheld · Player | 1280 × 800 | Touch player: 56 dp top buttons (back, subtitles/audio, lock), 96 dp play/pause, double-tap −10 s feedback on the left third, vertical volume on the right (swipe), 44 dp tall seek bar with 28 dp thumb and buffered range, chips: Smooth · 60 fps, speed, Fit, audio track, Up next. | Section 8.3 and 9. |
| Desktop · Player | 1440 × 810 | Mouse player: status pill (resolution, 24 → 60 fps, GPU, drops), seek bar with chapter ticks and **hover preview** (thumbnail + time + chapter), controls row, motion smoothing popover (switch, frame-rate stops, quality slider, "Keeping up · GPU 41 %"). | Section 9. |
| Tokens | 1440 × 1000 | Colours, type ramp, spacing, radius and motion, the adaptive layout table, the three input densities, buttons, focus ring, status chips, switch. | Sections 6.2–6.6. |

### 6.8 Window size and position (smart default, remembered)

**Problem:** `Main.kt` line 73 always opens at `rememberWindowState(width = 1280.dp, height = 800.dp)`. On the handhelds (1920 × 1200 at 150% or 2560 × 1600 at 200% scaling) the screen is only about 1280 × 800 dp, so the "normal" window is as big as the whole screen, runs under the taskbar, and is neither maximised nor a useful window.

**Fix, `ui/WindowPlacement.kt`:**
1. **First start (nothing saved):** read the work area of the monitor the app opens on (`GraphicsConfiguration` bounds minus `Toolkit.getScreenInsets`, in dp).
   - Small screens (work area under 1400 × 900 dp, or handheld mode): **start maximised**. On an 8-inch screen a floating window is rarely what you want, and maximised respects the taskbar.
   - Bigger screens: a centred normal window at **75% of the work area**, clamped between 960 × 600 dp and 1600 × 1000 dp, in a 16:10 shape.
2. **After that, remember** normal size, position and maximised state per monitor (key = monitor device name + resolution + scaling) in the JSON settings file. Save on move or resize with a 500 ms debounce and on close. Never save the fullscreen-player state as the window state.
3. **Restore safely:** if the saved bounds are off every current screen (monitor unplugged, scaling changed), or bigger than the work area, fall back to rule 1 for the current monitor. Keep at least 64 dp of the title bar on screen.
4. **Restore size when un-maximising:** if the app started maximised on a handheld and you press Restore, use the 75% size from rule 1, not a full-screen-sized normal window.
5. **Minimum size:** 640 × 480 dp, so the compact layout (6.6) always fits.
6. **Setting:** Settings → Appearance → "Window at start": Remember last (default) / Always maximised / Small (60%) / Medium (75%) / Large (90%). Plus a "Reset window size" button.
7. **Keep Windows' own behaviour:** native caption buttons, Snap layouts (hover the maximise button) and Win + arrow keys must keep working; don't override `WM_GETMINMAXINFO` except for the minimum size.
8. **Test:** handhelds: first start is maximised; Restore gives a 75% window; close and reopen keeps the last state. Laptop: first start is a centred 75% window; move it to an external monitor, close, unplug the monitor, reopen: it appears on the laptop screen.

### 6.9 Accessibility (fixes B33)

`jdk.accessibility` is now packaged, so Compose controls are visible through Java Access Bridge. Windows UI Automation still sees only the window frame, caption buttons, canvases and the mpv pane. That's normal for Compose Desktop 1.8, so test with Access Bridge, not UI Automation.
- Every icon-only button passes its description to `Modifier.semantics { contentDescription = … }` (the shared `SkyIconButton` currently drops it).
- Rail, tab and settings-category rows use `Modifier.selectable(selected, role = Role.Tab)` so the selected state is announced.
- Every switch, slider and text field has a name tied to the control (`semantics { contentDescription }` or a merged label), including the player's seek, volume, frame-rate stops and quality slider. Sliders also report their value ("Quality: Balanced", "38 minutes 12 seconds of 1 hour 41 minutes").
- Focus order follows reading order; every control can be reached by keyboard and shows the focus ring (6.6).
- Tests: Compose semantics tests for these nodes, plus one Access Bridge check against the packaged app (as in the 20 November change report).

---

## 7. P4: "Add network drive" (fixes B2 and B3)

### 7.1 Why it fails today
- **No credentials:** SkyWing reads `\\server\share` through Java, which uses **your current Windows sign-in only**. A drive that needs its own username and password can't be opened.
- **Guest access blocked:** Windows 11 24H2 blocks guest (no-password) access and requires SMB signing by default. Many router USB drives only offer guest access or old SMB, so they fail with a vague error.
- **Ethernet vs Wi-Fi:** I can't tell from here why Wi-Fi fails. Likely causes: a different subnet (guest Wi-Fi), a VPN, a "Public" network profile with name resolution off, or the drive answering on a different address (`.249` vs `.250`). The new connection check (7.4) must show which one it is.

### 7.2 New flow (a dialog with 3 short steps)
1. **Where is it?**
   - One field: "Drive address". It accepts `192.168.71.250`, `\\192.168.71.250\USB`, `smb://…` or a computer name. A **Find drives** button checks the local network for drives (port 445 on the current subnet, user-triggered only, like Android's `LanDiscovery`) and lists what answers.
   - "Other addresses (optional)" sits under an **Advanced** expander, with the help text "If your router sometimes gives the drive a different address".
2. **Sign in:**
   - Choices: **Guest (no password)** or **Username and password**.
   - Fields: Username, Password (with a show eye), optional Domain/Workgroup under Advanced.
   - **Remember password** is on by default. The password is stored in **Windows Credential Manager**, never in SkyWing's own files.
3. **Choose share:**
   - SkyWing lists the drive's shares (`NetShareEnum`) as cards. Pick one (e.g. "USB"), then optionally choose folders.
   - The name defaults to the share name and can be renamed later.
- **Saved drives** show as rows: name, address, a status dot (Connected via `.249` · Wi-Fi), and buttons **Open**, **Edit**, **Remove**.

### 7.3 Implementation (`network/SmbConnector.kt`)
```kotlin
// JNA: mpr.dll and advapi32.dll. Verify struct layouts against the Windows SDK.
interface Mpr : StdCallLibrary {
    fun WNetAddConnection2W(res: NETRESOURCEW, password: WString?, user: WString?, flags: Int): Int
    fun WNetCancelConnection2W(name: WString, flags: Int, force: Boolean): Int
}
interface NetApi32 : StdCallLibrary {
    fun NetShareEnum(server: WString, level: Int, buf: PointerByReference, prefMax: Int,
                     read: IntByReference, total: IntByReference, resume: IntByReference?): Int
    fun NetApiBufferFree(buf: Pointer): Int
}

/** Connects without a drive letter (CONNECT_TEMPORARY); Java can then read \\host\share as usual. */
fun connect(host: String, share: String?, user: String?, password: CharArray?): ConnectResult {
    val remote = if (share == null) "\\\\$host\\IPC$" else "\\\\$host\\$share"
    val res = NETRESOURCEW().apply { dwType = RESOURCETYPE_DISK; lpRemoteName = WString(remote) }
    val code = mpr.WNetAddConnection2W(res, password?.let { WString(String(it)) }, user?.let(::WString), CONNECT_TEMPORARY)
    password?.fill('\u0000')
    return ConnectResult.from(code)
}
```
- **Remember password:** `CredWriteW` with `Type = CRED_TYPE_DOMAIN_PASSWORD`, `TargetName = host` (and every "other address"), `Persist = CRED_PERSIST_LOCAL_MACHINE`. Windows' SMB client then uses it automatically, also for the other addresses. **Forget:** `CredDeleteW`. Never log or report passwords.
- **Error 1219** (same server, different credentials): cancel the existing connection to that host (`WNetCancelConnection2W`) and retry once.
- **Plain-language errors** (`ConnectResult.from`):

  | Code | Message |
  |---|---|
  | 53, 67 | "Can't find the drive at this address. Check it's on and on the same network." |
  | 1326 | "The username or password is wrong." |
  | 1272 | "Windows blocks guest access to this drive. Use the drive's username and password." |
  | SMB-signing / SMB1 refusals (**verify** the exact codes Windows 11 24H2 returns, and test against a router USB share) | "This drive uses an old, insecure connection that Windows blocks. Turn on SMB2/3 in the router's USB sharing settings." |
  | 1231, 1232 | "The network isn't reachable from this PC right now." |
  | 64 | "The connection dropped. Try again." |

- **On app start:** reconnect saved drives in the background (credentials come from Credential Manager), using `ShareHosts.resolve` for the alternate addresses. Never block the UI.
- **Migration:** convert existing `WindowsShareProfileStore` profiles into the new format, keeping the library paths exactly the same.

### 7.4 Connection check (the Wi-Fi problem)
A **Check connection** button on each saved drive runs, in order:
1. Which network the PC is on (Wi-Fi/Ethernet, its IPv4 address and subnet, Private/Public profile, whether a VPN is active).
2. TCP 445 to every address (ms or timeout).
3. Sign-in result.
4. Share list.

It shows the first failing step with one suggestion, e.g. "Your Wi-Fi is on 192.168.68.x but the drive is on 192.168.71.x. They're on different networks (often a guest Wi-Fi)." Include this in a copyable report.

---

## 8. P5: Handhelds (MSI Claw, OneXPlayer) and power

### 8.1 Handheld mode, controller and power

- **Handheld mode:**
  - Detected automatically: touchscreen present (`GetSystemMetrics(SM_DIGITIZER)` has `NID_INTEGRATED_TOUCH`), screen ≤ 9 inches or below 1400 px logical width, and a controller present. Can also be set manually.
  - Effects: compact rail, 44 dp targets, larger text (+1 step), 2-column grids, player controls 20% bigger.
- **Touch:**
  - Drag-to-scroll with fling on every list and grid, pull-to-refresh where Android has it, long-press for menus, double-tap the left or right third of the player to seek ±10 s.
  - **Verify** how Compose 1.8 on Windows reports touch (`PointerType.Touch`). If touch arrives as mouse events, add a small adapter that turns a touch drag into `scrollBy` with a `splineBasedDecay` fling.
- **Controller (XInput, `xinput1_4.dll` through JNA; poll at 60 Hz only while the window is focused):**

  | Control | Action |
  |---|---|
  | D-pad / left stick | Move focus between cards and buttons (Compose `FocusManager.moveFocus`) |
  | A | Open / play |
  | B | Back (same as Esc) |
  | X | Options menu (same as ⋮) |
  | Y | Search |
  | LB / RB | Switch section or tab |
  | Start | Play / pause |
  | LT / RT in the player | Seek −10 / +10 s (hold to scrub faster) |

  Show button hints in the bottom bar when a controller is in use.
- **Power-aware defaults:**
  - **On battery** (or Windows Battery saver): no animated covers, no shimmer, no adaptive background, thumbnail making paused.
  - **Motion smoothing on battery:** quality capped at Fast, and the default stop is the one closest to 60 fps. The user can override.
  - **Settings → Performance:** "Battery saver: Automatic / Always / Never".
- **Integrated GPUs:**
  - Default player scaling `profile=fast`-like settings (`scale=bilinear`, `dscale=bilinear`, `cscale=bilinear`, `deband=no`) unless the user picks "High quality". Use high quality on dedicated GPUs.
  - Use `hwdec=d3d11va` (zero-copy) whenever motion smoothing is off.

### 8.2 Touch keyboard (fixes ask 10 and B26)

**Why nothing happens today:** Windows opens the touch keyboard by itself only when a tap focuses a text control it recognises through UI Automation, and only when no hardware keyboard is attached. Compose Desktop draws its text fields inside one AWT canvas. The 20 November accessibility check confirmed it: UI Automation sees no Compose controls at all, only the frame, caption buttons, canvases and the mpv pane. Java Access Bridge doesn't help here, because the touch keyboard doesn't use it. So SkyWing must open the keyboard itself.

**New `platform/TouchKeyboard.kt`** (JNA, no new native DLL):
1. **Show, preferred (Windows 10 1709+ and 11):** get the WinRT `InputPane` for our window: `RoGetActivationFactory("Windows.UI.ViewManagement.InputPane", IID_IInputPaneInterop)` → `IInputPaneInterop::GetForWindow(hwnd, IID_IInputPane2)` → `IInputPane2::TryShow()`. Hide with `TryHide()`. (**Verify** the interface IDs against the Windows SDK headers `inputpaneinterop.h` and `windows.ui.viewmanagement.h`; don't copy GUIDs from blogs.)
2. **Fallback (older builds or if the call fails):** `ITipInvocation::Toggle(GetDesktopWindow())` (CLSID `4ce576fa-83dc-4f88-951c-9d0782b4e376`, IID `37c994e7-432b-4834-a2f7-dce1f13b834b`, method at vtable index 3). Because it **toggles**, first check whether the keyboard is already open with `IFrameworkInputPane::Location` (CLSID `D5120AA3-46BA-44C5-822D-CA8092C1FC72`): a non-empty rectangle means it's open. If the COM call fails because TabTip isn't running, start `%CommonProgramFiles%\microsoft shared\ink\TabTip.exe`, wait 300 ms and retry once.
3. **When to show:** a `SkyField` gains focus **and** the last input was touch or pen, **or** the device has no hardware keyboard (`GetSystemMetrics(SM_CONVERTIBLESLATEMODE) == 0` in tablet posture, or no keyboard device in the Raw Input list), **or** the user pressed Y (search) on a controller. Never on mouse or keyboard focus with a keyboard attached.
4. **When to hide:** focus leaves all text fields (wait 150 ms, so moving from Username to Password keeps it open), the dialog closes, or Enter submits.
5. **Keep the field visible:** subscribe to `InputPane` `Showing` / `Hiding` (`OccludedRect`), or poll `IFrameworkInputPane::Location` every 200 ms while a field is focused. Add a bottom inset equal to the covered height, animate it 200 ms, and call `bringIntoView` on the focused field. Dialogs (Add drive, rename) move up so their buttons stay above the keyboard.
6. **Enter key:** each field declares its action: Search (search boxes), Next (moves to the next field), Done (submits). Handle Enter ourselves.
7. **Fullscreen player:** the keyboard can't show over an exclusive-like borderless fullscreen in some builds. If a field is focused in the player (track search), drop to windowed chrome first or show the field in a small overlay window that the keyboard can sit under (**verify** on the OneXPlayer 3).
8. **Controller-only typing:** the Windows touch keyboard can't always be driven by a gamepad (**verify** on both handhelds). If not, add a simple **SkyWing on-screen keyboard** for controller use only: a QWERTY grid, D-pad moves, A types, X deletes, Y space, Start done, plus recent searches as chips.
9. **Setting:** Settings → Controls → "Show touch keyboard": Automatic (default) / Always / Never.
10. **Test:** on both handhelds, tap search, drive address, username, password and rename: the keyboard appears every time, the field stays visible, Username → Password keeps it open, closing the dialog hides it.

### 8.3 Touch-friendly everywhere

- **Every hover thing has a touch equivalent:**

  | With a mouse | With touch |
  |---|---|
  | Hover shows card actions | ⋮ always visible on cards; long-press opens the same menu |
  | Right-click menu | Long-press (500 ms) opens it, with 56 dp rows |
  | Hover seek preview | Preview shows above the thumb while dragging |
  | Tooltip | Long-press on an icon button shows the tooltip |
  | Scroll wheel | Drag with fling and overscroll stretch |

- **Lists and grids:** drag-to-scroll with fling everywhere (8.1); a fast-scroll thumb on long lists with a letter or date bubble; pull-to-refresh on drive and folder views (runs a scan of that folder only).
- **Selection:** long-press a card to enter selection mode; then taps add or remove; the top bar shows the count and actions (hide, add to custom folder, rename, cover). Same as Android.
- **Player gestures:**
  - Tap: show or hide controls (not pause).
  - Double-tap the left or right third: −10 / +10 s, repeated taps add up (+20, +30) with the ripple feedback.
  - Swipe up/down on the right half: volume. On the left half: video brightness (mpv `brightness`; it never changes the Windows screen brightness).
  - Horizontal drag: scrub with a time bubble; release to seek.
  - Pinch: zoom to fill / fit.
  - The lock button disables all gestures until unlocked (as on Android).
- **Stay away from screen edges:** Windows uses edge swipes (left: Widgets, right: notifications, top: title bar). Keep gesture zones 24 dp inside the edges.
- **Sliders and switches:** 44 dp invisible hit area around thumbs; tapping the track jumps there.
- **Text size:** follow the Windows "Text size" setting (scale type, not layout), and support high-contrast themes with plain system colours.
- **No tiny close buttons:** dialogs get a 48 dp close button in touch mode, and a tap outside closes non-destructive dialogs.

---

## 9. P6: The new player

### 9.1 Layout (overlay windows, as today, so the video window is never resized)
```
┌──────────────────────────────────────────────────────────────────────┐
│ ←  Movie title.mkv                     ⓘ  [CC]  ⧉ PiP  ⋮            │  top bar: fades with the controls
│                                                                      │
│                              ( ▶ )                                   │  centre feedback icon on play/pause/seek (fades out over 400 ms)
│                                                                      │
│ ╭──────────────────────────────────────────────────────────────────╮ │
│ │ 12:34 ━━━━━━━━━━━━●───────────────────────────────── -1:02:10    │ │  seek bar: hover shows time + thumbnail
│ │ ⏮  ⟲10  ▶  ⟳10  ⏭    🔊 ━━━●──     1×   ✨60fps    ⛶            │ │  floating rounded panel, 12 dp from the edges
│ ╰──────────────────────────────────────────────────────────────────╯ │
└──────────────────────────────────────────────────────────────────────┘
```
- **Bottom panel:**
  - Rounded 16 dp, `Color.Black.copy(alpha = .62f)`, a 1 dp white edge at 8%, a max width of 980 dp, centred.
  - Left: previous, −10, play/pause, +10, next.
  - Right: volume (icon + slider; the wheel over the video also changes volume), speed (menu: 0.5–2×), the motion smoothing chip (on/off; a click opens the FPS and quality sliders), fullscreen.
- **Seek bar:**
  - 4 dp track that grows to 6 dp on hover, with a thumb that appears on hover. Shows the buffered range and chapter ticks (mpv `chapter-list`).
  - **Hover preview:** time + thumbnail. Make a trickplay strip in the background (one frame every 10 s, 160 px wide) using the existing `ThumbnailExtractor`, only for files longer than 2 minutes, cached on disk, paused while the motion engine is active.
- **Top bar:** back, title, info (codec, resolution, HDR, motion status), audio and subtitle menu (named tracks: language + title), picture-in-picture (a borderless always-on-top mini window, 480 px, which keeps the same mpv instance), and ⋮ (aspect, zoom, rotate, playback report, A-B repeat).
- **Mouse and keyboard:**
  - Double-click toggles fullscreen. A click toggles play/pause (**configurable**). The wheel changes volume; Shift + wheel seeks.
  - Space / K toggle play. J / L seek −10 / +10 s. ← / → seek −5 / +5 s. ↑ / ↓ change volume. F is fullscreen, M is mute, C is subtitles, `[` `]` change speed, Esc exits fullscreen.
  - `?` shows a shortcuts sheet.
- **Auto-hide:** after 2.5 s, as now. Kept open while paused, hovering the panel or a menu is open. The cursor hides with them (async, see B7).

### 9.2 Animations
- **Controls:** fade + 8 dp slide (bottom panel up, top bar down) over 180 ms ease-out on show; 220 ms on hide. Use `graphicsLayer { alpha; translationY }` only, no relayout.
- **Centre feedback:** on play/pause, seek or volume, an icon in a 72 dp dark circle that scales 0.85 → 1.0 and fades out over 450 ms. Seek shows "+10 s", volume shows "65%".
- **Seek preview:** fades in over 100 ms and follows the cursor.
- **Fullscreen and maximise transition:**
  - The video window can't be animated cheaply (resizing a Direct3D swap chain every frame is slow and flickers).
  - Use **fade-through-black** instead:
    1. Show a black, borderless, owned overlay window exactly over the player area.
    2. Animate its opacity 0 → 1 over 120 ms (`setOpacity` on a layered window, or Compose alpha inside it).
    3. Do the native fullscreen switch (`BorderlessFullscreen.set`).
    4. Move the overlay to the new bounds, then fade it 1 → 0 over 160 ms.
    5. Dispose of it.
  - The same for exiting. Total about 300 ms; it feels like a smooth cut, the way streaming apps do it.
  - **Maximise from the title bar:** keep the native caption buttons and don't set `DWMWA_TRANSITIONS_FORCEDISABLED`, so Windows' own maximise animation plays. **Verify** it's not currently disabled by `WindowChrome`.
  - Honour "Animation effects off" (instant switch).

```kotlin
/** Fade-through-black around a native fullscreen change. Runs on the EDT. */
suspend fun animatedFullscreen(owner: Window, area: () -> Rectangle, change: () -> Unit) {
    val veil = JWindow(owner).apply {
        background = java.awt.Color(0, 0, 0, 0); bounds = area(); isAlwaysOnTop = false; focusableWindowState = false
        contentPane.background = java.awt.Color.BLACK; opacity = 0f; isVisible = true
    }
    try {
        animate(0f, 1f, 120) { veil.opacity = it }   // small helper: withFrameNanos loop on the EDT
        change()
        veil.bounds = area()
        animate(1f, 0f, 160) { veil.opacity = it }
    } finally { veil.dispose() }
}
```
(**Verify** per-pixel opacity on `JWindow` with the current JBR/JDK on Windows. If unsupported, set `WS_EX_LAYERED` + `SetLayeredWindowAttributes` through JNA.)

### 9.3 Player bugs to fix on the way
- Make every UI → mpv call async (B7).
- Remember volume, mute and speed between videos (speed per video optional).
- Resume prompt when opening a video with saved progress: a small toast, "Resumed at 42:10 · Start over", not a dialog.
- End of video: "Up next" card for 5 s with a cancel button, if there is a queue.
- **From the next-build action plan (B34), each with a failing test first:**
  - Close: save the final watch position **before** the player tears down, and wait for it (bounded, 1 s).
  - Resume: tie the resume to the open request (a token), so a reopened or different file never gets an old seek (with B22's `start=`).
  - Display change: if the new screen allows no frame-rate stop (or smoothing isn't allowed there), turn motion off cleanly with a message instead of leaving the filter running.
  - Mouse Back (XButton1) over the fullscreen overlay windows: same as Back/Esc.
- **Cursor (B32):** the "still mouse" test and fix.
- **Fullscreen tests:** keep the 20 November fixture fixes (focus confirmed, pointer clear of controls, both overlays must hide). Add the B32 still-mouse case so the fixture's pointer movement can't hide a real bug.

---

## 10. P7: Tests and checks

- **Unit tests:**
  - GPU policy table (every row in 5.3).
  - Smoothing stops for fixed vs VRR screens, including 23.976, 25, 29.97, 60 Hz, 144 Hz, 240 Hz, VRR 48–240.
  - EDID range parsing with sample EDIDs (fixtures in `src/test/resources/edid/`).
  - SMB error mapping.
  - Settings navigation (reselect → list; Esc → list).
  - Image loader sizing.
  - Profile migration.
  - Library index: a corrupt or locked index never shrinks the library (B16); concurrent scan + hide keeps both changes (B17).
  - Registry → JSON migration runs once and keeps every record (B20).
  - Capability matrix decisions (5.8) from recorded probe results for NVIDIA, AMD, Intel Arc B390, Iris Xe, UHD and "no driver".
  - Adaptive: width class and input mode for sample window sizes and input sequences (6.6).
  - Watchdog: no restart over 30 simulated minutes with periodic heartbeat read failures; a genuinely stalled graph still recovers once (B28).
  - Drive type: mapped letters and UNC paths both report "network share" (B29).
  - Refresh: unknown refresh never allows stops above 60 Hz; fixed-screen judder notes for 60/120/144/165 Hz (B30, B31).
  - Close, resume token, display change with no allowed stop, Mouse Back over overlays (B34).
  - Accessibility semantics for icon buttons, rail selection, switches and sliders (6.9).
- **Native tests (existing harness):** fullscreen with the fade, 100 seeks with motion on, close while smoothing, async cursor, and no UI-thread `mpv_command` (add a debug assertion).
- **More native tests:** frame-sharing teardown stress, 10 × (100 seeks + fullscreen toggles + close) (5.5); still-mouse cursor hide over the video (B32).
- **Performance check script** (manual, on my three machines), with the numbers in the report:
  1. Scroll Videos (5,000 items) for 30 s: overlay shows frame time p95 < 8.3 ms on the laptop and < 16.6 ms on the handhelds.
  2. Open Settings → Playback → Esc → Library: no stuck pages.
  3. Add the drive over Wi-Fi with username and password; restart the app; the drive opens without asking again.
  4. Play 1080p and 4K, with smoothing on and off, on battery and plugged in; the report shows the GPU plan.
  5. Fullscreen in and out 20 times: smooth fade, no flicker, video keeps playing.
  6. Controller only: browse, play, seek, back to Folders.
  7. Touch only on both handhelds: every text box brings up the keyboard and stays visible (8.2); long-press menus, selection mode, player gestures (8.3).
  8. Resize the window from 600 to 2560 dp wide: navigation and grids change class without losing scroll position (6.6).
  9. Full scan of a 5,000-video drive over Wi-Fi: time before → after B19.
- **Clean-up:**
  - Delete dead code from the old glass styles once P3 lands.
  - Shorten `MOTION-ENGINE.md` and `SOURCE-STATUS.md` to the facts.
  - Merge or squash the stacked PRs #9–#13 into one reviewable PR before starting P3 (ask me first).

---

## 11. Extra recommendations (do if time allows, after P7)

1. **Shared backup with Android:** one versioned JSON (folders, covers, positions, settings; never passwords), so I can move my library between phone and PC.
2. **Jump lists and media keys:** play/pause/next from keyboard media keys, Windows' media overlay (System Media Transport Controls) and the taskbar thumbnail buttons.
3. **Single instance:** opening a video from Explorer while SkyWing runs plays it in the existing window.
4. **Crash reporter:** catch uncaught exceptions and native crashes (`-XX:ErrorFile=`) into the app-data folder. On next start, offer "Copy crash report". Nothing is sent anywhere.
5. **Auto-update check (optional, off by default):** read the latest GitHub release tag and tell me when a newer build exists.

### 11.1 My earlier recommendations: where they are in this plan

| Earlier recommendation | Where now |
|---|---|
| Hybrid GPU choice: keep active-display routing as Automatic, add Performance / Power saving and the "Use NVIDIA for smoothing" offer | B4 → 5.3 policy table (corrected after the efficiency review) |
| VRR detection for every vendor, no start-up flip and hitch | B6 → 5.4 (EDID range, `WM_DISPLAYCHANGE`) |
| No synchronous mpv calls on the UI thread | B7, B21 → 4.1 and the observe-property fix |
| Frame-sharing mpv: keep it, close the teardown timeout, pin MSYS2 | B12 → 5.5 |
| No 60 fps cap; frame-rate stops 2× / 2.5× / 3× / 4×, starting at 60 fps where it fits | 5.4 `stops()` and the Settings and Player boards |
| Just two controls: frame rate and quality, shown when smoothing is on | Boards "Desktop · Settings" and "Desktop · Player" |
| Keep full-resolution smoothing on PC (no Android-style 1080p process-then-upscale) | Unchanged; 5.8 limits by GPU class instead |
| Usernames and passwords for drives, Windows 11 guest block | B2, B3 → section 7 |

### 11.2 New features worth adding (my picks, in order of value)

1. **Home page:** Continue watching + Recently added (board "Desktop · Home").
2. **Smarter "can't keep up" offers** (quality first, then frame rate, then NVIDIA on hybrids), built on the existing offer (5.8 rule 5).
3. **Seek previews** (trickplay) and chapter ticks (9.1).
4. **Media keys + Windows media overlay + taskbar buttons** (11, item 2).
5. **Resume toast and Up next** (9.3).
6. **Subtitles:** auto-load `.srt`/`.ass` with the same name, subtitle delay (Z / X), size and position, a background box option.
7. **Audio:** audio delay, "Night mode" (gentle compression for quiet listening; mpv `af=acompressor`; **verify** the filter exists in the bundled build), remember the audio language.
8. **Sleep timer:** stop after this video / 30 / 60 min.
9. **Drive status without waking it:** show the last known state ("Asleep", "Reachable 14 ms") from the last real access; check only when I open the drive. Fits the "don't keep the drive awake" rule.
10. **Remember window size, position and maximised state** per monitor.
11. **Picture in picture** that keeps the same mpv instance (9.1).
12. **SkyWing on-screen keyboard** for controller-only typing (8.2 item 8).

### 11.3 Android features the PC port still lacks

| Android feature | PC status | What to build |
|---|---|---|
| Sort with ↑/↓ arrows (tap again flips order) | Partial | Same menu on every grid (board "Compact · Folders") |
| Drag to reorder custom folders | Missing | Drag with mouse, long-press-drag with touch, controller: X then D-pad |
| Hide and unhide videos and folders | Partial | A "Hidden" view in Library settings with Unhide |
| Backup and restore with ticks (choose what to include) | Missing | Same JSON format as Android (11, item 1), with ticks per part |
| Scene and movie posters per section, with a review step | Partial | Same review grid and per-section choice as Android |
| Performer portraits per section | Partial | Same per-section setting |
| Lock controls in the player | Missing | Lock button (board "Handheld · Player") |
| Playback speed menu | Missing | 9.1 |
| Resume from where I stopped | Yes, but racy | B22 |

### 11.4 More features to build (all approved)

Do these after P7, in this order. The first three come first because I'd notice them every day on the handhelds. Each is its own commit.

**1. Browse while the drive sleeps (offline library)**
- Keep the library index, covers, thumbnails, posters, folder tree and file sizes and dates on the PC (they're mostly cached already; make it complete and deliberate).
- Browsing, search, sorting, custom folders and Continue watching read only this cache. **No drive access until I press play**, open a folder that has never been scanned, or ask for a refresh.
- Each drive shows its last known state from the last real access ("Last seen 2 h ago"), never a live check.
- When play needs the drive and it's asleep, show "Waking the drive…" with a 20 s timeout and a Retry button, instead of a frozen player.
- **Test:** with the drive powered off, every library page opens and searches normally; nothing hangs.

**2. Sleep and wake safety (handhelds and laptops)**
- Listen for `WM_POWERBROADCAST` (`PBT_APMSUSPEND`, `PBT_APMRESUMEAUTOMATIC`) through the existing window hook. Also handle `WM_WTSSESSION_CHANGE` (lock and unlock).
- **On suspend:** pause, save the position right away (B20 store), stop thumbnail and scan work, release the SMB file handle.
- **On resume:** wait for the network (`NotifyIpInterfaceChange` or a 1 s poll up to 15 s), reconnect to the drive (the alternate addresses from `ShareHosts` included), reopen the file at the saved position, stay **paused**. If the drive doesn't answer, show the "Waking the drive" state from item 1.
- mpv's video output can be lost after sleep on some Intel drivers: if the first frame after resume fails, re-initialise the video output (`vo` reinit) without closing the player.
- **Test:** sleep in the middle of a 4K video for 1 minute and for 30 minutes on both handhelds; on wake the picture is right, paused at the same second, and play works.

**3. Watch progress sync with the phone (no drive writes)**
- PC and phone find each other on the home network with mDNS/DNS-SD (`_skywing._tcp`). Windows: `DnsServiceRegister`/`DnsServiceBrowse`, or JmDNS. Android: `NsdManager`.
- **Pairing once:** the PC shows a 6-digit code; I type it on the phone. Both keep a shared key (Windows Credential Manager; Android Keystore).
- **Sync:** watch positions, watched flags, hidden items, custom folders and sort choices, over a small TLS (or Noise) channel, last-writer-wins per item with timestamps. Paths are matched by drive + relative path, not by `\\server` or `smb://` spelling.
- Runs when either app opens and every 5 minutes while both are open. **Nothing is written to the drive.** Off until paired.
- Needs a matching change in the Android app: **ask me first** before touching `app/`, and list exactly what changes there.

**4. Handheld launch shortcut**
- Command-line options: `--handheld` (start maximised, controller and touch mode, handheld density), `--fullscreen`, and `--play <path>`.
- Settings → Controls → "Add to Steam" copies the exact command and icon path to the clipboard and shows short steps for Steam, Armoury Crate and MSI Center M. Don't edit those apps' files.
- The installer adds a second Start-menu shortcut, "SkyWing (handheld)".

**5. Windows accent colour and Mica background**
- **Accent:** read the Windows accent (`UISettings.GetColorValue(Accent)` through WinRT, or `HKCU\Software\Microsoft\Windows\DWM\AccentColor`), adjust it for contrast on the dark theme (WCAG AA against `base`), and use it as `accent` unless I picked one in Appearance. Refresh on `WM_SETTINGCHANGE`.
- **Mica:** `DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE = 38, DWMSBT_MAINWINDOW = 2)` and `DWMWA_USE_IMMERSIVE_DARK_MODE = 20` on Windows 11 22H2+. The rail and page backgrounds become slightly transparent so Mica shows through; cards stay solid.
- **Verify** that Compose/Skiko's window can be transparent with Mica without breaking the native video child window. If it can't, apply Mica only to the title bar area and settings dialogs.
- Off automatically on battery saver, with Windows "Transparency effects" off, and on Windows 10.

**6. Smarter buffering on Wi-Fi**
- Pick mpv cache sizes at open time from free memory and connection type:
  - Wired or local disk: `demuxer-max-bytes=150MiB`, `cache-secs=20`.
  - Wi-Fi: `demuxer-max-bytes` up to 10% of free RAM (max 1 GiB), `demuxer-readahead-secs=60`, `cache-secs=60`.
  - Also scale by bitrate (`file-size / duration`), so a 4K remux gets enough seconds.
- Read `demuxer-cache-state` (observed property, B21). If the cache runs dry, show a small "Buffering · 3 s" chip and the buffered range on the seek bar, instead of a frozen picture.
- In the playback report: average read speed, cache seconds, number of stalls.
- **Test:** 4K HEVC over Wi-Fi with a seek to 80%: playback resumes within 3 s and the chip reports progress.

**7. Match the screen's refresh rate (fixed-rate TVs and monitors)**
- Only in fullscreen, only on fixed-rate screens (no VRR range), and only when motion smoothing is **off**.
- Pick the closest mode that's a whole multiple of the video rate (23.976 → 23.976/47.952/119.88; 25 → 50/100; 29.97 → 59.94) from `EnumDisplaySettingsW` at the current resolution. Switch with `ChangeDisplaySettingsExW(..., CDS_FULLSCREEN)` and restore the original mode on leaving fullscreen, ending playback, losing focus for more than 5 s, or crashing (also restore at next start if a crash left it changed).
- Settings → Playback → "Match refresh rate in fullscreen": Off (default) / On.
- **Test:** on a 60 Hz TV with 23.976 and 25 fps files: the TV switches once, there is no judder, and it's back to 60 Hz afterwards.

**8. Audio passthrough to a soundbar or receiver**
- Settings → Playback → Audio: "Send surround audio to my receiver" (off by default) with ticks for Dolby Digital (`ac3`), DTS (`dts`), Dolby Digital Plus (`eac3`), DTS-HD (`dts-hd`) and TrueHD (`truehd`). These map to mpv `audio-spdif`. Optional "Exclusive mode" (`audio-exclusive=yes`).
- Choose the audio device (`audio-device`, list from mpv's `audio-device-list`); remember it.
- If passthrough fails (no sound, mpv error), fall back to decoded PCM and say so once.
- Passthrough disables volume control and audio-delay changes: show that in the player.

**9. Undo instead of "Are you sure?"**
- Hide, remove from library, remove from a custom folder, clear history: do it right away and show "Hidden · Undo" for 5 s (8 s on touch). Undo restores the exact previous state, order included.
- Keep a confirmation only for things that can't be undone (none of these touch the drive anyway).

**10. Watch local PC folders for changes**
- For local folders only: a `WatchService` (or `ReadDirectoryChangesW`) per library folder. New, renamed or deleted videos update the index within 2 s (debounced) with no full rescan.
- **Never for network drives:** watching keeps an SMB connection open and the drive awake. Network drives keep manual or scheduled scans.
- Stop watching on battery saver; catch up with one quick scan when it ends.

**11. Signed installer**
- Sign the MSI and `SkyWing.exe` in CI with **Azure Trusted Signing** (or another code-signing certificate I provide), so Windows stops showing "Windows protected your PC" on new machines.
- The signing secrets live only in GitHub Actions secrets; only run on `workflow_dispatch` or release tags; never print them.
- **Ask me first:** this needs an account I have to create. Prepare the workflow step behind an `if: secrets.SIGNING_ENDPOINT != ''` check so builds still work without it.

**12. winget listing (optional)**
- Publish a winget manifest (`Deepak.SkyWing`) pointing at the signed MSI on GitHub Releases, so `winget install SkyWing` and `winget upgrade` work.
- Needs item 11 and a public release. Prepare the manifest under `windows/packaging/winget/`; **ask me before submitting** the pull request to `microsoft/winget-pkgs`.

**13. Licence and source audit (needed before items 11 and 12, B36)**
- List every bundled native component with its licence: libmpv and the frame-sharing patch, FFmpeg and its MSYS2 dependencies (MSYS2 lists its FFmpeg as GPL-3.0-or-later), libplacebo, LuaJIT, VapourSynth R73, Python, open-svpflow, Skia/Skiko, the JDK.
- For each GPL/LGPL part: the exact source version and patches, and how the corresponding source is offered (a source zip attached to each GitHub release).
- Generate `THIRD-PARTY-NOTICES.txt` from that list in CI and fail the build if a bundled DLL isn't in the list.
- **Ask me** if a component's licence looks incompatible with public distribution (check open-svpflow first).

**14. Optional motion component in the installer**
- An MSI feature "Motion smoothing engine" (VapourSynth, Python, open-svpflow), on by default, so a small install without it is possible. The app hides smoothing with a plain reason when it's missing (the fallback already exists).
- Keep the MSI upgrade code and the in-place upgrade behaviour. Test: clean install with and without it, and upgrade from the current build keeping the library.

---

## 12. How to report back after each phase

For each phase:
- **What changed:** 3–6 plain bullets.
- **What to test on which machine:** laptop / Claw / OneXPlayer.
- **Numbers:** frame time p95, start time, memory, playback drops (before → after).
- **Commit IDs.**
- **Anything you couldn't do, and why.**
