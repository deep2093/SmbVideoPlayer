# Skywing Windows Phase 2 release gates

## Current preview
Embedded libmpv is the default. Legacy external mpv is retained temporarily for troubleshooting. Installer CI verifies that libmpv-2.dll exists in the app image, but this does NOT certify all transitive native dependencies or clean-PC functionality.

## Smart App Control and trusted distribution
- Do **not** ask users to disable Microsoft Defender, SmartScreen or Smart App Control.
- Investigate the exact blocked executable or DLL using Windows Security / Protection History and Event Viewer, then inspect its publisher signature and SHA-256 hash.
- Release builds should be signed using a trusted Windows Authenticode code-signing certificate and RFC3161 timestamping. Sign built EXE/MSI and any distributable binaries we own. Third-party binaries require legitimate upstream trust; never misrepresent authorship.
- Do not place any signing certificates, PFX passwords, private keys or tokens in the public repository or logs. Use approved GitHub Actions secrets / secure signing infrastructure.
- Fresh unsigned preview builds may remain blocked by Windows Smart App Control even when free of malware. Packaging itself does not remove that protection. A signing certificate may improve publisher identity but is not a guarantee against all reputation policies.
- Validate signing with `Get-AuthenticodeSignature` and Windows SDK `signtool verify /pa /v`, then test installation and launch with security defaults on a clean Windows 10 and Windows 11 x64 machine.

## Playback release checks
1. Clean Windows VM: no separate mpv, Java, or extra PATH configuration.
2. Install MSI without prompts for runtime prerequisites; open video in embedded Skywing area.
3. Verify pause, seek, stop, repeated load, resizing, multi-monitor/fullscreen.
4. Test MP4/H.264, MKV/HEVC, 4K, unusual filenames and files with audio/subtitle tracks.
5. Confirm installer upgrade preserves application settings.
6. Inspect package for relevant license and copyright notices from mpv, FFmpeg and native dependencies; comply with their actual redistribution licenses.
7. Test Smart App Control / SmartScreen without weakening system protections.

## Roadmap
- Embedded timeline with accurate duration/position tracking and frame hover thumbnails.
- Fullscreen, keyboard shortcuts, track selection, drag-and-drop and failure recovery.
- Replace legacy external player only after packaged native playback passes release gates.
- HDR10, Dolby Vision, SMB and debrid are separate later milestones.

## Verification update (2026-10-25)

The owner confirmed playback on a second PC without a separate mpv install.
Windows displayed an unknown-publisher warning before installation. This passes
the reported no-separate-mpv smoke check; it does not certify signing, an
otherwise-clean Java environment, every supported Windows version or all native
dependencies. Fullscreen restoration, closing, switching videos, maximized
windows and Alt-Tab passed on the owner's PC. The owner also confirmed audio/
subtitle switching and MP4/MKV/HEVC playback.

Explorer Open With registration and drag-and-drop remain missing. Opening a
video using SkyWing's file picker is not an Explorer association test.

Windows application licensing is now GPL-3.0-or-later; see LICENSING.md.
Packaging preserves legal files and runtime hashes, and CI verifies their
presence. **The licensing release gate remains open** pending the corresponding
native dependency sources and remaining component notices in
legal/SOURCE-STATUS.md. Do not call this a completed distribution audit.
