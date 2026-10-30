# Motion interpolation review

Current revision: preview 1528 targets **60 fps only**. The 90 fps selector and saved preference are no longer used. The preview-1527 source-frame-hold changes were rolled back after user testing found worse motion artifacts.

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

This is an improvement to the existing GLES engine, not parity with SVP. It now uses a two-scale pyramid but retains a limited search radius, one source interval of added video latency, half-float vector textures on supported GPUs (RGBA8 fallback elsewhere), a single filtered field for warping, and local blending fallbacks. The GLES2 reliability readback is synchronous and can stall; the previous comment claiming otherwise was incorrect. A future GPU reduction/asynchronous GLES3 readback can reduce this cost. A queued presentation timeline and explicit audio-delay compensation need separate integration work; timestamp cadence alone does not solve all arrival jitter.

The current output target is fixed at 60 fps. Previous 90 fps experiments are recorded below as history and are no longer exposed by the app. Varying source rates such as 24/28/30 fps use fractional positions between frame pairs rather than assuming frame doubling.

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


## Stripe and fast-motion follow-up (preview 1527)

User feedback identified artifacts on repeating stripes and fast motion. Inspection found that the vector filter could promote an undecided block by borrowing neighbours, accept a neighbouring object's reverse vector as confirmation, and allow Max to trust motion with no reverse evidence. Local fallback cross-faded the two source pictures, which can itself double stripe edges.

Changes:
- Keep ambiguous borrowed vectors untrusted in every preset, except genuinely static low-residual blocks.
- Require the destination block to confirm reverse motion in every preset, with stricter tolerances.
- Reject vectors near the search range limit, high-residual matches and median changes that can switch objects.
- Hold one actual source picture in unreliable areas instead of cross-fading source pictures; strengthen masking near low confidence and prevent stretched sampling beyond frame edges.
- Include full-colour disagreement in the mask so luma downsampling cannot hide all fine-pattern mismatches.
- Correct the on-screen target label to show the selected 60/90 fps value.

A targeted GPU regression checks trusted vectors, ambiguous filled vectors, absent reverse evidence and search-limit vectors across all three presets. Another checks that unsafe regions produce an actual source value at quarter/three-quarter phases rather than a blended value. These checks passed on the SM-X930.

A private user-selected 1080p reference was streamed through the existing SMB data source at the requested timestamp and a nearby striped-shirt section. Source/generated/source captures were inspected for Balanced and Max; the video and captures are not included in this repository. After excluding capture stalls, decoded rate was 23.88/23.91 fps and drawn rate 57.71/58.45 fps respectively at target 60. Whole-frame fallback was 0% in these samples; local holds/masking remain active. This is a short targeted observation, not a proof of zero artifacts or motion accuracy in every scene. The stricter local holds trade some smoothness for fewer warped/doubled edges.

The opt-in reference test accepts `referenceName` (exact indexed filename) and `referenceMs`. It opens only that item, mutes its own test player, and does not write playback history. Captures are temporary files named `motion-reference-<strength>-<index>.png` under the app cache; keep private and remove after inspection. Do not commit reference filenames or media. Synthetic tests require `motionPlayback=true` as before.


## 60 fps correction after user regression feedback (preview 1528)

The user found preview 1527 worse in Balanced at 60 fps. Its strict ambiguity rejection and local source-frame holds were reverted to the 3b69d15 masking/filter policy. The 90 fps selector and target-specific analysis budget were removed; a saved 90 fps preference is ignored. GPU policy tests that required the rejected source-hold behavior were replaced with a quantitative image-quality regression.

The motion maps were previously RGBA8. Encoding zero as 0.5 stores approximately 128/255, which decodes to +0.0784 analysis pixels rather than zero. At an 8x source/analysis ratio this becomes about +0.63 source pixels. It can disturb fine stripes and make masking react to a displacement that was never present. Supported devices now retain motion maps in half-float textures with an exactly representable zero. Capability checks include actual framebuffer completeness; unsupported hosts retain the byte path. Only a small reliability-map copy is converted to bytes for CPU statistics. A local cost-curve fit also refines trusted matches continuously rather than always snapping slow motion to half-analysis-pixel increments.

The GPU regression renders known stationary two-pixel stripes at an 8x source/analysis ratio using both byte and half-float motion maps. Masking is disabled for this measurement to isolate vector encoding. Mean absolute pixel error was 40/255 for the byte baseline and 0/255 for half-float on the SM-X930. This proves correction of that controlled precision defect, not perfect motion estimation on moving clothes.

The updated 24/28/30 fps synthetic playback checks delivered 59.73/60.24/59.98 drawn swaps per second respectively, with half-float active and 0% whole-frame fallback during these samples. The previous source-frame-hold regression remains documented above as a failed perceptual approach. Natural-scene quality still needs user assessment; do not equate passing cadence/shader tests with visual equivalence to SVP.


The moving-stripe regression supplies a known four-pixel translation and compares the generated midpoint with a two-pixel-translation ground truth. With masking disabled to isolate encoding, mean error was 12/255 for byte vectors and 0.5/255 for half-float. This tests compensation precision with known motion; it does not prove that the estimator infers every repeating-pattern displacement correctly. The private shirt reference, replayed at the same nearby timestamp, decoded at 23.94 fps and drew 59.51 fps (Balanced) / 59.17 fps (Max), with half-float active. Both GPU quality checks, 60 fps synthetic playback, unit tests and lint passed. User perceptual review remains necessary after the prior regression; the former source-hold strategy is not being reapplied.
