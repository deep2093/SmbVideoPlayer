# Phase 2B: Embedded libmpv spike

Status: experimental source integration; NOT yet a self-contained video release.

- Isolated feature branch `feature/windows-phase2b-embedded-player` from the last user-validated external-mpv build.
- New `EmbeddedMpv` calls native libmpv via JNA and hosts mpv's child window in a Windows AWT Canvas HWND (`wid` option). This uses native mpv as a library instead of launching mpv.exe.
- Built-in mpv render API (D3D11/OpenGL) may eventually be preferable for HDR, high-DPI resizing, device loss and Composable overlays. Verify the child-window approach before choosing production architecture.
- Do not remove `LocalPlayback` or the user's stable preview until native drawing, playback controls, window resizing and shutdown are tested on Windows x64.
- Native binaries and their licence notices MUST be bundled before claiming this works on machines with no mpv installed. At present no libmpv DLL or FFmpeg dependencies are included. CI compilation does not demonstrate runtime playback.
- Validation gates: native libmpv loads from the installer, video appears INSIDE Skywing, repeated open/close doesn't crash, subtitles/audio switching, seek, stable fullscreen, multiple monitors, HDR and SDR later. Validate that Win10 x64 supports the selected binaries.
- Windows MSI upgrade identity/versioning remains in the parent branch; no Android code changes.
