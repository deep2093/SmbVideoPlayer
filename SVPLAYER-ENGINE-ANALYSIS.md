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

The active generated configuration for one phone session has now been captured below. Comparable output at the same timestamp and resolution, additional source rates, and the tablet's resolved configuration remain unverified. Continue focused analysis of the analysis/synthesis plugin entry points and the generator's configuration branches. Use controlled occlusion and moving-pattern ground truth to validate a replacement compensation path, rather than treating frame counters or compilation as visual-quality proof.

## S26 Ultra runtime comparison — 2026-11-01

SVPlayer 1.8.2 was inspected on the connected SM-S948B / Adreno 840. Its installed base APK contains `assets/libsvpflow2_arm.so`; its SHA-256 matches the artifact above exactly. Read-only ELF inspection confirms direct imports of `vkCreateShaderModule`, `vkCreateComputePipelines`, `vkCmdDispatch`, `vkQueueSubmit`, and fence/barrier operations, and a dependency on `libvulkan.so`. This is evidence of a Vulkan **compute** implementation in the synthesis plugin, separately from mpv's display renderer. It does not prove which backend branch was selected in the observed session. OpenCL device detection alone must not be described as proof of OpenCL synthesis.

The same generated 1920x1080 / 30 fps clip used for SkyWing's live GPU reproduction was opened in SVPlayer. It initially ran at 30 fps. After the user enabled interpolation, SVPlayer logged `playing at 60 [30 *2/1]`. The user subsequently opened the natural problem video; logs reported 1920x1080, 29.970 fps input and `playing at 59.94 [29.97 *2/1]`. These are reported source/result dimensions, not a recovered motion-analysis pyramid size. Exact quality-slider position and generated parameters were not captured.

Two SurfaceFlinger latency snapshots of the SVPlayer video SurfaceView each contained 127 valid presentation timestamps. Each averaged approximately 60 presentations per second with a maximum interval of about 16.667 ms and zero intervals above 25 ms. These short windows establish presentation cadence, not five-minute stability, unique interpolated frames, or artifact-free quality. The natural-video session was still advancing during observation. Private screenshots, package copies, logs and media are retained outside the repository.

The open-source reconstruction's synthesis currently uses OpenCL. A separate independently written Vulkan synthesis backend is therefore a concrete compatibility direction to evaluate; no claim is made that merely changing APIs will reproduce SVP's algorithm or fix every device. Validate a sustained moving-pattern compute test with CPU ground truth before integrating another experimental backend into normal playback. No SVPlayer library or recovered shader is licensed or bundled by this project.

## Captured active phone configuration — 2026-11-01

SVPlayer 1.8.2.323 on the SM-S948B was playing the generated 1920x1080, 30 fps motion clip. The frame-rate UI showed conversion enabled, target 60 fps, automatic options enabled, the quality slider at its maximum, animation optimization off, and Average artifact masking. Runtime logs selected automatic film profile #1.

REA could discover the USB device and its process, but its Frida attach failed because no accessible remote Frida server was available. The installed package is not debuggable. A supported mpv Lua diagnostic provided a narrower successful capture route: read the running `vf` property and copy only the script named by its active VapourSynth filter. The diagnostic loaded from SVPlayer's own external app directory. Its 30-second timer stopped automatically. No root access, APK replacement, or license changes were used.

The mpv editor's **Restart** link was required to save and apply the option. Force-stopping immediately after editing lost the change; the initial script/socket attempts therefore do not establish that mpv lacks those capabilities. The final capture read an enabled `svp` VapourSynth filter with `concurrent-frames=9`, `buffered-frames=3`, and `share-data=3`. It reported `hwdec-current=mediacodec-copy`, 1920x1080 and estimated filter output 60.000600 fps. This estimate is not a measured presentation cadence.

The generated script's SHA-256 is `7abade5ff229e9650b24bc4b85296fd6d7c9684229d1e028f901cb1603878386`; two host copies matched. Raw script, settings screenshots and runtime property captures remain outside this repository. The following values are transcribed configuration facts, not copied engine implementation:

| Stage | Explicit values in the active generated script |
|---|---|
| Scheduling | VapourSynth `core.num_threads=9` |
| Super | `full=false`, `gpu=1`, `pel=1`, `scale.up=0` |
| Main search | `type=2` |
| Coarse search | `distance=-8`, bad-match `range=24`, `sad=2000` |
| Refinement | One entry with `thsad=250` |
| Synthesis | `algo=21`, `api=1`, `gpu_qn=1`, `gpuid=11` |
| Mask | `area=100`, `cover=80` |
| Output rate | Multiplier `num=2`, `den=1` |
| Scene / HDR options | `scene.mode=3`, `hdr.dovi=true` |
| Input preparation | Point conversion to format `0x30080101`, random dithering; no width/height resize in this script |

Unspecified fields, including block width and overlap, use plugin defaults; their effective values have not been recovered. `hdr.dovi=true` is an option, not evidence that the SDR test clip contains Dolby Vision. The script is complete for the captured graph, but it does not expose internal kernels, all plugin defaults, or the actual execution of every branch. The `api=1` selector and registered OpenCL device are more specific evidence than a Vulkan dependency; a direct backend-call trace was not obtained. Do not describe this session as proven Vulkan synthesis.

Separately observed **saved preferences** included:

| Preference | Value |
|---|---|
| `frc.frame.resize` / `.algo` | `-19201080` / `bilinear` |
| `frc.frame.align` / `.valign` | `4` / `2` |
| `frc.frame.autocrop` / `.crop` | `1` / `0` |
| `frc.color.allow_10bit` | `false` |
| `frc.threads` | `0` (automatic; resolved to 9 in the captured script) |
| `frc.prefetch.mpv_add` | `0` |
| `frc.gpu.multiple_queues` | `1` |
| `frc.target.max` / `.tolerance` | `47` / `0.02` |
| `frc.disable_vs` | `true` |
| `main.dev.api` / `.sustained_perf` | `0` / `true` |
| `main.performance.cpu` / `.gpu` / `.corr` | `7730` / `3164` / `1.8` |
| `main.player.hw` / `.hw_decoder` | `true` / `mediacodec-copy` |

The [vendor's advanced-settings reference](https://www.svp-team.com/wiki/SVPlayer_for_Nerds) documents the negative resize value as a bounding rectangle, zero threads as automatic, and automatic quality selection using CPU/GPU scores. `frc.disable_vs=true` only skips VapourSynth when both interpolation and bar lighting are off; it did not disable the active filter above. The [SVPflow parameter reference](https://svp-team.com/wiki/Manual%3ASVPflow) describes `full=false` as reduced Super storage; it must not be equated with half-resolution source frames. Neither documentation page establishes unobserved internal defaults in version 1.8.2.

### Consequences for SkyWing

At commit `f374429`, SkyWing's native stream uses four threads, `pel=2`, algorithm 13, explicit 16-pixel blocks with overlap 2, mask area 30 / cover 80, and half analysis. Its Adreno path remains CPU processing bounded to 1280x720. SVPlayer's captured graph instead combines GPU rendering, `pel=1`, reduced Super storage, algorithm 21, different search/refinement settings, and a nine-frame concurrent mpv/VapourSynth pipeline. Its script does not spatially downscale this 1080p source before the analysis call. Internal analysis pyramid dimensions remain unknown.

These are concrete inputs for an isolated compatibility/performance comparison. Increasing threads alone or pasting proprietary-plugin options into the open reconstruction is not a demonstrated fix: supported options, memory growth, source lookahead and asynchronous queue behavior differ. The independent Vulkan probe remains a separate backend experiment, not evidence that SVPlayer needs Vulkan on this phone. Preserve the working CPU profile until a moving-input test and sustained live playback validate any change. No SkyWing playback code or installed SkyWing build was changed during this capture.

Cleanup: the temporary mpv option was cleared and applied through SVPlayer's Restart link. The diagnostic Lua file and its two phone output files were removed and their absence verified. The earlier IPC port forward was removed. No Frida session was created.
