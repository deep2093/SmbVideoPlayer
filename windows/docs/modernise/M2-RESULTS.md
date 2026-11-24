# M2 results: GPU choice, screens, smoothing safety and measuring

This is the report for M2 (`prompts/02-M2-gpu-screens-smoothing.md`, plan section 5). It says what changed, what was tested on the laptop and how, what wasn't and why, and what the owner should check by hand on each PC. Keep media names and paths out of this file: the repository is public.

M2 changes no preset, stop or per-file setting. The two cheaper-smoothing experiments are hidden (the PotPlayer-like options) or off by default (Smooth at screen size).

## 1. What changed

- **One GPU, chosen by a plan.** SkyWing lists the PC's GPUs (LUIDs, Windows' own integrated and external answers, the PCI vendor) and picks one with a pure, tested policy. Automatic keeps today's routing, the GPU driving the screen. Performance (dedicated) and Power saving (integrated) are new, in Settings > Playback > "Graphics for video". The same GPU draws the video and runs motion; two GPUs from one maker are matched by LUID. A change of screen, power source or GPUs that holds for 5 s re-plans, and a settings change moves a playing video without stopping it. On a hybrid PC in Automatic, "Use <dedicated GPU> for smoothing" is offered when 2× can't keep up.
- **Screens.** The refresh comes from the screen's current mode; the old 240 Hz guess is gone (B30). A stop the screen can't show evenly says "May judder on this N Hz screen" (B31), and every stop that divides the refresh uses `display-resample`. Each screen's HDR state, its EDID VRR range and NVIDIA's VRR bits are read again only on `WM_DISPLAYCHANGE`. "My screen uses G-SYNC / FreeSync" became the advanced switch "Let VRR control refresh (fullscreen)", off by default.
- **Smoothing safety.** The watchdog's heartbeat goes through shared memory instead of a status file (B28). Frame sharing keeps the VapourSynth core alive until its last shared frame is freed, its MSYS2 packages are pinned, and a 10-round teardown stress test exists. Interlaced video is deinterlaced and not smoothed. Without OpenCL on the plan's GPU, motion runs on another GPU's OpenCL, else on the processor up to 1080p at 2×. A lost graphics device re-creates the video output. A processor without AVX2 keeps smoothing off.
- **Measuring.** While smoothing runs, a per-minute CSV records drops, timing, CPU, heartbeat, events, HDR engaged, VRR active, the refresh and whether SkyWing was in front. Motion options has "Copy test report". `SkyWing.exe --bench` plays a file the same way on every PC, and `windows/tools/bench.ps1` runs it with optional PresentMon. The reports name the GPU plan, the processor, the render and decode paths and the driver versions. `verify.ps1` adds each screen's display state to its report.
- **Cheaper smoothing, as experiments.** A hidden PotPlayer-like option set for the benchmark (plan 5.7 item 1), and "Smooth at screen size" (item 4), off until the owner has compared it by eye.
- **Faults the acceptance runs found**, all fixed (section 3): the packaged app didn't start, three desktop tests had never run, a benchmark could hang the chain, and two tests were out of date or misread a covered window.

Changed files (55, all under `windows/`):
- GPU choice: `playback/GpuAdapters.kt`, `GpuInventory.kt`, `GpuPolicy.kt`, `OpenClDevices.kt`, `EmbeddedMpv.kt`, `SmoothingDialog.kt`, `SettingsScreen.kt`, `tools/open-svpflow-windows.patch`.
- Screens: `playback/DisplayPaths.kt`, `DisplayChanges.kt`, `DisplayVrr.kt`, `ScreenState.kt`, `MotionSettings.kt`, `Smoothing.kt`.
- Smoothing safety: `playback/MotionEngine.kt`, `Processor.kt`, `tools/mpv-vapoursynth-output-preview.patch`, `tools/mpv-preview-packages.json`, `tools/stage_mpv_preview.py`, `tools/test_stage_mpv_preview.py`, `legal/SOURCE-STATUS.md`.
- Measuring: `Bench.kt`, `Main.kt`, `playback/PlaybackLog.kt`, `WindowFocus.kt`, `tools/bench.ps1`, `tools/verify.ps1`, `tools/m0-runner/README.txt`.
- Cheaper smoothing: `playback/SmoothingSize.kt`, `docs/modernise/reports/potplayer-script.md`.
- Packaging: `build.gradle.kts` (the app image's Java runtime gains `java.management` and `jdk.management`).
- Documents: `MOTION-ENGINE.md`, `TESTING-POLICY.md`, this file.
- Tests: `BenchOptionsTest`, `DisplayStateReportTest`, `FrameSharingTeardownNativeTest`, `GpuInventoryTest`, `GpuPolicyTest`, `OpenClSelectionTest`, `PlaybackLogTest`, `ProcessorTest`, `ScreenStateTest`, `SmoothingSizeTest` (new); `EmbeddedPlaybackNativeTest`, `FullscreenWindowTest`, `GpuAdaptersTest`, `M0HandheldTest`, `M0SettingsTest`, `M0Handheld`, `M0Measurement`, `MotionEngineTest`, `MotionNativeTest`, `SkyWingAutomationTest`, `SmoothingTest` (changed).

## 2. The brief, item by item

| Item | State | Commits |
|---|---|---|
| 1. GpuInventory (5.2) | Done | 7fce4ab |
| 2. GPU policy (5.3) | Done | af99e5d, 681fbc7, d5054cd, 5bbf394 |
| 3. Display sync (5.4) | Done, with the gaps below | 360b0c6, 63065c3 |
| 4. Frame sharing (5.5) | Done in source; the two native patches need CI's build | 711e6b5, a5ff522 |
| 5. Watchdog heartbeat (B28) | Done | 3a8f01f |
| 6. Measuring (5.6) | Done; on-screen frame times need the owner's rights | 7d273b0, d3a7512 |
| 7. Compatibility matrix (5.8) | Rules 2–4 and 7–10 done; the family rows are not | 833f1a6, d3370b2, 613daa1, 49b2781, f821fe7 |
| 8. Cheaper smoothing (5.7) | Items 1 and 4 done; 2 and 7 checked and not possible as asked; 5 and 6 are owner runs | 5fc20b2, 070ccba |
| 9. Any processor (5.9) | Done; the laptop's in-front and behind comparison is in section 3, the handheld's is an owner run | 9cea102, 300eade |
| Fixes from the acceptance runs | Done | 21c3709, 2d3a561, 4f0f75c, 3f4b7d6, 2f43e54, 7acb9bd |

Where the code or the facts differ from the brief (the code wins, as the brief asks):
- **Item 3, VRR range.** NvAPI has no VRR range (`NvAPI_Disp_GetVRRInfo` returns only five flags), so the range comes from the EDID for every vendor. ADLX (AMD) and IGCL (Intel) were not added: each is a large driver interface to wrap through JNA, the brief asked for ADLX only if cheap, and the EDID already gives the range. NVIDIA's VRR-enabled flag is the only "VRR active now" reading; other GPUs say "not measured". On this laptop the NvAPI VRR query returned -6 (`NVAPI_NVIDIA_DEVICE_NOT_FOUND`) for the built-in screen, which Intel drives in Hybrid mode.
- **Item 3, VRR with the embedded window.** Whether VRR engages with SkyWing's video window and its overlays is not measured: it needs PresentMon's present mode (independent flip or composed), which needs the owner's rights, or the monitor's own VRR indicator, which needs eyes. The switch stays off by default, as the owner decided.
- **Item 4.** VapourSynth R73 doesn't keep a core alive for a held frame, and its memory tracker has a race on teardown. The patch keeps the core alive until the last shared frame is freed instead of copying planes, which isn't possible for frames already sent on. Neither this PC nor the installed 0.1.101 has the frame-sharing runtime, so the teardown test ran on the stock runtime only (section 3).
- **Item 6.** This account has no ETW rights, so `bench.ps1 -PresentMon` said "not measured". PresentMon next to `--bench` costs less than an ETW consumer inside SkyWing for the same rights, so SkyWing has no ETW code.
- **Item 7, rule 1.** The capability probe isn't cached per driver. It runs once when a video starts, the OpenCL listing only while smoothing is on, and a cache would only add the risk of stale answers after a driver update.
- **Item 7, the family rows.** Not done. The rows' first-use quality conflicts with the owner's M0 decision (Fast on every PC). The rest (the "may struggle on this graphics chip" note, "Off by default" for small integrated GPUs, "Not tested on this graphics chip") needs each GPU's family, and names don't say it: this laptop's integrated GPU reports only "Intel(R) Graphics", and AMD drivers call many integrated GPUs "AMD Radeon(TM) Graphics". It needs PCI device-ID tables per family, and the codec rows need checking against the vendors' decode tables. Section 7 asks for a decision.
- **Item 7, rule 6.** HDR and Dolby Vision smoothing stay bypassed. `target-colorspace-hint` was not changed: it needs checking with Windows HDR on and off on a real HDR screen.
- **Item 8, 5.7 item 2 (luma-only scoring).** Not possible: open-svpflow has no equivalent of MVTools' `chroma=false`; it always adds chroma to the block cost (`reports/potplayer-script.md`).
- **Item 8, 5.7 item 4, where to resize.** The pinned libmpv has `d3d11vpp` (with `scaling-mode`), but it scales only D3D11 frames, and the motion engine decodes with `hwdec=auto-copy`. Resizing on the GPU would need a different decode path (d3d11va, then `d3d11vpp` scaling, then a download before VapourSynth). That isn't built or measured; the resize runs in VapourSynth before Super, and sections 3 and 4 measure it.
- **Item 8, 5.7 item 7 (fewer copies).** Checked in open-svpflow's source: svpflow2 uploads each frame with `EnqueueWriteImage` and reads every output frame back with `EnqueueReadBuffer` or `EnqueueReadBufferRect`; it has no D3D11 sharing (`cl_khr_d3d11_sharing`). So frames can't stay on the GPU through VapourSynth without an architecture change. Estimate (not measured), 4K 4:2:0 at 24 to 48 fps: about 0.3 GB/s for the decode copy, at least 0.3 GB/s upload, about 0.6 GB/s readback and about 0.6 GB/s for mpv's upload. At screen size the readback and upload fall to about 0.15 GB/s each.
- **Item 9.** Only AVX2 is checked. FMA, BMI1, BMI2, LZCNT, MOVBE and F16C are also in x86-64-v3 but have no Windows flag. The minimum instruction sets of libmpv, ffmpeg, VapourSynth and Python weren't checked against their build flags. The EcoQoS work ("Lowest power that holds the frame rate") wasn't in the brief, so it isn't done; the in-front and behind comparison comes first.

## 3. Tests and results

All on the laptop (Lenovo 83F5: Core Ultra 9 275HX, Intel graphics plus RTX 5070 Ti in Hybrid mode, 2560 × 1600 at 240 Hz, HDR off), plugged in, Windows power mode Balanced, on 24 November. The acceptance runs used an app image of the M2 build with the installed 0.1.101's native files, as M1's did (section 6).

### First run: five faults, all fixed

The first full run (`verify.ps1 -Mode full` and the M0 runs, from 07:52) gave FAIL:
- **The packaged app didn't start** (21 desktop tests: "SkyWing did not start", and both M0 runs). The per-minute log reads SkyWing's own processor time through `java.lang.management`, and the app image's Java runtime lacked `java.management` and `jdk.management`: `NoClassDefFoundError` at the first player. The focused runs before it ran no desktop test, so every build from 7d273b0 until 21c3709 had this fault. Fixed in 21c3709 by adding both modules to the image.
- **The software-renderer test got the Intel renderer.** With a GPU plan naming an adapter, mpv ignores `d3d11-warp`. A PC without a graphics driver lists no hardware adapter, so the forced-WARP path now plans with none (2d3a561).
- **The lost-device test waited forever for the third loss.** The player recovers in `updateSmoothing`, which the app calls once a second; the test's last wait never called it. A test fault, not a player fault (2d3a561).
- **The 4K screen-size test failed with "A frame step from 0.0 s showed no later frame".** Two test races: the test clip had one keyframe in 250 frames, so the decoder restart that adding the filter causes ran to the end of the file, and mpv treated it as a single picture; and the first frame step came before the first 4K frame was on the output. The clips now have a keyframe every 24 frames, and the test waits for `vo-configured` (2d3a561). It passed three times in a row after that.
- **A benchmark whose SkyWing never ends** held the chain for 4.5 hours. `bench.ps1` now stops SkyWing 10 minutes after `-Minutes` and exits 1 (4f0f75c). Why that SkyWing didn't end wasn't found: it wrote no report.

### Full verification

- **Second run** (14:11, built from 3f4b7d6): FAIL, 2 of 358 tests.
  - `SkyWingAutomationTest.navigatesFromFoldersToPlaybackSettingsAndBack` still listed M1's three Playback switches; "Smooth at screen size" is the fourth. The test hadn't run on an M2 build before, because the first run's packaged app didn't start. Fixed in 2f43e54.
  - `FullscreenWindowTest.composeEscapeRestoresSameVisibleFrameAndCanvas`: "Fullscreen cursor did not hide with controls". The test's screenshot showed another program's window (the desktop app of the assistant running the tests) over the full-screen player, and the window under the pointer was that program's, so the arrow was its cursor. SkyWing kept focus, and bringing it to the front again didn't move that window below. The test now fails naming such a window instead of blaming SkyWing (7acb9bd, with a test that covers the player with an always-on-top window), and the runs after it minimised that program's window first.
- **Third run** (15:40, with 2f43e54): PASS. 358 tests, none failed or skipped, in 59 classes; the 16 Python build-tool tests passed. Isolation held: the real preferences and library cache were unchanged, no temporary folders or test processes were left, and all 54 SkyWing starts came from the image under test. Player memory after 4 sessions of 10 file switches: 682, 546, 535 and 537 MB. Over 12 rounds of open, play, seek and back to the library, memory grew by −1 MB from round 4 to 12.
- **Final run** (16:08, from 7acb9bd, that program's window minimised): PASS. 359 tests (the new covered-window test included), none failed or skipped; isolation held, and all 56 SkyWing starts came from the image under test. Player memory after 4 sessions: 653, 565, 545 and 545 MB; over 12 rounds, growth from round 4 to 12 was 8 MB.

### M0 acceptance with the M2 build

The M0 method (`M0-RESULTS.md` section 2), as in M1: the same 4K SDR clip (29.97 fps, H.264), motion on at 2×, Balanced, a 60-second warm-up and a 10-minute window, full screen. In every window no other program came to the front, full screen held and no input arrived.

| Run | GPU (drawing and motion) | Drops in 10 min | Drops per minute | Restarts | Throughput, report B (fps) | CPU cores | Private memory, MB: start → end |
|---|---|---|---|---|---|---|---|
| A, Automatic (14:36) | Intel | 62 | 6.2 | 0 | 59.80 | 2.46 | 6,081 → 6,152 |
| B, `SKYWING_GPU=NVIDIA` (14:48) | RTX 5070 Ti | 0 | 0.0 | 0 | 60.97 | 1.99 | 6,405 → 6,413 |
| A again (16:32) | Intel | 1 | 0.1 | 0 | 61.65 | 2.40 | 6,050 → 6,137 |
| A again (16:44) | Intel | 0 | 0.0 | 0 | 59.06 | 2.27 | 6,122 → 6,181 |

No run restarted, and the motion graph was built once in each (generations 1 → 1). Each SkyWing closed by itself on `WM_CLOSE`. Run A's 62 drops were all video-output drops (the decoder dropped none), none in the window's last minute; the M0 method reads only reports A and B, so when they came isn't known. Run A was repeated twice for that reason: 1 drop and none. M1's before build had 108 drops in one such run and M0's and M1's after runs none, so one run in three or four with a burst of drops seems to happen with or without M2. The 28 benchmark minutes of the same file on the same GPU (below) had none.

Every report names the GPU plan, the processor and the screen's state. Run A's lines:
- `GPU plan: Hybrid · drawing and motion on Intel(R) Graphics · plugged in · The screen is on this GPU` (run B: NVIDIA, "Forced by SKYWING_GPU").
- `Processor: Intel(R) Core(TM) Ultra 9 275HX; cores: 24 (24 threads) by Windows' efficiency class, higher is faster: class 1 × 8, class 0 × 16; instruction sets: SSE4.2 yes, AVX yes, AVX2 yes, AVX-512 no; motion engine (built for x86-64-v3) needs AVX2: yes`.
- `Screen: 240 Hz · Variable refresh not detected · HDR off`, and `VRR active: not measured (only NVIDIA reports it; Display capability unavailable)`. Intel drives this screen in Hybrid mode, so NVIDIA's VRR reading doesn't apply, and the EDID gives refresh limits (48–240 Hz) but no VRR range.

### Benchmarks: Smooth at screen size

`windows/tools/bench.ps1`, the same clip, 5 minutes each, Automatic GPU (Intel), `-PresentMon`. "Smoothing size" was 2560 × 1440 with the switch on (the screen is 2560 × 1600). CPU is SkyWing's process CPU, the mean of minutes 2 to 5; minute 1 is the start.

| Quality | Full size: cores | At screen size: cores | Saved | Drops (both) | Slowest second, minutes 2–5 (fps) |
|---|---|---|---|---|---|
| Fast | 1.30 | 0.84 | 0.46 (35%) | 0 | 56.8 / 56.6 |
| Balanced | 2.25 | 1.19 | 1.06 (47%) | 0 | 57.8 / 57.2 |

During playback the switch saves more than in the headless test (section 4), probably because the 4K picture is drawn and copied less after the resize; that wasn't measured apart. How it looks is the owner's comparison (section 5). PresentMon said "not measured (no rights)", so on-screen frame times aren't known.

### In front and behind (plan 5.9, laptop half)

An 8-minute Balanced benchmark of the same clip. A helper kept SkyWing in front for the first 4 minutes, then put its own window in front from about 250 s to the end, without minimising SkyWing. The per-minute log's Focus column read yes for minutes 1 to 4, mixed for minute 5 and no for minutes 6 to 8, so the switch worked.

| Minutes | Focus | Drops | CPU cores (mean) | Slowest second (fps) |
|---|---|---|---|---|
| 2–4 | yes | 0 | 2.24 | 57.7–57.9 |
| 6–8 | no | 0 | 2.36 | 57.8–57.9 |

Plugged in, being behind another window changed neither drops nor SkyWing's CPU on the laptop. So nothing here asks for opting out of Windows' power throttling. The handheld on battery, where Windows throttles harder, is the owner's run (section 5).

### Before and after

"Before" is M1's after build (061ea24) on this laptop: its M1-runner numbers and its M0-method playback run (`M1-RESULTS.md`). "After" is the M2 build (3f4b7d6), measured the same way on 24 November. One run each (three for M2's playback), so small differences may be chance.

| Measure | Before (M1, 061ea24) | After (M2, 3f4b7d6) |
|---|---|---|
| Start: first / everyday (s) | 1.32 / 1.25 | 1.43 / 1.27 |
| Library scan: first / everyday (s) | 0.11 / 0.13 | 0.12 / 0.15 |
| Scrolling Videos at 240 Hz: frame time p95 (ms) | 4.41 | 4.52 |
| Scrolling: frames per second; tasks over 8 ms; longest (ms) | 237.5; 2; 8.7 | 238.4; 2; 12.8 |
| Scrolling while thumbnails are made: frame time p95 (ms) | 4.85 | 4.82 |
| Memory after scrolling, private (MB) | 1,480 | 1,423 |
| CPU cores: idle / scrolling / scrolling with thumbnails | 0.01 / 0.46 / 1.76 | 0.02 / 0.55 / 1.65 |
| Playback, 4K Balanced 2× on Intel: drops per minute | 0.0 | 6.2, 0.1, 0.0 (three runs) |
| Playback: CPU cores | 2.13 | 2.46, 2.40, 2.27 |
| Playback: private memory at the end of the window (MB) | 5,942 | 6,152, 6,137, 6,181 |
| Playback: on-screen frame time p95 | not measured | not measured (no ETW rights) |

Start, scan and scrolling moved by about a tenth of a second or a tenth of a millisecond, in one run each, and the longest scrolling task rose from 8.7 to 12.8 ms. Playback used 0.14 to 0.33 more cores and about 200 MB more memory in all three runs. That isn't explained:
- Motion ran on the same Intel OpenCL device in M1's run.
- The per-second additions (the per-minute log's sample: about ten mpv property reads, the process CPU time and one foreground check) are small.
- Memory varied by about 200 MB between M0's and M1's runs too.
- M1's run was at night and these in the afternoon, so the laptop's clocks may have differed.

A profile of a playing M2 build against M1's would tell.

### Not tested, and why

- **The two native patches and frame sharing.** No Rust or MSYS2 toolchain here, and neither this checkout nor the installed 0.1.101 has the frame-sharing runtime. The teardown test ran on the stock runtime: 10 rounds of 75 s, each closing in 64 to 113 ms. CI's build is the first of the patches.
- **On-screen frame times and VRR engaging.** PresentMon needs ETW rights this account doesn't have, and SkyWing never asks for them. VRR also needs the monitor's own indicator.
- **HDR.** The screen's HDR was off throughout; HDR and Dolby Vision smoothing stay bypassed.
- **A real driver reset.** Needs the owner's OK (plan rule 8).
- **LUID matching of two GPUs from one maker.** No PC here has two.
- **Screen, charger and GPU-mode changes while playing, an external screen, the NVIDIA-only mode.** Hand tests (section 5).
- **The handhelds.** The owner's runs (section 5).

## 4. Cost of the experiments

`MotionNativeTest.eachOptionSetsCostIsMeasuredInRealTime` plays a generated pattern in real time at 2×, with no window, and records SkyWing's process CPU in cores. Laptop, 24 to 48 fps, about 20 s each, two runs each on 24 November.

OpenCL on the RTX 5070 Ti:

| Source | Fast | Balanced | PotPlayer-like |
|---|---|---|---|
| 1080p | 0.35, 0.44 | 0.47, 0.49 | 0.39, 0.41 |
| 4K | 0.97, 1.01 | 1.58, 1.64 | 1.08, 1.11 |
| 4K smoothed at 1920 × 1080 | 0.82 | 0.91 | — |

OpenCL on the Intel graphics (the Automatic plan on this laptop; the two full verification runs):

| Source | Fast | Balanced | PotPlayer-like |
|---|---|---|---|
| 1080p | 0.44, 0.47 | 0.47, 0.50 | 0.42, 0.35 |
| 4K | 0.72, 0.79 | 1.29, 1.33 | 0.72, 0.89 |
| 4K smoothed at 1920 × 1080 | 0.59, 0.48 | 0.55, 0.60 | — |

Runs vary by about 0.1 cores. PotPlayer-like costs about the same as Fast. Smooth at screen size saved 0.1 to 0.3 cores at Fast and about 0.7 cores at Balanced on 4K. The 4K decode copy and the resize stay, which may be why Fast saves less; they weren't measured apart. Played on screen, the switch saves more (section 3).

## 5. What the owner should test by hand

Every check below needs CI's installer from the M2 pull request. It is the first build with the two native patches (section 6), so start each PC with the checks under *Every PC*. Use local files, audio on, and the M0 method (`M0-RESULTS.md` section 2) wherever a check measures; that section's "My screen uses G-SYNC / FreeSync" is now "Let VRR control refresh (fullscreen)" in Settings → Playback, off by default (on by default since 25 November where the screen can vary its refresh: set it as each check says).

### Every PC

1. **Upgrade.** Install CI's MSI over 0.1.101. The library, Settings and a video's saved smoothing choice are kept, and SkyWing starts.
2. **Graphics for video.** Settings → Playback → "Graphics for video": Automatic, Performance, Power saving. Play a 4K clip with smoothing on, open Motion options and press "Copy test report". The `GPU plan:` line names the GPU that both draws and smooths. Change the setting while the clip plays: the video keeps playing, and a new report names the new GPU.
3. **The report.** It names the processor (`Processor:` with its core types and AVX2), each screen's refresh, HDR and VRR state, and the decode and render paths.
4. **Player basics.** Full screen in and out (F, Esc, double-click), Alt+Tab away and back, seek, pause, then close SkyWing while a video plays. No SkyWing process or sound is left after closing.
5. **A 59.94 fps file.** Which stops it offers depends on the screen: none at 60 Hz (or when the refresh is unknown), only 2× at 120 or 144 Hz, all four at 240 Hz. If a 120 Hz or faster screen offers none, send the report's `Display:` line.
6. **HDR.** With SkyWing open, turn Windows HDR on and off (Win+Alt+B). The report's HDR line follows. An HDR video still plays without smoothing.
7. **An interlaced clip** (a TV recording) plays deinterlaced, and Motion options says it isn't smoothed.

### Laptop (RTX 5070 Ti)

1. **The three choices in Hybrid mode.** Automatic and Power saving use Intel, Performance uses NVIDIA. At 4K Best, when 2× can't keep up, Automatic offers "Use NVIDIA for smoothing" once per file.
2. **The NVIDIA-only mode** (Lenovo Vantage's GPU switch, if you use it): Automatic uses NVIDIA, and the VRR line reads NVIDIA's state instead of "not measured".
3. **An external screen** on the NVIDIA port: move a playing video to it. Within about 5 s the report's GPU plan and refresh follow that screen, and the video keeps playing.
4. **Charger out and in** while a video plays: the plan is made again after about 5 s, with no stop.
5. **VRR.** Turn "Let VRR control refresh (fullscreen)" on, play full screen, and watch the screen's refresh indicator (NVIDIA Control Panel → Display → G-SYNC indicator, on the NVIDIA port or in NVIDIA-only mode). Tell me whether it varies: that decides the switch's default (section 7).
6. **On-screen frame times** (optional). From an administrator PowerShell, in the repository folder: `windows\tools\bench.ps1 -Video <clip> -Minutes 5 -Quality Fast -PresentMon`. Without those rights the result says "not measured"; SkyWing never asks for them.
7. **A driver reset** (only if you want it tested, plan rule 8): Win+Ctrl+Shift+B while a smoothed video plays. The video output is made again and playback goes on, or stops with a plain message. Send the report either way.

### MSI Claw 8

1. **The M0 table with the M2 build.** Install CI's MSI, copy the handheld runner folder (`SkyWing-M0-runner`, built with `gradlew -p windows m0Runner`) to the device's own drive as in M0, then `Run M0.cmd`. With a version other than 0.1.101 the runner writes `results\<device>-<version>`, apart from M0's rows, and prints the version it found.
2. **Smooth at screen size, by eye (plan 5.7 item 4).** Play the 4K clip at Fast, then Balanced, with Settings → Playback → "Smooth at screen size" off and on. If it looks the same to you, it can become on by default. Then `Run M0.cmd screen-size` for its CPU and battery numbers; its folders end in `-screen-size`.
3. **In front and behind (plan 5.9).** Play 1080p Fast on battery, full screen. After 3 minutes, open another program over it (Explorer) and leave it in front for 3 minutes, without minimising SkyWing. Then press "Copy test report": its per-minute table has a Focus column (yes, no or mixed) next to the drops. If the minutes behind have more drops, SkyWing should opt out of Windows' power throttling while it plays; that is a later change.
4. **Efficiency floor (plan 5.7 item 5).**
   1. Install 0.1.101 again for these runs, so they compare with M0's rows, or keep the M2 build and compare with its own `results\<device>-<version>` rows.
   2. Set MSI's limit to 20 W and Windows to Best performance. Move the old `…-1080p-fast-battery` folder out of `results`, unplug, and run `Run M0.cmd`: it redoes only that run (about 7 minutes).
   3. Rename that run's new folder after the limit (for example `…-1080p-fast-battery-20W-best-performance`).
   4. Repeat at 15 W. Then repeat the lowest limit that kept 0 drops in Windows Balanced.
   5. Keep the screen brightness the same for every run: the runner's `Battery:` line is the whole device's draw. Check that the Windows mode reads as set before each run.
5. **Baselines (plan 5.7 item 6).** On battery, at one power setting, with the 1080p and the 4K clip:
   1. In SkyWing with smoothing off (Motion options), using the M0 section 2 snippet, 5-minute window.
   2. In PotPlayer with its 2× script (`reports/potplayer-script.md`), using the same snippet with `$name = 'PotPlayerMini64'` (the process name; check it in Task Manager).
   3. Optional: `windows\tools\bench.ps1 -Experiment PotPlayer-like` plays SkyWing with PotPlayer's motion options, for a like-for-like CPU comparison.

### OneXPlayer 3 (Intel Arc B390)

1. Items 1 to 4 of the Claw list, with OneXConsole's limits in place of MSI's.
2. The report's `GPU plan:` line names the Arc B390, and its `Preferred motion GPU:` line names that GPU's OpenCL device.
3. The screen-size comparison by eye matters most here: its 4K drops in M0 came from the start.

## 6. Risks and open findings

- **Two native patches are uncompiled.** The open-svpflow LUID patch (681fbc7) and the VapourSynth keep-alive patch in the frame-sharing mpv (a5ff522) apply cleanly but were never built here: there is no Rust or MSYS2 toolchain on this PC. CI's build is their first. The motion payload's hashes change with it, and the frame-sharing mpv needs the M0 acceptance again before use. The MSYS2 pin may need new versions if UCRT64 has moved on.
- **The acceptance runs used the installed 0.1.101 natives**, as M1 did: this checkout's stock libmpv and motion payload couldn't keep up at 2×. So they don't test the two patches.
- **Playback cost.** In three M0-method runs the M2 build used 0.14 to 0.33 more cores and about 200 MB more memory than M1's one run, and one run of the three had a burst of 62 drops (section 3). Neither is explained.
- **Java modules.** The packaged app lacked `java.management` from 7d273b0 until 21c3709, and only a desktop test showed it. The app image's module list is written by hand; a later change that uses another JDK module would fail the same way, and only a full run (or `jdeps`) would show it.
- **The first benchmark hang** (4.5 hours, run 1) wasn't explained: that SkyWing wrote no report. `bench.ps1` now ends such a run after 10 minutes and exits 1.
- **Turning smoothing on near the end of a file with few keyframes.** Adding the filter restarts the decoder from the last keyframe; with one keyframe in the file the restart ran to the end and mpv treated the video as a single picture. The test clips now have a keyframe every 24 frames; real files with very long keyframe gaps weren't tried.
- **A GPU lost while playing** is tested only by handing the player libplacebo's message. mpv and libplacebo never recover by themselves; after a driver restart the output may stay on Microsoft's software renderer, and an adapter change may re-create the output twice. A real test needs a driver reset, which needs the owner's OK.
- **LUID matching** of two GPUs from one maker needs CI's motion payload, and no PC here has two GPUs from one maker.
- **Virtual display drivers** of game-streaming hosts show in DXGI under the GPU's own name. DXCore leaves them out, so SkyWing drops them, but Windows before 10 2004 has no DXCore.
- **`motion.fail`** keeps smoothing off for a file once its graph failed, until the per-file settings are cleared.
- **The per-minute log** drops the last part-minute when the player closes.
- **The HDR state** after Win+Alt+B while SkyWing is open relies on `WM_DISPLAYCHANGE` arriving for that change; not checked.
- **Desktop tests and other windows.** A window that stays over the full-screen test (always on top, or raised again and again) makes the full-screen test fail; it now names that window. Nothing on the desktop should be in front during a full run.
- **Variable rate.** Interlaced video plays at the field rate after deinterlacing. A variable-rate Matroska file reports its fastest rate as its rate, so its first frames are timed from that.
- **PotPlayer's 1.3 cores** may come from `Prefetch(1)` (one VapourSynth thread); not measured.
- **open-svpflow's search types** are numbered differently from MVTools': MVTools' `search=3` is open-svpflow's default type 4.

## 7. Decisions for the owner

1. **The M2 pull request** is a draft on top of M1's (#15), so M1 merges first. CI's build of it is the first of the two native patches; check that it passes before the hand tests.
2. **The GPU-family rows (5.8).** Not done. Two questions:
   - Their first-use quality per family conflicts with your M0 decision (Fast on every PC). Keep Fast everywhere?
   - The notes ("may struggle on this graphics chip", "Off by default" on small integrated GPUs, "Not tested on this graphics chip") need each GPU's family, and its name doesn't tell it. It needs PCI device-ID tables per family, kept up to date. Build them, or drop the notes?
3. **Smooth at screen size.** On by default only after your by-eye comparison on the Claw.
4. **"Let VRR control refresh (fullscreen)".** Stays off until VRR is seen to engage with SkyWing's window (laptop item 5, or PresentMon's present mode with your rights). *Decided 25 November:* PresentMon on the Claw showed independent flip at 72 fps on its 120 Hz screen, so the switch is now on by default where the screen can vary its refresh and off elsewhere (`MOTION-ENGINE.md`).
5. **The driver-reset test** needs your OK each time (plan rule 8).
6. **Power throttling.** SkyWing opts out of Windows' throttling only if the minutes behind show more drops. On the laptop, plugged in, they didn't (section 3); the Claw on battery is your run. The automatic "lowest power" work (EcoQoS on the motion threads, plan 5.9) wasn't in the M2 brief: start it in a later milestone, after those numbers?
7. **Pre-warming the card code** stays parked until all phases are done, as you said.

## 8. Commits

On `feature/windows-m2-gpu-screens`, on top of M1's `feature/windows-modern-pc`:

- 7fce4ab M2: Add the GPU inventory: LUIDs, Windows' own integrated and detachable answers, mode labels and the Windows graphics setting
- af99e5d M2: Add the GPU policy as a pure function: Automatic keeps the screen's GPU, plus Performance and Power saving
- 681fbc7 M2: Let the motion engine's OpenCL filter pick a GPU by its Windows LUID
- d5054cd M2: Draw and smooth on the GPU plan's GPU, and move a playing video between GPUs
- 5bbf394 M2: Add Settings > Playback > "Graphics for video" and the dedicated-GPU offer button
- 360b0c6 M2: Plan stops for the real refresh, note stops that may judder, and make VRR control an advanced option
- 63065c3 M2: Read each screen's HDR state, EDID VRR range and NVIDIA VRR bits, again only on WM_DISPLAYCHANGE
- 711e6b5 M2: Pin the frame-sharing mpv's MSYS2 packages to the versions validated in M0
- a5ff522 M2: Keep the VapourSynth core alive until the last shared frame is freed, and add the teardown stress test
- 3a8f01f M2: Send the motion watchdog's heartbeat through memory instead of a status file (B28)
- 7d273b0 M2: Log smoothing once a minute, add Copy test report and the hidden --bench mode (plan 5.6)
- d3a7512 M2: Put each screen's HDR and VRR state in verify.ps1's report, and add bench.ps1 with optional PresentMon
- 833f1a6 M2: Deinterlace interlaced video and never smooth it (plan 5.8 rule 9)
- d3370b2 M2: Smooth on another GPU's OpenCL, else on the processor up to 1080p at 2x (plan 5.8 rule 4)
- 613daa1 M2: Report mpv's decode step-down, software rendering and driver versions (plan 5.8 rules 2, 3 and 7)
- 49b2781 M2: Keep a variable rate's frame times when it averages a standard rate (plan 5.8 rule 10)
- f821fe7 M2: Re-create the video output when the graphics device is lost (plan 5.8 rule 8)
- 9cea102 M2: Name the processor in the reports and keep smoothing off without AVX2 (plan 5.9)
- 300eade M2: Record in the per-minute log whether SkyWing's window was in front (plan 5.9)
- 5fc20b2 M2: Add a hidden PotPlayer-like motion experiment and measure its cost (plan 5.7 item 1)
- 070ccba M2: Add "Smooth at screen size", off by default (plan 5.7 item 4)
- 21c3709 M2: Bundle java.management so the packaged app starts again
- 2d3a561 M2: Fix the three desktop tests that had not run before
- 4f0f75c M2: Stop a benchmark whose SkyWing never finishes
- 3f4b7d6 M2: Let the handheld runner measure an M2 build and Smooth at screen size
- 2f43e54 M2: Expect the "Smooth at screen size" switch in the Settings test
- 7acb9bd M2: Say when another window covers the full-screen test, not that SkyWing failed
- This report.
