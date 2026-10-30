# Motion interpolation review

Base: feature/scene-posters-on-60fps. Comparison target: SVPlayer Android 1.8.2 (86).

## What SkyWing already implements

Media3 decodes into a SurfaceTexture. GLES copies two decoded frames, originally produced luma images at up to 480 pixels wide, searched block motion in both directions at one image scale, filtered vectors, then warped images directly at window resolution. This branch now uses two image scales and warps at video resolution. The existing mode already does motion compensation despite stale cross-fade descriptions in the UI.

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

This is an improvement to the existing GLES engine, not parity with SVP. It now uses a two-scale pyramid but retains a limited search radius, one source interval of added video latency, RGBA8 vector precision, a single filtered field for warping, and local blending fallbacks. The GLES2 reliability readback is synchronous and can stall; the previous comment claiming otherwise was incorrect. A future GPU reduction/asynchronous GLES3 readback can reduce this cost. A queued presentation timeline and explicit audio-delay compensation need separate integration work; timestamp cadence alone does not solve all arrival jitter.

The target selector offers 60 and 90 fps (default 60). The display request follows that selection when a matching resolution/refresh mode exists. A slower display limits actual presentation; 90 fps on 120 Hz has uneven display holds. Source pass-through thresholds follow the target. Varying source rates such as 24/28/30 fps use fractional positions between frame pairs rather than assuming frame doubling.

## Physical-device checks

Use local copies and SMB playback of the same 24, 28, 30 and variable-rate samples. Check fast pans, patterned clothing, thin edges, objects crossing, cuts, flashes, seeks, pauses and playback-speed changes. Compare Clean/Balanced/Max and inspect the existing saved frame strips. Confirm the first pair after a cut holds the old scene until its source boundary and the next clean pair recovers. Test 60/90/120 Hz displays and thermal throttling; compare the stats with actual presentation/frame captures. Check audio sync, subtitles, aspect changes and HDR. Unit tests and an APK build cannot establish artifact-free playback or GPU performance.

## Validation for this change

Local JDK 17 / Android SDK build: `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passed (183 tests, zero failures/errors). The initial build had no device; the subsequent physical-device validation below supersedes that limitation.


## Physical-device validation (2026-10-31)

Samsung SM-X930, Android 16, preview upgraded in place from version code 1525 to 1526. Local synthetic H.264 640x360 testsrc2 clips exercise Media3 decoding, SurfaceTexture ingestion, production motion shaders and TextureView presentation. No personal video was opened by these tests. The GPU test compiles/links all six production programs and checks maximum matching residual for a black-to-white cut, followed by zero residual for identical frames.

The first real playback measurement exposed a performance failure: about 21 drawn frames/s at the 60 fps target, with all frames using whole-frame fallback. A 256-pixel search alone was insufficient. Device-driven changes add a quarter-size predictor image and coarse vector grid, followed by a local full-scale search. Analysis widths are 256 for target 60 and 192 for target 90. Full-resolution pictures are retained; lower analysis resolution reduces fine-motion precision. Warping now uses video-sized output buffers rather than magnifying the expensive warp to the tablet window. TextureView handles display scaling.

The Balanced guard previously held entire frames when over 7% of blocks failed the motion check. This pattern produced 11-22% untrusted blocks and was always held. Balanced now uses local masking until 35% of blocks fail; Max uses 60%. Clean retains its conservative 2% guard. These are heuristics, not universal quality thresholds. Scene-cut protection remains independent of these thresholds.

Final measured averages (drawn swaps per second, not a measurement of distinct scanout frames):

| Source fps | Target | Decoded fps | Drawn fps | Whole-frame fallback |
|---|---|---|---|---|
| 24 | 60 | 23.94 | 59.85 | 0% |
| 28 | 60 | 28.02 | 59.99 | 0% |
| 30 | 60 | 30.02 | 59.79 | 0% |
| 24 | 90 | 23.91 | 83.69 | 0% |
| 28 | 90 | 27.97 | 81.62 | 0% |
| 30 | 90 | 29.89 | 82.01 | 0% |

Zero whole-frame fallback does not mean zero local masking or zero artifacts. The playback test requires at least 85% of the requested output rate, near-source decoding, and some interpolation instead of permanent source-frame holds; it passed both test classes. Sustained exact 90 fps has not been established. This panel has 60/96/120 Hz modes, not an exact 90 Hz mode. These short 360p synthetic tests do not establish 1080p/4K performance, real SMB throughput, thermal stability, audio sync or natural-scene visual quality.

Reproduce with `python tools/generate_motion_fixtures.py` (FFmpeg required), then build/install preview and androidTest APKs with a version code no lower than the installed preview. Run only `com.deepak.videolibrary.player.MotionGpuTest` and `com.deepak.videolibrary.player.MotionPlaybackTest` using AndroidJUnitRunner with `-e motionPlayback true`. Generated test assets are ignored by Git. The tests use a temporary test Activity and app cache without opening library videos or SMB connections.
