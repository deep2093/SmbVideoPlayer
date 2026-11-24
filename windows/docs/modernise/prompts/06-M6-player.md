# Milestone 6 of 10 (M6) — The new player (phase P6)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M6:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M6"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 9 (9.1–9.3), findings B15, B21, B22, B23, B27, B32, B34, section 8.3 (player gestures), the boards "Desktop · Player" and "Handheld · Player", and `windows/FULLSCREEN-REGRESSION.md`. Keep the transparent owned overlays and the video HWND.

**Do:**
1. Observed mpv state (B21) if not done: no UI-thread mpv calls (debug assertion).
2. Resume via `loadfile ... start=` with an open-request token (B22, B34), plus the resume toast.
3. Close saves the final position first (B34); display change with no allowed stop turns motion off cleanly (B34); Mouse Back over overlays = Back (B34).
4. Cursor (B32): make the M0 still-mouse test pass.
5. The new layout (9.1): top bar, floating panel, volume, speed, smoothing chip and popover (four stops, five-level quality), chapter ticks, buffered range.
6. Seek-bar hover preview with a trickplay strip (paused while smoothing runs).
7. Animations (9.2) including the fade-through-black fullscreen transition; respect Windows animation settings.
8. Keyboard and mouse (9.1) with symmetric arrows (B27) and the text-field fix (B23); `?` sheet.
9. Touch gestures (8.3) and the lock button.
10. Picture in picture with the same mpv instance; Up next; remembered volume, mute and speed.

Test target: fullscreen in and out 20 times with a smooth fade, no flicker, motion heartbeat fresh; still mouse hides the cursor; typing in a player field never pauses or goes fullscreen.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8). No OneXPlayer 3 runs: the Claw stands in for both handhelds (owner, 25 November).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
