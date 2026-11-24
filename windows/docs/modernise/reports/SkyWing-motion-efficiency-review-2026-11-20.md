# SkyWing Windows motion smoothing and efficiency review

Prepared 19 November 2026, Australia/Sydney. Updated 20 November 2026 from all five original user-supplied sensor CSVs. Covers the Windows motion work, laptop testing, diagnostic experiments, PotPlayer comparisons, and outstanding project requirements recorded in this chat and workspace. Repository and CI state below remains the snapshot verified on 19 November.

## Main findings

- Real SDR frame interpolation works through embedded libmpv, VapourSynth, and open-svpflow at full video resolution. The player supports exact 2×, 2.5×, 3×, and 4× source multipliers, five quality presets, per-video-type preferences, recent-drop reporting, and one stall recovery attempt.
- Scheduling and renderer fixes resolved several early failures. A real ten-minute network-share run at 4K 29.97 → 74.93 fps, Balanced, recorded zero new video or decoder drops in every minute. That test used NVIDIA and an earlier runtime.
- PotPlayer generally used less process CPU with smoothing enabled in our comparisons. In the verified all-Intel comparison, SkyWing Balanced used 2.312 logical core equivalents against PotPlayer's 1.336. With smoothing off, SkyWing used 0.057 against PotPlayer's 0.386. Equal interpolation quality and sustained displayed cadence were not established across the two players.
- The most repeatable optimization that preserved sampled output was sharing VapourSynth output planes with mpv. Two matched Intel A/B series reduced process CPU by about 11%, with zero measured output/decoder drops. Pixel hashes, sizes, and timestamps matched 48 frames for each of all five presets.
- Recalculation from the original long captures confirms 51.59 W average CPU package power for the frame-sharing build against 53.21 W and 56.90 W in two stock runs. Those captures had different fan behavior and were sequential user runs. A fifth, newly supplied 34-second capture averaged 16.38 W under very low GPU load and lacks matching playback/build evidence. It is recorded separately. The long captures support further Hybrid validation but do not establish a controlled whole-laptop power saving.
- Build 0.1.97 recovered from a restart at about 6m 42s despite a retained heartbeat only 1.04 s old. Source inspection found a watchdog defect that can trigger on one missed status read. The local fix is committed as `ed072bf` and passed the recorded local suite and targeted native test. It has not appeared in a published build verified for this report.
- Hybrid efficiency, long-run reliability after that fix, actual presentation timing, quality equivalence, HDR/Dolby Vision smoothing, and full project parity still need acceptance work.

## 1. Project and evidence snapshot

| Item | State checked for this report |
| --- | --- |
| Repository | `deep2093/SmbVideoPlayer` |
| Windows branch | `feature/windows-motion-engine` |
| Local committed HEAD | `f64d409`, a merge preserving the local watchdog fix and incorporating repository guidance |
| Local watchdog commit | `ed072bf` |
| Remote-tracking HEAD | `9f58021` |
| Ahead/behind against remote tracking | Local ahead by two commits, remote ahead by zero at inspection |
| Latest published frame-sharing preview verified | 0.1.97, commit `2bfc524` |
| Later build number verified | 0.1.98 uses stock mpv and adds repository guidance, with no watchdog fix |
| Current uncommitted work | Changes in `windows/build.gradle.kts`, `Main.kt`, `PlayerChrome.kt`, and `FullscreenWindowTest.kt`, plus an untracked action plan and diagnostic files |

The uncommitted changes concern Java accessibility packaging and fullscreen/cursor diagnostics. Their existence does not imply that their proposed fixes have passed or shipped. This review did not modify those files, push commits, change player settings, or run new playback benchmarks.

The working directory for this chat is a separate investigation folder. The Git checkout is under `referenced-chatgpt-conversation-this-is-an/work/SmbVideoPlayer`. `AGENTS.md` and the four SkyWing skills are in that checkout. They were absent from the chat's investigation directory when checked.

This report consolidates existing evidence. Older investigation reports describe their historical branch heads and pending items. The snapshot and build table here take precedence for the repository/build state verified on 19 November. All five original user CSVs were supplied again and independently parsed on 20 November. The revised sensor tables use those originals. Copies and SHA-256 hashes are retained beside this report in `SkyWing-review-source-csv-2026-11-20/`, together with `recalculated-summary.json`. Raw benchmark JSON and frame-hash files remain available for key laboratory comparisons.

### Hardware and media

- Lenovo Legion Pro 7i / Legion Pro 7 16IAX10H, Core Ultra 9 275HX, Intel integrated graphics, RTX 5070 Ti Laptop GPU.
- Internal display reported approximately 240 Hz. Windows display resolution was 2560 × 1600 in the recorded presentation checks.
- NVIDIA driver changed from 621.14 to 621.24. The user updated and restarted before the 0.1.92 reports. These driver periods must be kept separate in comparisons.
- Intel driver in the latest Hybrid reports was 32.0.101.8860.
- Principal real comparison source was local or SMB-hosted H.264 SDR, 3840 × 2160, exact 30000/1001 fps, commonly displayed as 29.97. Private media names and paths are omitted here.
- Early supplied 4K files were identified as 30/1 or 30000/1001 fps. A requested genuine 4K/25 network test was never established with those files. Generated 4K/25 fixtures were tested separately.
- The 1080p file described as 23 fps was verified as exact 23/1. Its 115 fps test was valid for the then-current 5× setting, which was later superseded by the four-stop UI.
- Alternate SMB mappings were used with authorization when the original drive was unavailable. A desktop copy was used for later reproducible local comparisons.

### How to interpret the measurements

| Measurement | What it establishes | Limit |
| --- | --- | --- |
| Source FPS | Input cadence, preferably represented as a rational fraction | A rounded label alone cannot identify the exact fraction |
| Target FPS | Requested output cadence | Does not measure delivered or displayed frames |
| Filter processing FPS / callbacks | Frames reaching the diagnostic output callback over its measurement interval | Can vary because of buffering, requests, restart, and reporting interval |
| mpv video/decoder drops | Counters from the playback pipeline | A final total cannot locate drops by minute without intermediate samples |
| PresentMon intervals | Windows presentation timing for captured swap chains | Must match the correct foreground video window and time interval |
| Process CPU cores | CPU seconds divided by elapsed seconds | Logical core equivalents, separate from CPU watts and system CPU percentage |
| HWiNFO total CPU usage | System-wide CPU percentage | Includes other processes |
| CPU package power | Package sensor reading including relevant on-package domains | Intel GPU power can overlap this domain and should not simply be added |
| RTX board power | NVIDIA board sensor reading | Includes display/idle activity and excludes the rest of the laptop |
| Fan RPM and temperature | Reported physical sensor values | Depend on cooling mode, prior load, room conditions, and sensor behavior |

The old approximately 690 fps standalone benchmark did not demonstrate equivalent playback capacity through the complete decode/filter/render/audio path. A 240 Hz display reading and a selected G-SYNC switch also do not establish active VRR or smooth presented cadence.

## 2. Implemented motion behavior

### Engine and lifecycle

The architecture is Kotlin/JVM 17 and Compose Desktop, with an AWT/Win32 video surface hosted through JNA. The motion path is hardware copy-back decode → VapourSynth → `Super` → `Analyse` → `SmoothFps` → mpv renderer. The pinned engine revision is `deadbad2201d77056173c05c2380758d88612019`, with portable VapourSynth R73 and Python 3.13.9.

While smoothing runs, the session selects `hwdec=auto-copy`, disables mpv's own interpolation, and manages video synchronization. Ending the session restores the prior owned settings and removes only the named `@skywing-motion` filter. Other filters are preserved. Native libraries stay loaded across graph rebuilds so Rust worker and OpenCL state can survive seeks.

Missing engine files, failed filter creation, and asynchronous plugin errors fall back to ordinary playback with a message and per-session failure memory. Reports retain the last useful engine metrics after fallback. The packaged motion component currently comes with the preview. Installer component selection remains outstanding.

### Frame-rate and quality controls

- Motion panel and toolbar use on/off. Blend, engine selection, fixed 60/75/90/120 buttons, and the old original-motion VRR button were removed.
- Four frame-rate stops use exact fractions. For a 29.97 source, the targets are approximately 59.94, 74.93, 89.91, and 119.88 fps. A 25 fps source at 2.5× targets exactly 62.5 fps.
- The first-use stop is closest to 60 fps. Sources around 50/60 fps use 2×. Sources above 60 fps bypass smoothing as already smooth.
- Stops above the known display refresh are disabled. Unknown refresh falls back to a 240 Hz ceiling. The former 120 fps cap is removed.
- Both sliders apply on release and rebuild at the sampled playback position. Multiplier and quality are stored for six resolution/SDR-HDR classes.
- The global Playback settings contain the VRR switch and automatic lowering, which defaults off. Manual VRR choices override detection. Hybrid capability can remain unknown.
- Sustained recent drops offer the next lower stop. Automatic lowering acts only when enabled. Motion can stop when the minimum 2× cannot keep up. Warm-up resets around seek, pause, cache wait, and reconfiguration. Low instantaneous callback throughput alone does not trigger lowering.
- The live drop label uses the last 60 seconds. Lifetime counters remain in the report. Three clean measurement intervals clear a recovered warning.

The five retained quality presets change motion estimation and synthesis work. Output remains full resolution. No DLSS-style whole-video reduction and upscale has been introduced.

| Quality | Super options | Analysis options | Synthesis options |
| --- | --- | --- | --- |
| Fastest | `gpu:1,pel:1,scale:{up:0}` | 32 × 32 blocks, overlap 0, coarse search distance -4 | algo 2, mask area 0 |
| Fast | `gpu:1,pel:1` | 32 × 32 blocks, overlap 1 | algo 13, mask area 30 |
| Balanced | `gpu:1,pel:2` | 16 × 16 blocks, overlap 2 | algo 13, cover 80, area 30 |
| Better | `gpu:1,pel:2` | 16 × 16 blocks, overlap 2, refine threshold 200 | algo 23, cover 80, area 30 |
| Best | `gpu:1,pel:4` | 8 × 8 blocks, overlap 3, refine threshold 200 | algo 23, cover 100, area 50 |

Best's overlap changed to preserve CPU-fallback compatibility after refinement. The preset names indicate different motion settings. They do not mean the source picture has been successively downscaled. `gpu:1` also does not guarantee that every motion-search operation runs on the GPU. The pinned exact-analysis path performs substantial CPU work.

### Fullscreen and reporting

Title and controls use transparent owned overlay windows. Showing them preserves the video bounds and HWND. The inactivity timer is 2.5 seconds with hold-open conditions for playback controls. Input, pause, focus loss, and fullscreen exit should restore the pointer. Local cursor regressions remain open despite hosted CI passing.

The playback report includes renderer and motion GPU separately, driver, input format, decoder, exact-derived target, callback throughput, rolling and total drops, graph generations, errors, retained incident data, preceding actions, filter presence, heartbeat age, recovery count, and the last 20 VapourSynth warnings/errors. Full state-duration history and reliable presented FPS remain unavailable. Some mapped SMB drive paths have been classified as local because the classifier recognizes UNC paths only.

## 3. Change and build history

| Commit | Change | Recorded result or status |
| --- | --- | --- |
| `d586aa8` / `83ba3b7` | Portable pinned native motion runtime, SDR integration, lifecycle/fallback/report | Foundation for later Windows builds |
| `da935d0` | Normalize Windows native patch line endings | CI patch application compatibility |
| `da21c94` | Preserve upstream x86-64-v3 alongside static CRT Rust flags | Fixed the reported open-svpflow compile failure |
| `ce491d0` | Automatic concurrency, 0.5 s status writes, retained diagnostics, bounded restart | Six-minute 1080p runs and 60-seek network test passed |
| `e8a828a` | Prefer validated NVIDIA renderer and report actual renderer | Early mismatched renderer reproduction improved from 398 drops to zero |
| `4c66daf` | Four-stop exact FPS UI, five presets, per-type storage, simplified controls | Native policy and preset tests |
| `a51d5f0` | Best overlap compatible with CPU fallback | Build 0.1.88 passed CI |
| `d69c118` | Fullscreen transparent controls | Build 0.1.89 passed CI |
| `c60eb0e` | Rolling-minute drops and warning recovery | Build 0.1.90 passed CI, ten-minute native-rate network qualification |
| `4f351b7` | Cursor timer and VRR capability/manual choice | Build 0.1.91 passed CI, later local cursor failures recorded |
| `9f9d76f` | Opt-in NVIDIA D3D11 presentation compatibility | Build 0.1.92 passed CI, local dedicated-mode workaround tested |
| `749b5fc` | Route playback to the active display adapter | Decode/render/OpenCL alignment tested in Hybrid and Intel mode |
| `0664ed0` | Build custom mpv frame-sharing preview in CI | First custom build failed thumbnail test because Lua support was absent |
| `2bfc524` | Restore Lua support in custom mpv | Frame-sharing builds 0.1.95 and 0.1.97 passed CI |
| `ed072bf` | Keep last valid active-graph heartbeat across failed polls | Local fix, tested and committed, awaiting published build |
| `f64d409` | Merge remote repository skill/guidance work with local changes | Current committed local HEAD at inspection |

The Rust failure involved `RUSTFLAGS` replacing the upstream `.cargo/config.toml` flags. Retaining `-C target-cpu=x86-64-v3 -C target-feature=+crt-static` restored the intended CPU target. The upstream baseline import issue remained outside that Windows build target.

### Build numbers that matter for the efficiency review

| Build | mpv path | Watchdog fix | Role |
| --- | --- | --- | --- |
| 0.1.92 | Stock pinned DLL | Absent | Driver 621.24 and dedicated-mode comparison |
| 0.1.93 | First custom DLL | Absent | Failed test build, missing Lua |
| 0.1.94 | Stock pull-request path | Absent | Same source as 0.1.93, successful conventional build |
| 0.1.95 | Custom frame-sharing DLL | Absent | First successful frame-sharing installer |
| 0.1.96 | Stock pull-request path | Absent | User's two Hybrid baseline captures |
| 0.1.97 | Custom frame-sharing DLL | Absent | User's latest ten-minute Hybrid capture |
| 0.1.98 | Stock pull-request path | Absent | Repository-guidance update, verified conventional build |

Higher version numbers alone do not identify the frame-sharing feature. The workflow input/event and packaged `MPV-PREVIEW-MANIFEST.json` identify the runtime. Push builds on the motion branch and explicitly selected manual builds use the custom path. The verified pull-request builds above used the stock download path.

Verified CI pages are [0.1.95](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44305169466), [0.1.96](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44305182572), [0.1.97](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44322008158), and [0.1.98](https://github.com/deep2093/SmbVideoPlayer/actions/runs/44427749923). The 0.1.97 installer artifact was available when checked. Artifacts have limited retention.

## 4. Early speed, frame-drop, and stability work

### Six-minute network tests

These used production motion scripts, hardware copy-back, and null video output. They establish engine/scheduler progress with network media. They did not measure real screen presentation or audio sync.

| Source and then-current target | Median processing FPS | Last-minute mean | Video drops | Result |
| --- | --- | --- | --- | --- |
| 1080p exact 23 fps → 115 | 114.98 | 115.00 | 9, all in first second | No later drops, stalls, or engine errors |
| 1080p exact 25 fps → 100 | 99.68 | 100.00 | 1 at about 221 s | No stalls or engine errors |
| Real 4K 25 → 100 | — | — | — | Requested source was not established |

The 1080p network file also completed 60 exact seeks over 130 s, producing 61 graph generations with at least 114 callbacks each and no filter errors. The suspected OpenCL/Rayon seek hang did not reproduce in that run.

### Scheduling on a generated 12-second 4K/25 fixture

| Concurrent requests / buffered frames | Unpaced elapsed time |
| --- | --- |
| 2 / 8 | 13.42 s |
| Auto / 8 | 7.47 s |
| Auto / 16 | 7.89 s |

Automatic concurrency with buffer 8 was selected. Increasing the buffer did not help this fixture and increased buffering exposure. Later all-Intel tests with only two requests produced 480 output drops despite filter readings near target. Twelve requests versus automatic on the sharing path also showed no saving.

### Stage and early renderer experiments

Generated 4K/25 fixture, median of three unpaced runs, including startup/tail work.

| Pipeline enabled | Elapsed time |
| --- | --- |
| Decode and GPU copy-back, null output | 1.65 s |
| Above plus VapourSynth pass-through | 2.12 s |
| Above plus interpolation, auto/8 | 7.03 s |
| Motion plus default Intel `gpu` rendering | 12.43 s |
| Motion plus default Intel `gpu-next` rendering | 12.33 s |

These are overlapping pipeline comparisons. Pure copy-back, interpolation kernel, and upload times cannot be extracted as independent wall times from their differences. An upload timer reported 1.33 ms on Intel with only four samples, against 0.46 ms on NVIDIA with 256 samples.

On the actual 4K/29.97 network clip at 119.88 target, a 65 s run with fullscreen entered at 30 s recorded 398 video drops on default Intel rendering and zero on explicit NVIDIA. Both had zero decoder drops. A further 180 s NVIDIA run had zero video/decoder drops. These tests predated active-display routing and used audio-disabled probes. They identify the old mixed-device configuration's cost, rather than a universal inability to use Intel.

### User-reported playback progression

| Build and setting | User report |
| --- | --- |
| 0.1.85, 4K/29.97 Balanced 4× | Around 121.8 processing fps, but fallback after 137 incident drops fullscreen and 67 in a separate windowed run |
| 0.1.86, Balanced 4× | Windowed report had zero drops/errors over the recent minute. Fullscreen report retained 43 video drops amid seeks/reconfiguration |
| 0.1.88, Best 2× | 16.99 processing fps, 174 incident video drops, smoothing stopped |
| 0.1.88, Better 2× | 58.98 processing fps, zero drops in the final minute |
| 0.1.88, Fastest 2× | 60.62 processing fps, zero total drops in the supplied report |
| 0.1.88, Fastest 4× | First report 119.75 fps and 19 total drops. Later report 118.01 fps and zero total/recent drops |
| 0.1.89, Fastest 3× | 90.00 processing fps, zero drops |
| 0.1.89, Better 3× | 89.93 processing fps, two total video drops and zero in final minute |
| 0.1.92, local Balanced 4×, driver 621.24 | 117.88 processing fps, zero recent drops, 51 total drops, no engine errors |
| 0.1.92, network Balanced 2×, driver 621.24 | 61.16 processing fps, zero recent drops, five total drops, no engine errors |

Snapshot processing rates below target with zero drops are compatible with callback buffering and sampling. Those snapshots cannot measure maximum processing capacity. The reports also cannot assign each lifetime drop to a fullscreen transition, seek, startup, or steady playback without timed samples.

## 5. Ten-minute fullscreen network qualification

The real embedded SkyWing UI at `c60eb0e` played a mapped SMB 4K SDR source at its native 30000/1001 cadence, Balanced 2.5×, target 75000/1001 or 74.925 fps. NVIDIA handled rendering. Ten measured minutes followed startup/fullscreen settling, with no seeks or pauses and all 600 focus samples in foreground.

| Minute | Output callbacks/s | New video drops | New decoder drops |
| --- | --- | --- | --- |
| 1 | 75.33 | 0 | 0 |
| 2 | 74.83 | 0 | 0 |
| 3 | 75.42 | 0 | 0 |
| 4 | 74.17 | 0 | 0 |
| 5 | 75.42 | 0 | 0 |
| 6 | 74.83 | 0 | 0 |
| 7 | 74.23 | 0 | 0 |
| 8 | 75.35 | 0 | 0 |
| 9 | 74.75 | 0 | 0 |
| 10 | 75.42 | 0 | 0 |

There were 44,985 callbacks over 600 s, averaging 74.98 fps, one graph generation, zero delayed frames, and no errors, warnings, stalls, or recovery attempts. Four scheduled overlay show/hide cycles caused no drops. An extra reveal at second 518 stayed visible for an unrecorded reason and caused no drops.

The first interrupted attempt was excluded. Its saved interval had no added drops after 61 baseline startup/transition drops. The successful retry began and ended at zero. This qualifies the native 29.97 source and the tested software revision. Audio sync, VRR activation, subjective artefacts, genuine 4K/25 network playback, and newer Hybrid runtime reliability remain separate checks.

## 6. PotPlayer setup, glitches, and comparison

PotPlayer used AviSynth/MVTools with the saved MBlock2 preset and one prefetch worker. The user supplied a gist and `strayge/potplayer_true_motion` as setup references. This review uses the configuration and measured behavior recorded in the workspace. The full exact script/plugin source inventory should be archived before treating future runs as identical.

### Horizontal colored-line investigation

The 4K clip showed repeated colored horizontal lines with AviSynth smoothing. The user reported a clean picture with AviSynth disabled and also on 1080p. Changing PotPlayer to the high-performance GPU did not remove the lines. Reducing to `Prefetch(1)` removed the visible lines. The user subsequently confirmed a clean picture with the one-worker preset on Intel as well.

This isolates the observed problem to the multiworker filtering configuration sufficiently to use the single-worker workaround. We did not capture the underlying failing memory operation or prove a specific plugin thread-safety defect. The observations do not support blaming integrated graphics alone.

### Initial actual-player comparisons, 15 November

Same local 4K/29.97 source, about 57–59 s per sample, target 59.94. Scenes overlapped but sample windows were not frame aligned.

| Player/path | Process CPU cores | RTX board power | Rate evidence |
| --- | --- | --- | --- |
| PotPlayer on Intel | 1.394 | 3.62 W idle RTX | 59.91 fps in player panel |
| PotPlayer on NVIDIA | 1.259 | 16.76 W | Approximately 60 fps checked earlier with same settings, measured run field blank |
| SkyWing 0.1.91 Balanced | 1.909 | 19.11 W | 59.76 output callbacks/s |

SkyWing used Intel decode and NVIDIA render/copy. Its measured CPU was about 52% higher than the NVIDIA PotPlayer sample. The Intel PotPlayer board-power row records the inactive RTX, excluding its active integrated GPU. A whole-system ranking cannot be made from that row.

### iGPU-mode investigation and corrected routing

An early user-selected iGPU-mode run still exposed NVIDIA to Windows. PotPlayer used Intel at 1.312 process cores, while installed SkyWing still used NVIDIA render/copy at 1.874. The old app's NVIDIA preference made that SkyWing result unsuitable as an all-Intel comparison. A forced-Intel native probe held 4K Balanced 2× with zero video/decoder/delayed drops and 2.20 cores.

After the active-display routing change, both actual players were verified on Intel in a later approximately 32–34 s comparison.

| Verified all-Intel player | Smoothing off CPU cores | Smoothing on CPU cores | On-rate evidence |
| --- | --- | --- | --- |
| SkyWing | 0.057 | 2.312 | 60.14 mean filter fps, 17 valid heartbeats |
| PotPlayer | 0.386 | 1.336 | AviSynth 2× enabled, sustained displayed timing not traced |

SkyWing used about 73% more process CPU with smoothing on and substantially less with it off in this comparison. This supports prioritizing the interpolation path. The user also reported visibly smooth Intel PotPlayer playback. Quality equivalence, CPU package power, and wall power were absent from these short runs.

### Hybrid routing, later actual-player sample

| Player | Off CPU cores | On CPU cores | GPU activity |
| --- | --- | --- | --- |
| SkyWing with active-display routing | 0.072 | 2.354 | Intel decode/compute/render |
| PotPlayer with its existing renderer setting | 0.395 | 1.296 | NVIDIA render, no GPU decode observed |

SkyWing sustained 59.89 mean filter fps. These approximately 32–34 s samples compare the selected configurations on the same laptop mode. Their different GPU paths prevent attributing the entire difference to the interpolation engine alone.

### Dedicated NVIDIA mode and driver change

Before driver 621.24, PotPlayer used 1.271 cores and 24.25 W RTX board power over 88.8 s, with a 59.92 fps spot reading. SkyWing 0.1.91 stopped smoothing, invalidating its corresponding power comparison. Native NVIDIA tests still generated about 60 fps but dropped 709 frames with D3D11VA copy-back and 831 with NVDEC copy-back over about 30 s.

After the user updated/restarted to 621.24, both players completed the compared output interval.

| Driver 621.24 fullscreen sample | Process CPU cores | RTX board power | Rate evidence |
| --- | --- | --- | --- |
| PotPlayer | 1.273 | 16.22 W | 60.03 fps panel spot check, 87.4 s sample |
| SkyWing 0.1.92 Balanced 2× | 1.945 | 32.45 W | 59.67 callbacks/s, 88.9 s sample |

SkyWing had one graph with all sampled heartbeats fresh. This sample lacked its drop counters. PotPlayer's final panel rate was blank, and its RTX video-decode activity was zero. The RTX power gap was 16.23 W and process CPU gap about 53%. Neither player had matching full-laptop energy or independently matched displayed cadence in this comparison.

### Latest user-run Hybrid sensor captures

Full-capture averages recalculated from the originals on 20 November, including startup and any pause intervals. HWiNFO CPU usage is system-wide. The application/build labels come from the user's matching playback reports and capture descriptions. HWiNFO alone does not identify the SkyWing version, selected quality, smoothing state, or presented FPS.

| Capture | Length | CPU package power | CPU usage | Package temperature | Intel GPU power | CPU/GPU fan |
| --- | --- | --- | --- | --- | --- | --- |
| SkyWing 0.1.96 run 1 | 10m 52s | 56.90 W | 16.15% | ~75°C | 6.46 W | ~1600/1700 RPM |
| PotPlayer Hybrid | 10m 44s | 40.40 W | 10.82% | ~72°C | 0.01 W | ~1600/1700 RPM |
| SkyWing 0.1.96 run 2 | 10m 08s | 53.21 W | 13.26% | ~68–69°C | 6.82 W | ~1600/1700 RPM |
| SkyWing 0.1.97 sharing | 10m 40s | 51.59 W | 12.00% | ~68°C | 6.84 W | Zero recorded initially, then ~1600/1700 RPM |

The package-temperature rows use the two similarly named package sensors rounded to avoid implying identical definitions. In 0.1.97 all three fan channels reported zero for the first 9m 40s, then ramped to approximately 1600/1700/2300 RPM for CPU/GPU/PCH. This unusual profile requires confirmation before crediting quieter operation to the app. The first two SkyWing runs also differed by 3.69 W without the sharing change.

The original files confirm the previously reported long-run averages to their stated precision. The 0.1.97 CSV contains 290 zero readings out of 321 on each fan channel. The first nonzero fan sample occurs 580.313 s after the first logged sample, at 01:53:35.362. Raw full-capture fan means are 153.89, 162.62, and 215.58 RPM for CPU/GPU/PCH because those recorded zeros are included. Reporting those arithmetic means alone would hide the switch from zero readings to normal fan speeds. The table therefore describes the observed sequence.

Using the last eight minutes yields CPU package averages of 57.75 W, 38.77 W, 53.84 W, and 52.54 W in the same row order. That removes some startup influence but retains the fan and workload differences. The package-power improvement in 0.1.97 is about 3.0% against stock run 2 and 9.3% against stock run 1. These are observed capture differences, not a controlled causal estimate.

The PotPlayer screenshot was taken after its CSV interval. It showed full 3840 × 2160 output, 29.97 fps input, approximately 59.92 fps output, a Direct3D 11 NVIDIA renderer, zero drops at a 20 s spot check, and about 1 ms reported jitter. It supports the renderer/rate identification at that moment. It does not supply ten minutes of synchronized presentation/power measurements.

The 0.1.97 CSV additionally recorded about 3.44 W on the discrete GPU with near-zero core activity. Earlier CSVs lacked that NVIDIA reading. CPU package and Intel GPU watts overlap in domain coverage, and neither their sum nor the incomplete NVIDIA readings produce wall power.

### Additional 34-second capture

`sky wing balanced 2x.CSV` covers 01:42:11.037–01:42:45.051 on 19 November, before the ten-minute 0.1.97 capture. It contains 18 samples over 34.014 s. Its version and playback state have not been independently established.

| Metric | Whole short capture |
| --- | --- |
| CPU package power | 16.38 W average, 9.83–26.70 W range |
| Total CPU usage | 2.91% average |
| CPU package temperature, first / second sensor | 41.67 / 42.44°C average |
| Intel GPU power | 0.044 W average |
| Discrete GPU power | 3.11 W average |
| Intel GPU media engine and both video-decode counters | Zero throughout |
| CPU / GPU / PCH fan sensors | Zero throughout |

These low GPU readings are compatible with an idle, paused, or otherwise lightly loaded interval. The CSV cannot select between those explanations. It does not establish active full-resolution interpolation at 59.94 fps, so its low power is excluded from the sustained smoothing comparison. No eight-minute comparison window can be calculated for it.

### Source verification and calculation method

All captures were read using their quoted CSV fields. Repeated headers and the final sensor-description/footer records were excluded from data rows. Date/time rows were checked for consistent column count. Each interval is approximately two seconds, with observed gaps ranging from 1.979 to 2.022 s across the supplied logs. Averages are arithmetic sample means. Capture duration is the timestamp difference between the first and last samples. The last-eight-minute window includes samples at or after the final timestamp minus 480 s.

The first three files have 601 columns. The two later files have 695, including the NVIDIA GPU power sensors. Metrics were matched by header name in each file. Both `CPU Package [°C]` columns were retained as separate sensors. Missing NVIDIA power is left unavailable, and zero fan readings are retained as recorded. Neither pause segments nor other workloads were removed without a synchronized playback trace.

| Capture file | Samples | Duration | Recorded start–end on 19 November |
| --- | --- | --- | --- |
| `hybrid skywing 2x balannced.CSV` | 327 | 652.171 s | 00:31:07.606–00:41:59.777 |
| `pot player hybrid.CSV` | 323 | 644.004 s | 00:44:07.779–00:54:51.783 |
| `2nd sky wing ba;lanced 2x.CSV` | 305 | 608.455 s | 00:55:39.769–01:05:48.224 |
| `sky wing balanced 2x.CSV` | 18 | 34.014 s | 01:42:11.037–01:42:45.051 |
| `sky wing balanced.CSV` | 321 | 640.380 s | 01:43:55.049–01:54:35.429 |

Times are the timestamps written in the CSVs. No timezone conversion or alignment to the playback restart was assumed. The 34-second capture ends 69.998 s before the longer frame-sharing capture starts. The originals confirm that these are separate intervals.

## 7. Renderer, decode, and presentation investigations

### Presentation failure and opt-in workaround

Dedicated-mode output drops reproduced with interpolation replaced by duplicate source frames, in small foreground windows, and with multiple decoders/renderers. Null-output motion had zero drops. This located a substantial problem in presentation/scheduling under those conditions.

Short probes recorded 465 drops on default `gpu-next`, 368 with repeated source frames, 460 in a small foreground window, 330 on classic D3D11 with vsync waiting disabled, 142 with flip disabled, and four with both flip and vsync wait disabled. An Intel renderer diagnostic had zero. Decoder drops were zero.

The opt-in compatibility mode `SKYWING_D3D11_COMPAT=1` uses `vo=gpu`, `d3d11-flip=no`, and `d3d11-sync-interval=0`. Native follow-ups had zero output/decoder/delayed drops over 77.42 s fullscreen and 26.19 s windowed. The full app sustained 59.96 processing fps over 87.8 s, using 1.97 cores and 36.68 W RTX power. That is a measured power tradeoff. Tearing and perceived cadence still needed visual review. The later driver reduced the original standard-mode failure, so this workaround was retained as opt-in.

### Decoder comparisons

- In Hybrid with NVIDIA rendering, moving decode from Intel D3D11VA to NVIDIA NVDEC changed process CPU from approximately 1.80 to 1.81 cores and raised RTX power from approximately 18.4 to 20.2 W. All four approximately 50 s runs had zero video/decoder/delayed drops. No total-laptop saving was demonstrated.
- Sending decode, interpolation, and rendering to Intel in Hybrid held 59.93 callbacks/s for 50.06 s at 1.94 cores, with zero drops and about 3.45 W on the idle RTX. That established feasibility and motivated active-display routing.
- A short standalone software-decode screen initially suggested 4–5 W less RTX power. In the actual Hybrid player it used 19.61 W against 18.83 W for hardware decode, at similar throughput. In dedicated mode it used 32.77 W against 33.38 W across approximately 89 s, a small 0.61 W difference. The broad improvement did not repeat, so the decoder default stayed.
- Allowing software decode required a deliberate experimental capability bypass. That experimental allowance was removed afterward.

### Embedding and fullscreen window comparisons

One initial standalone `gpu-next` sample used 23.87 W against 30.61 W for embedded SkyWing and 30.89 W for a minimal Win32 parent. This suggested embedding as a candidate cause. Later better-controlled measurements did not reproduce a large embedding penalty.

A same-process experiment with a separate fullscreen mpv window used 32.32–32.34 W against 30.14 W for the existing embedded window, with zero drops in the valid samples. The separate window had no SkyWing controls. Planned embedded repeats crashed in Skiko during the diagnostic build's transition, leaving only one valid embedded sample. No efficiency reason to adopt a new video-window architecture was established.

After obtaining an administrator-started PresentMon trace, foreground fullscreen comparisons produced the following.

| Matched NVIDIA sample | RTX power | Filter output FPS | Display interval median / p99 | Undisplayed video presents |
| --- | --- | --- | --- | --- |
| Standalone defaults | 33.48 W | 59.72 | 16.67 / 20.88 ms | 0 / 3064 |
| Installed SkyWing | 33.51 W | 59.96 | 16.67 / 20.88 ms | 0 / 2818 |
| Standalone earlier explicit rendering options | 31.49 W | 59.86 | 16.67 / 21.31 ms | 0 / 2767 |
| Standalone defaults repeat | 33.54 W | 60.00 | 16.67 / 25.01 ms | 0 / 2407 |

All video presents were Composed Flip. Default standalone power bracketed SkyWing within 0.03 W. The earlier 23.87 W result did not repeat. The explicit rendering options saved about 2 W in this comparison but changed scaling/dithering/output settings, with no completed picture-quality acceptance.

Earlier traces with background or mismatched window states were excluded from this conclusion. PresentMon initially failed for lack of Windows trace permissions, and later captures required a separately started administrator trace. The older NVIDIA trace does not establish current Hybrid presentation behavior or VRR activation.

## 8. Quality and search-efficiency experiments

### Half-size analysis proposal

Source inspection and real 4K runtime tests checked the requested half-size `Super`/`Analyse` plus full-size `SmoothFps` design against the pinned implementation.

| Test | Result |
| --- | --- |
| Half-size vectors, full-size main clip and `src` | Rejected because source/vector dimensions differ |
| Half-size main clip and vectors, full-size `src` | Runs but outputs 1920 × 1080 |

The ordinary pinned path takes geometry from its main clip and does not use `src` as the requested vector-scaling/full-size-output mechanism. The proposed four-step analysis-size UI was therefore not implemented. The five presets remain. No valid Balanced-half-analysis versus Better-full-analysis still comparison exists for that proposed path.

A separate NVIDIA Optical Flow path with scaled analysis did run at full output size. In a short 74.93 fps screen it used 30.12 W and 2.90 cores against Balanced's 22.17 W and 2.03. It was not selected. Whole-video downscaling/upscaling was deferred by user choice.

### Initial unpaced quality timing

Generated 12 s 1080p25 clip at a 100 fps target, including startup.

| Preset | Initial elapsed time |
| --- | --- |
| Fastest | 3.48 s |
| Fast | 3.22 s |
| Balanced | 3.33 s |
| Better | 4.48 s |
| Best before overlap fix | 12.13 s |

Revised Best later took 21.98 s. These measurements describe different Best parameters and should not be combined. The close lower-preset times on this small sample motivated the 4K investigation. They do not negate the later real-time CPU differences.

### Short 4K screening matrix

NVIDIA 621.14, same 4K/29.97 SMB segment, about 17 s after warm-up, audio-disabled native host. Each row retained full 4K output.

| Variant | Target FPS | Processing FPS | RTX W | CPU cores | Steady drops |
| --- | --- | --- | --- | --- | --- |
| Fast, auto/8 | 74.93 | 74.87 | 21.79 | 1.26 | 0 |
| Fast, 4/4 | 74.93 | 74.86 | 21.84 | 1.39 | 0 |
| Fastest, auto/8 | 74.93 | 75.03 | 20.97 | 1.19 | 0 |
| Fastest, 4/4 | 74.93 | 75.02 | 21.03 | 1.17 | 0 |
| Balanced | 74.93 | 74.91 | 22.17 | 2.03 | 0 |
| Balanced, whole-pixel search | 74.93 | 74.83 | 21.28 | 1.68 | 0 |
| Balanced, whole-pixel and 32 px blocks | 74.93 | 75.03 | 21.86 | 1.36 | 0 |
| Better | 74.93 | 74.66 | 33.34 | 2.94 | 0 |
| NVOF scaled analysis | 74.93 | 75.24 | 30.12 | 2.90 | 0 |
| Fast, auto/8 | 119.88 | 119.79 | 28.09 | 1.61 | 0 |
| Fast, auto/4 | 119.88 | 119.80 | 27.97 | 1.56 | 0 |
| Fastest, auto/8 | 119.88 | 119.65 | 27.80 | 1.63 | 0 |
| Fastest, auto/4 | 119.88 | 119.67 | 27.20 | 1.54 | 0 |
| Balanced, lean GPU search | 74.93 | 74.72 | 24.93 | 2.38 | 0 |
| Fast, lean GPU search | 119.88 | 119.83 | 33.39 | 1.28 | 0 |
| Fastest, lean GPU search | 119.88 | 119.75 | 32.49 | 1.24 | 0 |

The GPU-search alternatives moved work between processors and changed algorithm settings. Fast/Fastest lean variants reduced CPU but added about 4.7–5.3 W RTX power. The whole-pixel Balanced variants changed vectors and lacked visual acceptance. Queue changes saved little in this screen. Some variants had startup drops excluded from the steady column.

On a generated 4K/25 fixture at 62.5 target, RTX averages were 19.46 W Fastest, 20.53 W Fast, 20.38 W Balanced, and 35.20 W Better. Copy-back playback with smoothing off used 15.57 W. All measured intervals had zero drops. These are synthetic short screens and were not substituted for the requested real 4K/25 network test.

On Intel, an existing Fast graph used 1.39 cores against a later-section Balanced baseline of 2.13, both at the 59.94 target with zero drops in that standalone sample. An actual Fast preview used 1.494 cores against an earlier Balanced preview's 2.312. Fast's larger blocks and whole-pixel estimation are a quality tradeoff. Equal visual quality to Balanced or PotPlayer was not demonstrated.

### Profiling the unchanged exact-analysis path

Intel pipeline controls used approximately 0.03 cores for native direct decode, 0.10 for source-cadence copy-back, 0.69 for doubled repeated frames through VapourSynth, 1.85 for analysis while repeating pictures, and 2.20 for full Balanced interpolation. All samples had zero measured video and decoder drops. Graph scheduling differs in the controls, so the differences locate likely costs rather than isolating pure functions.

The pinned exact analysis is CPU-heavy. Full-resolution search used about 71–72% of measured `Analyse` thread cycles, the next level about 15%, and the next about 7.5%. `Super` was approximately 14–15% of combined `Super` plus `Analyse` cycles. Sampled block-search phases found costs in exhaustive search, luma/chroma scoring, block setup, predictor checks, refinement, and output bookkeeping. Contrast accounted for about 10–11% of the sampled block iteration in its dedicated profile. Cycle scopes and timestamp counters have sampling/overhead limits and are not calibrated whole-player wall-time shares.

Three isolated candidates preserved the complete vector-plane SHA-256 hashes for 128 consecutive source frames in each of two source sections, 256 matches per candidate.

| Exact-vector candidate | CPU result | Decision |
| --- | --- | --- |
| Cache repeated chroma score | Baselines 2.21/2.21, candidates 2.22/2.18 cores | ~0.5% average difference, within run variation, kept experimental |
| AVX2 contrast broadcast | Baselines 2.22/2.21, candidates 2.21/2.17 | ~1.1% average difference, within run variation, kept experimental |
| Pack and score U/V together | Opening average 2.185 → 2.15, later 2.13 baseline → 2.17 candidate | Effect reversed, kept experimental |

These candidates retained exact vectors for the checked samples but failed to establish reliable CPU savings. None was shipped as an efficiency improvement.

## 9. Frame-sharing implementation and verification

The pinned mpv bridge copied incoming decoded frames into VapourSynth and copied every filtered output back into a new mpv frame. The optimization removes the second copy. The input copy, decoder copy-back, and renderer upload still exist.

Each output plane is wrapped as a read-only FFmpeg buffer that holds its own VapourSynth frame reference. The frame stays alive through downstream use. Buffer bounds, attributes, timestamps, and copy-on-write behavior are retained. Allocation/unsupported-path failure falls back to copying. Queued frames are released before script teardown.

The custom mpv retains the pinned mpv revision and R73 headers, but uses a different compiler/dependency build from the old downloaded DLL. Recorded laboratory builds used GCC 16.2, FFmpeg 9.0.2, and libplacebo 7.387.0. The packaged manifest records actual DLL hashes and MSYS2 package versions. Whole-package comparisons between 0.1.96 and 0.1.97 therefore also include dependency/build differences. Same-DLL on/off comparisons isolate the sharing switch more closely.

### Matched CPU results

| Intel Balanced 2× series | Copy runs | Sharing runs | Mean reduction |
| --- | --- | --- | --- |
| First 27–28 s samples | 2.28 / 2.24 cores | 2.01 / 2.03 | 2.26 → 2.02, 10.6% |
| Hybrid follow-up, ~32 s samples | 2.13 / 2.27 | 2.00 / 1.92 | 2.20 → 1.96, 10.9% |

All eight samples retained 3840 × 2160, processed around 59.5–59.9 fps against the 59.94 target, and recorded zero video, decoder, and delayed-frame drops. All measured focus samples were foreground. The test did not capture matching CPU package power, wall power, or sustained thermals.

On the sharing path, automatic concurrency used 2.00/1.98 cores and twelve requests used 2.01/1.99, with zero drops in all approximately 27 s intervals. This gave no reason to change automatic concurrency.

### Correctness and stress tests

- SHA-256 output hashes, byte sizes, and muxed timestamps matched all 48 checked frames for each of five presets, giving 240 paired frame comparisons. These untimed checks used the same 4K sample. They do not certify every input format or driver.
- A completed Intel stress test issued 52 seeks, toggled fullscreen off/on, reloaded the file once, closed all 55 observed graphs, and exited with no filter errors or framebuffer-lifetime warnings. The final heartbeat age was 269 ms.
- Its mixed seek-heavy throughput was 44.23 fps, which is not a steady-playback speed result. Seek-driven counter resets also limit interpreting an aggregate drop delta for that stress run.
- An earlier sharing run timed out during filter teardown shortly after fullscreen/seek activity. Its root cause remains unconfirmed. Later short replays in both modes exposed a separate harness bug that sought during reload. Waiting for FILE_LOADED corrected that harness bug, and the completed stress test passed. The earlier timeout remains recorded as unresolved evidence.
- The ordinary per-frame Python heartbeat was also bypassed experimentally. CPU averaged 2.13 versus stock 2.17 cores, but candidate runs were 2.20 and 2.06, straddling stock. The gain was too inconsistent to remove reporting.
- Removing final `AssumeFPS` preserved sampled pixels but changed duration from exact `1001/60000` to `166833/10000000`. Exact normalization was retained.

Set `SKYWING_VS_SHARE_FRAMES=0` before launching the custom build to use the original output copy for a controlled comparison. The mode is read at filter creation. Changing it after a player process has started is not a valid A/B method.

## 10. Latest Hybrid restart and local watchdog fix

The 0.1.97 playback report showed two graph generations and one automatic recovery attempt. Its retained incident occurred 401,604 ms into playback. At that incident it had 59.462 processing fps, 23,947 callbacks, zero reported video/decoder/delayed drops, the motion filter present, and a retained heartbeat age of 1,040 ms. No VapourSynth warnings/errors were recorded.

The final report showed 58.409 instantaneous processing fps, 57 total video drops, zero in the final minute, zero decoder/delayed drops, a fresh 465 ms heartbeat, and no current failure. The final lifetime counter cannot locate all 57 drops relative to the restart.

Source inspection found that the old stall calculation could ignore `lastHeartbeat` when the current status-file read returned no heartbeat. Once the graph was more than 15 s old, one missed read could then trip the watchdog immediately. The recent retained heartbeat and source mechanism support this diagnosis. The report did not capture the failed file read itself, so the exact runtime trigger remains inferred.

Commit `ed072bf` uses the most recent valid heartbeat of the active graph and retains the 15 s threshold. A deterministic regression checks a missed read after 400 s, a heartbeat only 1.04 s old, then a genuinely stale heartbeat and bounded recovery. Recorded Windows suite and targeted native motion tests passed locally. There is no verified ten-minute laptop run or published installer containing this fix yet.

## 11. Verification record and known failures

| Verification | Recorded outcome | Boundary |
| --- | --- | --- |
| Early Windows suite | 52 tests passed after initial runtime/renderer changes | Historical revision |
| Preset/fullscreen suites | 53, then 55/56 tests as features grew | Counts vary with revision |
| Hosted builds 0.1.95 and 0.1.97 | Windows tests, native engine, EXE/MSI, legal/native payload checks passed | Does not validate the physical laptop's HDR/VRR/thermals |
| Native seek test | 60 seeks, all presets, pause, fallback, property restoration, Unicode, close | Synthetic source and selected runtime |
| Latest watchdog regression | Local suite and targeted native test passed | Long-run Hybrid acceptance pending |
| Local cursor tests | Failures included cursor staying hidden after focus loss and cursor failing to hide with controls | Reproduced with both stock and custom DLLs |
| Diagnostic fullscreen architecture build | Skiko crashes during some transitions | Separate diagnostic build, not proof the installed build crashes |
| Installed fullscreen cycles | Eight F/Escape cycles preserved window and fresh motion heartbeat | Short actual-machine check |
| Real network ten-minute test | Zero minute-by-minute drops and no restarts | Earlier NVIDIA runtime at native 29.97 × 2.5 |
| Frame-sharing stress | Completed 52 seeks/reload/fullscreen sequence | Earlier unexplained teardown timeout remains open |
| Missing engine/plugin | Fallback/property-restoration tests passed | Broader share disconnect and GPU/device-loss tests remain |

The first custom CI build failed because Lua was missing and SkyWing's thumbnail code set the `ytdl` option. `2bfc524` enabled Lua and included LuaJIT. Corrected hosted builds passed thumbnail and fullscreen tests. A green later CI run does not erase a reproducible local cursor failure.

Native/legal checks preserve hashes, pinned source records, runtime inventories, and bundled notices. The broader corresponding-source and third-party notice audit remains incomplete, especially for all native transitive dependencies, Skia, VapourSynth, and runtime source availability. The custom build records package versions and available notices but does not close that audit.

## 12. Current brief status and remaining project work

The original brief is background specification. Later explicit user instructions replaced its FPS range/default, engine controls, optional automatic lowering, and order of work. The requested four-step analysis-size redesign was conditional on proving full-size output, and that prerequisite failed. The five quality steps therefore remain.

| Area | Implemented or established | Remaining review |
| --- | --- | --- |
| SDR optical-flow smoothing | Portable runtime, full-size output, fallback, exact targets | Wider media/codec/driver acceptance |
| FPS/quality controls | Four stops, five presets, release-to-apply, per-type multiplier/quality | Position continuity, all six classes, overload/Try behavior on laptop |
| Hybrid adapter routing | Uses display-driving Intel/NVIDIA when detected, same vendor for motion | External monitors, moving between adapters, monitor changes during playback |
| Efficiency | Repeatable ~11% process CPU saving from output sharing | Controlled package/wall-power and temperature comparison, PotPlayer parity |
| Recovery | One restart and retained incident metrics | Ten-minute watchdog-fixed acceptance and unexplained native teardown timeout |
| Fullscreen | Transparent owned controls preserve surface/handle | Cursor/focus, Alt-Tab, DPI, multi-monitor, mouse navigation |
| VRR | Capability query/manual override, audio-sync behavior | Actual driver/display VRR activation and Hybrid detection |
| 10-bit HDR and Dolby Vision smoothing | Protected bypass remains in current capability logic | 10-bit output/metadata, real HDR10/DV files, Windows HDR on/off |
| Artwork | Existing cover/portrait/frame/collage foundation | Full scene/poster/review and section rules from brief |
| Library/player parity | Existing folder/player foundation | Audit and implement missing section 4 details |
| Installer | EXE/MSI builds and upgrade identity preserved | In-place acceptance, optional motion-engine component, cleanup/source audit |

### Artwork and library requirements still to review

- Preserve item identity and manual cover priority across scans, hidden items, offline drives, and reappearance.
- Complete scene posters, per-section movie/portrait switches, online consent, cleaned-name-only queries, encrypted keys, matching/review, resumable work, and retained artwork.
- Confirm cover crop/rotate/flip/collage, precise custom frame selection, animated covers, card shape, and grid-size parity.
- Verify Original/Virtual/Custom folder grouping edge cases, names before a hyphen, comma names, fallback names, renames, merge behavior, hidden/manual placements, and rescans.
- Complete per-folder sorting, drag reorder with persisted order, hidden-items restore, and compatible versioned Android/PC backup with selectable data and protected optional keys.
- Review settings hierarchy, named audio/subtitle menus, speed/aspect/zoom, queue, Continue Watching actions, central play/pause, accumulated ±10 s seeking and seek-bar release behavior.
- Preserve alternate share addresses and implement optional user-triggered drive discovery if still requested. Built-in WireGuard, phone UI, and download/offline queue were excluded from PC scope.

These items have not received a fresh comprehensive implementation audit in this efficiency report. Existing older progress documents identify them as unfinished. They should be reviewed against the present code before deciding individual fixes.

### Additional current review backlog

An untracked action plan in the checkout describes potential UI-thread blocking by synchronized native work, final watch-position persistence racing close, stale delayed resume for a reused file path, an active motion filter surviving a display with no allowed rate, and Mouse Back events over fullscreen overlays. It also lists accessibility, narrow layouts, settings/navigation, readability, feedback states, and session restoration. These are review findings and planned regression tasks. They are not confirmed test passes or completed fixes in this report.

The current uncommitted Java accessibility module and chrome tracing/test diagnostics belong to that ongoing work. They are outside the published `2bfc524` runtime and outside this report's verified efficiency measurements.

## 13. Manual review worksheet

The user chose Hybrid-first work. Keep that order until Hybrid runtime identity, power, and reliability have been checked. Tick the following only after reviewing the evidence or performing the stated check.

### Build and repository

- [ ] Confirm branch and local diff before each test. Record the source commit and any uncommitted changes.
- [ ] Confirm whether the installer includes `MPV-PREVIEW-MANIFEST.json` and uses frame sharing. Do not choose by version number alone.
- [ ] Confirm the tested build contains `ed072bf` before expecting the watchdog-read fix.
- [ ] Preserve the existing MSI upgrade identity, settings/library data, and runtime provenance when packaging.
- [ ] Audit the uncommitted fullscreen/accessibility changes separately.

### Hybrid reliability and efficiency

- [ ] Use the same local clip, starting scene, fullscreen state, 59.94 target, Balanced quality, display brightness/refresh, audio, AC state, and laptop power/cooling mode.
- [ ] Record Intel and NVIDIA routing from the player and counters. Hybrid firmware mode alone cannot identify the active processing device.
- [ ] Run the watchdog-fixed build for at least ten uninterrupted minutes after warm-up. Save the report and sensor capture with matching timestamps.
- [ ] Record drops by minute, heartbeat/restart count, graph generations, and any seek/pause/cache event. Investigate every recovery attempt.
- [ ] Confirm whether the CSV's zero fan values reflect physical stopped fans or unavailable polling. Note any laptop mode or logger changes.
- [ ] Compare sharing on/off in the same custom build using separate launches. Repeat in alternating order and let conditions settle.
- [ ] Collect CPU package power, NVIDIA board power where available, temperatures, fan RPM, and battery or wall energy when possible. Treat overlapping power domains carefully.
- [ ] Measure presentation cadence over the same interval, including low-percentile frame pacing, while confirming foreground fullscreen.

### PotPlayer comparison

- [ ] Archive the exact AviSynth script, `Prefetch(1)`, MVTools/AviSynth versions, decoder, renderer adapter, scaling/output settings, and enabled filters.
- [ ] Verify 4K 2× output throughout the capture. Save beginning/end panel data and preferably a synchronized presentation trace.
- [ ] Distinguish a practical default-Hybrid comparison from a same-adapter comparison. Record the GPU path in both.
- [ ] Compare the same motion sequence for judder, duplicate frames, ghosting, edge breakup, occlusions, scene cuts, and subtitles.
- [ ] Avoid declaring quality parity from matching targets alone. Retain short motion examples only with appropriate media/privacy handling.
- [ ] Repeat enough matched runs to separate the observed few-watt variation from an app change.

### Playback and project acceptance

- [ ] Exercise 50+ seeks, pause/resume, next/previous, Stop, reload, and close with motion active.
- [ ] Check fullscreen overlays, 2.5 s cursor hiding, focus loss, Alt-Tab, mouse movement/clicks, F/Esc, and preserved video size/HWND.
- [ ] Verify audio sync and ordinary playback after overload, plugin failure, and share interruption.
- [ ] Confirm the existing Fast/Better/Best tradeoffs visually and test remembered settings for independent video types.
- [ ] Keep HDR/Dolby Vision smoothing claims pending real 10-bit and metadata/color validation.
- [ ] Review artwork and library/player parity against the latest user requirements after the motion/Hybrid work.

## 14. Reproduction commands and evidence index

The following are review/reference commands. They were not executed as new playback tests during compilation of this report. Run them from the actual repository checkout, not the separate investigation folder.

```powershell
git status -sb
git log -8 --oneline
.\gradlew.bat -p windows test --no-daemon
```

For native tests, set `SKYWING_TEST_MPV=true` and `SKYWING_TEST_MPV_DIR` to a directory containing the intended `libmpv-2.dll`, its dependencies, and the portable `motion` payload, as documented in `windows/FULLSCREEN-REGRESSION.md`. Test reports should be checked for skips as well as failures. Packaging changes require EXE/MSI verification.

```powershell
$env:SKYWING_TEST_MPV = 'true'
$env:SKYWING_TEST_MPV_DIR = '<directory of the intended packaged native runtime>'
.\gradlew.bat -p windows test --tests '*MotionNativeTest' --no-daemon
.\gradlew.bat -p windows packageExe packageMsi --no-daemon
```

Core implementation files for manual source review are under `windows/src/main/kotlin/com/skywing/desktop/` in the checkout.

| File | Review focus |
| --- | --- |
| `playback/MotionEngine.kt` | Native session ownership, generated script, heartbeat, fallback, watchdog |
| `playback/EmbeddedMpv.kt` | Adapter selection, playback orchestration, report, recovery/drop response |
| `playback/MotionSettings.kt` | Exact multiplier list, presets, preference classes and manual overrides |
| `playback/Smoothing.kt` | Capability gates, source/display eligibility and HDR bypass |
| `playback/WindowsDisplayAdapter.kt`, `DisplayVrr` implementation | Output-device routing and VRR capability |
| `PlayerChrome.kt`, `FullscreenChrome.kt`, `BorderlessFullscreen.kt`, `Main.kt` | Owned controls, cursor/focus, fullscreen and UI/native sequencing |
| `windows/tools/mpv-vapoursynth-output-preview.patch` | Plane references, read-only FFmpeg buffers, ownership and teardown |
| `windows/tools/build_mpv_preview.sh`, `stage_mpv_preview.py` | Source hashes, compiler dependencies, DLL closure, manifest/notices |
| `windows/tools/build_motion.py`, `open-svpflow-windows.patch` | Rust flags, process-lifetime GPU/runtime patch, portable packaging |
| `.github/workflows/skywing-windows-x64.yml` | Stock/custom build selection, tests, packaging and payload verification |

### Existing investigation reports

All links below point to reports in the same investigation output folder. Their dates and test revisions matter.

- [Initial speed, six-minute files, and scheduling](SkyWing-first-speed-test.md).
- [Exact-file Intel/NVIDIA renderer reproduction](SkyWing-4K-renderer-reproduction.md).
- [Half-analysis source/API investigation](SkyWing-analysis-size-investigation.md).
- [Ten-minute fullscreen network run and minute table](SkyWing-ten-minute-fullscreen-test.md).
- [Initial preset/GPU-search/NVOF efficiency matrix](SkyWing-motion-efficiency.md).
- [Initial PotPlayer actual-player comparison](PotPlayer-SkyWing-4K-comparison.md).
- [iGPU-mode early routing investigation](PotPlayer-SkyWing-iGPU-mode-comparison.md).
- [Dedicated-mode failure comparison](PotPlayer-SkyWing-dGPU-mode-comparison.md).
- [Driver 621.24 PotPlayer comparison](PotPlayer-SkyWing-621.24-comparison.md).
- [Hybrid decoder routing comparison](SkyWing-Hybrid-decode-comparison.md).
- [Dedicated-mode presentation workaround](SkyWing-dGPU-presentation-investigation.md).
- [Software/hardware decoder efficiency trials](SkyWing-decode-efficiency-investigation.md).
- [Embedded and minimal Win32 presentation screen](SkyWing-embedded-renderer-comparison.md).
- [Same-process separate fullscreen window trial](SkyWing-fullscreen-window-ab.md).
- [Presentation traces, focus control and fullscreen qualification](SkyWing-performance-qualification.md).
- [Cumulative power, profiling, exact-vector experiments, frame sharing and Hybrid follow-up](SkyWing-power-parity.md).
- [Historical build-brief progress and remaining items](SkyWing-build-brief-progress.md).

### Raw evidence retained in the workspace

The paths below are relative to the investigation folder `we-were-working-skywing-pc-port`.

| Evidence | Local path |
| --- | --- |
| Ten-minute samples and minute reports | `work/fullscreen-10min-retry/` |
| Native scheduling/decode/render A/B summaries | `work/hybrid-decode/*.summary.json` |
| Actual-player CPU and NVIDIA power samples | `work/player-efficiency/` |
| Hybrid/iGPU actual-player settings and measurements | `work/hybrid-player-170425/`, `170750/`, `171152/`, `004922/` |
| Quality/GPU-search screen | `work/motion-efficiency/` |
| Exact search/scoring/contrast profiles and vector hashes | `work/hybrid-decode/exact-*` |
| First output-sharing A/B | `work/hybrid-decode/transfer-timing-*` |
| Sharing seek/fullscreen/reload stress | `work/hybrid-decode/transfer-transitions*`, `transfer-repro-*` |
| All-five-preset output hashes | `work/mpv-copy-prototype/framehash-<Quality>-0.txt` and `-1.txt` |
| Latest Hybrid sharing A/B and queue screen | `work/hybrid-decode/hybrid-nov19-*` |
| Windows presentation traces | `work/skywing-presentation-trace-3263-26277.csv`, `skywing-presentation-trace-5163-23161.csv` |
| Separate video-window experiment | `work/window-bench/`, `skywing-window-bench.patch` |
| Rebuilt staged runtime | `work/mpv-release-check-v2/native-resources/MPV-PREVIEW-MANIFEST.json` |

All five HWiNFO originals were available and recalculated on 20 November. Byte-for-byte archive copies are in the adjacent `SkyWing-review-source-csv-2026-11-20/` folder. The JSON summary records unrounded means, sample counts, minima/maxima, last-eight-minute windows, per-minute CPU package power/usage/fan means for the long captures, and sensor coverage. The following hashes identify the exact supplied files.

| Original CSV | SHA-256 |
| --- | --- |
| `hybrid skywing 2x balannced.CSV` | `cf74d412ab700a07949902c47a1aa41145cb650484ab5e8cda8c9e8fc02ac0ba` |
| `2nd sky wing ba;lanced 2x.CSV` | `356c4a9b30fb18df9623c77c1855030165b856051356c7c8e90264bebf17f384` |
| `pot player hybrid.CSV` | `72734ef307c84365bb849f29bd83a1bb40ee69c56db37dd325ac4e7a53d48cf6` |
| `sky wing balanced.CSV` | `ab376d93f42e46629bf9fab996903827e2fe5eb091b5ca3ac8c68059a86af833` |
| `sky wing balanced 2x.CSV` | `4310be15a86733f9d420c0ecbc520c05726ccf881ee8015d08800a60ada2f17c` |

Raw private media paths, credentials, and keys have been omitted from this report.

