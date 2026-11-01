# Captured-profile compatibility experiment

Tested 2026-11-01 on the connected SM-S948B / Adreno 840, using the pinned open-svpflow revision `deadbad2201d77056173c05c2380758d88612019` and the existing Android patch. Only the instrumentation APK was updated. Normal playback settings, native app libraries and installed main APK version 1563 were unchanged.

## What the open engine supports

Source inspection and successful CPU execution confirm that `pel=1`, `full=false`, `scale.up=0`, the captured main/coarse search and refinement fields, algorithm 21, mask area/cover, and scene mode 3 are accepted. The captured profile in the probe transcribes these configuration values; it contains no SVPlayer plugin or recovered implementation.

The test normalizes the output rate to absolute 60 fps and the device ID to this host's zero-based `gpuid=0`. SVPlayer's captured device ID 11 is not a portable ordinal. The CPU control explicitly uses `gpu=0`; the GPU case requests `gpu=1`. `hdr.dovi=true` is retained as a parameter, but the generated source is SDR.

Important differences remain:

* The reconstruction's GPU renderer creates an OpenCL context. Parsing `api=2` does not supply a Vulkan backend; that selection is explicitly rejected by its GPU validation path.
* `gpu_qn=1` selects one synthesis resource unit; each unit creates separate compute and transfer queues. It is not proof of the same synchronization behavior as SVPlayer's similarly named option.
* The C API prepares up to two additional output requests per call, bounded by its window and memory budget. This is not SVPlayer's captured mpv/VapourSynth concurrency of 9 and buffering of 3. Increasing worker threads does not reproduce that scheduler.
* Omitted block fields resolve to 16x16 and overlap 2 in this reconstruction. Those defaults have not been established for SVPlayer's proprietary plugin.

## Test improvements

`NativeSvpProbeTest` now accepts `probeProfile=baseline|pel1|captured`. The precision profile changes only Super precision/storage; the captured profile also changes search, refinement and synthesis options. The main player does not select any of these experimental profiles.

The earlier small moving rectangle on a flat field returned source frames throughout short tests. It therefore could not establish synthesis performance. The new fixture moves textured content across the entire frame plus a separately moving striped foreground object. Sampled luma hashes compare every intermediate output with both source neighbours. The test requires at least one distinct intermediate output for source rates below 60 fps. This rejects complete frame repetition; it does not prove motion accuracy, artifact-free output, or perceptual smoothness.

The probe records generation-time percentiles, aggregate throughput, intermediate/distinct counts, progress and cleanup stages. Percentiles include cached original-frame returns and asynchronous work; they are not display frame deadlines. Aggregate loop time also includes fixture generation and verification, but excludes decoder/display/audio workload and initial graph construction.

`probeGuardFrames=4` measures an interior window of a source with four additional generated frames. The full source remains available to native prefetch. Default `0` still tests the finite-clip boundary. A guarded run does not validate EOS. No guard frames are added to the real player.

## Results

All rows below process 1920x1080 input into nominal 60 fps output. Each 720-source-frame case requests 1,439 output frames. These are single-run measurements without thermal control, not a ranked benchmark.

| Requested backend / profile | Analysis | Workers | Loop throughput | Distinct intermediate samples | Result |
|---|---|---:|---:|---:|---|
| CPU / baseline | Half | 4 | 111.99 fps | 719 / 719 | Completed, including cleanup |
| CPU / precision-only | Half | 4 | 115.27 fps | 719 / 719 | Completed, including cleanup |
| CPU / captured | Full | 9 | 97.50 fps | 678 / 719 | Completed, including cleanup |
| GPU requested / captured, interior window | Full | 4 | 71.26 fps | 678 / 719 | Produced all requested frames; hung in context destruction; watchdog stopped process |

The precision-only result is about 3% above baseline in this one run; this is not enough evidence for a performance improvement or a playback change. The captured full-analysis CPU graph has different quality/cost tradeoffs and is not an equal-analysis comparison with baseline.

Finite GPU-requested runs with 60 and 120 source frames stopped at requests 117/119 and 237/239 respectively. The failure followed the final interpolation request rather than the same elapsed point in both clips. An interior-window run produced every requested output, released the output and source clips, then failed to return from `osvp_destroy`. A shorter guarded reproduction confirmed the same context-destruction phase. The worker-draining/native GPU synchronization path is the next investigation target; the exact blocked function and driver/kernel cause remain unknown. A native backtrace through `debuggerd` was unavailable on this unrooted phone.

Final CPU checks at 24, 28 and 30 fps passed frame-count and distinct-output assertions and returned from cleanup. They are diagnostic compatibility checks, not sustained real-video playback tests. The GPU runs are failures despite producing useful processing measurements. No GPU profile was promoted to normal playback, and no live GPU preview or visual-quality claim follows from these results.

## Reproduce with a bounded run

Build the native/test libraries as documented in `NATIVE-SVP-ENGINE.md`, then build/install matching debug and instrumentation APKs. For this experiment only `libskywing_svp_probe.so` and the instrumentation APK needed rebuilding because the main app and engine libraries were already installed and unchanged. Keep private diagnostic logs outside version control.

```powershell
python tools/run-native-svp-probe.py --adb <adb.exe> --serial <device> --out <logs-directory> --name captured-cpu --profile captured --gpu false --threads 9 --frames 720 --timeout 90
python tools/run-native-svp-probe.py --adb <adb.exe> --serial <device> --out <logs-directory> --name captured-gpu --profile captured --gpu true --threads 4 --frames 720 --guard 4 --timeout 45
```

The runner returns failure for test assertions, crashes and timeouts. A timeout stops only the SkyWing preview and its instrumentation package; it does not clear app data or restart the phone. `SvpProbe` logs preserve the cleanup phase. A successful processing summary followed by a timeout is still a failed run.
