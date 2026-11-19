---
name: skywing-video-performance
description: Benchmark or improve SkyWing Windows video playback, motion interpolation, HDR, libmpv, VapourSynth/open-svpflow, hardware decoding, GPU selection, frame drops, smoothness, VRR, audio sync and memory use.
---

# Video performance and motion diagnostics

1. Read `windows/MOTION-ENGINE.md` and relevant current playback/renderer code. Preserve existing controls, profile migration, native resource lifetime and fallbacks.
2. Form a measurable hypothesis with an A/B comparison. Keep source video, duration, resolution, codec, color mode, target multiplier, preset, window/fullscreen state and GPU/renderer constant unless one is the variable under test.
3. Distinguish: input FPS, generated intermediate frames, worker throughput, decoded/video drop counters, renderer device, actual presentation cadence, display refresh rate, VRR availability and visible judder/artefacts. Do not label output target FPS as measured on-screen FPS.
4. Run realistic sustained tests after warm-up rather than timing only startup or a short unpaced render. Record test duration, seek count, cache waits and any filter reconfiguration. Make clear when a probe has audio disabled.
5. Measure CPU/GPU load, memory growth, dropped-frame windows, startup cost and recovery after errors. Test local and SMB sources separately; never hide a network bottleneck behind a renderer claim.
6. Preserve fallback and shutdown paths if native functions or GPU adapters are unavailable. Prefer explicit 'unavailable' over invented metrics; do not auto-assert hardware HDR, Dolby Vision, VRR or GPU correctness from CI.
7. For HDR/10-bit/Dolby Vision, demand hardware display verification of colour, tone mapping and metadata handling. Do not remove a protective bypass solely to show a higher FPS.
8. Finish with reproducible commands, raw measured numbers, interpretation, tradeoffs, and any Windows-host or display acceptance still required.
