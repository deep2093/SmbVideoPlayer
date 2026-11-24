# Milestone 7 of 10 (M7) — Tests, release assets, licence audit, cleanup (phase P7)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M7:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M7"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 10, findings B35 and B36, section 11.4 item 13. Use the `skywing-release-qa` skill.

**Do:**
1. Fill gaps in the unit and native tests listed in section 10.
2. Write `windows/docs/modernise/CHECKS.md` (manual checks 1–9 plus the M0 acceptance) with a fill-in table per machine.
3. Release assets (B35): installer carries matching motion assets; clean install and in-place upgrade test from the current installed build keep the library and settings; the uninstall check (section 10), on a clean PC or a virtual machine. **Ask me before** installing or uninstalling anything on my PCs, and about saved drive passwords.
4. Licence and source audit (11.4 item 13, B36): component list, `THIRD-PARTY-NOTICES.txt` generated in CI, build fails on an unlisted DLL. Ask me if anything looks incompatible with public distribution.
5. Clean-up: dead code, shorten `MOTION-ENGINE.md` and `SOURCE-STATUS.md` to the facts (keep the efficiency review's conclusions).
6. **Ask me first** before squashing or merging PRs #9–#13.
7. The 2-hour soak test (section 10). **Ask me before** running it: it takes the screen for 2 hours.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8). No OneXPlayer 3 runs: the Claw stands in for both handhelds (owner, 25 November).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
