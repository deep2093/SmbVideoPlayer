# The M3 runner

The M3 runner does M3's automatic acceptance checks (brief `prompts/03-M3-new-look.md`, plan section 6) on one device
with nobody at it, so the owner's part is to start it and bring back the results. It checks two SkyWing app images one
after the other: the build before M3 ("before") and the build with it ("after"). It is a test tool for the owner's own
devices, not part of any installer. Like the M0, M1 and checks runners it installs nothing: each image runs from the
runner folder on isolated data, and the installed SkyWing, the owner's library and settings and the Windows settings
stay as they are.

Code: `windows/src/test/kotlin/com/skywing/desktop/automation/M3Runner.kt`, with its window and picture calls in
`WindowShots.kt`. The parts that need no SkyWing (what to run, the pages and sizes, the window rule, the navigation
shape, the accessibility audit, the tables and the gallery) are in `M3Checks.kt` and have unit tests in `M3RunnerTest`.
The numbers check reuses the M1 runner's measuring pass (`M1Pass` in `M1Measurement.kt`).

The runner was written before M3's app changes, against the M2 build, and has not run yet. Its first trial on the
laptop tests the runner itself.

## Building it

1. On the commit before M3's first app change (the M2 app code), keep the before image:
   `.\gradlew.bat -p windows m3KeepBefore --no-daemon`. It is kept in `windows/build/m3-images/before`, with the commit
   it came from.
2. When M3 is done: `.\gradlew.bat -p windows m1Media m3Runner --no-daemon` (m1Media needs ffmpeg with libx264 and
   libx265 on PATH). The portable folder is `windows/build/m3-runner/SkyWing-M3-runner`: the launcher and README, a
   Java runtime, the harness, the test clips, the design boards, and both app images with the commits they came from.
   Rebuilding it keeps a run's `results`, `work` and `data`.

On a handheld, copy the folder to the device's own NTFS drive and double-click `Run M3.cmd`; the README in it says how.

## The checks

| Word | What it does | Plan | Judged by |
|---|---|---|---|
| `numbers` | M1's measurements: three starts on a 5,000-clip test library (first start with the scan, everyday start, Adaptive Glow start), start times, scrolling frame times, memory. `numbers/M1-table.md` has both images side by side. | Section 10, performance checks | Measured, not judged: the owner compares before and after |
| `pages` | A picture of every page (Home, Folders, Videos, Files and drives, Settings and each Settings category) at the boards' sizes: Desktop 1440 × 900, Handheld 1280 × 800, Compact 1024 × 720 and Narrow 680 × 720 dp, each size that fits the screen; then maximised. `gallery.html` puts before, after and the board side by side. | 6.4–6.7 | The owner looks |
| | The width sweep on Videos, scrolled down: 1300, 1150, 1000, 800, 700 and 640 dp. At each width the navigation's shape (wide rail, narrow rail, top tabs) from the Access Bridge bounds of its entries, and whether the video at the top before is still on the page. | 6.4, 6.6 | Pass or fail against the plan, with the wide rail from 1200 dp (below) |
| | The accessibility audit of every page at the first size: every button, switch, check box, slider, field and tab on screen has a name, every slider a value, every group of tabs a selected one. `access.md` lists what a screen reader can't tell. | 6.9, B33 | Pass when nothing is found |
| `window` | On fresh data: the first start's size and place; Restore after a maximised first start; moved to 60 % of the work area, closed and reopened; maximised, closed, reopened and restored; moved off every screen, closed and reopened; made 200 × 150 px. | 6.8 rules 1–5 | Pass or fail against the plan |
| `settings` | At 1300 dp, whether the category list stays beside the Playback page. At 680 dp (one pane under both 6.5's and 6.6's edges), from the Playback page: Settings again, Esc, the mouse back button, the Back button, and Folders then Settings must each bring back the category list. | 6.5, B1 | Pass or fail |

With no word it does all four (about 40 minutes on the laptop). `Run M3.cmd pages window` runs only those. `trial`
makes every check short (the M1 trial timing, two board sizes, two sweep widths, the first window steps): a trial
tests the runner, not SkyWing. `SKYWING_M3_ONLY=before` (or `after`) runs one image; `SKYWING_M3_BEFORE`,
`SKYWING_M3_AFTER`, `SKYWING_M3_MEDIA` and `SKYWING_M3_BOARDS` name other folders; `SKYWING_M3_NO_PAUSE=1` skips the
pause at the end.

How it works: SkyWing's window is moved and sized with Windows' own calls (`SetWindowPos`, `ShowWindow`), the content
size is set in dp from the window's DPI, and each picture is the window's content as the screen shows it. Pages are
opened through Java Access Bridge, as a screen reader would. Esc and the mouse back button are posted to SkyWing's
window, not typed, so they reach it only while it is in front; when it isn't, the step is "Not measured". Before each
picture the runner checks nine points over the window; a picture taken while another window covered part of SkyWing
says so in the gallery.

## Verdicts

The checks runner's words, plus two:

- **Pass** and **Fail**: the after image against the plan's rule; the details say what was seen.
- **Look at it**: the pictures, for the owner to compare with the boards.
- **Not measured**: the runner couldn't tell (SkyWing wasn't in front for a posted key, a page wasn't there, the cards
  have no names to follow).
- **Measured**: numbers with no limit of their own.
- **Before M3: shown, not judged**: the before image is pictured and measured the same way, so the owner sees what M3
  changed, but M3's rules don't apply to it.

The runner exits 1 when the after image failed a check, 0 otherwise, 2 when it refused to start and 3 on an error.

## Where the plan disagreed with itself

6.4 makes the rail wide from 1100 dp; 6.6 makes the Expanded width class, and so the wide rail, start at 1200 dp. The
owner chose 1200 dp (2026-11-25): the rail follows the width class. At 1150 dp the sweep expects the narrow rail, which
also shows whether a build still uses 6.4's 1100.

## What the runner can't see

These stay hand tests on the laptop (RTX 5070 Ti), the MSI Claw 8 and the OneXPlayer 3:

- Whether the pages match the boards' structure, sizes and tokens: the gallery shows them, the owner judges.
- Narrator or NVDA actually reading the pages, and Windows high-contrast themes.
- Keyboard focus: the focus ring, its order, and focus kept across a width change.
- Alt+Left, and the controller's B button, going back in Settings.
- Text size (Windows "Text size" setting) and 125 %, 150 % and 200 % scaling beyond what the screen it runs on uses.
- Playback, full screen in and out, Alt-Tab and closing: the checks runner's `fullscreen` and `stress` checks, and by
  hand as `AGENTS.md` asks.

## Privacy

The pictures show SkyWing as it ran, including the test library's folder inside the runner folder, so they can show
where the runner folder is on the drive. Keep the results private. The test clips are generated; the results hold no
names of the owner's files.
