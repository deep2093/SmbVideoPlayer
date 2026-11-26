## State Inventory

### Every page with cards

| State | What the person sees and can do | Recovery and timings | Message | Severity |
|---|---|---|---|---|
| A network folder's drive is off or out of reach | The cards stay (the previous list is kept, `LibrarySync`), with nothing on them to say so; pressing one ends in the playback problem dialog | A chip on every card from that drive; it goes on the first scan that reaches the drive again, with no button to press | "Can't reach drive" | Major |
| A title too long for its card | The title cut by an ellipsis; the full title only by opening the video (screen readers get it in full) | A tooltip with the full title after the 600 ms the icon buttons use | The full title | Minor |

### Videos and Folders

| State | What the person sees and can do | Recovery and timings | Message | Severity |
|---|---|---|---|---|
| On Videos, a search or the Network chip matches nothing | "No videos yet. Add folders or a network share in Files.", though the library has videos (`VideosScreen`) | The message names the filter, and its button clears the search or picks All | "No videos match '<words>'" with Clear search; "No network videos" with Show all | Minor |
| Loading | "Loading…" alone in an empty grid | Still grey cards in the page's own columns, filled in place as the videos arrive, keeping the scroll position | "Loading…" stays the screen readers' text | Minor |

### Settings

| State | What the person sees and can do | Recovery and timings | Message | Severity |
|---|---|---|---|---|
| A search with no result (once Search settings is built) | Nothing yet: there is no search | The list empties to one line and a Clear search button | "No settings match '<words>'" | Minor |

## Edge Case Catalog

| Scenario | What the design does now | Change | Severity |
|---|---|---|---|
| The window is dragged to its smallest at 125 % scaling or more | Java sets the 640 by 480 minimum in pixels (JDK-8221452), so at 150 % it reaches about 427 by 320 dp and the top tabs and toolbars overflow | The window's own minimum-size message gives Windows 640 by 480 dp times the scale. Built: `794d05c` ([plan](../../../docs/superpowers/plans/2026-11-26-window-minimum-size.md)) | Major |

## Open Questions

- The brief's states table puts the "Can't reach drive" chip in M3, but it belongs with M4's drive work: which milestone
  builds it?
- Do the selection bars (the count, Select all, Play, Add to virtual folder, Add to folder) fit at 640 dp? The
  runner's pictures don't show selection.
- CJK and Arabic folder names haven't been tried by hand.
- What do Settings' live values (screen, GPU) show while they are read? The brief says "Checking…"; this review didn't
  catch that moment.
