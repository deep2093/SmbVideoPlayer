# SkyWing for Windows: modernisation handover (updated 20 November)

Everything Codex (or any developer) needs to modernise the SkyWing PC app, now matched to the motion work already done.

## What's inside

| Path | What it is |
|---|---|
| `SkyWing-PC-modernise-plan.md` | The full brief: what's already built (don't redo), findings B1–B36, the phased plan (M0, P1–P7), GPU policy and matrix, measuring, touch keyboard, adaptive design, accessibility, tests and extra features. |
| `prompts/00-M0-…` to `prompts/10-M10-…` | Eleven ready-to-paste prompts, one per milestone. Use them in order. |
| `design/png/` | The 9 reference screens as pictures, at their real size. |
| `design/html/` | The same screens as web pages; open in any browser. |
| `design/source/` | The original design board files (they render in Claude's design viewer). |
| `reports/` | The motion efficiency review (19–20 Nov) and the 20 Nov change report, for context. |

## How to use it

1. Start a Codex session on `deep2093/SmbVideoPlayer` in your local checkout (it has the uncommitted accessibility and fullscreen-test changes). Attach this zip and paste `prompts/00-M0-ship-and-measure.md`. M0 copies this folder into `windows/docs/modernise/` and commits your local work.
2. When Codex says "ready to push", say "push", let the build finish, and run the acceptance in `M0-RESULTS.md` on the laptop and the handhelds. Paste the numbers back to Codex (and to me if you want a review).
3. Paste the next prompt. If Codex ran out of usage part-way, paste the **same** prompt again; it checks `git log` and carries on.
4. Say "push" only when you're happy with a milestone.

## Order and size (about 32 sessions, around 42 with fixes)

| # | Milestone | Sessions | Test on |
|---|---|---|---|
| 0 | Ship the watchdog fix, commit local work, measure laptop + handhelds | 1–2 | All three |
| 1 | Speed foundation | 2–3 | All three |
| 2 | GPU choice, screens, smoothing safety, in-app measuring | 3–4 | All three |
| 3 | New look, adaptive layout, Settings, window size, accessibility | 3–4 | Laptop + one handheld |
| 4 | Add network drive with password | 2 | Laptop on Wi-Fi |
| 5 | Handhelds: touch keyboard, touch, controller | 2–3 | Both handhelds |
| 6 | The new player | 3–4 | All three |
| 7 | Tests, release assets, licence audit | 1–2 | Run CHECKS.md |
| 8 | Android parity and backup | 2 | Phone + PC |
| 9 | Offline library, sleep/wake, phone sync, shortcut, Mica | 4–5 | Handhelds |
| 10 | Extras | 4–5 | Laptop + TV |

M0 → 1 → 2 → 3 go in order. After that, 4, 5 and 6 can go in any order, and 8–10 can be picked by priority.

## Things Codex must ask you about first

- Any change to the Android app (`app/`), e.g. phone sync in M9.
- Changing a motion quality preset (M2 experiments).
- Squashing or merging PRs #9–#13 (M7).
- Anything in the licence audit that looks incompatible with public release (M7).
- The code-signing account and submitting to winget (M10).
- Pushing, at any time.
