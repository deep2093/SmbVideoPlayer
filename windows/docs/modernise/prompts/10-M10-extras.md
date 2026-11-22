# Milestone 10 of 10 (M10) — Extras: buffering, refresh match, passthrough, undo, folder watching, media keys, signing, winget, motion component

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M10:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M10"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 11.4 items 6–14, section 11 items 2–5, section 11.2 items 6–8.

**Do, one commit or more each:**
1. Smarter buffering on Wi-Fi (item 6).
2. Match the screen's refresh in fullscreen (item 7): off by default, only with smoothing off, always restore the original mode.
3. Audio passthrough (item 8), off by default.
4. Undo toasts (item 9).
5. Watch local PC folders (item 10), never network drives.
6. Media keys, Windows media overlay, taskbar buttons; single instance; crash reporter; update check (off by default).
7. Subtitles, audio delay and Night mode (**verify** `acompressor`), sleep timer.
8. Optional motion component in the installer (item 14), keeping the upgrade code.
9. Signed installer (item 11) behind `if: secrets.SIGNING_ENDPOINT != ''`, only after the licence audit (item 13). **Ask me before** anything needing an account.
10. winget manifest (item 12). **Ask me before** submitting it anywhere.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
