# SkyWing repository guidance for Codex

This repository contains two separate applications:
- `app/`: SkyWing Android.
- `windows/`: SkyWing Windows x64, a Kotlin/JVM 17 + JetBrains Compose Desktop application.

**Scope changes to the application the task names.** A Windows request must not modify Android code, Android Gradle files, or Android workflows unless the user explicitly requests it. Never assume a previous conversation, cached project plan, or a different branch reflects the current code. Read the working tree, branch and relevant implementation before acting.

## Windows-specific ground rules
- Read `windows/README.md` and the relevant current documentation before implementation. For playback and smoothing consult `windows/MOTION-ENGINE.md`; for fullscreen consult `windows/FULLSCREEN-REGRESSION.md`; for parity, packaging and release consult the corresponding Windows documents.
- Keep the existing Kotlin/Compose Desktop architecture. Do not substitute WinUI/WPF/React/Electron approaches without a deliberate architecture decision.
- Embedded libmpv is hosted using JNA and an AWT/Win32 video surface. Preserve the video surface and native HWND across navigation and fullscreen; do not introduce competing window-placement owners or unsynchronised native player teardown.
- For motion smoothing, distinguish generated-frame throughput, mpv drop counters, actual display presentation and perceived visual quality. Do not describe one as proof of another. HDR/Dolby Vision claims require genuine device validation.
- Preserve existing per-file settings, migration behaviour, fallbacks, source provenance, native payload hashes, packaged legal notices and the Windows MSI upgrade identity.
- Avoid unrelated refactors. Trace usages and existing tests before changing public interfaces or input/playback state.

## Verification contract
- Start by stating the expected behaviour and a way to reproduce a defect or verify a feature.
- Add or amend focused tests where feasible. Use a failing regression test first for reproducible bugs.
- On a Windows x64 host with JDK 17, normal checks are:
  - `./gradlew.bat -p windows test --no-daemon` (PowerShell: `.\gradlew.bat -p windows test --no-daemon`)
  - `.\gradlew.bat -p windows packageExe packageMsi --no-daemon` for packaging changes.
- Native playback tests require the pinned `libmpv-2.dll` and environment described in `windows/FULLSCREEN-REGRESSION.md`. Report missing prerequisites rather than silently skipping mandatory assertions.
- Manually test playback, keyboard focus, fullscreen exit, Alt-Tab, close/shutdown and navigation on the actual target machine for UI/player changes. Hosted CI cannot certify local hardware HDR, VRR or GPU behaviour.
- When finished, report changed files, tests run and their outcomes, things not tested, remaining risks and any manual acceptance steps. Never claim a pass without running the test.

## Project-specific skills
Relevant repo-local skills are in `.agents/skills/`: `skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance` and `skywing-release-qa`. Invoke one explicitly when it fits the task, or let Codex select by its description.

These files do not install third-party Codex plugins or modify the user's local Codex environment. See `.agents/README.md` for optional local installation commands.
