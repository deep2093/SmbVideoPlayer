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

## Safety nets for devices that were not tested

The engine was validated on the SM-X930 (Mali) only. On a Snapdragon phone it failed with `ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED` and stalled the picture while audio kept playing.

* **Start-up probe** (`NativeSvpProbe`): on a background thread with a 25 s limit, a synthetic 1080p 24 to 60 fps clip runs through the engine. Until it passes, video plays normally. A hard failure or no answer marks the device for this app version (`native_probe_<versionCode>`); "Check again" in the smoothing settings clears it.
* **Normal playback on failure:** an effect-pipeline error (both 5001 and 5002) or a stall (three seconds under 30 fps after a 6 s grace, from the rendered-frame counter) switches the video to normal playback, not to the GL smoother. Two non-video-specific failures in separate sessions persist the block.
* **Real reason shown:** `NativeSvpDiagnostics` keeps the root cause of the last failure (also logged under the `NativeSvp` tag) and it appears in the toast and settings.
* **No blocking teardown:** the effect waits at most 1.5 s for its worker thread; a worker stuck in a driver call and its native stream are abandoned so Media3's video thread cannot deadlock.
* Not done: output catch-up after a slow start, a lighter native profile.
