# Motion interpolation review

Base: feature/scene-posters-on-60fps. Comparison target: SVPlayer Android 1.8.2 (86).

## What SkyWing already implements

Media3 decodes into a SurfaceTexture. GLES copies two decoded frames, produces luma images at up to 480 pixels wide, searches block motion in both directions, filters vectors, then warps full-size images. Its coarse/fine search uses one image scale; it is not a multi-resolution pyramid. The existing mode already does motion compensation despite stale cross-fade descriptions in the UI.

## What the SVPlayer package establishes

The inspected APKM includes mpv/FFmpeg, libSVPlayer_arm64-v8a.so, assets/libsvpflow1_arm.so and assets/libsvpflow2_arm.so. Its native strings include smoothfps_params, fi_target_exact, fi_scene_changes, Interpolation mode and On a scene change. Package inspection and strings establish components and controls, not exact active defaults or measured playback quality.

SVP's published SVPflow documentation describes hierarchical block search, subpixel precision, forward/backward motion vectors, cover/uncover and vector-quality masks, adaptive frame selection and scene-change handling. Its renderer is closed source. These published desktop interfaces describe the engine family; identical current Android defaults have not been established. No SVP binary or proprietary code is added to SkyWing.

Sources:
- https://svp-team.com/wiki/SVPlayer
- https://svp-team.com/wiki/Manual:SVPflow
- https://www.svp-team.com/wiki/Manual:FRC

## Changes in this branch

- Add a persisted 60/90 fps target selector and matching display-mode preference; preserve 60 as the default.

- Derive each source interval from presentation timestamps rather than decoder callback spacing. The callback still anchors the two-frame presentation window.
- Schedule 60 fps output by deadlines instead of integer display divisors. A 90 Hz display previously selected divisor 2, producing about 45 fps; deadlines draw 60 times per second with alternating display holds. Prefer a matching 60 Hz mode for even cadence.
- Read reliability after generating the current pair's vectors. The old code applied the previous pair's fallback to a new pair, missing cuts and extending fallback into clean motion.
- Keep mean matching residual in the vector map. Large residuals across over 60 percent of blocks suppress interpolation across a likely cut/flash, even when the matcher cannot choose a confident vector. The threshold is a conservative heuristic requiring device/content tuning.
- Refine trusted integer matches in half-pixel steps for slow motion. Keep ambiguity checks from the independent integer search.
- Update outdated product copy and add regression tests for cadence, cuts, recovery, variable source intervals and decoder jitter.

## Remaining differences and validation limits

This is an improvement to the existing GLES engine, not parity with SVP. It retains a single image scale, a limited search radius, one source interval of added video latency, RGBA8 vector precision, a single filtered field for warping, and local blending fallbacks. The GLES2 reliability readback is synchronous and can stall; the previous comment claiming otherwise was incorrect. A future GPU reduction/asynchronous GLES3 readback can reduce this cost. A queued presentation timeline and explicit audio-delay compensation need separate integration work; timestamp cadence alone does not solve all arrival jitter.

The target selector offers 60 and 90 fps (default 60). The display request follows that selection when a matching resolution/refresh mode exists. A slower display limits actual presentation; 90 fps on 120 Hz has uneven display holds. Source pass-through thresholds follow the target. Varying source rates such as 24/28/30 fps use fractional positions between frame pairs rather than assuming frame doubling.

## Physical-device checks

Use local copies and SMB playback of the same 24, 28, 30 and variable-rate samples. Check fast pans, patterned clothing, thin edges, objects crossing, cuts, flashes, seeks, pauses and playback-speed changes. Compare Clean/Balanced/Max and inspect the existing saved frame strips. Confirm the first pair after a cut holds the old scene until its source boundary and the next clean pair recovers. Test 60/90/120 Hz displays and thermal throttling; compare the stats with actual presentation/frame captures. Check audio sync, subtitles, aspect changes and HDR. Unit tests and an APK build cannot establish artifact-free playback or GPU performance.

## Validation for this change

Local JDK 17 / Android SDK build: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passed (183 tests, zero failures/errors). No Android device or emulator was available, so changed GLSL shaders have not been executed on a GPU. Existing GPU setup failure handling falls back to normal playback; successful compilation alone would not establish visual quality.
