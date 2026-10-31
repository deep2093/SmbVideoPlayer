# SVPlayer Android: targeted native analysis

Target: SVPlayer 1.8.2 (version code 86), ARM64. Analysis performed 2026-10-31. This report concerns frame interpolation, not the app's licensing or unrelated features. No SVPlayer libraries, recovered scripts, private media or credentials are included in this repository.

## Evidence identity

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| libSVPlayer_arm64-v8a.so | 28756328 | 6ef1080e394ac5ce875ae24895e099c6c9589a26643cd839253b5a8c22cc5b5e |
| libsvpflow1_arm.so | 235472 | c1a5e710c07e048b9a40d13f710cce2c26129646bfc49927b6fe6570c3535710 |
| libsvpflow2_arm.so | 529168 | 687c2f3f2edbab187f84fbf2e2a6008dd9a9b82352e7176246b9b23de5b7f92a |

The main ELF has only .dynstr/.dynamic/.shstrtab section metadata. REA's section-based export inspection reported unsupported. Parsing PT_DYNAMIC instead recovered 4,684 dynamic symbols, including the processing-script generator and profile functions. Capstone disassembly was used for focused call/reference analysis. These are static observations, not recovered original C++ source.

## Recovered processing path

The common Qt resource registration function at VA 0x422dec supplies version-3 resource tables. Its embedded res/base.py describes the sequence:

1. Prepare a Super representation from the 8-bit analysis input.
2. Analyse motion using that representation and the analysis input.
3. Generate the output through SmoothFps, with the source pictures, analysed vectors, output settings, source reference and source frame rate.

The generator independently prepares input_m, input_m8 and input_um. The native generator contains format conversion, cropping, resizing, dithering, source/output rates and plugin-loading paths. Seeing an 8-bit analysis input does not establish a low spatial analysis resolution; it is a pixel-format choice. Exact active resize dimensions have not yet been recovered.

Relevant defined functions in this exact build:

| Function | VA | Bytes |
|---|---|---:|
| Script::generate | 0x397114 | 13840 |
| Script::generate_p | 0x39a724 | 26240 |
| Profile::getScriptOptions | 0x381848 | 4452 |
| Profile::findAutoValues | 0x388edc | 1536 |
| Profile::fillAutoValues | 0x3894e8 | 1024 |
| MpvControl::setScript | 0x33d678 | 6168 |

Script::generate calls generate_p after assembling media, profile, format, GPU and output-rate inputs, then writes a generated script. These roles are supported by call targets and referenced keys. Complete branch conditions and runtime parameter values remain unverified.

## Engine configuration recovered from native helpers

A helper starting at VA 0x3a20a8 builds motion-analysis settings, referencing block width, overlap, vectors, main/coarse search, search type/distance/SATD, bad-match search, refinement and refinement SAD thresholds. It consumes options including mv_grid, mv_radius, mv_wide, mv_coarsewidth, mv_refine and fi_precision.

A helper starting at VA 0x3a3fa8 builds synthesis settings, referencing rate numerator/denominator/absolute rate, algorithm, adaptive controls, mask cover/area, scene limits/mode/blend, and GPU settings. It consumes options including fi_shader, fi_masking, fi_adaptive, fi_mode and fi_scene_changes.

This establishes supported configuration paths. It does NOT establish that every path is enabled for the user's profile or disclose the complete implementation inside the plugins. In particular, a parameter named cover alone does not prove the exact occlusion algorithm or its quality.

The main library depends on mpv and Vulkan; the synthesis plugin also depends on Vulkan. These dependencies establish available integration, not proof of which GPU API was active for a particular playback session.

## Direct runtime inspection

SVPlayer was opened on the same installed tablet and the user-specified video was opened. The inspected profile showed target 60 fps, automatic options enabled, the performance/quality slider around its middle, animation optimization off, and Average artifact masking.

Normal restart restored the available HFR trial session. Live diagnostics reported 59.9401 fps (23.976 multiplied by 5/2), automatic film profile #1, and a resulting frame of 1920x1012 resized from 2048x1080. OpenCL GPU detection succeeded; this alone does not establish the active synthesis API or analysis pyramid dimensions. A temporary Lua diagnostic through mpv.conf failed with a script-read error; its setting and transferred scripts were removed. No active generated configuration was captured. No purchase or license modification was performed. A valid matched-timestamp 60 fps output comparison has not been completed; still screenshots alone cannot establish smoothness or actual output cadence. Parameter values above must not be described as a captured running script.

## Concrete differences from SkyWing preview 1528

| Area | SkyWing | Evidence from SVPlayer |
|---|---|---|
| Motion analysis | Fixed 256-pixel-wide luma, quarter-size predictor scale, four-analysis-pixel vector grid, local SAD search | Generator exposes block overlap, search metrics including SATD, coarse search and refinement settings; exact active dimensions/values still needed |
| Compensation | One filtered field warps both source pictures; reverse field is used primarily for confirmation | Dedicated SmoothFps engine consumes analysed motion and separate synthesis configuration; exact internal field handling needs further analysis |
| Reliability | Brightness disagreement, neighbour spread and whole-frame threshold | Distinct cover/area mask controls and adaptive/scene controls are constructed by the native generator |
| Integration | Media3 SurfaceTexture plus GLES fragment passes | mpv/VapourSynth pipeline plus native plugins and GPU integration |

The current GLES implementation is an approximation and has not reproduced the native SVP engine. More threshold tweaks are not evidence of engine parity. The next implementation should be justified by a measured failure case and these verified processing stages, especially overlapping-object boundaries and repeating-pattern ambiguity. Keep 60 fps only. Do not reapply the preview-1527 source-hold policy that regressed user-visible quality.

## Remaining work

Capture the active generated configuration and comparable output at the same timestamp, resolution and 60 fps target. Continue focused analysis of the analysis/synthesis plugin entry points and the generator's configuration branches. Use controlled occlusion and moving-pattern ground truth to validate a replacement compensation path, rather than treating frame counters or compilation as visual-quality proof. No new renderer revision was installed as part of this analysis pass.

## S26 Ultra runtime comparison — 2026-11-01

SVPlayer 1.8.2 was inspected on the connected SM-S948B / Adreno 840. Its installed base APK contains `assets/libsvpflow2_arm.so`; its SHA-256 matches the artifact above exactly. Read-only ELF inspection confirms direct imports of `vkCreateShaderModule`, `vkCreateComputePipelines`, `vkCmdDispatch`, `vkQueueSubmit`, and fence/barrier operations, and a dependency on `libvulkan.so`. This is evidence of a Vulkan **compute** implementation in the synthesis plugin, separately from mpv's display renderer. It does not prove which backend branch was selected in the observed session. OpenCL device detection alone must not be described as proof of OpenCL synthesis.

The same generated 1920x1080 / 30 fps clip used for SkyWing's live GPU reproduction was opened in SVPlayer. It initially ran at 30 fps. After the user enabled interpolation, SVPlayer logged `playing at 60 [30 *2/1]`. The user subsequently opened the natural problem video; logs reported 1920x1080, 29.970 fps input and `playing at 59.94 [29.97 *2/1]`. These are reported source/result dimensions, not a recovered motion-analysis pyramid size. Exact quality-slider position and generated parameters were not captured.

Two SurfaceFlinger latency snapshots of the SVPlayer video SurfaceView each contained 127 valid presentation timestamps. Each averaged approximately 60 presentations per second with a maximum interval of about 16.667 ms and zero intervals above 25 ms. These short windows establish presentation cadence, not five-minute stability, unique interpolated frames, or artifact-free quality. The natural-video session was still advancing during observation. Private screenshots, package copies, logs and media are retained outside the repository.

The open-source reconstruction's synthesis currently uses OpenCL. A separate independently written Vulkan synthesis backend is therefore a concrete compatibility direction to evaluate; no claim is made that merely changing APIs will reproduce SVP's algorithm or fix every device. Validate a sustained moving-pattern compute test with CPU ground truth before integrating another experimental backend into normal playback. No SVPlayer library or recovered shader is licensed or bundled by this project.
