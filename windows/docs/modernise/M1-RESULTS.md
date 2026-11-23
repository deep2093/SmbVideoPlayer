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
- Every number for a window comes from the whole seconds inside it. The scroll's first part-second is left out: it has 3 to 9 tasks over 8 ms (the longest 10 to 13 ms) in both builds, while Java still compiles the card code. Section 4 gives that warm-up separately.
- *Recompositions per second* counts the frames in which Compose recomposed something.
- *CPU cores* is SkyWing's CPU time divided by the wall time.
- *Java start to SkyWing's code* and *single-instance check* split the time before SkyWing's log starts. The first is from the process start until SkyWing's own code runs. The second is the single-instance check (B38), which waits up to 5 s for an answer from a SkyWing already open. "(ran alone)" means the check failed and SkyWing started without it. Builds from before 24 November don't log these, so their column shows a dash.
- *SkyWing closing* is the time from the runner's close message until every SkyWing process has exited. "stopped after 10 s" means SkyWing hadn't closed by then and the runner stopped it. "still running" means a process was left even after that, which can hold up the next start's single-instance check.

## 3. How to run it

On the laptop, Claude builds and runs it (`gradlew -p windows m1Runner`, then `Run M1.cmd` in the runner folder). It takes the desktop for about 6 minutes per build, so it needs the owner's go-ahead each time, and nobody may touch the PC while it runs.

On a handheld, copy the runner folder to the device's own drive and follow `README.txt`: plug in, close SkyWing, double-click `Run M1.cmd` and leave the device alone until it beeps. Bring back `results\<device>`.

The "before" build is kept with `gradlew -p windows m1KeepBefore` while the checkout is at the before commit. `m1Runner` refuses to build without it.

## 4. Results

**Run-to-run noise.** The trial on 23 November measured the same build twice with short windows (8 s of scrolling). Frame rates and p95 times differed by up to 10%, and the row "scrolling while thumbnails are made" by more (181 to 201 frames per second; longest UI task 10.5 to 21.6 ms). A difference smaller than that, or one or two UI tasks over 8 ms, is not a change.

### Laptop (RTX 5070 Ti)

2560 × 1600 at 240 Hz, plugged in, Windows power mode Balanced. Before: build b9ea781, 23 November. After: build 061ea24, 24 November, with no rerun needed.

| Measure | before | after |
|---|---|---|
| Start to first page, first start (s) | 1.31 | 1.32 |
| Start to first page, everyday start (s) | 1.28 | 1.25 |
| First scan of 5,000 videos (s) | 0.33 | 0.11 |
| Startup check of 5,000 videos (s) | 0.35 | 0.13 |
| Thumbnails made per minute | 732 | 502 |
| CPU cores while making thumbnails | 2.60 | 0.79 |
| Idle on the start page: CPU cores / UI thread busy | 0.01 / 0.6% | 0.01 / 0.4% |
| Scrolling Videos: frames per second | 231.9 | 237.5 |
| Scrolling Videos: frame time p95 / worst second (ms) | 5.17 / 9.06 | 4.41 / 6.37 |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 2 / 8.13 | 2 / 8.73 |
| Scrolling Videos: UI thread busy / CPU cores | 17.0% / 0.70 | 17.1% / 0.46 |
| Adaptive Glow: frames per second | 229.7 | 236.3 |
| Adaptive Glow: frame time p95 / worst second (ms) | 6.05 / 8.71 | 4.84 / 6.20 |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 3 / 11.84 | 2 / 14.31 |
| While thumbnails are made: frames per second | 227.1 | 239.3 |
| While thumbnails are made: frame time p95 / worst second (ms) | 6.07 / 10.30 | 4.85 / 5.24 |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 11 / 11.10 | 4 / 8.77 |
| While thumbnails are made: CPU cores | 3.28 | 1.76 |
| Memory after scrolling: private / Java heap / pictures (MB) | 1,374 / 54 / 197 | 1,480 / 31 / 255 |

Against the M1 targets:
- **Cold start:** 1.3 s, under the 2.5 s target before and after. M1 didn't make the start faster; the start-up work it changed (B13) is in plan section 4.4.
- **Scrolling:** the frame time p95 fell by 15 to 20% and the worst second's by 30 to 50%, larger than the run-to-run noise. Scrolling while thumbnails are made gained most: 11 UI tasks over 8 ms became 4, at about half the CPU.
- **No UI task over 8 ms: not met.** A 30-second scroll still has a few: 2 plain (longest 8.7 ms), 2 with Adaptive Glow (one of 14.3 ms) and 4 while thumbnails are made. The second measurement of the same build in the JVM-flags run had 0, 1 and 2, so plain scrolling is at the edge of the noise, but Adaptive Glow and thumbnails still give tasks over 8 ms. Most of them come in the scroll's first second or two; see *Where the tasks over 8 ms are* below.

**Fewer thumbnails per minute, at less CPU.** Both builds make background thumbnails one at a time (three at once only for cards on screen), so the rate is the time per picture. B24 decodes them on the graphics card (`hwdec=auto-copy`, as plan section 3 asks). A throwaway probe made the runner's 50 clips one at a time, three times each way, with kept players:

| Decoding | Per picture (ms) | Per minute | CPU cores |
|---|---|---|---|
| Graphics card (`auto-copy`, the after build) | 125 | 479 | 0.70 |
| Processor (`no`) | 67 | 900 | 2.80 |

So the kept players alone would be faster than before, and the graphics card costs about 60 ms per picture: the first pictures of 5,000 videos take about 10 minutes instead of 7, at a quarter of the CPU. The handhelds don't gain from the graphics card either: 788 to 546 per minute on the Claw and 282 to 231 on the OneXPlayer, at about a third of the CPU.

**Memory.** Private memory is 8% higher (1,480 MB). The card pictures are now one budget of at most 256 MB (B10) and it is full after scrolling 5,000 cards: 255 MB against 197 MB before. The Java heap is lower (31 MB).

**Where the tasks over 8 ms are.** Three runs of the after build on 24 November (9 scrolls), and a throwaway build that sampled the UI thread during every slow task:
- **The scroll's first second or two, in every scroll.** The first part-second has 4 to 9 tasks over 8 ms (the longest 10 to 13 ms), the next second 0 to 3. The samples are spread over all the card, layout and text code with no single slow place, and some are classes being loaded: Java hasn't compiled that code yet. Every scroll the runner measures is the first after a start; it doesn't measure a second scroll in the same session.
- **After that, 6 of the 9 scrolls had none.** The other three each had one: 8.0 ms (first start), 14.3 ms (Adaptive Glow) and 16.3 ms (while thumbnails were made). None lined up with a garbage collection or with the picture budget filling, and the sampling build caught none, so their cause is not known.
- **The longest task keeps falling** during the scroll, to about 3 ms in its last second.

Compiling the code sooner doesn't help (see *Compiling sooner* below).

### JVM flags (plan 4.4), laptop

The plan's four flags (`-XX:+UseG1GC -XX:MaxGCPauseMillis=8 -Xss2m -XX:+UseStringDeduplication`) in the after build's launcher settings, against the same build without them, measured right after the run above with the same runner. 24 November, plugged in, Balanced.

| Measure | 061ea24 | 061ea24 with the flags |
|---|---|---|
| Start to first page, first / everyday start (s) | 1.31 / 1.23 | 1.28 / 1.25 |
| Thumbnails made per minute / CPU cores | 507 / 0.77 | 501 / 0.79 |
| Scrolling Videos: frame time p95 / worst second (ms) | 4.43 / 6.02 | 4.34 / 5.94 |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 0 / 7.73 | 0 / 7.40 |
| Adaptive Glow: frame time p95 / worst second (ms) | 4.78 / 6.83 | 4.94 / 6.24 |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 1 / 8.93 | 1 / 8.58 |
| While thumbnails are made: frame time p95 / worst second (ms) | 4.88 / 5.22 | 4.81 / 5.58 |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 2 / 8.39 | 2 / 9.71 |
| Memory after scrolling: private / Java heap (MB) | 1,482 / 93 | 1,464 / 37 |

Every difference is within the run-to-run noise, so the flags aren't adopted. G1 is already JDK 17's default here, and a lower Java heap after scrolling is the timing of the last collection, not a saving.

### Compiling sooner, laptop

`-XX:CompileThresholdScaling` makes Java compile code after fewer calls. The after build with it at 0.1 and 0.3, against the same build without it, measured one after the other on 24 November, plugged in, Balanced.

| Measure | 061ea24 | at 0.1 | at 0.3 |
|---|---|---|---|
| Start to first page, first / everyday start (s) | 1.31 / 1.25 | 2.24 / 2.16 | 1.50 / 1.48 |
| Scroll's first part-second, everyday start: UI tasks over 8 ms / longest (ms) | 5 / 10.57 | 5 / 13.50 | 4 / 15.21 |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 0 / 7.82 | 3 / 16.57 | 7 / 15.97 |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 1 / 8.66 | 4 / 16.12 | 10 / 17.55 |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 1 / 16.28 | 12 / 21.35 | 6 / 21.82 |
| Scrolling Videos: CPU cores | 0.60 | 0.85 | 0.79 |

Both settings start slower and give more and longer tasks over 8 ms, so neither is adopted. The likely reason: compiling sooner compiles far more code, and the compiler threads then compete with the start and the scroll.

### MSI Claw 8

MSI Claw 8 AI+, 1920 × 1200 at 120 Hz, plugged in, Windows power mode Balanced. Before b9ea781 and after 061ea24, 24 November, with no rerun needed.

| Measure | before | after |
|---|---|---|
| Start to first page, first start (s) | 1.37 | 3.25 |
| Start to first page, everyday start (s) | 1.36 | 1.40 |
| First scan of 5,000 videos (s) | 0.79 | 0.13 |
| Startup check of 5,000 videos (s) | 0.89 | 0.12 |
| Thumbnails made per minute | 788 | 546 |
| CPU cores while making thumbnails | 2.44 | 0.85 |
| Scrolling Videos: frames per second | 120.5 | 120.5 |
| Scrolling Videos: frame time p95 / worst second (ms) | 8.65 / 12.92 | 8.71 / 16.52 |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 10 / 11.62 | 15 / 17.03 |
| Adaptive Glow: frames per second | 120.3 | 120.7 |
| Adaptive Glow: frame time p95 / worst second (ms) | 8.62 / 15.72 | 8.71 / 12.15 |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 12 / 12.34 | 15 / 12.22 |
| While thumbnails are made: frames per second | 114.9 | 118.4 |
| While thumbnails are made: frame time p95 / worst second (ms) | 9.56 / 17.11 | 8.91 / 30.81 |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 23 / 16.24 | 17 / 17.66 |
| While thumbnails are made: CPU cores | 2.96 | 1.48 |
| Memory after scrolling: private / Java heap / pictures (MB) | 1,274 / 62 / 197 | 1,359 / 81 / 255 |

Against the M1 targets:
- **Scrolling at the refresh rate: met.** Videos scrolls at 120 frames per second on the 120 Hz screen in both builds, with Adaptive Glow too. The frame time p95 (8.7 ms) is one refresh (8.3 ms). While thumbnails are made it went from 115 to 118 frames per second at half the CPU.
- **Cold start under 4 s: met.** 3.25 s on the first start and 1.40 s on the everyday start. The first start took 1.9 s longer than before. Half of it (0.9 s) comes before SkyWing's own log starts, so this runner can't tell what it is, and it is one run. The runner from d9d2116 on times that part.
- **No UI task over 8 ms: not met.** The warm-up lasts about 3 seconds on the Claw (2 to 7 tasks over 8 ms a second, the longest 18 ms). After it a 30-second scroll has 3 to 9.

### OneXPlayer 3

OneXPlayer 3 (Arc B390), 1920 × 1200 at 144 Hz, plugged in, Windows power mode Best performance, power limits PL1 20 W and PL2 21 W in the device's own app. Before b9ea781 and after 061ea24, 24 November, with no rerun needed. The table is the first run; a second run of the same two builds the same day is under *Second run* below.

| Measure | before | after |
|---|---|---|
| Start to first page, first start (s) | 4.85 | 4.87 |
| Start to first page, everyday start (s) | 3.38 | 8.44 |
| First scan of 5,000 videos (s) | 1.88 | 0.25 |
| Startup check of 5,000 videos (s) | 1.82 | 0.28 |
| Thumbnails made per minute | 282 | 231 |
| CPU cores while making thumbnails | 2.82 | 1.00 |
| Scrolling Videos: frames per second | 139.1 | 139.4 |
| Scrolling Videos: frame time p95 / worst second (ms) | 8.36 / 25.32 | 8.16 / 27.72 |
| Scrolling Videos: UI tasks over 8 ms / longest (ms) | 117 / 25.26 | 133 / 34.32 |
| Scrolling Videos: UI thread busy | 40.8% | 42.9% |
| Adaptive Glow: frames per second | 136.8 | 139.3 |
| Adaptive Glow: frame time p95 / worst second (ms) | 10.19 / 41.51 | 10.41 / 23.19 |
| Adaptive Glow: UI tasks over 8 ms / longest (ms) | 130 / 36.16 | 146 / 31.27 |
| While thumbnails are made: frames per second | 126.0 | 138.2 |
| While thumbnails are made: frame time p95 / worst second (ms) | 12.17 / 26.51 | 7.79 / 37.19 |
| While thumbnails are made: UI tasks over 8 ms / longest (ms) | 147 / 124.78 | 150 / 36.12 |
| While thumbnails are made: CPU cores | 5.50 | 2.92 |
| Memory after scrolling: private / Java heap / pictures (MB) | 1,389 / 148 / 197 | 1,341 / 80 / 255 |

**This device is two to three times slower than the Claw at everything, in both builds.** That covers scanning, thumbnails and the UI thread, which has tasks over 8 ms in nearly every second of every scroll (100 to 150 per scroll, not only in the warm-up). The cause isn't known. Whether the power limits above cause it is not tested.

**Second run.** The second run repeats the first's thumbnail and scrolling numbers within the run-to-run noise: before and after, 285 and 229 thumbnails a minute, 137 and 140 frames per second on Videos, and 125 and 138 UI tasks over 8 ms there. The before build's Adaptive Glow scroll had a single 484 ms UI task. The start times of both runs:

| Start to first page (s) | first run, before | first run, after | second run, before | second run, after |
|---|---|---|---|---|
| First start | 4.85 | 4.87 | 8.30 | 8.71 |
| Everyday start | 3.38 | 8.44 | 8.10 | 3.40 |
| Adaptive Glow start | 3.37 | 3.36 | 3.41 | 3.35 |

Four of the twelve starts are 3.5 to 5 s slower than the same start in the other run, two in each build. In all four, SkyWing's log began about 4 s later than usual. The log's first line comes a second after the recorder starts, and starting the recorder sets up Java's graphics toolkit. So the time goes on Java starting, on that set-up or, in the after build, on the single-instance check (B38). The before build has no single-instance check and starts its recorder on the first line of its code, so the check isn't the cause. The runner from d9d2116 on times Java's start and the check for each start.

Against the M1 targets:
- **Scrolling at the refresh rate:** 139 frames per second on the 144 Hz screen in both builds. While thumbnails are made it went from 126 to 138, and the longest UI task from 125 ms to 36 ms.
- **Cold start under 4 s: not met.** A normal first start takes 4.9 s in both builds, and a normal everyday start 3.4 s. Some starts in both builds take 3.5 to 5 s longer, before SkyWing's log begins (see *Second run*). The after build's normal starts lose 0.3 s more than the before build's before the log.
- **No UI task over 8 ms: not met** in either build.

### Playback, laptop

The M1 runner doesn't play video, so playback was measured with the M0 method (`m0Run`, `M0-RESULTS.md` section 2) on 24 November. The settings were those of M0's laptop run A: the same 4K SDR clip, motion on at 2×, Balanced, Automatic GPU (Intel), plugged in and Windows power mode Balanced. Each run had a 60-second warm-up and a 10-minute window.

Each build ran from a copy of its runner image with the native files of the installed 0.1.101, which M0's run A used: its frame-sharing libmpv and CI's motion payload. The runner's own images have the stock libmpv and a locally built motion plugin. With those, the before build turned smoothing off within the window because it couldn't keep up at 2×. The reports' `Build:` line still says "runtime stock", because a build writes that line when it is built.

| Measure | M0 run A (0.1.101) | before | after |
|---|---|---|---|
| Drops in the 10 minutes (video + decoder) | 0 | 108 | 0 |
| Drops per minute | 0.0 | 10.8 | 0.0 |
| Filter processing throughput, report B (fps) | 59.91 | 60.79 | 60.15 |
| Restarts | 0 | 0 | 0 |
| Graph generations, report A → B | 1 → 1 | 1 → 1 | 1 → 1 |
| Process CPU (cores) | 2.06 | 2.17 | 2.13 |
| Private memory, MB: start → end of the window | 5,659 → 5,946 | 5,684 → 5,965 | 5,876 → 5,942 |

In both windows no other program came to the front, full screen held and no input arrived. All the before build's 108 drops came before the window's last minute, and the reports don't say when. One run of each can't tell whether they were chance: M0's run A had none. So M1 made playback no worse in this run, and these numbers don't show it made playback better. The test data has no library, so no thumbnails were being made, and B24 (thumbnails pause during playback) isn't measured here.

## 5. Not in this runner

- **Playback frame drops per minute.** The M1 runner doesn't play video. The laptop's playback numbers above come from the M0 method; the handhelds' weren't measured for M1.
- **What the screen showed.** Display presentation and VRR need PresentMon and come in M2.
