# Milestone 4 of 10 (M4) — Add network drive with username and password (phase P4)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M4:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M4"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 7 (7.1–7.4), findings B2, B3 and B29, and the board "Add network drive".

**Do:**
1. The 3-step dialog: Where (auto-find and manual address) → Sign in (Guest or username and password, show-password, "Remember password" in Windows Credential Manager, Advanced: domain) → Choose share.
2. `SmbConnector` (7.3): `WNetAddConnection2W`, `NetShareEnum`, `CredWriteW`/`CredReadW` (never store the password anywhere else), the error-to-message table (**verify** every code).
3. The Windows 11 guest-access note when guest sign-in fails.
4. **Check connection** (7.4) with a copyable report.
5. Replace the old three-field row in `FilesScreen.kt` (B3). Keep alternate share addresses.
6. Unit tests for the error mapping.
7. Long paths (7.3): a file whose path is over 260 characters scans, plays, gets its thumbnail and keeps its position.

Test target: add my drive over **Wi-Fi** with username and password, restart, and it opens without asking again.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
