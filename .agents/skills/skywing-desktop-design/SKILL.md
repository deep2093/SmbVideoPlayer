---
name: skywing-desktop-design
description: Design or refine the SkyWing Windows Compose Desktop UI, including library, folders, settings, player controls, keyboard and mouse interactions, fullscreen, accessibility and high-DPI layouts. Do not use for unrelated Android or web UI work.
---

# SkyWing desktop design

1. Inspect the current Windows UI code and navigation state before proposing a redesign. Read `windows/README.md`, `windows/ANDROID-PARITY-AUDIT.md` and related design docs as relevant; do not infer today's UI from older screenshots.
2. State the user task in terms of flows, states and acceptance criteria: empty/loading/error states, focus, mouse and keyboard behaviour, back navigation, and interaction while video plays.
3. Use the established Compose Desktop components and theme tokens. Prefer readable hierarchy, high contrast, consistent spacing, responsive window resizing and sensible keyboard focus. Treat Windows desktop conventions as guidance, not an excuse to replace Compose with WinUI.
4. For player controls, preserve video area, HWND, input ownership and playback state. Never rebuild or dispose the video canvas just to show an overlay, change controls or enter fullscreen. Use the single existing fullscreen owner.
5. Consider mouse hover, scroll, right-click, double-click, keyboard-only access, Alt-Tab, multiple monitors and 100%-200%+ Windows DPI scaling. Avoid focus-stealing overlay windows.
6. When implementation is requested, make the smallest cohesive change, add relevant tests and validate navigation/playback regressions. Use UI automation only when a suitable accessible runtime and tool are actually present.
7. Summarise what changed, show a concise before/after behaviour table if helpful, and explicitly identify manual visual checks still needed on Windows.
