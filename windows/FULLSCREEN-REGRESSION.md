# Windows fullscreen exit regression

## Diagnosis

At b3db78a4, F/Esc changed Compose WindowPlacement between Fullscreen and
Floating while a separate LaunchedEffect asynchronously rewrote the same HWND's
Win32 style. Compose/AWT exclusive fullscreen and the live borderless style
controller therefore competed for the frame lifecycle, bounds and decorations.
An exclusive/decorated transition may recreate a native peer, invalidating mpv's
Canvas wid even if the Java objects survive.

The old singleton also captured the entire frame style on its first call,
including the initial non-fullscreen effect, potentially before the window was
shown. Restoring that stale integer could clear WS_VISIBLE or overwrite live
AWT state. This explains a hidden frame with mpv still running. These are code
level failure mechanisms; the reporter's exact machine/GPU trace was unavailable.

## Fix

- Fullscreen has one owner per frame, runs synchronously on the EDT, and publishes
  Compose UI state after completion. Compose placement remains under its normal
  window management; no exclusive fullscreen, undecorated setter, hide/dispose,
  frame replacement or Canvas replacement is used for fullscreen.
- Snapshot bounds, decoration bits, extended state and native WINDOWPLACEMENT on
  each entry. The native placement retains a maximized window's normal restore
  rectangle. Only owned decoration bits are merged with the current style.
- Resize using AWT logical coordinates on the current monitor. Refresh Win32
  non-client layout with FRAMECHANGED and SHOWWINDOW, then validate/repaint/focus.
  Restore visible bounds if the original monitor disappears. No topmost mode.
- Repeated requests are idempotent. A failed entry attempts rollback; native errors
  are checked rather than silently publishing a successful fullscreen state.
- The stable SwingPanel call keeps the same Canvas across fullscreen/navigation.
  EmbeddedMpv refuses a changed Canvas HWND instead of silently using a dead wid.
- AWT handles Esc before text/modifier exclusions, consumes handled events, and
  debounces held F until release. Focus changes reset the latch. The dispatcher
  and focus listener are removed on disposal. mpv's default/VO keyboard bindings
  and user config are disabled so it cannot independently fullscreen/quit.
- The app closes mpv on an IO worker before disposing the parent. Polling and
  shortcuts stop while closing. Native calls/close are serialized; close is
  idempotent and permanently rejects delayed attach. A JVM shutdown hook and
  disposal fallback also release mpv. Native initialization failures release the
  created context.

## Automated verification

Run on Windows Java 17:

    .\gradlew.bat -p windows test --no-daemon
    .\gradlew.bat -p windows packageExe packageMsi --no-daemon

FullscreenWindowTest mounts the actual production Compose content in a live
ComposeWindow and exercises its AWT dispatcher through ten F/Esc cycles, including
held F. It checks restored bounds/decorations, native WS_VISIBLE, the same Java
Canvas and unchanged frame/Canvas HWNDs. It also checks maximized/idempotent
transitions and closed-player reattach rejection.
Another test launches the production application in a separate JVM, sends
WM_CLOSE only to a visible window belonging to that child process, and requires
exit within 20 seconds with code zero (no lingering background process).

Set SKYWING_TEST_MPV=true and SKYWING_TEST_MPV_DIR to the pinned libmpv DLL's
directory (not a generic library search path) to require real
libmpv initialization/playback. The test generates a 60-second animated GIF locally,
uses decoded video in the embedded output, checks playback across transitions, then
pause/seek/mute/resume and advancing playback, and destroys mpv before the
frame. It needs no audio endpoint on hosted runners. This checks native embedding
and video playback; it does not certify
hardware-decoded video, HDR, multi-monitor DPI or a particular GPU driver.

GitHub Windows CI runs this native path, publishes test reports even on failure,
builds EXE/MSI and verifies libmpv is in the app image. MSI identity, upgrade UUID,
bundling and Android code are unchanged.

## Manual acceptance on the reporter's machine

1. Install the new MSI, open a local video, and confirm picture/audio/timeline.
2. Press F then Esc at least twenty times while playing. Try both Canvas and
   controls focus, holding F, repeated Esc, and Alt-Tab out/back.
3. Start maximized, fullscreen, Esc, then unmaximize. Check the original normal
   size/position. Move to a second monitor, repeat at different scaling factors,
   and unplug that monitor during fullscreen.
4. After Esc, drag/resize/maximize the window, pause/resume, seek, change volume,
   audio/subtitle tracks, open another file, and navigate all library sections.
5. Close with X and Alt-F4 from both normal/fullscreen during playback. Confirm
   audio stops and no SkyWing process remains in Task Manager.
6. Repeat with the reporter's original video/GPU. Confirm no detached mpv window,
   hidden taskbar-only frame, black video or loss of keyboard control.
