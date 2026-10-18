# VideoLibrary 0.3 source update

- Back from an open virtual folder now returns to virtual folders; back from player returns to video list.
- Virtual-folder keys end at the first comma or hyphen. Existing keys change after a Refresh scan.
- Added Media3 playback UI using SMB random-access reads, seek controls, double-tap seek (left -5s / right +10s), configurable long-press speed, Fit/Crop/Stretch options, basic resume, and Media3 default controller.
- Added best-effort Wi-Fi subnet SMB server discovery. Select a host, then enter the share name and credentials; the existing Keystore-based password storage remains.
- Updated GitHub Actions Android SDK package setup and added unit tests.

## Known limitations

- Not compiled or device-tested in this environment. GitHub Actions logs are the source of truth for compilation.
- Media3 built-in decoders may not support every MKV, Dolby Vision, DTS, TrueHD, or ASS subtitle format. Needs test media.
- No external subtitles, gesture brightness/volume, pinch zoom, or advanced track-management UI beyond default Media3 controls.
- LAN discovery scans up to two local /24 ranges for port 445. It does not discover share names automatically or work across all VPNs/subnets.
- No new manual virtual-folder move/merge tools yet.
- Touch gestures overlay may interfere with built-in player controls on some devices; separate playback buttons are provided.
- Scanning and thumbnail workers and local thumbnail cache remain as before.
