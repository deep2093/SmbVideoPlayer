---
name: skywing-debugging
description: Investigate and fix SkyWing Windows crashes, hangs, black video, lost keyboard focus, fullscreen exit failures, regressions, SMB issues or other intermittent runtime bugs using evidence-first reproduction and regression testing.
---

# Reproduce, isolate, fix, verify

1. Identify the exact current branch, failure scenario, expected behaviour and observed behaviour. Inspect current code, recent changes, logs and targeted tests. Do not assume an older diagnosis still applies.
2. Reproduce with the smallest deterministic sequence. Record relevant environment: OS, GPU/renderer, monitor/DPI, mpv mode, local vs SMB file, media properties and whether the window is fullscreen. Redact personal filenames, share locations and credentials from diagnostic reports.
3. Separate the symptoms from hypotheses. Find the failing boundary (Compose UI state, EDT/AWT, HWND/Win32, JNA/libmpv, GPU renderer, network/IO, worker shutdown). Prefer assertions, traces and thread dumps over speculative code changes.
4. For hangs, capture wait chains/threads when available. For native crashes, use Windows event logs, dump files and WinDbg if installed. Never claim WinDbg analysis without obtaining evidence.
5. Write a regression test that fails on the bug when feasible, then implement a focused fix. Avoid changing unrelated player behaviour or silencing errors with broad catch blocks.
6. Always recheck the reported path and adjacent invariants: playback/seek/pause, repeated F/Esc, Alt-Tab, navigation during playback, close/Alt-F4, no orphaned process or audio, stable video HWND, and no lost focus.
7. Verify as `windows/TESTING-POLICY.md` requires: `windows\tools\verify.ps1 -Mode focused` first, then full mode when the policy says so. Tell the user before tests that take the desktop. Note whether tests were actually executed, any that were blocked or skipped, and what remains machine-specific.
8. End with root cause, evidence, specific fix, regression coverage, remaining risks and a manual reproduction checklist.
