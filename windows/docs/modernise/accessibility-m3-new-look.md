## Accessibility Audit — Per WCAG Principle

### Perceivable

- **Medium.** At 640 to 700 dp the Videos toolbar and an opened folder's header squeeze "Play all" to "P", so the page's
  main action can't be read or found at the narrowest window. Fails 1.4.10 Reflow (in spirit: content is lost at a
  narrow width). Change: when the line is narrower than the title (at least 200 dp) plus the actions, the actions move
  to their own line under the title and every button keeps its own width. Test: `HeaderLineTest` at 608 dp (the 640 dp
  window less the page padding) finds Play all at its full width, inside the line and under the title; at 1100 dp it is
  on the title's line. Made with this review.
- **Medium.** The window can be dragged to about 427 by 320 dp at 150 % scaling, below the 640 by 480 dp the layout is
  built for, because Java sets the minimum in pixels (JDK-8221452). Fails 1.4.10 Reflow at that size. Change: the
  window's minimum-size message gives Windows 640 by 480 dp times the current scale (960 by 720 px at 150 %), updated
  when the window moves to a screen with another scale. Test: the runner's `window` check drags the window to its
  smallest at 150 % and finds it at least 960 by 720 px. Built: `794d05c`
  ([plan](../../../docs/superpowers/plans/2026-11-26-window-minimum-size.md)).
- **Low.** A card title cut by an ellipsis has no tooltip, so a mouse user can't read it in full; screen readers do get
  the full title. Meets WCAG; falls short of the brief's states table. Change: a cut title shows in full in a tooltip
  after 600 ms, the icon buttons' delay. Test: hovering a card whose title is cut shows the full title after 600 ms.
- **Low.** The focus ring's contrast holds today but no test pins it, so a later colour change could drop it below
  3:1 unnoticed. Meets 1.4.11 Non-text Contrast today. Change: `SkyTokensTest` pins the
  ring at 3:1 or more against every layer of every theme. Test: the new case fails when Light's accent is lightened
  until the ring falls under 3:1.
- Keep: text at 7:1 or more and secondary text at 4.5:1 or more on every surface of every theme, pinned by
  `SkyTokensTest`.
- Keep: the focus ring at 3.94:1 or more (Light, its lowest) and up to 8.68:1 (OLED).
- Keep: the Adaptive accent from `fromHsv(h, 0.55, 1)` at 5.0:1 or more on the page and for text on the accent, and
  3.9:1 or more on a selected row, for every hue.
- Keep: the chosen theme shown with a check mark, not by colour alone.

### Operable

- **High.** The card grids are not one Tab stop: Tab visits every card, so crossing a library of hundreds of videos
  takes hundreds of presses, and arrows do nothing. Meets 2.1.1 Keyboard; fails the brief's section 7. Change: each grid
  is one Tab stop; Left and Right move one card, Up and Down one row, Home and End to the first and last card, Page Up
  and Page Down by one screen, and Tab leaves the grid. Test: a Compose test on a 48-card grid at 4 columns: Tab enters
  on card 1, Right three times reaches card 4, Down reaches card 8, and one Tab leaves the grid.
- **Medium.** When a one-pane Settings page goes back to the list, nothing moves focus (no `FocusRequester` in
  Settings), so the next Tab starts again from the window's first control. Fails 2.4.3 Focus Order. Change: focus
  returns as the keyboard map below says. Test: a semantics test opens Appearance at 680 dp, presses Back, and finds the
  Appearance entry focused.
- **Low.** Esc goes back only from a one-pane Settings page, Alt+Left goes back nowhere, and Ctrl+F doesn't reach the
  search; the mouse's side button goes back everywhere. Meets WCAG (every page has a Back button); falls short of the
  brief's section 7. Change: Esc and Alt+Left do what the side button does on every page, Ctrl+F focuses the search on
  Videos and on an opened folder, and Esc in a search with text clears it first. While full screen, Esc leaves full
  screen as now. Test: the automation suite opens a folder, presses Alt+Left and finds the Folders list; presses Ctrl+F
  on Videos and finds the search focused.
- Keep: targets of 40 dp for a mouse and 48 dp for touch.
- Keep: the focus ring as a 2 dp gap and a 2 dp ring, shown only after keyboard use.
- Keep: the full-screen bars hide after 2 s and come back on any key, pointer move or tap.

### Understandable

- **Low.** One place has three names: "Files and drives" on the wide rail, "Drives" on the narrow rail and top tabs,
  and "Files" as the page title, so a screen reader user arriving on the page hears a name they didn't choose. Meets
  3.2.4 Consistent Identification narrowly (the short label is an abbreviation). Change: the page title is "Files and
  drives". Test: the page title's text equals the wide rail entry's label.
- **Low.** Home's "Add folder" opens the Files page, where the user presses Add again. Meets WCAG; the button's name
  doesn't match what it does. Change: it opens the folder picker ("Add drive folders"). Test: the automation suite
  presses Home's "Add folder" and finds the picker's Cancel button.

### Robust

- **Medium.** The Now playing entry is named only "Now playing" (`Navigation.kt`), so a screen reader user doesn't hear
  what is playing, which the screen shows beside it. Fails 1.3.1 Info and Relationships. Change: the entry's
  description is the title and the time left, as "<title>, 12 min left". Test: the automation suite plays a clip and
  finds the Now playing tab's description starting with its title.
- **Medium.** No change is announced: there is no live region anywhere, so "3 selected", the library finishing loading
  and a folder being added are silent. Fails 4.1.3 Status Messages. Change: the live regions in the table below. Test: a
  semantics test finds each listed node marked polite; NVDA speaks "3 selected" by hand.
- Keep: navigation entries and Settings categories are page tabs with a selected state.
- Keep: every icon button has a name and a tooltip.
- Keep: switches and check boxes are named through their description, so NVDA doesn't call them read only (the App
  layout boxes since commit 45f7463).

## Screen Reader Flow Documentation

When a header's actions move under its title, they stay after the title in reading and Tab order.

| Change | Announced | Politeness |
|---|---|---|
| A selection's count changes | "3 selected" | Polite |
| The library finishes loading | "Library loaded" | Polite |
| A folder is added | "<folder> added" | Polite |
| Settings search finds nothing (when built) | "No settings match '<words>'" | Polite |

## Keyboard Navigation Map

| When | Focus goes to |
|---|---|
| Back from a one-pane Settings page (button, Esc, side button) | The category entry the page belongs to |
| Settings pressed again in two panes | The selected category entry |
| Tab into a card grid | The card last focused there, or the first card |
| Back from an opened folder | That folder's card in the list |
| Ctrl+F on Videos or an opened folder | The search field |
| The folder picker closes | The Add button that opened it |

## Open Questions

- Does Compose Desktop 1.8.2 pass live regions to Java Access Bridge, so NVDA and Narrator speak them? If not, the
  announcements need another route.
- Does SkyWing follow Windows' text size setting (Accessibility › Text size)? It hasn't been tried.
- NVDA and Narrator haven't been run on the M3 pages by hand, nor scaling at 125 to 200 %.
