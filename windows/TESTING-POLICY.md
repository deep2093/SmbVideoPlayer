# SkyWing Windows testing policy

This is the single testing policy for every assistant and person working on `windows/`: Claude Code, Codex and
humans. `AGENTS.md` (read by Codex and, through `CLAUDE.md`, by Claude Code) points here. The skills in
`.agents/skills/` and `.claude/skills/` refer to this file instead of repeating it. Change the rules here only.

The runner is `windows/tools/verify.ps1`. Its change-to-test table must match the table below; update both together.

## 1. Rules

1. **Test what you changed, then widen by risk.** Run focused mode first. Run full mode when section 3 says so.
   Run release mode before any package is given to someone else.
2. **Never report success you did not observe.** A test that did not run is not a pass. Say "not run" and why.
3. **Missing prerequisites block; they never skip.** Native and UI tests use `assumeTrue` and skip silently when
   their environment variables are absent. A skipped test in a required class is a failure of the run, not a pass.
   `verify.ps1` sets the variables itself and fails a run with any skipped test.
4. **Ask before taking the desktop.** UI automation, full-screen and native playback tests open windows, take focus
   and go full screen. Tell the user before running them. `verify.ps1` refuses (BLOCKED) without
   `-AllowDesktopFocus`. Do not pass that switch until the user has been told.
5. **Protect the user's machine.**
   - Tests use `IsolatedPreferencesFactory` and temporary `skywing-*` folders. Never point a test at the real
     preferences, the real library or a real share.
   - The test JVM itself is isolated too: `tasks.test` in `build.gradle.kts` installs the file-backed preferences
     factory and redirects `LOCALAPPDATA` and `APPDATA`. App code rendered in-process (for example
     `SkyWingWindowContent`) therefore cannot run a library sync against the user's folders and shares.
     `TestIsolationTest` fails if that set-up is lost; focused runs always include it. A child app process a test
     starts must get the same isolation (see `SkyWingTestApp.launch` and
     `FullscreenWindowTest.closingProductionApplicationLeavesNoBackgroundProcess`).
   - `verify.ps1` fingerprints `HKCU\Software\JavaSoft\Prefs\com\skywing` and `%LOCALAPPDATA%\SkyWing` before and
     after, and fails if either changes or if a `skywing-*` temp folder or test process is left behind.
   - The only network path used is the read-only loopback admin share `\\localhost\C$`. Never write to an SMB share
     or to personal media.
   - Do not install, uninstall or upgrade SkyWing, an MSI or any other software, and do not change Windows settings
     (accessibility, display, Smart App Control), without the user's explicit approval for that action.
   - `SKYWING_TEST_MPV_DIR` may point at the installed `C:\Program Files\SkyWing\app\resources`; tests only read it.
6. **Do not change release identity.** Never change the MSI `upgradeUuid`
   (`f40c59ef-e520-4e10-b7a2-b86e7458f923`), the pinned native hashes or the legal notices to make a check pass.
7. **Write the report.** Every verification ends with the report in section 6, whatever the outcome.
8. **Commit, push, publish and release only when the user asks.**

## 2. Prerequisites

| Prerequisite | Needed by | How it is checked |
|---|---|---|
| Windows x64, interactive desktop session | everything | `PROCESSOR_ARCHITECTURE`, `UserInteractive` |
| JDK 17 (`JAVA_HOME` or `PATH`) | everything | `java -version` |
| `libmpv-2.dll` in `SKYWING_TEST_MPV_DIR` | native and UI tests | file exists. Default: `windows/build/native-resources/windows`, where the CI workflow puts the pinned libmpv (for a local setup see `FULLSCREEN-REGRESSION.md`); or the installed `app\resources` |
| Vulkan loader `vulkan-1.dll` | native and UI tests | System32 or the libmpv folder. The pinned libmpv imports it; GPU drivers install it. A PC without it cannot load libmpv |
| `ffmpeg` on `PATH` | `MotionAutomationTest`, `EmbeddedPlaybackNativeTest` | `Get-Command ffmpeg`. Builds the multi-track MKV and 24 fps fixtures |
| `\\localhost\C$` readable | `NetworkAndStabilityAutomationTest` | `Test-Path`. Plays a fixture over SMB loopback, read-only |
| Packaged app image | UI automation tests | `verify.ps1` runs `createDistributable` first |
| `.claude/skills` identical to `.agents/skills` | always | file hashes. Copy `.agents/skills` over `.claude/skills` after editing a skill |

Environment variables used by the tests (set by `verify.ps1`):
`SKYWING_TEST_MPV=true`, `SKYWING_TEST_MPV_DIR=<folder with libmpv-2.dll>`, `SKYWING_TEST_AUTOMATION=true`.

## 3. What a change must run

Focused mode selects these classes from the files changed in the working tree (`git diff HEAD` plus untracked
files; add `-Base <ref>` for committed branch work). A changed test class runs itself; a changed test helper runs
every test class that uses it. "Full" means a passing focused run is not enough: full mode is required.

| Area | Files | Focused tests | Then |
|---|---|---|---|
| Playback / libmpv | `playback/EmbeddedMpv`, `LocalPlayback`, `ThumbnailExtractor`, `PlayerPage`, `PlaybackProblemDialog`, `LocalVideoDropHandler` | `EmbeddedPlaybackNativeTest`, `PlaybackAutomationTest` | Full |
| Motion / smoothing | `playback/Motion*`, `Smoothing`, `RecentDrops`, `DisplayVrr`, `WindowsDisplayAdapter`, `SmoothingDialog`, `tools/build_motion.py`, the open-svpflow patch | `MotionEngineTest`, `SmoothingTest`, `MotionNativeTest`, `MotionAutomationTest` | |
| Full screen / window chrome | `BorderlessFullscreen`, `FullscreenChrome`, `PlayerChrome`, `WindowChrome` | `FullscreenWindowTest`, `PlayerChromeTest`, `FullscreenAutomationTest` | Full |
| App shell | `Main.kt` | full screen, playback and navigation tests | Full |
| Accessibility / shared UI | `SkyUi`, `SkyTheme`, `AdaptiveTheme`, `SkyIcons`, `GridSelection` | `AccessibilitySemanticsTest`, `SkyUiLogicTest`, `AccessBridgeTreeTest`, `SkyWingAutomationTest` | |
| Library | `LibraryModel`, `LibrarySync`, `LocalFolderLibrary`, `WindowsLibraryData`, `VideosScreen`, `VideoViews`, `FoldersScreen`, `FolderOrganizer`, `FolderPickers`, `Thumbnails`, `Covers`, `VideoRename`, `ChangeCoverDialog` | `FolderOrganizerTest`, `WindowsMediaIndexScaleTest`, `HubBackNavigationTest`, `LibraryAutomationTest`, `SkyWingAutomationTest` | |
| SMB / shares | `ShareHosts`, `WindowsShareBrowser`, `WindowsShareProfileStore`, `FilesScreen` | `WindowsShareBrowserTest`, `PortSettingsTest`, `NetworkAndStabilityAutomationTest` | |
| Settings | `SettingsScreen`, `ArtworkSettingsPage` | `HubBackNavigationTest`, `SkyWingAutomationTest`, `LibraryAutomationTest` | |
| Artwork / matching | `artwork/*`, `matching/*`, `EmbeddedGoogleImages` | `ArtworkTest`, `PerformerMatcherTest` | |
| UI automation framework | `automation/SkyWingTestApp`, `AccessBridgeClient`, `ControlQuery`, `TestMedia`, `src/test/java/*` | every class that uses the helper | Full |
| Installer, packaging, native payload, legal | `build.gradle.kts`, `legal/*`, `LICENSE`, `tools/build_mpv_preview.sh`, the mpv preview patch, the Windows workflow, `tools/compare_builds.py` and its test, Gradle wrapper | all off-screen unit tests | Full, then **release** before shipping |
| Python build tools | any `windows/tools/*.py` (also in full and release mode) | `python -I -m unittest discover -s windows/tools -p "test_*.py"` | |
| Anything else in `src/main` | not in the table | all off-screen unit tests | Full |
| Documentation, skills, instructions | `*.md`, `.agents/`, `.claude/skills/` | none | |

Changes in three or more areas also require full mode.

Release mode is required before giving anyone an installer, after any change to the installer, payload or legal
inputs, and before any statement about release readiness.

## 4. Running it

From the repository root, in PowerShell:

```powershell
# What would run, and are the prerequisites present? Builds and runs nothing.
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -ListOnly

# Focused tests for the current changes (tell the user first if the plan includes desktop-focus tests).
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -AllowDesktopFocus

# Focused tests for named files only.
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -Paths windows/src/main/kotlin/com/skywing/desktop/playback/Smoothing.kt -AllowDesktopFocus

# The whole suite with native libmpv and UI automation (takes the desktop).
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode full -AllowDesktopFocus

# Full suite, then packageExe/packageMsi and the release gates (builds installers, installs nothing).
powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode release -AllowDesktopFocus
```

Options: `-Paths` (explicit changed files), `-Base <ref>` (add committed changes since a ref), `-MpvDir <folder>`
(libmpv folder; default `SKYWING_TEST_MPV_DIR`, then `windows/build/native-resources/windows`).

Verdicts and exit codes:

| Exit | Verdict | Meaning |
|---|---|---|
| 0 | PASS | every selected test ran and passed, isolation held, no broader mode is required |
| 0 | NO-TESTS | only documentation or instructions changed |
| 0 | PLAN | `-ListOnly`; nothing was built or run |
| 1 | FAIL | a build, a test, an isolation check or a release check failed, a selected class did not run, or a test was skipped |
| 2 | BLOCKED | a required prerequisite is missing, or desktop focus was not allowed |
| 3 | INCOMPLETE | the selected tests passed, but the policy requires a broader mode, or public-release gates are open |

Each run writes `windows/build/reports/verification/verify-<mode>-<time>.md`. JUnit XML is in
`windows/build/test-results/test/`.

Release mode also checks:
- the 16 legal notices
- libmpv against `NATIVE-PROVENANCE.json`, or the experimental payload against `MPV-PREVIEW-MANIFEST.json`
- the motion payload hashes, and that no local plugin override is present
- the MSI `UpgradeCode`
- that `SkyWing-<version>.msi` and `.exe` for `0.1.$GITHUB_RUN_NUMBER` (default 0.1.1) were built by this run, with
  that ProductVersion
- whether the version is above the default local 0.1.1
- the MSI platform (x64) and per-machine install
- the file associations listed in `build.gradle.kts`, each with an Open command
- the Start menu and optional desktop shortcuts
- that the MSI holds exactly the files of the tested app image, by SHA-256 after a read-only WiX `dark` extraction
  (the manifest-only `skiko-awt-runtime` jar that Compose regenerates per package, and `SkyWing.cfg` that names it,
  must match in content)
- the bundled Java 17 runtime, and that `SkyWing.exe`, `libmpv-2.dll` and `jvm.dll` are x64
- the Authenticode signatures
- whether `legal/SOURCE-STATUS.md` is still incomplete

Signing, the source audit and the local plugin override are reported as public-release **blockers**: they make the
verdict INCOMPLETE, not PASS. The Vulkan loader is reported as a **warning**: `legal/SOURCE-STATUS.md` records that
it is not shipped, so libmpv relies on the one a GPU driver installs. The script never certifies a release and never
installs the packages it builds. `packageExe` and `packageMsi` empty their output folders first, so copy any earlier
EXE or MSI you still need out of `windows/build/compose/binaries/main/` before a release run.

## 5. What automation cannot verify

Report these as not verified unless they were checked by hand on suitable hardware:
- HDR output, tone mapping and Dolby Vision
- VRR activation
- displayed frame pacing (`Holding N fps` is the configured output rate, not presented frames)
- multi-monitor behaviour
- real Alt+Tab between applications
- a real NAS, credentials, slow links and a dropped SMB connection
- fresh install, upgrade and uninstall on a clean PC
- first launch with the user's real library

Installing or uninstalling an MSI needs the user's approval and a UAC prompt that only the user can answer.

## 6. Final report

Every verification ends with this, in the chat and in any written report:

1. **Tests executed:** mode, command, classes or suite, and the report path.
2. **Results:** totals for tests, passed, failed and skipped, with the name and first line of each failure.
3. **Not run or skipped:** what, and why (missing prerequisite, desktop focus not allowed, out of scope).
4. **Limitations:** what this machine and these tests cannot show (section 5).
5. **Risks:** what could still be wrong, and what the user should check by hand.
6. **User data:** whether real preferences, the library and installed SkyWing were left untouched.
