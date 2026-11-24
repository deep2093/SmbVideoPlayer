# Milestone 5 of 10 (M5) — Handhelds: touch keyboard, touch, controller, power (phase P5)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M5:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M5"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 8 (8.1–8.3), findings B14 and B26, section 6.6, and the boards "Handheld · Home + touch keyboard", "Handheld · Settings", "Handheld · Player". Use the M0 handheld numbers for defaults.

**Do:**
1. **Touch keyboard (8.2):** `platform/TouchKeyboard.kt` with the InputPane path and the `ITipInvocation` fallback; show/hide rules; inset + `bringIntoView`; dialogs move up; Enter actions; the setting. **Verify** all interface IDs against the Windows SDK headers. (The 20 November check confirmed Windows UI Automation can't see Compose text fields, so this is required.)
2. **Controller-only typing:** the SkyWing on-screen keyboard if the Windows one can't be driven by a gamepad (**verify**).
3. **Touch everywhere (8.3).**
4. **Controller (8.1):** XInput at 60 Hz only while focused, button map, focus ring, hints bar.
5. **Handheld mode and power (8.1)**, Performance settings page as in the board; smoothing defaults from M0 results.

Test target: on both handhelds every text box brings up the keyboard and stays visible; the app is usable with touch only and with the controller only.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
