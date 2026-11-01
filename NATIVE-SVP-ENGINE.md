# Native interpolation engine: Android integration

Preview 1534 connects the native engine to normal ARM64 SkyWing playback through the existing Smooth 60 switch. The former fixed-width GLES estimator remains a fallback for builds without the native libraries. Unsupported native inputs, including HDR and variable-rate timestamps that drift from the inferred cadence, fall back to normal playback.

This is an open-source reconstruction, not a copy of SVPlayer's proprietary code. The user approved both a prepared native comparison and the integrated live profile on the problem segment.

## Evidence and source

SVPlayer 1.8.2 embeds Super -> Analyse -> SmoothFps through mpv/VapourSynth and native SVPflow plugins. Its installed diagnostics identify SVPflow 4.6.0.267. Exact active generated settings and the proprietary plugin implementation remain unrecovered; see SVPLAYER-ENGINE-ANALYSIS.md. No proprietary library is packaged here.

[open-svpflow](https://github.com/Z1xus/open-svpflow), revision `deadbad2201d77056173c05c2380758d88612019`, is an Apache-2.0 Rust reimplementation with a C API, hierarchical analysis, bidirectional vectors, block overlap, refinement and synthesis/masking algorithms. Its author reports parity with SVPflow 4.3.0.168. That claim does not establish parity with the Android app's newer version or this user's video. License and provenance accompany the instrumentation assets.

The small GLES engine uses fixed 256-pixel-wide analysis and one filtered field for symmetric warping. Moving to this native engine changes the underlying analysis/synthesis architecture rather than another masking threshold.

## Reproduced on the SM-X930

* ARM64 libraries compiled with Rust 1.98.1 and NDK r28c, Android API 29, 16 KiB maximum ELF page size.
* The C API and both plugins loaded in an Android shell process and in the instrumented SkyWing app process.
* Android tagged heap-pointer values initially failed the plugin's checked signed-integer conversion. The Android-only patch preserves the pointer bits through that internal integer-property round trip.
* The OpenCL loader patch selects Android's `libOpenCL.so`; preview builds declare this optional native library.
* Both CPU and GPU paths generated a 60/1 output clip from a 24/1 source. The shell probe logged the Mali-G925 GPU renderer as active.
* A 12-frame, 1920x1080 synthetic input produced 28 output frames. An upscaled testsrc2 sample measured approximately 75.8 output frames/s on CPU and 68.2 on GPU, including short-run frame processing but excluding graph creation, decoding, color conversion, display upload and audio. A simpler generated sprite is much faster and is not representative of complex footage.
* The opt-in JNI test also generated 1080p output in SkyWing's app process. This test uses the simple sprite and proves loading/callback/output operation, not natural-video quality or sustained performance.

These are feasibility measurements. They are not evidence that the shirt artifacts are fixed.

## Reproduce the prototype on Windows

Keep external source and toolchains outside the app repository. Clone the source above and check out the exact revision. Install Rust `1.98.1-x86_64-pc-windows-gnu` with the `aarch64-linux-android` target into the workspace's `toolchains/cargo` and `toolchains/rustup` folders. Extract NDK r28c into `toolchains/android-ndk-r28c`. The official Windows archive's SHA-1 is `086bba43ff2f5eb0e387b15c8278bb4e0d89ba1d`.

Run `tools/build-native-svp-probe.ps1 -SourceDir <checkout> -ToolchainRoot <toolchains>`. It verifies the revision, applies the small Android patch, builds the three external libraries and a JNI probe, and places app outputs under `app/src/main/jniLibs/arm64-v8a` and probe outputs under `app/src/androidTest/jniLibs/arm64-v8a`. Build the debug and instrumentation APKs with matching preview version codes; install as updates. Run only `NativeSvpProbeTest` with the instrumentation argument `nativeSvp=true`. Without that explicit argument the test skips, and ordinary CI does not need these native tools.

`tools/native_svp_probe.c` also builds as a standalone executable against the C API. Its optional raw input has the supplied frame count and rational frame rate (defaults: twelve YUV420P8 frames at 24/1). An optional output path writes the generated YUV frames. `OSVP_GPU=1` selects GPU synthesis. Natural reference diagnostics are explicitly opt-in; private footage is kept outside the repository.

## Integration requirements addressed

* A 32-slot owned YUV ring backs native callbacks. Eight decoded frames of input capacity and three output textures bound the bridge queues; the external engine has its own bounded cache. Native callbacks never borrow a decoder texture or Java buffer after the input call returns.
* Media3 owns rendering deadlines and the audio clock. Output timestamps use a 60 fps media-time grid. Common fractional and integer source rates are inferred from input timestamps, and excessive drift is rejected rather than silently desynchronizing.
* Seek/flush cancels blocked source reads, wakes the output worker, joins it, and releases the old native graph before reusing buffers. Pausing and effect replacement retain Media3 scheduling.
* Media3 1.6.1 does not forward per-stream decoder EOS directly to effects, and requires a final output timestamp at least as large as the final input timestamp. `NativeSvpRenderersFactory` supplies that decoder signal. The effect emits the last source timestamp, drains owned textures, and renews input capacity when the next stream's listener is installed.
* Input RGB is packed into planar BT.709 limited-range YUV on the GPU before readback. Native output remains YUV and is converted to RGB on the GPU. This avoids per-output CPU conversion and large RGBA uploads.

## Selected live profile and validation

The display image is limited to 1920x1080 with preserved aspect (the reference is 1920x1012). Motion analysis uses the native `Halve -> Super -> Analyse -> DoubleVectors` path at half those dimensions; synthesis uses the full display-sized source. Settings remain `pel:2`, 16-pixel analysis blocks with overlap 2, algorithm 13, cover 80, area 30, GPU synthesis, and four native worker threads. Two output frames of native prefetch are permitted. The `exact:false` GPU-search experiment is not enabled.

Full-size motion analysis passed short tests but dropped frames during sustained live decoding/display, even though the standalone engine exceeded 100 fps. Half-size analysis provided the required headroom and was visually approved by the user. It is not claimed to be pixel-identical to the earlier full-analysis comparison or SVPlayer.

On the SM-X930, the integrated reference test ran for 72 seconds including seeking, pausing and toggling smoothing. Both 30-second measurement windows scheduled 60.0 output fps; sampled video timestamp offsets relative to the playback clock were approximately +63 and +71 ms (accounting for Media3's initial renderer timestamp offset). This is a scheduling check, not a physical audio/video latency measurement. Separate 24/28/30 fps and playlist-transition tests exercise cadence and lifecycle. No claim of artifact-free interpolation for every video is made.

Build the native app libraries with `tools/build-native-svp-probe.ps1` on the documented Windows toolchain, or `tools/build-native-svp-android.sh` with NDK r28c on Linux. The GitHub APK workflow builds the pinned source and applies the checked-in Android patch before Gradle packages all ARM64 variants. Libraries are ignored build outputs; the source pin, patch, bridge and license notices are committed.

`NativeLivePlaybackTest` is opt-in with `nativeLive=true`; `nativeSource=24|28|30` selects generated fixtures, `nativeEos=true` checks automatic playlist advance, and `nativeReferenceName` selects exactly one explicitly requested SMB item without saving history. `nativeMeasureMs` extends each measurement window. Private reference media and credentials are never committed. `NativeReferenceDecodeTest` and `NativeReferencePlaybackTest` support explicitly requested local comparisons only.

## Media3 integration investigation

SkyWing currently uses Media3 1.6.1. Its shipped `GlShaderProgram` interface explicitly permits asynchronous output, retaining multiple inputs before producing output, and output callbacks with independent presentation timestamps. The integration is a bounded native interpolation effect in Media3's video-frame processor, with output frame timestamps driven by the media timeline. This integration now keeps scheduling under the existing playback clock. The tests cover output multiplicity, backpressure, flush/end-of-stream handling and the live conversion path. The 1.6.1 processor also distinguishes default SDR working color from linear working color; the bridge must not assume raw decoder YUV is equivalent to its GL texture values.

### Adreno compatibility
Live OpenCL interpolation stalled on the Galaxy S26 Ultra (Adreno 840) while audio continued. The captured worker was waiting in native frame generation. Adreno devices now conservatively use CPU interpolation; GLES still handles conversion and display. The same 1080p30 source ran for over one minute with CPU interpolation in build 1551, confirmed by the user. This is a device-specific validation, not a guarantee for every Adreno device or source. Other GPU families retain the existing OpenCL path.
CPU interpolation on Adreno is capped at 1280x720 to reduce sustained workload; the GPU profile remains capped at 1920x1080. Scaled dimensions divide by four as required by half-analysis. This trades detail for processing headroom. On the connected S26 Ultra, the user verified almost five minutes of playback at 59–60 fps in build 1553; logs confirmed 1280x720, 30 fps source, CPU backend and 60 fps output. Build, debug unit tests and lint passed. The automated live test could not run on this phone because the installed Espresso version calls an unavailable InputManager method; the sustained result is user-observed, not an automated frame trace. The separate left-edge strip report remains unresolved.

GPU investigation: a plain ActivityScenario live test replaces the incompatible Compose/Espresso host. Generated 640x360 30 fps video passed GPU playback at 60.2/60.0 scheduled fps before/after seeking. A long generated 1920x1080 source reproduced the freeze automatically. Android OpenCL logging showed two synthesis readback events remaining at execution status 2 (submitted) beyond 500 ms. Disabling pinned readback, retaining the work-resource lease through completion, using one synthesis/readback queue, and explicitly dispatching 8x8 workgroups did not resolve it. These native experiments were reverted. No exact driver/kernel root cause is established; normal Adreno playback remains CPU-based.

### Long standalone Adreno reproducer
The probe now accepts instrumentation arguments probeFrames, probeFps, probeHalf, probeGpu and probeThreads. Example: nativeSvp=true probeFrames=720 probeFps=30 probeHalf=true probeGpu=true (NativeSvpProbeTest). Generated motion repeats across the frame and progress is logged under SvpProbe. The matching CPU control completed 1439 output frames. The moving GPU test stalled; a stationary control completed, but completion alone does not prove actual synthesis because the engine can repeat source frames. Instrumentation in the external engine confirmed real synthesis calls in the moving sequence. GPU host worker count 1, CPU-only analysis with GPU synthesis, disabled masks, algorithm 21 and serialized GPU readback did not resolve the stall. The serialized run stopped at its first logged synthesis invocation. All temporary engine changes were reverted. The short 28-frame benchmark must not be treated as evidence of sustained GPU compatibility. The exact native kernel/driver root cause remains unresolved.

The [captured-profile experiment](SVP-PROFILE-COMPATIBILITY.md) adds `probeProfile=baseline|pel1|captured`, an optional `probeGuardFrames` interior window, distinct-intermediate-frame checks, timing and cleanup-phase logs, and a host watchdog runner. Its GPU-requested graph can process an interior 1080p window but still hangs in context destruction; this is a failed test, not a validated GPU playback fix. Normal playback retains the working device profile.

## Experiment: 1080p CPU smoothing on Adreno phones (branch `claude/fps-experiment`)

The phone's CPU probe at 1080p averaged 112 fps, but per-output time was uneven (p95 34.7 ms, max 82 ms) against a two-output lookahead of about 33 ms, so roughly one output in twenty could miss its slot. 24 fps films need 48 synthesized pictures a second against the 30 fps fixture's 30. This experiment changes only the CPU path; the OpenCL path keeps its validated settings (4 threads, 2 ahead, 3 output textures, input credit 8).

* CPU path: 6 engine threads, 8 outputs of engine lookahead (`osvp_clip_set_end(index + ahead)`), 5 output textures, input credit 12. The JNI `create` call now takes `threads` and `ahead`.
* The interpolation worker raises itself to `THREAD_PRIORITY_URGENT_DISPLAY`; engine pool threads created from it inherit that. On Android 13+ a PerformanceHint session (16.7 ms target) covers the worker and engine threads (found by the inherited thread name) and receives per-output render durations. Sustained-performance mode is requested where the device supports it.
* 1080p versus 720p is chosen by measurement: "Check 1080p speed" in the player settings runs `NativeSvpSpeedCheck` (synthetic 1080p 24 fps pan plus moving stripes, same CPU settings): 120 outputs flat out, then 600 outputs requested at the real 60 fps pace. Pass: at most 3 outputs later than the four-frame display slack, and at least 90 fps flat out. The result is stored per app version; CPU playback uses 1080p only after a pass.
* A 1080p watchdog drops to 720p (and remembers it for this version) after three consecutive seconds under 50 shown fps, following an 8 s grace period.
* The overlay's second line shows the active processing size, backend, threads and lookahead.
* Both Media3 effect-pipeline errors (5001 and 5002) now trigger the existing normal-playback fallback; previously only 5001 did.
* Not changed yet: the synchronous `glReadPixels` per input frame and the `glFinish` per output frame. A one-frame-delayed PBO readback changes end-of-stream timing (the decoder end signal arrives off the GL thread) and is left for a separate change if the speed check and live tests point at GL-thread stalls.

## GPU lab (Adreno OpenCL investigation)

Player settings → "GPU lab (diagnostics)" runs the OpenCL path on a synthetic 1080p 24 fps clip (120 source frames, every output, clip end, engine shutdown) in a separate `:gpulab` process, once per variant. All OpenCL calls go through `libskywing_cl_trace.so` (`app/src/main/cpp/cl_trace.c`), selected by the patched engine through `OSVP_OPENCL_LIB`. The wrapper records per-queue enqueue/flush counts, each event's originating command and kernel, every thread's blocking call and event polls, and the last 96 calls; a watchdog writes a stall report when a call or event wait exceeds three seconds. The app kills a lab process that makes no progress for 20 s (60 s before the first output).

Variants (`OSVP_CL_VARIANT`): `trace` (unchanged), `flushwait` (flush every queue before any wait, poll or finish: tests "a command only runs when later work is submitted"), `flushall` (flush after every enqueue), `blockwrite` (all host-to-device writes blocking: tests host-memory lifetime), `all`. The report is saved to Downloads as `SkyWing-gpu-lab-<time>.txt`.

### GPU lab result on the S26 Ultra (SM-S948B, Adreno 840, driver 0842.19.13) and the fix

Two full lab runs gave identical outcomes: `trace`, `flushall` and `blockwrite` hang at the first output; `flushwait` (and `all`) produce all 298 outputs of the 1080p clip at 64–69 fps, including the clip end, and shut the engine down cleanly. In every hang the stuck event is the last readback on a transfer queue (status 2, submitted) whose first readback waits on the synthesis kernel's event from another queue; every queue had already been flushed. Flushing once after enqueueing (`flushall`) is not enough: the Adreno driver starts that readback only when its queue is flushed again after the cross-queue dependency completes. Blocking host writes make no difference.

Playback fix (opt-in, "Use the GPU on Snapdragon phones"): on Adreno the effect sets `OSVP_OPENCL_LIB=libskywing_cl_trace.so`, `OSVP_CL_VARIANT=flushwait` before the engine loads OpenCL, and uses the GPU path at 1080p with the validated GPU settings. The wrapper's stall reports go to `files/gpu-stall.log`; the player switches back to the CPU engine on a stall report or after three seconds under 45 shown fps (after an 8 s grace). The effect no longer waits without limit for its worker at shutdown. A targeted engine patch (flush the awaited queue inside the engine's wait loops) can replace the wrapper once live playback confirms the diagnosis.

### First live Adreno GPU test and follow-up

The S26 Ultra played 1080p at 60–61 fps on the GPU, phone temperature normal. Two problems: the app crashed on seeking (timeline tap, double-tap, scrubbing), and roughly once a minute the picture froze for 10–20 s and then resumed.

Wrapper fixes: (1) released command queues stayed in the wrapper's table and the flush-while-waiting mode kept flushing them after the engine freed them during a seek's shutdown, a use-after-free in the driver; queues now leave the table under a write lock before release (flushes hold a read lock), only `clCreateCommandQueue` adds queues, and freed slots are reused, so the table no longer fills up after several seeks. (2) In flushwait mode a pump thread now flushes every live queue each millisecond, so a command whose cross-queue dependency completes while all engine threads are blocked inside the driver (blocking map or finish) still starts. (3) Event pointers held for stall reports are cleared on error status and on release by any thread, and reports only query events that are still alive.

Diagnostics: in GPU mode the player writes a per-second line to `files/gpu-playback.log` (player state, position, shown fps, engine outputs, outputs that differ from the previous one, input frames, slowest output, active stream). "Share GPU playback report" saves it with the wrapper's stall reports, Android's recorded app exits (including the readable strings of a native crash trace) and the last Java crash to Downloads.

## Smoothing quality levels, GPU quality check and step-down

The second live GPU test on the S26 Ultra (build 1579) ran 6.5 minutes at 1080p on the GPU, average 59.6 shown fps with almost every output a new picture, no stall reports and no crashes (the four native crashes Android recorded, in `libOpenCL.so`/`libCB.so`, predate the queue-lifetime fix). The engine then dipped to 55–57 outputs a second with its slowest outputs rising from about 30 to 65–68 ms, most likely GPU clocks easing as the phone warmed; the player dropped most of those slightly late frames (7 shown fps) and the old watchdog switched to the CPU engine at 720p. At 1080p the phone's GPU has only about 5–10% headroom.

* Quality levels: Performance 720p, Balanced 900p, Quality 1080p (default), Max 1440p. `NativeSvpScale(gpuMax, cpuMax)` fits the picture into the level's size.
* GPU quality check: in the `:gpulab` process (with the Adreno fix on Snapdragon), the paced test from the CPU check runs on the GPU at 900p, 1080p and 1440p and stops at the first failure (pass: at most 3 of 600 outputs late at 60 fps and at least 66 fps flat out). Until checked, the GPU may use up to 1080p; Max needs a pass. CPU smoothing on Snapdragon stays at 720p unless the CPU 1080p check passed.
* One watchdog for every native mode: after an 8 s grace, three seconds under 50 shown fps lower the level by one on the same engine; at 720p a Snapdragon GPU falls back to the CPU engine; a wrapper stall report falls back immediately. After 120 calm seconds (at least 57 fps) and three minutes since the last step down it raises the level by one, up to the chosen quality.
* Snapdragon GPU path only: 4 outputs of lookahead, 5 output textures, input credit 10 (the tablet keeps 2/3/8).
* The GPU path is now on by default for Snapdragon phones; the playback log records the active cap.
