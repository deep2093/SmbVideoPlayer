# Functional test coverage (Windows)

Stage 4 adds automated functional tests on top of the Stage 3 Java Access Bridge framework
(`src/test/kotlin/com/skywing/desktop/automation/`, see `ACCESSIBILITY.md`). No second framework was added.

## Running

The supported way is `tools\verify.ps1`, which follows `TESTING-POLICY.md`. It sets the variables below, checks the
prerequisites, selects tests for the changed files, fails on any skipped test and checks isolation:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode full -AllowDesktopFocus
```

The same run by hand:

```powershell
$env:SKYWING_TEST_MPV = "true"
$env:SKYWING_TEST_MPV_DIR = "C:\Program Files\SkyWing\app\resources"   # read-only: pinned libmpv for in-process tests
$env:SKYWING_TEST_AUTOMATION = "true"
.\gradlew.bat -p windows createDistributable --no-daemon               # the packaged test image the UI tests launch
.\gradlew.bat -p windows test --no-daemon --rerun
```

Without the variables, the native and UI tests are skipped and only the plain unit tests run. The UI tests open
SkyWing windows and full screen, so they take desktop focus while they run.

## How the tests stay isolated

- **The test JVM.** `tasks.test` gives the test JVM file-backed preferences and its own `LOCALAPPDATA` and `APPDATA`
  (`build/test-user-data`), so app code rendered in-process never reaches the user's data. `TestIsolationTest` guards it.
- **Media.** Every video is generated per run into a temporary folder by the bundled libmpv's encoding mode (lavfi
  test patterns and tones). ffmpeg, when it is on PATH, is used only for two fixtures: the multi-track MKV (muxing
  only) and the constant-rate 24 fps clip for motion tests. libmpv's encoder leaves the last frame without a
  duration, so its files report 24.34 fps, which SkyWing correctly treats as an unknown rate. Tests that need ffmpeg
  are skipped without it.
- **App data.** Each app launch gets its own temporary data folder. Java preferences are file-backed through
  `IsolatedPreferencesFactory`, and `user.home`, `LOCALAPPDATA` and `APPDATA` are redirected. Library, history and
  settings tests compare `HKCU\Software\JavaSoft\Prefs\com\skywing` and `%LOCALAPPDATA%\SkyWing\library` before and
  after.
- **Cleanup.** Test data is deleted in `finally` blocks, including when a step fails between a launch and its
  relaunch.
- **SMB.** No share is created, changed or scanned:
  - A generated clip is played through the loopback admin share (`\\localhost\C$\` plus the temp path).
  - An unreachable share is a library entry on a `.invalid` host.
  - A loopback share is never put in the library, because a rescan walks the whole share root, which would be C:.
- **Input.**
  - Controls are found by role and label and pressed with their accessible action.
  - For shortcuts with no control on screen (hidden full-screen chrome) and for typing into the search field, key
    messages are posted to SkyWing's own window only.
  - No screen coordinates, no global input and no accessibility-setting changes.
- **Evidence.**
  - Every UI failure saves the accessibility trees to `build/reports/automation/`.
  - JUnit XML lands in `build/test-results/test/`.
  - Memory samples are written to `build/reports/tests/test/playback-memory.txt` and `app-memory.txt`.

## Coverage matrix

Legend: **N** native/in-process test, **E** end-to-end through the packaged app over JAB, **U** unit test,
**H** needs physical hardware or a real network (not automated).

| Area | Behaviour | Test | Kind |
|---|---|---|---|
| Playback | MP4 (H.264/AAC) and MKV (VP9/Opus): decoder, duration, play, pause, resume, seek | `EmbeddedPlaybackNativeTest.playsMp4AndMkvWithPauseResumeAndSeek` | N |
| | Transport through the real buttons: Pause/Play, ±10 s, graceful exit while playing | `PlaybackAutomationTest.mp4…`, `mkv…PlaysPausesResumesAndSeeks` | E |
| | Two audio and two subtitle tracks: switch, cycle, subtitles off | `EmbeddedPlaybackNativeTest.switchesBetweenTwoAudioTracksAndTwoSubtitleTracks` | N |
| | External .srt next to the video | `EmbeddedPlaybackNativeTest.loadsTheSubtitleFileNextToTheVideo` | N |
| | Queue: Play all, Next, Previous; Stop saves progress; Continue watching after restart | `PlaybackAutomationTest.videosPagePlaysTheQueue…` | E |
| | Opening a video after a pause plays it (regression for the bug fixed in Stage 4) | `EmbeddedPlaybackNativeTest.openingAnotherVideoWhilePausedPlaysIt` | N |
| | Missing and corrupt files fail without breaking the player | `EmbeddedPlaybackNativeTest.missingAndCorruptFilesFail…` | N |
| | A corrupt file reports "SkyWing can’t play …"; the next video clears it | `EmbeddedPlaybackNativeTest.aCorruptFileReportsAPlaybackFailure…` | N |
| | Corrupt file: "Can’t play this video" window with the reason, OK closes it, reason stays in the status, next video plays | `PlaybackAutomationTest.aCorruptVideoDoesNotStopTheNextOne` | E |
| | Missing file: "Video not available" window naming the file, OK closes it, library usable | `PlaybackAutomationTest.aMissingVideoOnTheCommandLineShowsWhy…` | E |
| | 40 switches and 4 player restarts keep native memory bounded | `EmbeddedPlaybackNativeTest.repeatedVideoSwitching…` | N |
| Motion | Smoothing button on/off, "Holding N fps" (configured rate), survives seek, choice survives restart | `MotionAutomationTest.smoothingButtonTurnsMotionOn…` | E |
| | Saved profile sets the rate (2× = 48 fps for 24 fps); out-of-range stop and unknown preset fall back | `MotionAutomationTest.aSavedMotionProfile…` | E |
| | Engine across seek, pause, every quality preset, property restore, plugin-failure fallback | `MotionNativeTest` | N |
| | Rates, missing runtime, failures, stall recovery, report redaction | `MotionEngineTest` | U |
| | Profile migration, stops and display limits, drop-trip fallback, VRR default | `SmoothingTest` | U |
| | Real output cadence, judder, VRR activation, GPU behaviour under load | — | H |
| Full screen | Covers the monitor, auto-hides controls, a key press brings them back into the accessibility tree, F returns to the same window size, Space pauses, one main window, clean exit | `FullscreenAutomationTest.fullScreenCoversTheMonitor…` | E |
| | Leaving the player pauses; Now playing returns to the same video and position; Play resumes | `FullscreenAutomationTest.leavingThePlayerPauses…` | E |
| | Inactivity and focus recovery, pointer placement, Escape restores the frame, motion panel, no resurrected player, no background process | `FullscreenWindowTest`, `PlayerChromeTest` | N, U |
| | Real Alt+Tab with other apps, mouse-hover reveal, multi-monitor, HDR output | — | H |
| Library | Folder scan ignores non-videos; search filters; cached thumbnails per video, reused after restart; removed file leaves the library | `LibraryAutomationTest.scansAFolderSearches…` | E |
| | Folder picker, refused unsafe actions, adding the ticked folder | `SkyWingAutomationTest.folderPickerCheckBoxes…` | E |
| | Grouping, organizer persistence, virtual folders | `FolderOrganizerTest`, `PerformerMatcherTest`, `ArtworkTest`, `WindowsMediaIndexScaleTest` | U |
| Settings | Playback switches toggle and persist across restart; registry untouched | `SkyWingAutomationTest.playbackSwitchesToggle…` | E |
| | Damaged settings (bad enums, numbers, history, share profiles) and a damaged library index: app starts, rescans, plays | `LibraryAutomationTest.damagedSettings…` | E |
| SMB | Playback and seeking over the loopback admin share | `NetworkAndStabilityAutomationTest.playsAndSeeks…LoopbackSmbShare` | E |
| | Unreachable share: list kept, "Not reachable" scan error shown, its video shows "Video not available" instead of a broken player, local playback still works | `NetworkAndStabilityAutomationTest.anUnreachableShare…` | E |
| | Share browsing, host fallback | `WindowsShareBrowserTest`, `PortSettingsTest` | U |
| | Connection lost during playback, reconnection, credentials, slow links, real NAS | — | H |
| Stability | 12 rounds of open, play, seek, back, switch pages: memory bounded, graceful exit | `NetworkAndStabilityAutomationTest.repeatedPlayback…` | E |
| Accessibility | Tree, roles, names, switches as check boxes | `AccessBridgeTreeTest`, `AccessibilitySemanticsTest`, `ControlQueryTest` | N, U |

## Known gaps and findings

- **Fixed in Stage 5: corrupt and missing files.** A file mpv cannot open or decode now shows a "Can’t play this
  video" window and keeps the reason in the player status. A missing file (moved, renamed, drive or share offline)
  shows "Video not available" naming the file. Both are separate windows, like Motion options, so they show above the
  native video surface. OK, Enter or Escape closes them; nothing else changes.
- **Hidden full-screen controls leave the accessibility tree (by design).** Any key press, including a screen reader's
  own keys, or pointer movement brings them back; the test checks this with Shift. They hide again after the
  inactivity timeout. Controls stay while paused or hovered. No change made: keeping hidden controls in the tree would
  make screen readers announce invisible controls.
- **Motion options sliders.** The Frame rate and Quality sliders cannot be moved over JAB. Profiles are tested through
  saved preferences and the rate the engine reports.
- **Compose text fields.** They refuse JAB `setTextContents`, and a filled field takes its text as its accessible
  name. Its label stays in the description.
- **Not measured.** Whether opening a video on a host that resolves but does not answer blocks the UI while Windows
  waits for SMB. The `.invalid` host fails fast.
- **Not verified here.** "Holding N fps" is the rate the engine is configured to output, not frames measured on
  the display. HDR accuracy, physical VRR operation and real smoothness are not verified by any test.
