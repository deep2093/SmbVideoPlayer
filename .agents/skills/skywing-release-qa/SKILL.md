---
name: skywing-release-qa
description: Verify SkyWing Windows EXE/MSI release readiness, Kotlin/Gradle tests, native libmpv and motion payloads, packaging, installation upgrades, licence notices, file associations, security and smoke tests.
---

# Windows packaging and release gate

1. Read `windows/RELEASE-READINESS.md`, `windows/LICENSING.md`, `windows/legal/SOURCE-STATUS.md` and current `.github/workflows/skywing-windows-x64.yml`.
2. Check that the change is Windows-scoped and does not modify Android packaging or dependencies without explicit authorisation.
3. Use JDK 17 and Windows x64. Run `.\gradlew.bat -p windows test --no-daemon --stacktrace`; then `.\gradlew.bat -p windows packageExe packageMsi --no-daemon --stacktrace` for packaging-related changes.
4. Verify the bundled libmpv DLL, motion payload and related manifests/hashes, runtime legal notices, source provenance and package output. Do not alter or regenerate the existing MSI upgrade UUID.
5. Check installation, in-place upgrade and uninstall on a Windows test device; Start menu shortcut, optional desktop shortcut, supported file associations, first launch, media playback and app shutdown. Only report checks performed, never imply untested MSI acceptance.
6. Verify regression matrix: local and SMB files, MP4/MKV, subtitle/audio track switch, 1080p and 4K playback, fullscreen entry/exit, focus-free shortcuts, overlay interaction and no lingering process/audio after close.
7. Treat native dependency provenance and corresponding-source obligations as release gates. Do not declare commercial or open-source release compliance without reviewing them.
8. Summarise CI results, binary paths, install/upgrade outcomes, unresolved failures and release/no-release recommendation with evidence.
