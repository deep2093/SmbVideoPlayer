# Independent Vulkan known-motion probe

This opt-in diagnostic establishes whether a small, independently written Vulkan
motion-compensation shader is correct and can sustain the processing budget for
60 fps on the connected device. It does **not** implement motion estimation,
occlusion masks, SVP algorithm 13, or the live Media3 playback pipeline. No SVPlayer
library, shader or recovered implementation is included. Normal playback retains
the existing engine and Adreno CPU fallback.

## Workload and checks

- Two changing planar YUV420 sources and an output sentinel use three mapped,
  coherent storage buffers, with one uint per component sample.
- The compute shader compensates known horizontal/vertical motion and blends
  phases in integer arithmetic. Luma and both subsampled chroma planes have
  independent bounds; signed displacements and image-edge clamping are exercised.
- A separately written CPU sampler checks every output sample for the first six
  frames (phases 0, 64, 128, 192, 256 and 113), then every 120th frame. Sparse
  samples are checked on every intervening frame. Any mismatch fails the test.
- Host-to-compute and compute-to-host barriers plus a fence protect buffer reuse.
  Each fence wait is bounded at three seconds. If that timeout occurs, the probe
  reports failure and intentionally retains in-flight resources; force-stop the
  diagnostic app before another run. It never frees potentially in-use memory.
- Work is paced at 60 dispatches per second. Timing includes CPU source generation,
  output initialization, Vulkan submission/completion and sparse validation. Full
  CPU comparisons are excluded from latency percentiles but included in wall time.
- Assertions require p95 processing below 16.667 ms and at least 54 dispatches per
  second over the requested interval. These are compute-test metrics, not measured
  video presentation cadence or end-to-end playback performance.
- The test creates/destroys the Vulkan instance, device, pipeline and buffers for
  1920x1080, then repeats at 1280x720. It requires Vulkan 1.1, coherent host-visible
  memory and 256-invocation compute workgroups. Other devices need their own tests.

## Reproduction

Use the installed NDK r28c and Python; no runtime dependency downloads are needed.
The shader compiler output is checked by `spirv-val` and embedded as aligned words.
All generated binaries stay in ignored build/test directories.

Windows:

```powershell
./tools/build-vulkan-motion-probe.ps1 -Ndk /absolute/path/to/android-ndk-r28c
./gradlew.bat :app:assembleDebugAndroidTest
```

Linux (build recipe supplied; not exercised in the Windows device session):

```bash
ANDROID_NDK_HOME=/absolute/path/to/android-ndk-r28c bash tools/build-vulkan-motion-probe.sh
./gradlew :app:assembleDebugAndroidTest
```

Install a compatible debug app and its test APK, then run:

```text
adb -s DEVICE install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -e vulkanMotion true -e vulkanDurationMs 60000 -e class com.deepak.videolibrary.player.VulkanMotionProbeTest com.deepak.videolibrary.preview.test/androidx.test.runner.AndroidJUnitRunner
```

The test skips unless `vulkanMotion=true`; duration is clamped to 2–60 seconds per
resolution. This command runs no media and may show no visible video on the phone.

## Device observations — 2026-11-01

On the connected Samsung SM-S948B / Adreno 840, the initial 30-second runs passed:

| Resolution | Dispatches | Samples checked | Mismatches | p50 work | p95 work |
|---|---:|---:|---:|---:|---:|
| 1920x1080 | 1,800 | 62,907,540 | 0 | 11.91 ms | 13.37 ms |
| 1280x720 | 1,800 | 27,959,500 | 0 | 6.62 ms | 6.98 ms |

These initial results precede the explicit six-phase and sustained-rate assertions.
The final code passed a 60-second run at each resolution, including those checks:

| Resolution | Dispatches | Samples checked | Mismatches | p50 work | p95 work |
|---|---:|---:|---:|---:|---:|
| 1920x1080 | 3,600 | 110,265,045 | 0 | 11.91 ms | 13.36 ms |
| 1280x720 | 3,600 | 49,007,875 | 0 | 6.71 ms | 7.04 ms |

Both sustained-rate and p95 assertions passed. Total device-test time was 120.115
seconds, including device destruction/recreation. SPIR-V validation, debug unit
tests, Android test packaging and debug lint passed. The ELF load segments use
16 KiB alignment. The Linux recipe received a shell syntax check only; its build
was not run in this Windows session. Only the connected Adreno 840 was tested.

## Integration gate

A successful probe supports evaluating Vulkan as a replacement compute backend;
it does not establish parity with the existing engine. Live integration still needs
real estimated bidirectional motion, occlusion/reliability masks, source-frame
ownership, asynchronous queue scheduling, seek/flush/EOS handling, color handling,
and sustained natural-video quality and audio-clock checks. Compare against the
CPU engine before enabling the new backend in normal playback.
