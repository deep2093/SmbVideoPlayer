# Native interpolation reconstruction: Android feasibility

The user still sees striped-shirt artifacts after preview 1530. Further local shader corrections have not reproduced SVPlayer's quality. This work evaluates replacing that engine with a complete native motion-analysis and synthesis pipeline. It does not switch normal playback to the prototype.

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

Run `tools/build-native-svp-probe.ps1 -SourceDir <checkout> -ToolchainRoot <toolchains>`. It verifies the revision, applies the small Android patch, builds the three external libraries and a JNI probe, and places ignored outputs under `app/src/androidTest/jniLibs/arm64-v8a`. Build the debug and instrumentation APKs with matching preview version codes; install as updates. Run only `NativeSvpProbeTest` with the instrumentation argument `nativeSvp=true`. Without that explicit argument the test skips, and ordinary CI does not need these native tools.

`tools/native_svp_probe.c` also builds as a standalone executable against the C API. Its optional raw input is exactly twelve YUV420P8 frames at the supplied dimensions. `OSVP_GPU=1` selects GPU synthesis. All input used so far is generated test footage.

## Required before a live-player switch

1. Implement a bounded decoded-frame provider with explicit frame ownership and cancellation. The native C API can request source frames ahead and out of order; it cannot safely read whichever two SurfaceTextures happen to be current.
2. Keep source presentation timestamps and output cadence authoritative. Do not add unaccounted video delay relative to Media3 audio. Variable frame intervals and fractional 23.976 rates must be handled explicitly.
3. Reset the provider and native graph together on seek, source/size/rate changes and playback teardown; invalidate pending outputs from the old generation.
4. Measure YUV decode/conversion and upload costs in the complete pipeline. Standalone interpolation throughput does not include those costs.
5. Compare generated frames against controlled motion with occlusion and repeating patterns, then inspect the requested natural segment in motion. Keep the current shader implementation available during validation.

Preview 1531 installs only the optional native-library declaration; normal video playback still uses the existing renderer. Native engine libraries and JNI entry points currently belong to the opt-in test APK. Live playback integration and perceptual verification remain unfinished.

## Media3 integration investigation

SkyWing currently uses Media3 1.6.1. Its shipped `GlShaderProgram` interface explicitly permits asynchronous output, retaining multiple inputs before producing output, and output callbacks with independent presentation timestamps. A candidate integration is a bounded native interpolation effect in Media3's video-frame processor, with output frame timestamps driven by the media timeline. This could keep scheduling under the existing playback clock instead of adding an unrelated TextureView clock. It has not yet been implemented or validated end to end; output multiplicity, backpressure, flush/end-of-stream handling and color conversion must be tested in the actual player. The 1.6.1 processor also distinguishes default SDR working color from linear working color; the bridge must not assume raw decoder YUV is equivalent to its GL texture values.
