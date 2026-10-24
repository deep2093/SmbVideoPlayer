# SkyWing Windows x64: Project Plan
**Status:** Foundation / Milestone W0
**Target:** Windows 10 and Windows 11 on x86-64 (Intel/AMD). No x86, ARM32, or ARM64 binaries.
**Baseline:** `feature/skywing-brand-and-glass`; retain original Android `app/` untouched.
**Release strategy:** Developer Preview -> Alpha -> Beta -> v1.0. No merging until Windows build/test verification.

## Product goal
Native-feeling, hardware-accelerated Windows media player for local files, SMB video shares and optionally direct debrid playback. Match SkyWing's library concepts rather than copying its phone layout. Responsive 4K playback, correct subtitles/audio tracks, watch history, virtual/custom/original folders, fast indexing and seek, HDR10/HDR10+/HLG, and best-effort Dolby Vision content processing with clearly stated output limits.

## Existing repository audit (Oct 25, 2026)
- Android is a Kotlin/JVM 17 Gradle 8.11.1 project with Compose, SMBJ 0.14.0, Room, WorkManager, Media3 ExoPlayer 1.6.1 and LibVLC 3.6.5.
- The SkyWing feature branch adds desktop-relevant settings layout, theme work and player-control styling; it is ahead of `main`.
- Android `LibraryViewModel`, Android Room entities, Compose Android UI, WorkManager, native libVLC and WireGuard must NOT be imported directly into desktop. Pure Kotlin rules (folder grouping, path/host helpers, model transformations) can be extracted behind tests.

## Target architecture
- `windows/`: standalone Kotlin 2.1.21 / Compose Desktop 1.8.2 build. Deliberately not added to root `settings.gradle.kts`, so Android workflows are unaffected.
- `shared-core/` (later): pure JVM-compatible models, grouping/sort/search/playlist rules with JVM tests; extracted only after tests show same Android behavior.
- `windows/source/` (later): LocalFileSource; SmbSource using SMBJ or Windows UNC; DebridSource via provider APIs, with secrets protected using Windows Credential Manager/DPAPI.
- `windows/library/` (later): desktop SQLite-backed per-profile library, versioned migrations, indexed scanning, secure metadata, cover cache. No direct reuse of Android Room database.
- `windows/player/` (later): native libmpv x64, GPU rendering and hardware decode; abstract player controls, events, tracks and seeking independently of library sources.
- SMB->libmpv access spike: benchmark UNC on a native SMB session, or a secure read/seek bridge (mpv stream callback; alternatively authenticated loopback HTTP byte ranges). Do not expose SMB passwords in media URLs. Never bind a proxy to an external interface.
- Leave Android APK, Android signing, themes, and Android CI unchanged until a shared-core extraction is independently tested.

## Milestones and acceptance gates
| ID | Scope | Rough effort (one experienced developer) | Gate |
|---|---|---|---|
| W0 | Isolated Windows x64 skeleton, roadmap, versioned dependencies | 1-3 days | Desktop UI builds/runs on a Windows x64 machine; Android build unchanged |
| W1 | libmpv x64 binding, video surface, local-file open, play/pause/seek, audio/subtitle switching | 1-2 weeks | Local MP4/MKV/HEVC plays correctly; no leaks/crashes on repeated open |
| W2 | SMB connection profiles, alternate addresses, secure credentials, directory browsing, streaming seek | 1-3 weeks | Seek and resume from a real SMB share; no full-file download |
| W3 | Indexed original/custom/virtual folders, search, thumbs, queue, history, desktop UI/shortcuts | 2-3 weeks | Folder grouping parity tests, 10k+ library indexing and restart persistence |
| W4 | HDR10, HLG, HDR10+ support and SDR tone mapping, 10-bit output options | 1-2 weeks | Correct SDR/HDR playback on SDR+HDR screens, no forced clipping or crushed blacks |
| W5 | Dolby Vision profiles 5/7/8 handling via libmpv/libplacebo, fallbacks and user diagnostics | 1-3+ weeks | Profile-specific matrix validated. Distinguish DV metadata processing from native DV passthrough |
| W6 | Optional debrid providers using user-authorized accounts, direct HTTPS URL playback, expired-link recovery | 1-2 weeks | Authorized files can be browsed, played, sought, resumed, canceled; no keys in logs |
| W7 | Windows EXE/MSI packaging, x64 bundling, licensing audit, crash logs, CI/manual QA, beta | 1-2 weeks | Fresh Win10/Win11 x64 installs tested, rollback and update strategy documented |

**Estimated first playable local-file preview:** around 1-2 weeks after W0, subject to native video integration.
**Estimated SMB-capable alpha:** around 3-6 weeks.
**Full HDR/Dolby feature-rich beta:** around 8-14+ weeks. These are planning estimates, not promises.
W6 can run separately from W4/W5 once W1 source abstraction is stable.

## HDR and Dolby Vision implementation contract
- First target HEVC Main10 HDR10 (PQ/BT.2020), HLG, HDR10+ metadata-aware tone mapping, and correct 8-bit SDR conversion.
- Use libmpv `gpu-next` renderer/libplacebo and benchmark D3D11 hardware decoding (`d3d11va`), swapchain HDR output, GPU vs CPU, renderer integration with Compose Desktop.
- Respect actual Windows HDR mode, display capabilities, ICC/SDR white level, and changes when moving between monitors. Provide automatic fallback and an optional diagnostics view (input metadata, decoded profile, output mode, GPU renderer, dropped frames).
- Dolby Vision P5/P7/P8 are **test categories, not guaranteed full feature support**. Processing DV metadata/reshaping and rendering to HDR10 PQ or SDR is not native licensed Dolby Vision output. P7 dual-layer/MEL/FEL handling varies by decoder, demuxer and renderer. Do not label output as true DV passthrough without proof.
- Separate playback success, fidelity, HDR output mode, and metadata usage in reporting; include an unknown/unsupported path rather than presenting false quality badges.
- Tone mapping: dynamic to SDR; optional user-adjustable algorithm and peak brightness after calibrated defaults. Do not blindly force HDR when Windows HDR is off.
- Validation media: SDR 1080p H.264; 4K HEVC Main10 HDR10; HDR10+ scenes; HLG; DV P5; DV P7 MEL/FEL; DV P8.1, malformed files; surround audio, SRT/ASS/PGS; long seeks; bitrate spikes; mixed SDR/HDR monitors.
- Collect objective video output metadata and manual visual checks against trusted references, with no unsupported claims about certified Dolby Vision.

## Reliability, security and performance gates
- No SMB/debrid passwords/tokens in log output, playback URLs, clipboard, exports or analytics.
- Network operations async and cancellable; bounded thumbnail work/memory; debounced UI scans; explicit offline/disconnect states; no automatic destructive SMB file operations.
- Streaming seeks should not download entire video; retry only safe transient failures; cancellation closes handles.
- 4K HEVC test on hardware with HDR monitor and fallback tests on SDR monitor.
- Monitor startup times, library DB size, RAM and CPU, GPU hardware decode, dropped frames, 4K SMB seek latency. Record hardware/network specs with results.
- License review of mpv, libplacebo, FFmpeg, optional third-party binaries and redistribution terms; supply notices and source obligations.
- Windows 10 support must be tested, but note regular Microsoft support ended in Oct 2025.

## First build tasks (next after W0)
1. On an x64 Windows machine with JDK 17, run `..\gradlew.bat -p windows run` from repository root or `..\gradlew.bat run` inside `windows`. Fix any first-run compile/plugin incompatibilities.
2. Package starter desktop app with `..\gradlew.bat -p windows packageMsi` or `packageExe` on Windows; test actual architecture.
3. Implement native libmpv x64 integration spike, compare embedding vs D3D11 render API on a separate test branch before coupling it to SkyWing UI.
4. Add local-file-open test; then prototype SMB seeking; only then extract shared Kotlin library rules.
5. Add Windows CI on a Windows runner only after a local Windows build succeeds; do not consume Android-specific jobs needlessly.

## Excluded from initial scope
Windows ARM, x86 32-bit, Windows 7/8, embedded app-only WireGuard, native licensed Dolby Vision bitstream output, cloud sync across devices, and automatic ripping/content discovery.

## Decision record
- Desktop UI: Compose Desktop (JVM), first-version tech baseline Kotlin 2.1.21 / Compose 1.8.2 / JDK 17.
- Player: libmpv x64; native D3D11 render path must be proven before production.
- Storage: SQLite for desktop indexing; secure OS credentials; reuse tested pure-Kotlin rules, not Android UI or Room.
- Deliverables: Windows-x64 native installer, portable artifact if practical, separate versioning from Android, a tested HDR/DV support matrix.
