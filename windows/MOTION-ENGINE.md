# Windows motion preview

This is the first playable SDR engine milestone from the PC build brief, based on
99f8f84. It is not the completed motion/sliders/HDR/artwork port.

## Included

- Motion (preview) generates intermediate frames with open-svpflow deadbad2201d77056173c05c2380758d88612019 through the pinned libmpv VapourSynth filter.
- Portable VapourSynth R73 and Python 3.13.9; two plugins built from pinned Rust source in Windows CI. Downloads, source patch and payload hashes are recorded. The runtime adds about 32 MB uncompressed.
- Balanced parameters, full source dimensions, and the largest source-rate multiple at or below 120 fps and the active display rate: 23.976 → 119.88, 24 → 120, 25 → 100. No 1080p downscale is introduced.
- NVIDIA is preferred by vendor on hybrid laptops; enumeration order is not trusted. The report labels the preferred device, not a measured guarantee that every frame used the GPU.
- Process-lifetime native DLL references keep Rust worker threads valid across seek-driven filter rebuilds.
- Private local scripts; named filter ownership; restoration of decoder, interpolation and sync settings. Missing files, rejected commands and script errors are remembered per file for the app session. A heartbeat stall gets one filter restart per file; another stall or sustained drops bypass motion.
- Copy playback report includes source/decoder/transfer, current target, filter throughput, drop/late counters with a rolling sample window, and observed graph generations. File paths and share hosts are excluded.
- Automatic concurrent frame requests with buffer 8; status writes at most every 0.5 seconds.
- Failure reports retain the last engine metrics, counters and heartbeat age, filter presence, preceding actions, and the last 20 VapourSynth warnings/errors.
- Existing blend modes remain available until the requested simplified controls replace them. The installer identity and Android sources are unchanged.

## Evidence and limits

The pinned libmpv accepted a real VapourSynth filter. The native plugins loaded and
reported NVIDIA RTX 5070 Ti Laptop GPU. A six-second generated 1080p24 textured
sample produced 716 output frames at 120 fps cadence in 1.869 seconds (~383
processing fps including startup; ~692 after a 120-frame warmup). Interior frames
differed from both source neighbours and a linear blend. This short, direct
VapourSynth benchmark is not presented FPS or sustained 4K/SMB performance.
The complete mpv copy-back pipeline also reached EOF successfully on that sample.
A simpler synthetic pattern caused upstream scene handling to repeat frames;
nominal output cadence alone is not evidence of motion generation.

The pinned mpv's estimated-vf-fps remained at source FPS in the successful probe.
The UI therefore reports a target; the report explicitly labels processing
throughput and leaves presented FPS unmeasured. It never calls display refresh
an actual generated-frame measurement. Scene cuts may legitimately repeat frames.

Tests include exact fractional target selection, missing-runtime and partial-start
rollback, safe script paths, and a real JVM/libmpv/portable-Python lifecycle test:
60 seeks, pause/resume, another filter preserved, failed plugin load with continued
ordinary playback, second load, and close with motion active. Existing native
fullscreen/Unicode/process-exit tests are retained. CI additionally builds EXE/MSI
and checks packaged runtime hashes and legal files.

## Still to do

Four-stop exact multiplier and five-step quality sliders; per-type memory; measured
presentation meter; VRR/automatic-lowering settings; optional installer component selection;
10-bit HDR/Dolby Vision qualification; non-resizing fullscreen overlay; complete
state-time reporting and driver/device-error qualification; sustained 1440p/4K/SMB
acceptance. This preview bundles the engine but leaves it off by default; the
installer does not yet offer a component checkbox. HDR motion remains bypassed.
The existing native-source distribution audit in legal/SOURCE-STATUS.md remains open.

Then implement the brief's scene/movie posters and per-section artwork, followed
by library sorting/reordering/hidden items/shared backup, settings and player parity.
The earlier audit files remain historical baseline evidence, not current completion claims.

## Laptop checklist for this preview

1. Install over the previous preview and confirm the library/settings survive.
2. Play 1080p SDR 23.976/24; select Motion (preview), expect 119.88/120 target.
3. Test 25 fps (100 target), seek repeatedly, pause, next/previous, Stop and close.
4. Try 4K SDR, then ten minutes of SMB playback; copy the report if it falls back.
5. Compare Off and Motion with a moving camera; confirm audio stays in sync.
6. Check F/Esc, Unicode names and alternate share addresses. Fullscreen picture movement when controls show is a known remaining fix.
7. HDR files should play normally with motion bypassed. HDR colour validation and actual G-SYNC activation remain physical checks.
