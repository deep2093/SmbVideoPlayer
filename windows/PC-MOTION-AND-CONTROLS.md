# Windows motion and fullscreen controls

## Implemented in this preview

- Direct **Smoothing** on/off button and **Motion options** on the video toolbar. The last blending choice is restored when supported by the current display; otherwise the toggle selects Auto. Settings and the player use the same options and persistence.
- Fullscreen title and controls appear on entry, mouse movement, clicks, wheel or keyboard activity; hide after 2.5 seconds of playback inactivity. Pause, seeking, hovering a toolbar and the motion dialog hold them open. Closing a menu or ending interaction gets a fresh grace period. F/Esc and the fullscreen button preserve the native video HWND. Navigating back leaves fullscreen.
- Controls sit above/below the native video surface, then collapse to give video the full screen. This intentionally resizes the same surface; it is not a transparent overlay over a recreated player.
- **G-SYNC / FreeSync** is an explicit original-motion preset: audio timing and frame blending off. It does not enable driver VRR or report that VRR is active. Enable the desired VRR policy in the graphics driver; borderless/windowed behavior depends on that policy and driver/compositor. The app does not change system-wide display or driver settings.
- **Auto** selects mpv temporal blending at the display's reported cadence. The 60/75/90/120 FPS choices require a matching active refresh mode (within 1%). They cannot generate independently targeted motion frames on a 240 Hz display. Use Auto with fixed refresh for this blending path.
- The toolbar reports frame blending only after libmpv accepts it and reports display sync. This is a configuration/sync indication, not a measurement of synthesized frames. Waiting/bypassed states remain visible and distinguishable from enabled blending.
- No mobile 1080p cap or downscale filter: 1440p, UHD and DCI 4K input use normal video rendering. The source dimensions are shown. This does not promise native-source-resolution processing when the window/display itself is smaller.
- Existing GPU-output, hardware-decode, source-rate and HDR checks remain. Sustained dropped frames bypass blending after warmup; pause, seek and cache waits restart warmup. Off/on or a new file retries. Ordinary playback and HDR policy remain intact.

The previous message “mpv temporal blending requested … display sync active” was a status message, not an error. A 240 Hz report does not establish G-SYNC activation, generated 240 FPS, or optical flow.

## PotPlayer reference and next interpolation phase

The supplied [PotPlayer guide](https://gist.github.com/edjdavid/8ad0445042c4ca4fa66e4055f2cbfc3b) uses AviSynth+ with MVTools motion estimation and motion-compensated frame synthesis. Its `potplayer_source()` entry point belongs to PotPlayer; hardware decoding/copy-back does not by itself move the MVTools motion analysis to the GPU. It is a reference for the desired effect, not a drop-in libmpv script. No code from the unlicensed gist was copied into SkyWing.

The equivalent candidate is libmpv's [VapourSynth streamed filter](https://mpv.io/manual/stable/#video-filters-vapoursynth) plus [VapourSynth MVTools](https://github.com/dubhatervapoursynth/vapoursynth-mvtools). mpv supplies `video_in`; seek reloads the script and timestamp/duration propagation must be preserved. Buffer and concurrency must be bounded, especially at 4K. MVTools is GPL-2.0-or-later according to its build metadata; pin the actual distribution and preserve the source/notices alongside SkyWing's existing licensing obligations.

For an NVIDIA laptop, a separate RIFE/TensorRT adapter is worth benchmarking. [SVP's RIFE guidance](https://www.svp-team.com/docs/rife-ai/) shows how demanding 4K synthesis can be; GPU model names or maximum panel refresh are insufficient to promise an output rate. RIFE wrappers, model weights, TensorRT and commercial SVP packages have distinct redistribution requirements. This preview bundles none of those engines and does not label frame blending as optical flow.

Concrete implementation sequence:

1. Add a versioned, optional engine interface that reports missing runtimes/plugins and fails back to ordinary playback. Verify the pinned libmpv build's filter support before exposing an engine as available. Start with MVTools for an open motion-compensation baseline; evaluate GPU RIFE separately.
2. Qualify **each input resolution independently**, including 1080p, 1440p and 4K. No permanent 1080p ceiling. Keep full-resolution processing as a distinct choice; any reduced processing resolution must be explicit rather than silent.
3. Benchmark 60 FPS first with a representative moving-object and scene-cut sample, then 75/90/120 when sustained decode, frame transfer, synthesis and presentation budgets permit. Auto selects a measured budget with headroom; respect display refresh/VRR range without targeting a 240 Hz panel maximum by default.
4. Test generated intermediate pixel positions, timestamps, audio sync, scene cuts, seek/cancel, queue changes, pause, dropped frames, heat and battery behavior. Gracefully reduce the target or bypass on sustained overload. A successful filter command is not proof of generated motion.
5. Validate 10-bit/HDR color metadata and output separately before enabling HDR interpolation. Preserve integrated libmpv, HWND ownership, MSI upgrade identity and Android.

## Validation

Automated coverage includes 240 Hz/1440p/4K policy cases, VRR bypass, timer boundaries, native decoded video, fullscreen auto-hide/reveal, pause visibility, unchanged HWND, repeated F/Esc, UTF-8 names, seek/mute/close and existing library/artwork tests. CI builds MSI/EXE and verifies the bundled native/legal payload. A software GIF fixture proves native control/lifecycle behavior, **not** 4K GPU performance or G-SYNC activation.

Physical acceptance: test the new toolbar in windowed/fullscreen at 100/150/200% scaling; keep it open while hovering, seeking or using Motion options; check mouse and every keyboard shortcut wake it. Compare Off, G-SYNC original motion and Auto/fixed-refresh blending on local and SMB 1440p/4K SDR clips for ten minutes. Record dropped frames, A/V sync and the selected driver mode. Test HDR ordinary playback, monitor changes, Alt-Tab and in-place MSI upgrade with existing library data. Real motion-generation performance remains outstanding until an engine passes the sequence above.
