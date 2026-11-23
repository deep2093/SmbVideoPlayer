# Windows motion preview

The Windows player generates intermediate frames with pinned open-svpflow
(deadbad2201d77056173c05c2380758d88612019), portable VapourSynth R73 and Python
3.13.9. CI builds the x64 plugins, preserves x86-64-v3/static CRT flags and checks
native payload hashes, notices and EXE/MSI packaging. Android and the MSI upgrade
identity are unchanged.

## Controls

Motion options contains on/off, four exact source multipliers (2×, 2.5×, 3×, 4×),
five quality steps, status/Try lower, Copy playback report and Done. Off hides the
other controls. Old Motion preferences migrate to on; every retired mode migrates
to off. The blend implementation and fixed-rate/VRR mode buttons are removed.

First use selects the stop closest to 60 fps; 50/60 fps sources use 2×. Sources
above 60 fps bypass smoothing. Stops above the display rate are unavailable; when
mpv cannot report a rate, the fallback limit is 240 Hz. There is no 120 fps cap.
23.976 × 2.5 is exactly 60000/1001; 25 × 2.5 is 125/2. No output downscaling is used.

Any source rate from 1 fps can be smoothed. A standard rate, a whole number of
frames per second or 23.976, 29.97, 47.952 or 59.94 (each within 0.005), is
retimed to its exact frame period. Any other rate mpv reports, such as the average
of a variable-frame-rate file or an odd container rate, is measured to three
decimals. The motion engine works at that rate, and each output frame then takes
its duration from the source frames it falls between. mpv times filter output by
adding up frame durations, so the output keeps the source's own frame times, also
after a seek, and stays with the audio. mpv rounds source durations to whole
microseconds; each is snapped back to the exact period of a standard rate or of
the container rate, so a long video does not drift. Video below 1 fps shows
"Can’t smooth video below 1 fps". Until mpv reports a rate, the status is
"Waiting for source frame rate".

Limits of the measured path: a Matroska file whose frames carry no durations is
timed at its container rate. A variable-rate MP4 with B-frames has small local
timing errors where its rate changes, without drift. A variable-rate file whose
average is a standard rate (a phone video averaging 30 fps) is still retimed to
that rate (modernise plan 5.8 rule 10).

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
Native DLLs stay loaded for the process lifetime so Rust worker state survives
VapourSynth core rebuilds. Only the named filter is owned; previous decoder/sync/
interpolation settings and other filters are preserved when it ends.

Concurrent requests are automatic, buffer 8; heartbeat writes occur at most every
0.5 seconds. The script writes its heartbeat into 48 bytes of SkyWing's own memory
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
