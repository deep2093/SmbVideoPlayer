# 4K SMB playback test

Use the same 3840×2160 H.264, 29.97 fps file and Wi-Fi connection used for VLC.
The attached VLC information shows approximately 25.8 Mbps demux / 28.5 Mbps input.
The WhatsApp recording is a compressed screen recording, not the original 4K file.

1. Enable Settings → Diagnostics before opening the video. Play for 10 minutes,
   including a busy scene. Note initial startup time and any pauses after playback starts.
2. Seek forward and back, then pause while buffering. Playback should stay paused
   until Play is pressed. Try another file and playlist auto-advance; resume should still work.
3. Let playback run longer than the device screen timeout without touching the display.
   The screen should stay on. Pause, finish, or exit playback; normal timeout should return.
4. Pinch anywhere in the unobstructed video area, including across its center.
   Zoom should change continuously between 1× and 4×. Playback options → Reset zoom
   returns to Fit / 1×. Aspect changes and playlist transitions also reset zoom.
5. Check bottom black/red toolbar, seek slider, double-tap ±10 seconds, horizontal
   seek, left brightness, right volume, long-press speed, audio/subtitles and optional PiP.
6. Disconnect wired/Bluetooth headphones during playback; it should pause.
   Interrupt playback with another audio app or a call; verify audio focus behavior.
7. If playback stalls, exit and export Diagnostics. Reports include decoder identity,
   dropped frames, buffering wait and buffered duration, plus aggregate SMB bytes,
   network read time and request counts. They omit file names, paths and credentials.

## Implementation boundaries

Read-ahead uses one 1 MiB block and accepts smaller server responses. It is cleared
on open/seek. Decoder reads are served from that block rather than issuing a network
request per small read. Reconnect retries start at the next undelivered byte.
Media3 targets 15–30 seconds of buffering, starts at 2.5 seconds, and requires
5 seconds after rebuffering, with a 32/64/96 MiB sample-buffer target selected from the device memory class. At about 28 Mbps,
the byte target may stop loading before 30 seconds. These settings do not guarantee
sustained throughput on every server or Wi-Fi network.

TextureView enables reliable pinch scaling; 4K rendering performance and power use
must be checked on the actual device. Hardware decoding remains the normal choice
with existing decoder fallback; SMB signing/encryption behavior is unchanged.

CI runs the existing search benchmark, all JVM unit tests, Android lint, and a debug
APK build on pull requests. Download the APK and validation reports from Actions.
A successful build does not verify device gestures, hardware decoding or real SMB throughput.
Do not merge the draft PR until the user has tested the APK.


## v0.11 checks

- In Custom folders, select nested folders, all siblings, and a complete subfolder tree. Include a location outside the starting folder and verify scan membership. Toggle inclusion of descendants. Rename/remove the collection and confirm drive folders are unchanged.
- Scroll Virtual folders well down the list, open a folder, and return. Check the same visible card and pixel offset. Repeat with Custom and Original folders, search, and playback.
- Switch account/root/share, then switch back. Check independent artwork, assignments, queue jobs and resume state. Export organization, restore it into the same library, and reject a backup from another library.
- Exercise copy/move/delete with process interruption on a disposable share. Verify the recorded server identity, one final publication, and preserved assignments/history.
- Try Play all with more than 200 items and confirm advance across queue batch boundaries in Media3 and VLC.
- Test the fixed tablet sidebar and Scan/Settings at large font sizes and in split-screen.

Run `./gradlew :app:assembleOptimized :app:assembleProfile :benchmarks:assembleOptimized`. Install the optimized app and benchmark APK for StartupBenchmark. Then install the profile app over it for GenerateBaselineProfile; that non-debuggable variant keeps original method names so captured rules remain valid before R8. The workflow performs these steps automatically. Keep startup/frame reports alongside device model, Android version, refresh rate and library size. Emulator timing is not a performance guarantee.
