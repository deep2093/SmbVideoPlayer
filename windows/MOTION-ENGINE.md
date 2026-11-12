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

Both sliders save on release and the worker applies the latest saved values on
its next poll. A changed filter seeks exactly back to its sampled playback position.
Multiplier and quality are stored independently for six resolution/HDR classes
(≤1080p, ≤1440p, 4K and above × SDR/HDR). Balanced is the quality default.

Settings → Playback contains G-SYNC/FreeSync and Lower automatically when needed
(default off). VRR uses audio sync; a fixed display with a matching target can use
display-resample. The switch does not verify driver-level VRR activation.
Three consecutive recent drop intervals offer the next lower stop; three clean
intervals clear the warning. Low throughput telemetry alone does not trigger it.
The live status reports video plus decoder drops within the last 60 seconds,
tracking increments across counter resets; missing measurements remain unavailable.
Automatic lowering only happens when enabled; overload stops motion at 2×.
Seeks, pauses, cache waits and reconfiguration restart the warmup period.

## Runtime and diagnostics

NVIDIA is preferred for motion and for D3D11 rendering when mpv validates a matching
installed DXGI adapter. Otherwise automatic renderer selection remains available.
The report separates the preferred motion GPU from mpv’s actual renderer device.
Native DLLs stay loaded for the process lifetime so Rust worker state survives
VapourSynth core rebuilds. Only the named filter is owned; previous decoder/sync/
interpolation settings and other filters are preserved when it ends.

Concurrent requests are automatic, buffer 8; heartbeat writes occur at most every
0.5 seconds. One heartbeat-stall restart is allowed per file. Reports retain the
last engine metrics and incident counters, heartbeat age, elapsed time, filter
presence, preceding actions and last 20 VapourSynth warnings/errors. Paths are
redacted. Processing throughput is not measured physical presentation FPS.

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
Showing/hiding them does not resize or recreate the video surface. Native tests
cover mouse clicks, keyboard shortcuts, auto-hide, stable bounds and exit cleanup.

Next: proper 10-bit HDR/Dolby Vision,
then artwork and library/player parity. HDR motion remains bypassed. Physical HDR
colour/audio-sync/G-SYNC and in-place MSI acceptance remain laptop checks. Optional
installer component selection, full state-duration/presentation measurements and
the broader corresponding-source audit in legal/SOURCE-STATUS.md remain open.
