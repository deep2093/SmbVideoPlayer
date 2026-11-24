# Milestone 9 of 10 (M9) — Daily-use features: offline library, sleep and wake, phone sync, handheld shortcut, accent and Mica

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M9:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M9"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 11.4 items 1–5, section 11.2 items 9 and 12, finding B29.

**Do, in this order:**
1. **Browse while the drive sleeps** (item 1).
2. **Sleep and wake safety** (item 2), including smoothing: on resume, rebuild the motion graph cleanly and keep the watchdog from counting the sleep as a stall. The screen stays on while a video plays and may sleep again when paused.
3. **Phone sync** (item 3). **This needs an Android change: before touching `app/`, write down exactly what changes there and ask me.** Build the PC side behind a hidden setting first.
4. **Handheld launch shortcut** (item 4).
5. **Accent colour and Mica** (item 5). **Verify** Mica doesn't break the video window or the transparent overlays; if it does, title bar and dialogs only.

Test target: with the drive off, the library browses normally; a handheld slept mid-video for 30 minutes wakes paused at the same second, smoothing works after play, and no watchdog restart is reported.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8). No OneXPlayer 3 runs: the Claw stands in for both handhelds (owner, 25 November).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
