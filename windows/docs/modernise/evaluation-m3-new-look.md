# The M3 new look at commit 45f7463: shell, Home, Folders, Videos, Files and drives, Settings and the window

## Anti-Pattern Verdict

Clean.

## Priority Issues

### Major (significant friction, potential user harm)

**At the narrowest windows the Videos page cuts "Play all" down to its first letter.**
- What happens: the M3 runner's pictures at 640 and 680 dp show the Videos toolbar keeping the source chips, Sort, the
  card sizes, Select and Play all on one line, with Play all squeezed to "P"; an opened folder's header has the same
  single line.
- Why it matters: the page's main action disappears at a size the brief promises always fits.
- Breaks: recognition rather than recall.
- Change: when the line can't hold the title and the actions, the actions move to their own line under the title, and
  every button keeps its full width (made with this review: `HeaderLine`, tested at the 640 dp window).

**Keyboard users can't search, go back or cross a grid the way the brief says.**
- What happens: Ctrl+F does nothing, Esc goes back only from a one-pane Settings page, Alt+Left goes back nowhere
  (`Main.kt` handles only the mouse's side button), and Tab visits every card in turn.
- Why it matters: a keyboard user crossing a library of hundreds of videos has to press Tab once per card, and the way
  back changes from page to page.
- Breaks: flexibility and efficiency of use.
- Change: Ctrl+F focuses the page's search, Esc and Alt+Left go back wherever the side button does, and the grid is one
  stop in the Tab order that arrows move through.

**The window can be dragged smaller than the minimum the layout is built for.**
- What happens: Java applies the 640 by 480 minimum in screen pixels on Windows (JDK-8221452), so at 150 % scaling the
  window drags down to about 427 by 320 dp, where the top tabs and toolbars overflow.
- Why it matters: every "always fits" promise in the brief assumes the 640 by 480 dp minimum.
- Breaks: error prevention.
- Change: Windows is given the minimum in pixels for the window's current scaling through the window's own minimum-size
  message, the one change brief 5.6 rule 7 allows.

**Two check boxes on Settings › App layout had no name for screen readers.**
- What happens: the runner's audit found the Folders-tab boxes reading as "check box" and nothing more.
- Why it matters: a screen reader user can't tell the two boxes apart or know what they change.
- Breaks: recognition rather than recall.
- Change: each box carries its tab's name, as the folder picker's boxes do (made in commit 45f7463).

**The grid jumped back by several rows when a width change altered the number of columns.**
- What happens: the runner's width sweep showed earlier videos at the top after a narrowing, because the grid kept the
  start of the first line rather than the video there.
- Why it matters: the user loses their place in a long library just by resizing the window.
- Breaks: user control and freedom.
- Change: the video at the top stays on the first line across column changes (made in commit 45f7463).

### Minor (degraded experience, recoverable)

**Home's "Add folder" opens the Files page instead of adding a folder.**
- What happens: with an empty library, Home's only button is "Add folder", and it shows the Files page, where the user
  presses Add again (`Main.kt`, the Home wiring).
- Why it matters: the first button a new user presses doesn't do what it says.
- Breaks: match between system and the real world.
- Change: the button opens the folder picker itself.

**One place has three names.**
- What happens: the wide rail says "Files and drives", the narrow rail and top tabs say "Drives", and the page is titled
  "Files".
- Why it matters: the user can't confirm they arrived where they meant to go.
- Breaks: consistency and standards.
- Change: the page is titled with the navigation's full name, and the short label stays its abbreviation.

**A long card title is cut with an ellipsis and has no tooltip.**
- What happens: titles stay on one line as designed, but the full title the brief's states table promises on hover is
  missing.
- Why it matters: two videos with the same long start can't be told apart without opening them.
- Breaks: recognition rather than recall.
- Change: a cut title shows in full in a tooltip, as icon buttons already do.

**Parts of the brief's Settings design aren't built.**
- What happens: there is no "Search settings" field and no breadcrumb, and pressing Settings again in two panes doesn't
  move focus to the category list.
- Why it matters: a user who doesn't know which category holds a setting has to open them one by one.
- Breaks: flexibility and efficiency of use.
- Change: Settings gets its search, breadcrumb and focus return as brief 5.5 describes, or the brief drops them.

### Cosmetic (polish, not blocking)

**A thin black line runs along the bottom of every page.**
- What happens: the video surface is kept alive one dp tall while no video shows (`Main.kt`, the parked video panel),
  and it draws as a black line across the content.
- Why it matters: it reads as a rendering fault on an otherwise clean page.
- Breaks: aesthetic and minimalist design.
- Change: the parked surface stays alive but out of sight, which needs the playback and full-screen checks because it
  is the video's own window.

## Positive Findings

- Navigation changes shape by width as the brief says, and every entry is a tab with a selected state.
- Home opens on Continue watching and the newest videos, with a factual count and still grey cards while it loads.
- Settings keeps its categories beside the page from 900 dp, and Back, Esc and Settings again all return to the list
  below that.
- The focus ring shows only after keyboard use and stays at 3.9:1 or more against every surface of every theme.
- Text contrast is pinned by tests in every theme, and the Adaptive accent holds 5:1 for every hue it can take.
- The window opens by the screen's size and remembers its place per screen.
- M3 starts faster, scrolls with shorter slow frames and uses less memory than M2 on the same library.
