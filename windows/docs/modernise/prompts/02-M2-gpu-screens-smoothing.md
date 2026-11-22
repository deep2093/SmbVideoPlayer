# Milestone 2 of 10 (M2) — GPU choice, screens, smoothing safety and measuring (phase P2)

You are continuing the SkyWing for Windows modernisation. The full brief is `windows/docs/modernise/SkyWing-PC-modernise-plan.md` and the design references are in `windows/docs/modernise/design/` (open the PNGs; the HTML files open in any browser). Read the brief's sections 0, 1, 2 and 3.1 first, then the sections named below.

**Rules (from section 0, repeated so they're never missed):**
- Follow `AGENTS.md` and use the repo skills in `.agents/skills/` where they fit (`skywing-desktop-design`, `skywing-debugging`, `skywing-video-performance`, `skywing-release-qa`). Before touching playback, read `windows/MOTION-ENGINE.md`, `windows/FULLSCREEN-REGRESSION.md`, the motion efficiency review and `docs/superpowers/plans/2026-11-19-skywing-next-build-actions.md`. If the brief disagrees with the code or those documents about how motion works today, the code wins: tell me the difference.
- Don't redo what's already built (brief section 0, rule 2b). Improve it.
- Repository `deep2093/SmbVideoPlayer`. M0 works on `feature/windows-motion-engine`; every later milestone works on `feature/windows-modern-pc`, created from the head M0 leaves.
- Only change `windows/` (plus `docs/` for notes). Don't touch `app/` (Android) unless this prompt says to ask me first and I said yes. Never touch `codex/adaptive-motion-interpolation`. No force-push, no rewriting history.
- **Don't push and don't open a PR until I say "push".** Commit in small steps. Start every commit message with `M2:`.
- x64 only. Never write to my network drives or shares; never keep a drive awake with polling. Keep the MSI upgrade identity, per-file settings, native payload hashes and legal notices.
- For each bug: state the expected behaviour, write a failing test first where feasible, then fix. Where the brief says **verify**, check the real code, API docs or a test first. Never claim a pass without running the test; report missing prerequisites instead of skipping.

**If an earlier session stopped part-way:** run `git log --oneline --grep "^M2"`, check `git status`, run the tests, find what's done and continue. Don't redo finished work.

---

**Read:** section 5 (5.1–5.9), findings B4, B5, B6, B12, B28, B30, B31, and the motion efficiency review. Motion smoothing already works: keep the four stops, five presets, per-class memory, the "offer next lower stop" behaviour (auto-lowering off by default) and active-display routing.

**Do:**
1. `GpuInventory` (5.2), with AMD recognised by PCI vendor ID, external GPUs, and Windows' graphics setting for SkyWing.
2. The corrected policy (5.3) as a pure, unit-tested function: **Automatic = today's active-display routing**; add Performance (dedicated) and Power saving (integrated); re-plan on monitor, power or GPU change with a 5 s debounce; the "Use <dedicated GPU> for smoothing" offer on hybrids when 2× can't keep up; two GPUs from one vendor pick the motion device by LUID. Never mix devices.
3. Display sync (5.4): refresh from the current mode (B30, never a 240 Hz guess); VRR range from NvAPI, ADLX if cheap, and EDID for every vendor; VRR active now (NvAPI; **verify** which drivers report it) and HDR on or off per monitor, "unknown" where nothing reports them; refresh only on `WM_DISPLAYCHANGE`; the "May judder on this N Hz screen" note for non-divisor stops on fixed screens (B31). No new frame-rate options.
4. Frame sharing (5.5): keep it; add the teardown stress test (10 × (100 seeks + fullscreen toggles + close)); **verify** VapourSynth frame/core lifetime; pin the MSYS2 packages.
5. Watchdog heartbeat (B28): replace the status file with an in-process channel (callback or shared memory); keep the 15 s threshold and bounded recovery; 30-minute simulated test with read failures.
6. In-app measuring (5.6): per-minute CSV, "Copy test report", hidden `--bench` mode, all with the display state (HDR engaged, VRR active); optional on-screen frame times from ETW present events, only when I've given the rights; `verify.ps1` adds the display state to its report.
7. Compatibility matrix (5.8): capability probe cached per driver, decode/render/motion fallbacks, HDR/DV smoothing stays bypassed, recovery from a GPU lost while playing (rule 8), the new rows, full report.
8. Cheaper smoothing (5.7): only the experiments, behind hidden settings, with numbers. Don't change any preset without my OK.
9. Any processor (5.9): the instruction-set check, the CPU in the report, and the hybrid-core measurement before any throttling change.

Test target: the M0 acceptance repeated with this build shows no restart, no new drops, and the report names the GPU plan, the CPU and the screen's HDR and VRR state.

---

**When this milestone is done** (and not before), stop and report, as `AGENTS.md` asks:
- What changed: 3–6 plain bullets, and the changed files.
- Tests run and their results; what wasn't tested and why.
- What I should test by hand, and on which machine (laptop RTX 5070 Ti / MSI Claw 8 / OneXPlayer 3 with Intel Arc B390).
- Numbers where they apply (frame time p95, start time, memory, drops per minute, CPU cores, scan time: before → after).
- Commit IDs, remaining risks, and anything you couldn't do and why.

Don't start the next milestone.
