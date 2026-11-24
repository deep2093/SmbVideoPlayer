# Milestone 1 of 10 (M1) — Speed foundation (phase P1)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M1:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M1"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 4 (P1), and findings B7–B11, B13, B16–B20, B24, B25, B29 and B34 (UI blocking) with their fixes.

**Do:**
1. Nothing heavy on the UI thread (4.1): history and folder-store reads out of composition (B8), library maps built off the UI thread and cached (B9), async cursor hiding and every other UI → mpv call (B7, B34).
2. Pictures decoded at the size they're shown, with a memory-bounded cache (4.2, B10).
3. A cheap static background instead of the animated full-window gradients (4.3, B11).
4. Start-up and memory: release build, ProGuard, AppCDS, lazy JCEF (4.4, B13). **Verify** JCEF only loads when the Google Images tab opens. Keep `jdk.accessibility` in the runtime.
5. The performance overlay, Ctrl+Shift+F12 (4.5).
6. **Library safety (B16–B18):** in-memory index behind a Mutex, "missing" vs "unreadable" handled separately, `.bak` restore, never write in the error state, and a unit test proving a corrupt index never shrinks the library.
7. **Faster scans (B19):** `Files.walkFileTree` with the listing's attributes, no per-file stat.
8. **Stores off the registry (B20):** JSON files with atomic writes; history saved on events and at most every 30 s; one-time migration that keeps every record, then removes the old registry values.
9. **Thumbnails (B24):** reusable mpv per worker, `hwdec=auto-copy`, HDR tone mapping (**verify** `zscale`), worker count by device and power, paused during playback.
10. **Scan progress (B25):** StateFlow sampled at 4 Hz.
11. **Network-drive detection (B29):** `GetDriveTypeW` + `WNetGetConnectionW`, so mapped letters count as network shares everywhere.

Nothing should look different yet.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
