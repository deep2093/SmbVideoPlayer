# Video Library: cinema-dark redesign

Base: confirmed v0.11 commit ffeea817ca4e80ebbc4ed7b650964c39e97179bf.

## Direction
Local design-taste-frontend app draw: True-black flat, seed 1124370741.
No redraws. Existing font family and theme preference keys are retained.
The default neutral ground is #0d0d0d, surface #171717, raised #262626,
ink #f5f5f5, secondary #b8b8b8, progress #ff837a.
White controls replace low-contrast dark red text. Optional Midnight blue,
Forest and Plum themes remain available as existing user choices.

On this interface, the library's actual video stills become the lead,
and saved playback positions become the one colored mark.

## Screen questions
- Folders: Which collection do I want to open? Custom, virtual and original
  folders keep their existing identities, tabs and long-press actions.
- Videos: What should I resume or play next? Resume artwork is wider, its
  play action is explicit, and density respects readable artwork width.
- File Explorer: Where is the actual file? Physical paths and file actions
  retain their semantics.
- Player: Where am I, and what can I do next? Both engines use the same
  large transport, time labels, seek slider and track/options controls.
- Scan: What is running or needs attention? Existing task and failure
  disclosures remain, using the shared surface hierarchy.
- Settings: Which preference do I need? Existing categories remain.

## Data inventory and preserved behavior
Actual SMB files, physical paths, file sizes, artwork status, duration,
watched position, hidden state, saved theme, grid/list preferences,
scan tasks and folder memberships all remain sourced from the existing VM.
No sample films, statistics or artwork are inserted into the user's library.
No database, network or credential code is changed.
Existing lazy/paged rendering and scroll-restoration identities remain.

## Responsive frame
Tablet: 204dp labeled sidebar and 16dp equal media gutters.
Phone: labeled bottom navigation. Floating preference uses an opaque surface.
Saved grid columns are an upper bound; every multi-column tile is at least
148dp wide with 12dp horizontal gaps. Media remains 16:9.
Buttons retain at least 48dp targets. Captions retain up to two title lines.

## Resources reviewed
- Local design-taste-frontend SKILL.md and app-screens.md: app-specific run.
- https://www.tasteskill.dev/ : art direction and audit-first workflow.
- https://github.com/vercel-labs/agent-skills/tree/main/skills/web-design-guidelines
  and its current Web Interface Guidelines: accessible labels, touch
  alternatives, safe areas, long filenames, loading/retry states.
- https://github.com/VoltAgent/awesome-design-md and its Spotify media
  reference: artwork-led hierarchy and dark surfaces, without copying branding.
- https://github.com/Leonxlnx/taste-skill/tree/main/skills/image-to-code-skill :
  website image-first workflow. Native Compose is validated through Android
  screenshots instead of treating a generated web mockup as a native screen.
- https://github.com/microsoft/playwright-cli : browser tooling; it cannot
  exercise the native Android APK. Android instrumentation and adb are used.

## Verification
CI performs Kotlin compilation, Android lint, unit tests, instrumentation,
SMB/file-operation smoke tests and optimized-preview validation.
Added invariant tests for responsive density and interaction tests for player
tap alternatives. Native screenshots are captured at logical widths of
390, 768 and 1280dp by the existing device smoke script.

This document describes the implementation and test plan, not a claim that
pending CI checks have passed. Final results belong in the PR and delivery.
Physical-device SMB throughput, HDR, gestures, IME and PiP still need the
target phone/tablet and the user's media.

## Before-image review
The prior API 35 artifact was reviewed directly. Original folders and settings
are readable but use a narrow icon rail, heavy red selected states, and
large unstructured gaps. Floating navigation is translucent, colored and
very small relative to a tablet. VLC uses compact text transport controls.
The old home image contains a Quickstep launcher ANR dialog; it is not a
usable visual baseline. One VLC capture includes Android's immersive
tutorial; device capture now suppresses that emulator tutorial.

## Assets and content
Only the app's existing artwork loader and Material icon library are used.
No stock/generated media is shipped, no image credits are newly required,
and there is no sample production content to replace.
