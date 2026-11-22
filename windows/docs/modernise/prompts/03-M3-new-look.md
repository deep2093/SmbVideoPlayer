# Milestone 3 of 10 (M3) — New look, adaptive layout, Settings, window size, accessibility (phase P3)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M3:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M3"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 6 (6.1–6.9), findings B1 and B33, and **look at every PNG** in `windows/docs/modernise/design/png/` (HTML versions in `design/html/`). The boards are the reference: match their structure, sizes and tokens; pixel-perfect isn't required. Board notes are in the 6.7 table. Use the `skywing-desktop-design` skill.

**Do:**
1. Design tokens (6.2) and components (6.3) in `ui/theme` and `ui/components`. Delete the old glass styles once nothing uses them.
2. `LocalAdaptive` with `WidthClass` and `InputMode` (6.6). Width changes keep scroll position and focus.
3. The app shell (6.4): rail 240 dp / 72 dp / top tabs by width; the new **Home** page (board "Desktop · Home").
4. Folders and Videos grids restyled with the ↑/↓ sort menu (board "Compact · Folders").
5. Settings two-pane with category list, search and breadcrumb, and the `go()` reselect fix (6.5, B1). The Playback page matches the board: GPU choice with the plan line, Screen and VRR, smoothing with the four stops, the **five**-level quality slider and the "Lower smoothing automatically" switch (off by default). Esc and the mouse back button go back.
6. Window size and position (6.8).
7. Accessibility (6.9, B33): icon-button descriptions, selected state on rail/tab rows, names and values for switches, sliders and fields. Check with Compose semantics tests and once with Java Access Bridge on the packaged app.

Keep working: fullscreen without recreating the video window, Unicode paths, alternate drive addresses.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
