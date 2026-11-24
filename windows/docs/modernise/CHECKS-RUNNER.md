# The checks runner

The checks runner does the later phases' checks of one SkyWing build on one device with nobody at it, so the owner's
part is to start it and bring back the results. It is a test tool for the owner's own devices, not part of any
installer. Like the M0 and M1 runners it installs nothing: the app image runs from the runner folder on isolated data,
and the installed SkyWing, the owner's library and settings and the Windows settings stay as they are.

Code: `windows/src/test/kotlin/com/skywing/desktop/automation/ChecksRunner.kt`. The parts that need no SkyWing (what to
run, the verdicts, the soak and scan arithmetic, the table) have unit tests in `ChecksRunnerTest`.

## The checks

| Word | What it does | Plan | Phase |
|---|---|---|---|
| `fullscreen` | Full screen in and out 20 times while the video plays. After each change the video must still play, and there must be one main window. Smoothing must still be on at the end, without a watchdog restart. | Section 10, performance check 5 | M6 (and every phase that touches full screen) |
| `stress` | 10 rounds: SkyWing starts, then 100 times seek, full screen, seek, back to the window; then it closes. It must close by itself within 10 s, leave no SkyWing process and not crash, and smoothing must not restart. | 5.5, section 10 "More native tests" | M2 |
| `soak` | 2 hours in full screen with a seek every 10 minutes. Each minute it reads SkyWing's private memory, handles, threads, GDI and USER objects and CPU time; every 10 minutes it copies a playback report. | Section 10, soak test | M7 |
| `scan "<folder>"` | Walks the folder the way SkyWing did before B19 (`Files.walk`, then each file's size and time) and the way it does now (`VideoFiles.walk`), twice each after a warm-up walk, and compares the times and what they found. Read only; SkyWing doesn't start. | Section 10, performance check 9 | M1 (B19) |

With no word it does `fullscreen` and `stress` (about 35 minutes). `soak` and `scan` run only when named: the soak takes
the screen for 2 hours (the plan says to ask the owner first), and the scan needs the owner's folder. `trial` makes
every check short (3 full-screen rounds, 2 stress rounds of 6 steps, a 4-minute soak); a trial tests the runner, not
SkyWing.

Every check plays generated clips (`testsrc2` with a tone, 1080p at 23.976 fps, H.264), so the results hold no file
names. Smoothing is on at 2× in Fast quality on every PC, with "Lower automatically" off, as in the M0 runs.

## Verdicts

- **Pass**: nothing wrong was found.
- **Fail**: SkyWing did something wrong; the details say what and when.
- **Look at it**: nothing failed, but a soak number is past its first limit. The plan says to set the soak limits from
  the first runs. The first limits are: after the first 20 minutes, the median of the last five readings may not exceed
  the median of the first five by more than 10 %, and by at least 50 MB of private memory, 100 handles, 10 threads,
  50 GDI and 50 USER objects. Drops per minute "rise" when the second half's mean is over 1.5 × the first half's + 1.
- **Not measured**: another program was the active window. The runner posts its keys to SkyWing's window, and they
  only reach SkyWing while it is in front, so a failure then may be the disturbance's. Keyboard, mouse or touch input is
  noted; it spoils a check only when something also went wrong, because Windows sometimes reports input nobody made.

## What the runner can't see

- The picture. A flash, a black frame or a fade that isn't smooth during the full-screen changes needs the owner's eye;
  the runner checks the window, the video position and the playback report.
- What reaches the display. Drops are mpv's counters (`frame-drop-count`, `decoder-frame-drop-count`), not presented
  frames. A seek rebuilds the motion graph, and that can start the counters again at 0 (the first laptop trial saw
  76, then 53, then 6); a counter that fell between two reports counts from its new start, so such an interval can
  count too few drops, never too many.
- The soak from the loopback share. The plan asks for one soak from a local file and one from the loopback share; the
  runner does the local one. The share run stays with the native tests on the development PC.
- `--bench` (5.6) doesn't exist yet, so the soak reads Windows' own counters (`Get-Process`, `GetGuiResources`) instead.

## Building and running it

On the development PC, with ffmpeg (with libx264) on PATH:

```powershell
.\gradlew.bat -p windows checksMedia checksRunner
```

For the soak, make its 135-minute clip first (once; it takes a while):

```powershell
.\gradlew.bat -p windows checksSoakMedia checksRunner
```

That makes `windows\build\checks-runner\SkyWing-checks-runner`, with the current app image in `app`, the build label in
`app-build.txt`, its own Java runtime and the clips. Copy the folder to the device's own drive and follow its
`README.txt`. Results go to `results\<device>\<date-time>`: `checks.md` (the table), a text file per check, the playback
reports, the soak's `minutes.csv`, `runner-log.txt` and, after a crash or a hang on close, SkyWing's output and any Java
crash log. The runner refuses to start from a removable drive, a network share or a OneDrive folder, or while SkyWing is
running.

`SKYWING_CHECKS_IMAGE` names another app image than the runner's `app` folder, `SKYWING_CHECKS_MEDIA` another clip
folder, and `SKYWING_CHECKS_NO_PAUSE=1` skips the pause at the end.
