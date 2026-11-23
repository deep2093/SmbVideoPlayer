# Windows motion preview

The Windows player generates intermediate frames with pinned open-svpflow
(deadbad2201d77056173c05c2380758d88612019), portable VapourSynth R73 and Python
3.13.9. CI builds the x64 plugins, preserves x86-64-v3/static CRT flags and checks
native payload hashes, notices and EXE/MSI packaging. Android and the MSI upgrade
identity are unchanged.

## Controls

Motion options contains on/off, four exact source multipliers (2×, 2.5×, 3×, 4×),
five quality steps, status/Try lower, Copy playback report, Copy test report and Done. Off hides the
other controls. Old Motion preferences migrate to on; every retired mode migrates
to off. The blend implementation and fixed-rate/VRR mode buttons are removed.

First use selects the stop closest to 60 fps; 50/60 fps sources use 2×. Sources
above 60 fps bypass smoothing. Stops above the display rate are unavailable; when
mpv cannot report a rate, the fallback limit is 240 Hz. There is no 120 fps cap.
23.976 × 2.5 is exactly 60000/1001; 25 × 2.5 is 125/2. No output downscaling is used.

Any source rate from 1 fps can be smoothed. A standard rate is a whole number of
frames per second or 23.976, 29.97, 47.952 or 59.94 (each within 0.005). Any other
rate mpv reports, such as the average of a variable-frame-rate file or an odd
container rate, is measured to three decimals. The motion engine works at that
rate, and each output frame takes its duration from the source frames it falls
between. mpv times filter output by adding up frame durations, so the output keeps
the source's own frame times, also after a seek, and stays with the audio. mpv
gives each source frame the time to the next frame's, in display order, rounded to
whole microseconds; only the last frame before the end has none and takes the
rate's period. Each duration is snapped back to the exact period of a standard
rate or of the container rate, so a long video does not drift. Video below 1 fps
shows "Can’t smooth video below 1 fps". Until mpv reports a rate, the status is
"Waiting for source frame rate".

A standard rate's frame within mpv's own rounding tolerance of its period
(`MP_PTS_ROUNDING_TOLERANCE`, 3.1 ms: files round times to 1 ms, some muxers by
1 ms more) counts as that exact period, so a constant rate keeps its exact cadence,
also in Matroska, whose times are rounded to the millisecond. A frame further off
keeps its own time and is counted (modernise plan 5.8 rule 10). After 3 such
frames the file is a variable rate whose average is a standard rate, such as a
phone video averaging 30 fps: it plays against the audio instead of with
display-resample, for the rest of the file, and the report's "Frame times:" line
says so. One or two odd frames, such as a frame lost in a recording, keep the
exact cadence. Checked in `MotionNativeTest` with the pinned build, from the start
and after a seek at 2× and 2.5×: MP4 without and with B-frames (B-frames change
nothing, as each frame's time is taken in display order) and Matroska. A
variable-rate Matroska file reports the default duration its muxer wrote, which
ffmpeg takes from the fastest frames (60 fps for frames 17 and 50 ms apart), so its
standard rate can be its fastest rate rather than its average.

Both sliders save on release and the worker applies the latest saved values on
its next poll. A changed filter seeks exactly back to its sampled playback position.
Multiplier and quality are stored independently for six resolution/HDR classes
(≤1080p, ≤1440p, 4K and above × SDR/HDR). Fast is the quality default on every PC
until the user picks a quality for a class; each PC keeps the choices made on it.

Settings → Playback contains G-SYNC/FreeSync and Lower automatically when needed
(default off). VRR uses audio sync; a fixed display with a matching target can use
display-resample for a standard source rate. A measured rate always uses audio sync,
so on a fixed-rate display its frames can judder where they fall between refreshes.
The switch does not verify driver-level VRR activation. Its default follows a
read-only per-display NVIDIA VRR capability query when available; unknown/unsupported
displays default off. Saved manual choices always win, including existing choices.
Hybrid Intel/AMD-owned outputs and missing NVIDIA APIs can report unknown; users
can still enable the switch manually. Settings and reports distinguish unknown
capability from a negative result.
Three consecutive recent drop intervals offer the next lower stop; three clean
intervals clear the warning. Low throughput telemetry alone does not trigger it.
The live status reports video plus decoder drops within the last 60 seconds,
tracking increments across counter resets; missing measurements remain unavailable.
Interlaced video is not smoothed ("Interlaced video isn’t smoothed; it is deinterlaced instead"). SkyWing sets
`deinterlace=auto`, so mpv deinterlaces only frames flagged interlaced, to the field rate (25i plays at 50 fps).
mpv then reports the deinterlaced frame in `video-frame-info`, so SkyWing also reads `deinterlace-active`.
Smoothing waits for the first frame, so it never starts before this is known.

Automatic lowering only happens when enabled; overload stops motion at 2×.
Seeks, pauses, cache waits and reconfiguration restart the warmup period.

## Runtime and diagnostics

Rendering and motion use the GPU that drives the monitor the window is on when
SkyWing first creates its video surface (Intel or NVIDIA, as Windows names the
display adapter). Moving the window later does not change it. When Windows names
neither, NVIDIA is preferred if mpv validates an installed NVIDIA DXGI adapter;
otherwise automatic renderer selection remains available. For test runs only,
`SKYWING_GPU=NVIDIA` or `Intel`, set before SkyWing starts, overrides the choice
for rendering and motion; display queries such as VRR still follow the monitor,
and the report's renderer preference ends in "forced by SKYWING_GPU". The report
separates the preferred motion GPU from mpv’s actual renderer device.

The motion engine runs on that GPU's OpenCL device. When that GPU has none, it runs on another GPU's OpenCL device,
named exactly by vendor and LUID, and the report's "matched by" says "another GPU". Its frames come from system memory
(`hwdec=auto-copy`), so another GPU only adds an upload. A PC with no OpenCL device at all smooths on the processor:
open-svpflow skips every device (its vendor filter names none) and uses its CPU path. Then only 1080p and smaller, at 2×,
is offered; larger video shows "Update your graphics driver: without OpenCL, smoothing works only up to 1080p at 2×",
and the status says "on the processor (update your graphics driver)". Some Intel and AMD drivers install OpenCL only with
the full driver package. Checked in `MotionNativeTest` with the pinned build: on the processor each in-between frame is
a new picture. On the 24-thread laptop, unpaced (mpv untimed, no display), a generated 1080p clip from 24 to 48 fps at
Fast gave 963 output frames per second using 7.9 cores on the processor, and 1021 using 9.2 cores on OpenCL. A real
video, a slower processor or a higher quality gives less.

mpv itself steps down when decoding or drawing fails, and SkyWing reports what it did rather than adding its own
fallbacks. With `hwdec=auto-safe` mpv tries each hardware decoder in turn, and `hwdec-software-fallback` (3 by default)
moves on after decoding errors mid-file, from the same frame. Each step logs "Attempting next decoding method after
failure of …", which SkyWing records. A video that ended on software decoding after a failure is not smoothed, with
"Decoding fell back to software, so this video isn’t smoothed". Without a graphics driver, or under remote desktop,
`d3d11-warp=auto` draws with Microsoft's software renderer (WARP, named "Microsoft Basic Render Driver"). The video still
plays and smoothing is off with "Install your graphics driver for smooth playback". The playback report adds a
"Render path:" line (the graphics API on the hardware, or WARP), a "Graphics drivers:" line with each adapter's driver
version as Device Manager shows it, and a "Decode path:" line (decoder in use, interop, what was asked for and what
failed for this file). The "Renderer:" line is unchanged.

mpv does not step down when its graphics device is lost while a video plays (a driver reset or update, or the GPU
removed or hung). libplacebo then logs "Device lost!" at error level and refuses every frame after it; mpv skips those
frames without a word, so the window would stay black. SkyWing reads that message and, on its next once-a-second
update, re-creates the video output in the same window, as a GPU change does: smoothing stops and starts again on the
new output, and the video goes on from where it was. The first loss within a minute lists the GPUs again and uses a
fresh plan, the second uses Windows' choice of graphics, and the third pauses the video and says the graphics driver
stopped responding. The next video always gets a new output. An output re-created while the driver is still
restarting can open Microsoft's software renderer; it stays on it for the rest of that video, and the next video moves
back to the GPU. The reports add a "Graphics device lost:" line ("never", or how many times and the last step).
`EmbeddedPlaybackNativeTest` checks the recovery by handing the player libplacebo's message; a real loss needs a
graphics driver reset on the test machine.

Native DLLs stay loaded for the process lifetime so Rust worker state survives
VapourSynth core rebuilds. Only the named filter is owned; previous decoder/sync/
interpolation settings and other filters are preserved when it ends.

Concurrent requests are automatic, buffer 8; heartbeat writes occur at most every
0.5 seconds. The script writes its heartbeat into 56 bytes of SkyWing's own memory
through ctypes, under a sequence lock (odd while writing, even when done), and the
player reads it there, with no file in between. A read that catches a write is a
missed read, not a stall, and the last heartbeat is kept. Each smoothing start gets
its own block, freed only after mpv is destroyed, because a removed filter's script
may still write. After 15 s without a new heartbeat while playing, the graph counts as
stalled. One heartbeat-stall restart is allowed per file. Reports retain the
last engine metrics and incident counters, heartbeat age, elapsed time, filter
presence, preceding actions and last 20 VapourSynth warnings/errors. Paths are
redacted. Processing throughput is not measured physical presentation FPS.

The report starts with the build: its version, then a `Build:` line with the commit
(and whether `windows/` had uncommitted changes), the libmpv runtime staged for it
(`stock`, or `frame-sharing` when `MPV-PREVIEW-MANIFEST.json` was staged with libmpv)
and whether it contains `ed072bf`, the watchdog fix that keeps the latest heartbeat
across missed polls. For `frame-sharing` it also says whether sharing is on:
`SKYWING_VS_SHARE_FRAMES` unset or `1` shares the output planes, and any other value
uses the original output copy for A/B tests (set it before starting SkyWing). CI names
the installers after the same facts:
`SkyWing-<version>-<commit>[-modified]-<runtime>-<with|without>-ed072bf`.

With frame sharing, mpv keeps VapourSynth output frames after the script that made
them is freed (each seek frees and rebuilds the script). Checked in the VapourSynth
R73 source:
- A frame holds only a raw pointer to its core, so it does not keep the core alive.
- Its pixel memory does outlive the core. The core's memory tracker deletes itself
  once the core is gone and the last plane is freed.
- The API table mpv keeps is static. mpv and VSScript never unload their libraries.
- SkyWing's script sets only integer frame properties. A function stored as a frame
  property would touch the freed core when released.

R73 has one race. The core's destructor and a frame freed on another thread at the
same moment both check the tracker's counters without a shared lock, so the
tracker can be deleted twice or used after it is deleted.
`mpv-vapoursynth-output-preview.patch` prevents it. Every shared plane also holds a
small node that no one reads from, and frees its frame before that node. The core
is then destroyed only after the last shared frame is gone. Recheck this when
VapourSynth is upgraded.

## Measuring

While smoothing runs, the player worker takes one reading each time it updates smoothing (every 0.5 s) and closes a
row each minute. Each row holds the target and the filter's output rate (from the heartbeat: processing throughput,
not presented frames), and the minute's change in mpv's `frame-drop-count`, `decoder-frame-drop-count`,
`vo-delayed-frame-count` and `mistimed-frame-count`. It also holds the largest `vsync-jitter`,
`estimated-display-fps`, the process CPU in cores, the oldest heartbeat age, and the graph generations, seeks, pauses,
cache waits, fullscreen changes and recoveries in that minute. It ends with HDR engaged, VRR active and the refresh.
All of these properties are in the pinned libmpv's property list.
Rows go to `%LOCALAPPDATA%\SkyWing\playback-logs\playback-<time>.csv`. A new file starts when smoothing starts or
another video plays; a seek or a settings change stays in the same file. The newest 20 are kept. A last part-minute of at least 5 s is written when smoothing stops; closing the app loses it.

- **HDR engaged** is "yes" only when Windows HDR is on for the screen, the source is PQ or HLG (`video-params/gamma`)
  and mpv sends PQ or HLG (`video-target-params/gamma`). A "no" names the part that is not HDR. "unknown" names what
  was not reported.
- **VRR active** is NVIDIA's `bIsVRREnabled` ("VRR Mode is currently active on given display", nvapi.h). Other GPUs
  report nothing, so it reads "not measured". Whether NVIDIA's flag follows SkyWing's fullscreen is still to be
  measured on real screens.

**Copy test report** copies the build, GPU plan, renderer, screen, HDR engaged, VRR active, the VRR control setting,
the smoothing state, the log file's name and the per-minute table, to compare PCs.

**Benchmark mode** is hidden: `SkyWing.exe --bench <file> [--minutes 10] [--quality Balanced] [--stop 2x] [--start 0]
[--out <folder>]`. It opens a window of its own, plays full screen from `--start` with those settings (ignoring the
saved ones and automatic lowering) and measures for `--minutes` once smoothing holds. The video must be at least
`--start` + `--minutes` + 1 minute long. It writes the CSV and `bench-<time>.txt` (the options, the result and the
test report), saves nothing to the settings or library, and exits 0 when smoothing held, 1 when it stopped or the
run ended early, and 2 when the run could not start. Closing its window ends the run early.

`windows/tools/bench.ps1 -Video <file>` runs it (default: this checkout's `createDistributable` image). With
`-PresentMon` it also runs Intel's PresentMon console app (MIT; installed separately, not shipped) on `SkyWing.exe`,
and summarises the last `-Minutes` of the capture: presents, frames not displayed, the displayed-frame interval
(mean, median, 99th percentile, max, intervals over 1.5x the median), present mode and sync interval. PresentMon
needs an administrator PowerShell or the Performance Log Users group; the script never asks for elevation and
reports "not measured" without the rights. Presents are not video frames: with display-resample mpv presents once
per refresh. Reading the present events inside SkyWing would cost more (an ETW consumer, the same rights) for the
same numbers, so SkyWing itself reports on-screen frame times as not measured.

## Validation and remaining work

Native tests cover 60 seeks, all five presets, failure fallback, property/filter
restoration, Unicode, fullscreen handle preservation and renderer reporting.
Policy tests cover fractional rates/defaults/display limits, legacy migration and
six independent settings classes. Panel captures cover the on/off layout.

The supplied 4K SDR 29.97 network file at 119.88 reproduced 398 drops in 65 seconds
on default Intel rendering versus zero on NVIDIA. A 180-second NVIDIA render trial
also had zero drops, including fullscreen. Those probes had audio disabled.
User reports subsequently confirmed steady windowed playback with zero drops;
a fullscreen report retained 43 drops amid seeks/reconfiguration, so steady
fullscreen presentation still needs a clean measurement.

All five presets executed the same generated 12-second 1080p25 sample at target
100 with the initial presets: Fastest 3.48 s, Fast 3.22 s, Balanced 3.33 s, Better 4.48 s, Best 12.13 s.
These single unpaced runs include startup, not sustained presentation or visual
quality ratings. Best subsequently changed to half-block overlap so its refined
vectors also work on the CPU fallback; its timing above is not for that revision.
Preset speed/artefacts depend on the video; Best is expensive.

Fullscreen title and controls now use transparent, non-focus-stealing owned windows.
Showing/hiding them does not resize or recreate the video surface. The same
2.5-second inactivity timer hides the AWT and native video cursors. Input, pause,
fullscreen exit and focus loss restore the pointer without changing the system
cursor visibility counter. Native tests
cover mouse clicks, keyboard shortcuts, auto-hide, stable bounds and exit cleanup.

Next: proper 10-bit HDR/Dolby Vision,
then artwork and library/player parity. HDR motion remains bypassed. Physical HDR
colour/audio-sync/G-SYNC and in-place MSI acceptance remain laptop checks. Optional
installer component selection, full state-duration/presentation measurements and
the broader corresponding-source audit in legal/SOURCE-STATUS.md remain open.
