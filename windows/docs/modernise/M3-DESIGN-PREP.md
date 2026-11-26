# M3 layout design preparation

This is the design brief for M3's look and layout (brief `prompts/03-M3-new-look.md`, plan section 6), written before
any M3 app change. It plans the screens; it changes no code. It was written with the Impeccable `shape` method (plan the
UX and UI before code: job, outcome, direction, scope, states, interaction and layout, decisions) and the repository's
`skywing-desktop-design` skill.

Where it comes from:

- **Visual authority:** plan section 6 (6.1–6.9) and the boards in `design/png/` (`Main`, `FoldersCompact`,
  `SettingsDesktop`, `Tokens`; `HandheldHome` and `SettingsHandheld` are section 8 references). The root `DESIGN.md`
  is the Android library redesign, not the Windows authority. There is no `PRODUCT.md`.
- **Today's code** (the M2 build, commit `f5679a6`), read for this brief: `Main.kt` (shell, `go()`, window state),
  `SettingsScreen.kt`, `SkyUi.kt`, `SkyTheme.kt`, `VideoViews.kt`, `FoldersScreen.kt`, `VideosScreen.kt`. Where the plan
  and the code disagree, the code wins and this brief says so (section 8).
- **Checks:** the M3 runner (`M3-RUNNER.md`) already judges the navigation shape by width, the window rules, the ways
  back in Settings and the accessibility audit. Section 10 ties each part of this brief to a check.

The Impeccable context loader was not run: it downloads an engine binary, which needs the owner's permission. The
project files above were read directly instead.

## 1. The job and who does it

- **Who:** the owner and the household. A laptop with an RTX 5070 Ti at a desk or on a sofa (mouse, keyboard, a 240 Hz
  screen); two handhelds, the MSI Claw 8 and the OneXPlayer 3 (touch now, a controller from M5, about 1280 × 800 dp at
  150 % or 200 %); sometimes a large external monitor.
- **What they have:** local folders and network drives with thousands of videos (the M1 test library has 5,000).
- **What they come to do:** pick something and start it in a few seconds; carry on with what they were watching; find a
  folder; change one setting and get back to the library.
- **The scene:** often a dim room, the video is the main thing, and the interface steps back. Calm, quiet, readable from
  a sofa. This is an *Operate* surface: speed of finding things, consistency and Windows habits come before expression.

## 2. What done looks like

M3 is done when:

1. Every page has the structure, sizes and tokens of its board (the owner judges the M3 runner's `gallery.html`).
2. The navigation changes shape by window width: top tabs under 720 dp, a 72 dp rail from 720 dp, a 240 dp rail from
   1200 dp. The video that was at the top of the page before a resize is still on the page after it.
3. Every way back in Settings works, and the category list stays beside the page in two panes (B1).
4. A screen reader can name every control and tell which tab is selected (B33): the runner's audit finds nothing.
5. The first start, restore, reopen and off-screen rules of 6.8 hold on the laptop and on both handhelds.
6. Start time, scrolling frame time and memory are no worse than before M3 (the runner's `numbers` check).
7. Playback, full screen, Alt-Tab, closing, Unicode paths and alternate drive addresses work as before.

## 3. Direction

Plan 6.1, "calm, cinematic Windows 11", made concrete by the boards:

- **Layers, not effects.** Flat surfaces stacked by tone (`base` → `layer1` → `layer2` → `layer3`) with one 1 px
  `stroke` edge. The covers bring the colour. Today's glass (`skyGlass`), wallpaper glows (`skyWallpaper`), 20–22 dp
  pill shapes and halos go once nothing uses them (M3 brief, item 1).
- **One accent** (`#6CA8FF` in Dark) for selection, focus and progress only. Status colours (`success`, `warning`,
  `danger`) only in status chips such as "G-SYNC ready" and "Can't reach drive".
- **Type:** Segoe UI Variable (Segoe UI on Windows 10), the Tokens board ramp: Display 28/600, Title 20/600,
  Subtitle 16/600, Body 14/400, Caption 12/400, Touch body 16/400.
- **Space and shape:** a 4 dp grid (4, 8, 12, 16, 24, 32, 40, 48); radius 8 for controls, 12 for cards, 16 for panels
  and dialogs.
- **Motion:** 120 ms for small state changes, 200 ms standard, 250 ms for pages, never over 300 ms. With Windows'
  "Animation effects" off (or Battery mode, later), motion becomes a fade or nothing.
- **Themes:** today's theme choices (in `SkyTheme.kt`) map onto `SkyColors` sets (Dark, Light, Midnight, OLED,
  Adaptive) and the saved choice migrates. Adaptive keeps only an accent and one soft tint taken from the covers.
  Mica stays off: it is optional, behind a setting, and only after it is measured on the handhelds.

## 4. Scope

**In M3** (brief, items 1–7): tokens and components; `LocalAdaptive` (width class and input mode); the shell with the
new Home page; Folders and Videos grids with the ↑/↓ sort menu; Settings two-pane with search, breadcrumb and the
`go()` reselect fix; window size and position; accessibility. Item 8, added by the owner on 25 November: three
fixes to the full-screen player overlay (section 7, *Player overlay*).

**Not in M3:**

- The Add network drive dialog (board `AddDrive`, section 7, M4). M3 keeps today's add flow, restyled.
- Handheld mode, the controller, the touch keyboard and the button-hint bar (boards `HandheldHome`,
  `SettingsHandheld`, section 8, M5). M3 leaves room for them: `InputMode.Controller` exists, every target can grow to
  48 dp, and Settings rows work as tiles later.
- The new player (section 9). M3 restyles nothing inside the player except shared components and the overlay fixes
  of item 8.
- Mica (see above), and any new scanning: Home uses only data the library already has.

## 5. Layout

### 5.1 Two axes, one answer

A `LocalAdaptive` value gives every screen the same answer (plan 6.6).

**Width class**, from the width of the window's content in dp (the window, not the screen), recomputed 100 ms after
the last resize:

| Class | Content width | Navigation | Grid | Settings |
|---|---|---|---|---|
| Compact | under 720 dp | Top tabs | 2 columns | One pane: list, then the page with ← Back |
| Medium | 720–1199 dp | Rail 72 dp, icon above a short label | 3–4 columns | One pane under 900 dp; two panes, 240 dp list, from 900 dp (decision O1) |
| Expanded | 1200 dp and up | Rail 240 dp, full labels, Now playing card | 5–6 columns, cards at most 320 dp | Two panes, 288 dp list with search |

The wide rail starts at 1200 dp: the owner chose 6.6's edge over 6.4's 1100 dp on 2026-11-25. Handheld mode (M5) will
keep the 72 dp rail from 720 dp at any width.

**Input mode**, from the last input used (changed only between gestures, never mid-scroll or mid-drag):

| Mode | Minimum target | Row | Body text | Hover actions | Focus ring |
|---|---|---|---|---|---|
| Mouse (and keyboard) | 32 dp (40 dp for M3's components, 6.3) | 40 dp | 14 sp | Yes | On keyboard focus |
| Touch | 48 dp | 56 dp | 16 sp | No: ⋮ always shown, long press = right click, press tint | On keyboard focus |
| Controller (M5) | 48 dp | 56 dp | 16 sp | No | Always |

M3 detects Mouse and Touch (plan 6.6 says to **verify** that Compose 1.8 reports `Touch` from `WM_POINTER` on Windows
first). The controller arrives with XInput in M5.

### 5.2 The shell

```
Expanded (1200 dp and up)                      Medium (720–1199 dp)          Compact (under 720 dp)
┌──────────┬──────────────────────────────┐   ┌────┬─────────────────────┐   ┌──────────────────────────┐
│ ▶ SkyWing│ Title        [Search ⌃F][+][⋮]│   │ ▶  │ Title       [⌕][↕][⋮]│   │[Home][Folders][Videos]… ⚙│
│ ⌂ Home   │                              │   │ ⌂  │                     │   │ Title          [⌕][↕][⋮] │
│ ▭ Folders│  page                        │   │Home│  page               │   │                          │
│ ▷ Videos │                              │   │ ▭  │                     │   │  page                    │
│ ☰ Files  │                              │   │ …  │                     │   │                          │
│ ──────── │                              │   │    │                     │   │                          │
│ Now play.│                              │   │    │                     │   │                          │
│ ⚙ Settings                              │   │ ⚙  │                     │   │                          │
└──────────┴──────────────────────────────┘   └────┴─────────────────────┘   └──────────────────────────┘
```

**Keep the video window alive (the one hard rule).** Today the video `Canvas` lives in a `SwingPanel` inside the
content column of `SkyWingDesktopShell`, shown 1 dp tall on library pages so its native window survives navigation.
Changing the navigation's shape must not move that `SwingPanel` to another place in the composition, or Compose
disposes it and the native HWND that libmpv draws into is destroyed. So the shell keeps one structure in every class:

```
Row {
    NavigationRail(width = 240.dp, 72.dp or nothing in Compact)   // never wraps the content
    Column {                                                       // the same call site in every class
        TopTabs()            // Compact only
        PageArea()           // weight 1, hidden while the player shows, as today
        PlayerChrome(top); SwingPanel(video Canvas); PlayerChrome(bottom)
    }
}
```

Never write `if (compact) Column { content } else Row { rail; content }`. Full screen hides the navigation as today,
through the single fullscreen owner.

**Rail, 240 dp (Expanded)**, on `layer1` with a 1 px `stroke` on its right edge (board `Main`):

- The mark and "SkyWing" (Subtitle), 48 dp tall, 12 dp from the top.
- Sections: Home, Folders, Videos, Files and drives. Rows are 40 dp tall (56 dp in Touch), radius 8, 12 dp padding,
  a 20 dp icon and a Body label. Selected: `accentSubtle` fill, accent icon, `text` label. Hover: `layer3`.
- A divider, then **Now playing** (Caption, `textSecondary`) and a card while a video is loaded: a 56 × 32 dp cover,
  the title on one line, a 2 dp accent progress line. Clicking it opens the player; its play/pause button stays (today's
  sidebar has one).
- Settings at the bottom, the same row style.

**Rail, 72 dp (Medium)** (board `FoldersCompact`): the mark at the top; rows 56 dp tall with the icon above a Caption
label; shorter labels where needed ("Files and drives" becomes "Drives"). While a video is loaded, Now playing is a rail
item with a progress ring around its icon (assumption A1). Settings at the bottom.

**Top tabs (Compact):** a 48 dp row at the top of the content column: Home, Folders, Videos, Drives as a segmented
control (`SkySegmented`), Now playing as a fifth tab while a video is loaded (A1), and the Settings icon button at the
right, as on board `HandheldHome`.

**Top bar on every page,** 76 dp tall (board `Main`):

- Left: the page title, Display 28 at Medium and Expanded as the boards show it, Title 20 in Compact (A2).
- Right, at most three visible actions:
  - search: a 320 dp field with a "Ctrl F" hint in Expanded; an icon button that opens the field over the title in
    Medium and Compact;
  - the page's main action;
  - a ⋮ menu for the rest.

| Page | Main action | In ⋮ |
|---|---|---|
| Home | Add folder (accent) | Refresh library |
| Folders | Sort button, e.g. "Name ↑" | Grid or list, card size, tall cards, select |
| Videos | Sort button | The same as Folders |
| Files and drives | Add network drive (today's flow; the new dialog is M4) | Refresh |
| Settings | none (search sits in the category list) | none |

Today's grid-size and card-shape icon buttons (`GridControls`) move into ⋮ so the bar keeps three actions;
Ctrl + mouse wheel keeps changing the card size.

**Page padding:** 32 dp at the sides in Expanded, 24 dp in Medium, 16 dp in Compact; 24 dp between sections; 16 dp
between grid cards (12 dp in Compact).

**Moving between sections:** fade plus a 24 dp slide over 250 ms (fade only with animations off). Each section keeps its
own scroll state in the shell (one `LazyGridState` per section), across a section change and across a width change.

### 5.3 Home (new)

Board `Main`. Home uses data the library already has: watch history and index dates. No new scanning.

- **Continue watching:** one row of wide 16:9 cards with "See all" (which opens Videos, whose grid already starts with
  Continue watching). Each card has the time left in a chip at the bottom right, a 3 dp accent progress line, the title
  and "where · quality". As many as fit at a minimum of 260 dp each: 4 at 1440 dp, 3 in Medium, 2 in Compact. Not
  scrolled sideways.
- **Recently added:** a caption with the count ("24 new this week") and two rows of the normal video grid, newest first,
  with a quality chip at the top left ("4K HDR", "1080p", "DV") only when the library already knows it (A3).
- **Pinned folders** (plan 6.4): only once pinning exists. Today's code has no pinned folders, so M3 leaves this row
  out and says so in its report (A4).
- **Start page:** Home becomes the page SkyWing opens on (today it is Folders). See decision O5.

### 5.4 Folders and Videos

Board `FoldersCompact`.

- **Folders:** the existing Original / Virtual / Custom tabs become a `SkySegmented` under the title. Folder cards have
  a three-picture mosaic (one large picture over two thirds of the width, two small ones stacked beside it), then the
  name (Body 600) and "42 videos · 186 GB" (Caption, `textSecondary`).
- **Videos:** 16:9 cards with the duration; Continue watching stays at the top of the same grid, as today.
- **Sort menu** (both pages): a `SkyMenu` under the sort button, headed "Sort by · tap again to flip order". The
  selected key has an `accentSubtle` row and an accent ↑ or ↓; the others show a muted arrow for the direction they
  would start in. Choosing the selected key again flips it, as on Android. The keys stay today's: Folders sorts by Name,
  Number of items, Latest by date and Total size (`FoldersScreen.kt`); Videos turns today's cycling "Sort: …" button
  into the same menu with its existing keys. The board's wording is used only where it is accurate (a folder holds
  items, not only videos).
- **Columns:** the user's choices win. Today the App layout setting offers Auto or 2–5 columns, and the card-size
  buttons pick an adaptive minimum. M3 changes only the default for Auto: Compact uses 2 columns; Medium a minimum card
  of 180 dp; Expanded 220 dp; in both, a column is added when a card would be wider than 320 dp. A fixed column count
  is capped at 2 in Compact. Every grid keeps `key` and `contentType`.
- **Card states:** hover lifts the card 2 % with an accent edge at 40 % (150 ms, layer-only, no relayout); keyboard
  focus shows the same lift plus the focus ring; touch shows a 120 ms press tint instead; a selected card (multi-select,
  `GridSelection.kt`) shows a check and a full accent edge.
- **Width changes:** the first fully visible card before a resize stays on screen after it. The runner checks this on
  Videos at six widths.

### 5.5 Settings

Board `SettingsDesktop`; fixes B1.

**Panes:**

- **Two panes** from 900 dp (decision O1): the category list on the left, the page on the right.
  - The list is 240 dp wide below 1200 dp and 288 dp from 1200 dp, on `layer1`. At the top: "Settings" (Display 28)
    and a "Search settings" field.
  - Category rows are 44 dp tall with a 28 dp letter tile tinted at 16 % of its own colour, as on the board.
  - The page shows a breadcrumb ("Settings › Playback", Caption), the page title (Display 28), then panels.
- **One pane:** the category list fills the content. A page shows an ← Back icon button beside the breadcrumb.
- **Panels:** `layer2`, radius 12, 1 px `stroke`, 20 dp padding. A row has a title (Body 600), a subtitle (Caption,
  `textSecondary`) and its control at the right. Rows inside a panel are split by 1 px dividers.

**Where the open page lives:** in the shell's state, not in a `remember` inside `SettingsScreen` (today's `open`). Coming
back from another section shows the list in one pane, and the last category in two panes.

**Ways back:**

| Input | One pane, on a page | Two panes |
|---|---|---|
| Settings again (rail, tab, `go()` reselect) | The list | Focus moves to the category list |
| Esc | The list (a menu or the search field closes first) | Focus moves to the category list (A5) |
| Mouse back button, Alt+Left | The list | The section before Settings |
| ← Back button | The list | (not shown) |
| Controller B (M5) | The list | The section before Settings |

`go()` stops returning early: choosing the section already open calls a reselect handler. Settings goes to its list,
Folders to its root and Videos to the top.

**Categories** (plan 6.5) against today's eight (O3):

| Plan | Today |
|---|---|
| Appearance | Appearance, plus "Window at start" and "Reset window size" (6.8 rule 6), plus App layout's grid options |
| Library | Library, with Sync & scanning and Auto grouping as panels on its page |
| Network drives | New: the saved drives, their alternate addresses, and "Add network drive" (today's flow until M4) |
| Artwork | Artwork |
| Playback | Playback (below) |
| Controls | Today's keyboard shortcuts (now under Playback); controller settings in M5 |
| Performance | Only once it holds a real setting; until then it is not shown |
| About | About |

**Playback page** (M3 brief item 5; the controls exist since M2, so this restyles them and changes no behaviour):

- **Graphics for video:** Automatic / Power saving / Performance (`SkySegmented`), and under it the live plan line with
  a status dot.
- **Screen:** the display, refresh rate and variable-refresh range, with a status chip ("G-SYNC ready"). Under it, the
  switch "Use variable refresh for smoothed video", and in an Advanced group "Let VRR control refresh (fullscreen)"
  (off by default, 5.4).
- **Motion smoothing:** the switch; the four frame-rate stops (2×, 2.5×, 3×, 4×) as selectable tiles showing the
  resulting rate; the five-level quality slider (Fastest, Fast, Balanced, Better, Best) with its value named; the note
  about stops above the screen's refresh; "Smooth at screen size" (5.7); "Lower smoothing automatically when frames
  drop" (off by default).

**Search:** filters every row by its title and keywords across categories, with results grouped by category. Choosing a
result opens its page, scrolls the row into view and highlights it with `accentSubtle` for one second, fading out
(a still highlight with animations off). Moved rows stay findable under their old names, e.g. "App layout" and
"Sync & scanning".

### 5.6 Window size and position

Plan 6.8, in a new `WindowPlacement.kt`. Today `Main.kt` (line 106, not 73 as the plan says) always opens at
1280 × 800 dp.

1. **First start:** the monitor's work area in dp.
   - Under 1400 × 900 dp: start maximised.
   - Otherwise: a centred window at 75 % of the work area, clamped between 960 × 600 and 1600 × 1000 dp, 16:10.
2. **Remember** the normal bounds and the maximised state per monitor (device name, resolution and scaling) in the JSON
   settings: 500 ms after a move or resize, and on close. Never save the full-screen player's state.
3. **Restore safely:** saved bounds off every screen, or bigger than the work area, fall back to rule 1. At least 64 dp
   of the title bar stays on screen.
4. **Restore after a maximised first start** gives the 75 % window, not a screen-sized one.
5. **Minimum size** is 640 × 480 dp, so Compact always fits.
6. **"Window at start"** in Appearance: Remember last (default), Always maximised, Small 60 %, Medium 75 %, Large 90 %,
   and a "Reset window size" button.
7. **Windows' own behaviour stays:** caption buttons, Snap layouts and Win + arrow keys. The only `WM_GETMINMAXINFO`
   change is the minimum size.

### 5.7 Accessibility

What the code already does, which the plan's text predates:

- `SkyIconButton` does **not** drop its description any more: it passes it through `accessibleName`, as text semantics.
  Compose Desktop 1.8 gives Java Access Bridge the accessible *name* from text semantics. `contentDescription` arrives
  only as the description, so plan 6.9's "pass it to `contentDescription`" would leave buttons unnamed. The code's way
  stays.
- Switches use `SkySwitch`, which arrives as a labelled check box through `accessibleDescription`, because NVDA reads
  text semantics on a switch as "read only".
- `NavRow` already has `Role.Tab` and the selected state.

What M3 adds, component by component:

| Control | Role through Access Bridge | Name | State or value |
|---|---|---|---|
| Rail row, top tab, Settings category, segmented option | page tab | its label | selected |
| Icon button | push button | its description (also its tooltip, after 600 ms) | enabled |
| Switch | check box | its label, through the description | checked |
| Slider (quality, frame-rate stops) | slider | its label | its value, e.g. "Quality: Balanced" |
| Text field (search, settings search) | text | its label | its text |
| Card | push button | the title | progress where shown, e.g. "42 minutes left" |
| Sort menu row | menu item | the key | selected, and the direction |

- **Focus:** reading order (navigation, top bar, page). Every control is reachable by keyboard and shows the ring: a
  2 dp gap and a 2 dp ring in `focus`, set to the accent in the dark themes, as on the Tokens board (A6). Arrow keys
  move between cards in a grid; Enter opens; Ctrl+F focuses search; Esc closes a menu or search before it goes back.
- **Checks:** Compose semantics tests for each row above, the runner's Access Bridge audit of every page, and NVDA and
  Narrator by hand.

## 6. States and ranges

| Surface | Empty | Loading | Trouble | Long or many |
|---|---|---|---|---|
| Home | First run: `SkyEmptyState`, "Add a folder to see your videos here", Add folder button. A row with nothing in it is hidden. | One row of `SkySkeleton` cards (no shimmer with animations off) | A network folder offline: its cards stay, with a "Can't reach drive" chip | Titles on one line with an ellipsis; the full title is in the tooltip and the accessible name |
| Folders, Videos | "No folders yet" or "No videos match" with one button | Skeleton cards, keeping the page's own scroll position | As Home | 5,000+ items: lazy grids only, pictures at the size shown (M1) |
| Settings | Search with no result: "No settings match '…'" | Live values (screen, GPU) show "Checking…" | A value that can't be read says so, never a guess (M2 rule) | Rows wrap their subtitle; controls never shrink under 40 dp |
| Window | First start | | Monitor gone: rule 3 | 4K at 100 % up to 1600 × 1000 dp |

Names in any script (Unicode paths) fall back from Segoe UI Variable through Skia's font fallback; a CJK and an Arabic
folder name are part of the hand tests.

## 7. Interaction

- **Mouse:** hover lifts cards and shows their actions; right click opens the card menu; Ctrl + wheel changes the card
  size; the side back button goes back; double-click and keyboard shortcuts in the player stay as they are.
- **Keyboard:** Tab and Shift+Tab move in reading order; arrows inside grids and lists; Ctrl+F search; Esc and Alt+Left
  back; F6 moving between the navigation and the page is a nice-to-have, not required.
- **Touch (M3 detects it):** 48 dp targets, 56 dp rows, ⋮ always shown on cards, long press for the menu, press tint.
- **Controller (M5):** the ring always on, LB/RB between sections, B back, A select, Y search.
- **Resizing:** the class changes 100 ms after the last resize; scroll position and keyboard focus carry over; the video
  keeps playing.
- **Player overlay (item 8, owner, 25 November).** Today the full-screen bars hide 2.5 s after the last key, click,
  wheel or pointer move (`PlayerChromeClock`), and stay while paused, while the seek bar is dragged, while Motion
  options is open, or while the pointer is over a bar (`rememberPlayerChrome` in `Main.kt`). M3 changes three things:
  1. **2 s, not 2.5 s.** `PlayerChromeTest`'s edges and `FullscreenWindowTest`'s comment move with it. This replaces
     plan 9.1's "after 2.5 s, as now".
  2. **Touch doesn't hold the bars open.** A tap leaves Windows' hidden pointer where it touched, so after tapping full
     screen the bar counts as hovered and the bars never hide (seen on the Claw). Hover holds them open only for a
     mouse, using M3's input mode. Likely cause and the detector are **verify** first: `GetCursorInfo`'s
     `CURSOR_SUPPRESSED` flag (the system hides the cursor after touch or pen) can be read in the existing 100 ms poll.
  3. **A click or tap on the video hides the bars at once** in full screen while they show; on hidden bars it shows
     them. Clicks on a bar act as now. This replaces plan 9.1's "A click toggles play/pause" as the full-screen
     default. **Verify** how SkyWing hears the click: libmpv's own child window likely takes it before AWT, so the
     candidates are an mpv `keybind MBTN_LEFT` that sends a `script-message`, or `input-cursor-passthrough`.

## 8. Tokens and components against today's code

| Plan | Today | M3 |
|---|---|---|
| `SkyColors`, `SkySpace`, `SkyRadius`, `SkyMotion`, `SkyType` | `SkyTheme` (background, surface, card, accent, menu, panel; `ink`, `muted`) | New tokens; themes mapped and the saved choice migrated |
| `SkyButton` (Accent, Standard, Subtle) | `SkyButton` | Restyled, three variants |
| `SkyIconButton` with a tooltip | `SkyIconButton` (named through `accessibleName`) | Tooltip added; the name stays |
| `SkyTextField` | `SkyField` | Label above, 36 dp, radius 8, error line |
| `SkyToggle` | `SkySwitch` | Restyled; keeps its check-box mapping |
| `SkySegmented` | `SkyChipBar`, `SkyChip` | Replaces the pill chip bar |
| `SkyListRow` | `SettingsRow`, `NavRow` | One row component |
| `SkyCard` | Card code in `VideoViews.kt`, `FoldersScreen.kt`, `CardPictures.kt` | One card with lift, focus and progress |
| `SkyDialog`, `SkyMenu` | `SkyMenu`, several dialogs | `layer3`, radius 16, fade and scale from 0.96 |
| `SkyToast`, `SkyEmptyState`, `SkySkeleton` | none | New |
| Glass, wallpaper | `skyGlass`, `skyWallpaper` | Deleted once unused |

A suggested order for small `M3:` commits, each with its tests:

1. Tokens and the theme mapping.
2. Components.
3. `LocalAdaptive`.
4. The shell, with the `SwingPanel` kept in place.
5. Home.
6. Grids and the sort menu.
7. Settings.
8. Window placement.
9. A last accessibility pass.

## 9. Decisions

**Decided by the owner** (2026-11-25)

- **D1. The wide rail starts at 1200 dp:** it follows 6.6's width classes, not 6.4's 1100 dp. The M3 runner's sweep
  expects the narrow rail at 1150 dp.

The owner then left O1–O5 and A1–A6 to the recommendations ("do whatever you feel is good", 2026-11-25), so each is
decided as recommended below.

- **O1. Where Settings becomes two panes.** 6.5 says from 900 dp with a 280 dp list. 6.6 and the Tokens board say from
  720 dp with a 240 dp list, and 288 dp from 1200 dp. At 720 dp, 6.6 leaves the page about 400 dp, too narrow for the
  Playback page's rows. *Decided:* two panes from 900 dp; a 240 dp list below 1200 dp and 288 dp from 1200 dp; search
  always in the list. The runner checks two panes at 1300 and 1000 dp, and one pane at 680 dp.
- **O2. The rail beside Settings at 1200 dp and up.** Board `SettingsDesktop` draws the 72 dp rail at 1440 dp; 6.6 says
  240 dp at that width. *Decided:* follow the width class everywhere, so the rail doesn't jump when you open Settings
  and the Now playing card stays.
- **O3. Settings categories.** *Decided:* the mapping in 5.5, with Performance shown only once it holds a real setting.
- **O4. Columns.** *Decided:* the user's fixed column count and card size win; the width class sets only the default
  (5.4).
- **O5. The start page.** *Decided:* Home, as on board `Main` (today it is Folders). Existing UI tests that expect
  Folders at start must then open Folders first.

**Assumptions in this brief** (accepted with the decisions above)

- A1. Now playing is a rail item with a progress ring in Medium, and a fifth tab in Compact, while a video is loaded.
- A2. Page titles are Display 28 at Medium and Expanded (the boards) and Title 20 in Compact; 6.4's text says Title.
- A3. Quality chips show only what the library already knows; no file is probed for them.
- A4. Pinned folders wait until pinning exists.
- A5. In two panes, Esc moves focus to the category list rather than leaving Settings.
- A6. The focus ring is the accent colour (Tokens board, 6.6) rather than white (6.2's `focus` value); the `focus` token
  keeps it changeable per theme.

## 10. Acceptance

| M3 item | Automatic check | By hand |
|---|---|---|
| 1. Tokens and components | Compose tests of component states; runner `pages` gallery against the boards | Owner compares the gallery; Light and OLED themes |
| 2. `LocalAdaptive` | Unit tests of the width classes (the edges 719, 720, 1199, 1200) and of the input-mode rules | Touch on a handheld switches density between gestures |
| 3. Shell and Home | Runner `pages`: the width sweep (navigation shape, the top video still on the page) | Resize across 720 and 1200 dp while a video plays: no black flash, playback goes on (the video window survives) |
| 4. Grids and sort | Unit tests of the column rule and of the sort flip | Ctrl + wheel, multi-select, right-click menus |
| 5. Settings | Runner `settings`: two panes at 1300 and 1000 dp; five ways back at 680 dp. Semantics tests of the reselect handler | Alt+Left; search highlight; every moved row found by its old name |
| 6. Window | Runner `window`: rules 1–5. Unit tests of the placement rules | Rule 8 on the laptop with an external monitor; both handhelds |
| 7. Accessibility | Semantics tests per section 5.7; runner audit with no findings | NVDA and Narrator read the pages; Windows text size and 125–200 % scaling |
| 8. Player overlay | Unit tests: the clock hides at 2000 ms; hover holds the bars only in Mouse mode; a video click hides them at once | Claw: tap full screen and touch nothing, the bars hide after 2 s; a tap on the video hides them. Laptop: a mouse resting on a bar keeps it; a click on the video hides it |
| Everything | Runner `numbers`: before and after | Playback, full screen in and out, Alt-Tab, close (checks runner `fullscreen` and `stress`) |

Hand tests run on the laptop (RTX 5070 Ti), the MSI Claw 8 and the OneXPlayer 3 (Intel Arc B390).

The built M3 was reviewed against this brief with the Intent methods (owner's request, 25 November):
`evaluation-m3-new-look.md` (heuristics and anti-patterns), `accessibility-m3-new-look.md` (WCAG 2.2) and
`states-m3-new-look.md` (states and edge cases).
