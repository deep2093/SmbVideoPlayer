# Milestone 0 of 10 (M0) — Ship and measure what's already done

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M0:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M0"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 3.1, findings B28, B35, and the 20 November change report.

**First step of the whole project:** if `windows/docs/modernise/` doesn't exist, I'll have attached a zip. Copy its contents into `windows/docs/modernise/` and commit as `M0: add modernisation brief and design references`.

**Do (on `feature/windows-motion-engine`):**
1. Commit my local work as separate commits: the `jdk.accessibility` packaging change in `windows/build.gradle.kts`, and the fullscreen test fixture fixes in `FullscreenWindowTest.kt` with the two new regressions. Remove leftover diagnostic files only if they're clearly temporary; list anything you're unsure about and ask me.
2. Make sure a locally packaged build can't silently come out lower than the installed one (a local package came out as 0.1.1 while 0.1.98 is installed): use the CI version scheme locally or refuse to package a lower version. Test it.
3. Add a "build identity" line to the playback report and installer file name: commit, runtime (stock or frame-sharing) and whether `ed072bf` is included.
4. Add the B32 **still-mouse** fullscreen test (mouse not moving over the video; Win32 cursor must hide within 3 s). It may fail; if so, keep it marked as a known failure for M6 and say so.
5. Write `windows/docs/modernise/M0-RESULTS.md` with the acceptance steps from section 3.1 items 4–5 as a fill-in table for me.

Then tell me: "ready to push". After I push and the build finishes, I'll run the laptop and handheld acceptance and paste the numbers back to you.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
