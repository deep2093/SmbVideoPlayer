# M1 results: speed before and after

These are the before and after numbers for M1, the speed foundation (`prompts/01-M1-speed-foundation.md` and plan section 4). One runner measures two SkyWing builds on the same device, one after the other, on the same test library. Keep media names and paths out of this file: the repository is public.

M1's targets:
- Every screen scrolls at the display's refresh rate on the Claw.
- No frame takes more than 8 ms of UI-thread work while a 5,000-video library scrolls.
- Cold start to the first page takes under 2.5 s on the laptop and under 4 s on the handhelds.

## 1. The two builds

| Build | What it is |
|---|---|
| before | The M0 code with the performance recorder (Ctrl+Shift+F12, plan 4.5) and the runner's test hooks, and none of the M1 speed work. Its commit is in `images\before-build.txt` in the runner folder. |
| after | The M1 code. Its commit is in `images\after-build.txt`. |

Both are app images from `createDistributable`, run from the runner folder. Nothing is installed.

## 2. What the runner does

The runner is `windows/tools/m1-runner` plus the Gradle task `m1Runner`, which puts both builds, a Java runtime, the test clips and the measuring code in `windows/build/m1-runner/SkyWing-M1-runner`. The code is `src/test/kotlin/com/skywing/desktop/automation/M1Measurement.kt`.

**Test library.** 50 short test clips made with ffmpeg from its built-in test patterns (40 at 1080p H.264, 10 at 4K HEVC), each with a 480-pixel picture. The runner links them 5,000 times as `video 00001.mp4` to `video 05000.mp4` with NTFS hard links, so the library takes the space of the 50 clips. That is why the runner folder must be on the device's own NTFS drive.

**Each build starts three times**, each time with test data of its own (preferences, library index, thumbnails), never the user's:
1. **First start.** SkyWing scans the test library and makes thumbnails. The runner measures the time to the first page, the scan, then thumbnails per minute and their CPU cost for a minute. Then it opens Videos and scrolls while thumbnails are still being made.
2. **Everyday start.** The library index is kept, and the runner puts a picture in place of every thumbnail, so SkyWing makes none (it checks that). It measures the start, the startup check of the library, half a minute idle on the start page, scrolling Videos, and memory.
3. **Adaptive Glow start.** The same with the Adaptive Glow theme, whose wallpaper follows the covers (B11), then scrolling Videos.

**Scrolling.** The runner turns the mouse wheel by posting wheel messages to SkyWing's window: one notch every 80 ms for 30 seconds. Posted messages don't move the pointer or count as input. The frame and UI-thread numbers come from SkyWing's own measuring log (`SKYWING_PERF_LOG`), which holds times and counts only. CPU time and memory come from Windows.

**Checks.** The runner stops a build's run and measures it again (once) when the device was used during it, another window covered SkyWing, or SkyWing wasn't the front window. It refuses to start when another SkyWing runs, when the folder isn't on a local NTFS drive, or when a build or the test media is missing. A rerun keeps the builds it already measured at the same commit.

**What the numbers mean.**
- *Frames per second* counts the frames Compose drew. It is not proof of what the screen showed; that needs PresentMon (M2).
- *Frame time p95* is the median, over the scroll's seconds, of each second's 95th-percentile time between frames. *Worst second's p95* is the highest.
- *UI tasks over 8 ms* and *longest UI task* time every task on the UI thread (the AWT event thread, where Compose works).
- *Recompositions per second* counts the frames in which Compose recomposed something.
- *CPU cores* is SkyWing's CPU time divided by the wall time.

## 3. How to run it

On the laptop, Claude builds and runs it (`gradlew -p windows m1Runner`, then `Run M1.cmd` in the runner folder). It takes the desktop for about 6 minutes per build, so it needs the owner's go-ahead each time, and nobody may touch the PC while it runs.

On a handheld, copy the runner folder to the device's own drive and follow `README.txt`: plug in, close SkyWing, double-click `Run M1.cmd` and leave the device alone until it beeps. Bring back `results\<device>`.

The "before" build is kept with `gradlew -p windows m1KeepBefore` while the checkout is at the before commit. `m1Runner` refuses to build without it.

## 4. Results

**Run-to-run noise.** The trial on 23 November measured the same build twice with short windows (8 s of scrolling). Frame rates and p95 times differed by up to 10%, and the row "scrolling while thumbnails are made" by more (181 to 201 frames per second; longest UI task 10.5 to 21.6 ms). A difference smaller than that, or one or two UI tasks over 8 ms, is not a change.

### Laptop (RTX 5070 Ti)

2560 × 1600 at 240 Hz, plugged in, Windows power mode Balanced. Before: build b9ea781, 23 November. After: not measured yet.

| Measure | before | after |
|---|---|---|
| Start to first page, first start (s) | 1.31 | |
| Start to first page, everyday start (s) | 1.28 | |
| First scan of 5,000 videos (s) | 0.33 | |
| Startup check of 5,000 videos (s) | 0.35 | |
| Thumbnails made per minute | 732 | |
| CPU cores while making thumbnails | 2.60 | |
| Idle on the start page: CPU cores / UI thread busy | 0.01 / 0.6% | |
| Scrolling Videos: frames per second | 231.9 | |
| Scrolling Videos: frame time p95 / worst second (ms) | 5.17 / 9.06 | |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 2 / 8.13 | |
| Scrolling Videos: UI thread busy / CPU cores | 17.0% / 0.70 | |
| Adaptive Glow: frames per second | 229.7 | |
| Adaptive Glow: frame time p95 / worst second (ms) | 6.05 / 8.71 | |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 3 / 11.84 | |
| While thumbnails are made: frames per second | 227.1 | |
| While thumbnails are made: frame time p95 / worst second (ms) | 6.07 / 10.30 | |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 11 / 11.10 | |
| While thumbnails are made: CPU cores | 3.28 | |
| Memory after scrolling: private / Java heap / pictures (MB) | 1,374 / 54 / 197 | |

Against the M1 targets: the cold start (1.3 s) is already under 2.5 s; scrolling has UI tasks over 8 ms (2 plain, 3 with Adaptive Glow, 11 while thumbnails are made), so the "no task over 8 ms" target isn't met yet.

### MSI Claw 8

### OneXPlayer 3

## 5. Not in this runner

- **Playback frame drops per minute.** M1 doesn't change playback, but it pauses thumbnails during playback (B24). The playback numbers for both builds come from the M0 method in `M0-RESULTS.md`.
- **What the screen showed.** Display presentation and VRR need PresentMon and come in M2.
