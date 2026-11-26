# Actor pages for SkyWing Windows: design

Date: 2026-11-26. Status: draft for the owner's approval. Nothing is built yet.

The clickable mock-up the owner approved (Version 6, private artifact) shows most of this document. Where the
mock-up and this document differ, this document wins; the differences are listed in section 9.

## 1. Intent

Today an actor in SkyWing Windows is a plain folder card: a name group made from file names inside a Virtual folder
(or inside a Custom folder). Opening it shows a grid of videos and nothing else. The owner wants actors to feel like a
film library: a grid of faces, and a proper page for each actor that makes it quick to pick up where you left off,
find a video, and move to the next actor, with pictures that look right on a PC.

Success: from the Virtual folder, the owner can open an actor, resume the last video in one click, come back to the
same place, and jump to the next actor without going back; every picture can be changed; nothing on the drive is
ever changed or kept awake.

## 2. Goals, non-goals, anti-goals

Goals
- An actor grid and an actor page for name groups in Virtual folders and in Custom folders.
- Two page layouts, Header (default) and Studio, chosen in Settings.
- One View button on every library page, remembered per folder and per actor.
- Better right-click menus for actors and videos, made for a PC (and for touch on the handheld).
- A watched mark that survives finishing a video, Mark as watched / unwatched, Shuffle.
- Change picture / Change photo with two tabs (step 2), and ThePornDB pictures (step 3).

Non-goals
- Drive folders (Original-style pages, a drive folder opened inside a Custom or Virtual folder) keep today's page.
- No new controller / big-screen mode (planned for M5).
- No network drive Slow / Offline banner work here: that belongs to M4 (network drive). Step 1 only shows grey
  placeholders while pictures load, as today.
- Android is a source of ideas, not a list to copy. Android-only items (Download, Cut, Copy, Paste) are not added.

Anti-goals (a failure even if it works)
- Writing anything to a network drive or share, or polling one to keep it awake.
- Losing today's actions: Select, Rename with the join rule, Details, Take out (now Hide), Add to folder.
- Rebuilding or disposing the video surface when going to and from the player.
- A page that jumps when pictures arrive, or a grid that loses its place after playing a video.

## 3. Constraints

- Kotlin/JVM 17, Compose Desktop 1.8.2. Keep the existing architecture (`HubState`, `FolderLogic`, `TileGrid`,
  `FolderTile`, `VideoTile`, `GridControls`, `FolderSelectionBar`, `NameSpec`, `ChangeCoverDialog`, `ArtworkKeys`).
- M3 width classes stay: top tabs under 720 dp, 72 dp rail from 720 dp, 240 dp rail from 1200 dp; two panes from
  900 dp (`windows/docs/modernise/M3-DESIGN-PREP.md` section 5).
- Pictures chosen by the user are stored on this PC only (`%LOCALAPPDATA%` artwork store), never next to the video.
- Keep per-file settings, the `history.json` format, the MSI upgrade identity, native payload hashes and legal notices.
- Windows' "Show animations" off means no movement: switch instantly.
- Testing follows `windows/TESTING-POLICY.md`; UI changes need the owner's hands-on check on the PC and the Claw.
- Only `windows/` and `docs/` change. Android code is not touched.

## 4. Approaches considered

1. **Three steps on their own branch, stacked on M3 (chosen by the owner, 2026-11-26).** Step 1 the page, menus,
   View and the layout setting; step 2 the picture dialogs; step 3 ThePornDB after M4. Each step is its own pull
   request and can be tested on its own. Trade-off: the page first ships with today's Change cover dialog behind the
   new menu words.
2. All at once in M3. One large change that is hard to test and review, and it would pull ThePornDB ahead of the
   network drive work.
3. Only the page, pictures much later. Fastest, but leaves the mock-up's most visible part (pictures that fit 2:3 and
   portrait shapes) half done.

## 5. Step 1: actor grid, actor page, menus and View

### 5.1 Where it applies

- A Virtual folder's generated name subfolders (`HubState.setId` + `setGroup`, artwork key `vset:<setId>:<identity>`).
- A Custom folder's name groups (`HubState.ownId` + `ownGroup`).
- The Virtual folder page itself becomes the **actor grid**, in three sections with small headings: **Folders**
  (child virtual folders and directly chosen drive folders, as today), **Actors**, then **Videos** (videos chosen
  directly, as today). Sort and the A–Z letters act on actors only; search filters actors and videos by name. Actors
  use the actor shape from View, direct videos the video shape. A mixed selection shows only the actions that fit
  every selected item, so today's Take out stays for direct folders and videos. *(builder-check call)*
- A Custom folder's page shows its name groups as actor cards in the same way, with the same sorts. *(builder-check
  call)*
- **One actor per folder**, as today: an actor in two folders is two cards. The photo key stays per folder
  (`vset:<setId>:<name>`, falling back to the library-wide `virtual:<name>`; Custom groups keep the library-wide key),
  and the new dot, "last opened" and View records use the same per-folder key. *(builder-check call)*

### 5.2 Actor grid

- Cards show the actor photo (Portrait 3:4 or Landscape 16:9, from View), the name in Windows' serif (Cambria), and a
  line "12 videos · 3 new" or "12 videos · 8 watched".
- No photo yet: the actor's initials on a tinted tile of the same shape, so nothing jumps when a photo arrives.
- A small dot on an actor with new videos (section 5.8); it goes once the actor page has been opened.
- A–Z letters beside the grid when the list is long (more than about two screens).
- Sort (the existing folder sorts, same as Android): Name, Number of videos, Latest by date, Total size; click again
  to reverse.
- Search box above the grid filters actors by name; Ctrl+F focuses it.

### 5.3 Actor page, both layouts

Shared by both layouts:
- **Actor list** on the left (A–Z, with photos and counts) from 900 dp of page width; a search icon above it filters
  the list as you type; Enter opens the first match; Esc clears, then closes it. Below 900 dp the list hides and
  ‹ › buttons at the top go to the previous or next actor.
- **Resume** button: the video most recently half watched for this actor, with time left ("Resume · 23 min left").
  If none, it reads **Play newest**. **Play all** and **Shuffle** sit beside it.
- **Sort bar** that sticks under the top while scrolling: Newest, A–Z, Longest; a **Hide watched** switch; View.
- **Video tiles**: picture, title, length, a progress bar, a tick when watched, a "New" tag when new.
- The line under the name: "24 videos · 9 watched · 2 new".
- Ctrl+F searches this actor's videos; no match shows a message and a Clear search button.

**Header layout (default):** a big photo, the name and two counts at the top; scrolling folds them into a slim bar
that stays at the top (photo, name, Resume). The side menu folds to its 72 dp icon rail on this page to give the
videos room; a pin at the bottom of the rail, **Keep menu open**, keeps it wide and is remembered. In windows under
1200 dp nothing changes (the rail is already narrow).

**Studio layout:** a small header that does not fold. Under the name: a big **Continue watching** picture with the
title, a progress bar, the time left and Resume on it, and **Up next** beside it (the next video by the current sort).
Then the videos. The Continue picture comes from SkyWing's own history, so it shows at once even when the drive is
slow.

**Settings › Library › Actor page layout:** Header (default) or Studio.

### 5.4 Moving between grid and page

- Clicking an actor: the photo moves from the card into the page header in about 0.3 s (Compose
  `SharedTransitionLayout`), and back to the same card on the way out. With Windows animations off it switches at once.
- Back: Esc, Alt+←, the mouse back button, or ← in the top bar returns to the grid with the actor card in view and
  focused.

### 5.5 Coming back from a video (owner: "do what you feel right")

- Stopping playback returns to the page the video was played from (today's behaviour), same actor, same scroll
  position (today's per-list `HubState.grid` keeps it), same header state.
- Keyboard focus lands on the card of the last video that played, if it is on this page. The page scrolls only if
  that card is out of view.
- That card's progress bar, watched tick and the Resume button update straight away.
- The photo animation does not replay. If the actor is gone (renamed into another, hidden, emptied), the page returns
  to the actor grid, as today.

### 5.6 Right-click menus (owner: "improve it for a PC")

Menus open by right-click, the menu key, Shift+F10, a long press on touch, or the ⋯ button. Icons sit in one column so
the words line up. Shortcuts are shown on the right.

**Actor** (card on the grid, name in the actor list, ⋯ on the actor page):

| Item | Notes |
|---|---|
| Open | Enter. Not on the actor page itself. |
| Play all | |
| Shuffle | New on Windows (Android has none). |
| — | |
| Change photo… | Step 1 opens today's Change cover dialog; step 2 replaces it. Hidden when Artwork is off, as today. |
| Rename… | F2. "Only the name in SkyWing changes. Giving it the name of another actor joins the two. Your files keep their names." (today's text, actor wording) |
| — | |
| Show in File Explorer | Opens Explorer on the folder of their videos. If their videos are in several folders, a submenu lists the folders with counts. Read only. |
| Details | Alt+Enter. Name, videos, total size, total length if known, "Actor in <folder>", and today's note that a video in several actors is the same file. |
| Select | Kept for touch on the handheld; Ctrl+click and Shift+click also select. |
| — | |
| Hide from <folder name> | Today's "Take out of this folder", in plainer words. No dialog: a notice "Hidden. Undo" for a few seconds. The grid shows **Show hidden actors (N)** to bring them back. |

Custom folder name groups show the same menu minus anything that group kind can't do today (for example, Rename if
own-folder groups have no rename store). Instead of Hide they keep today's **Take out of <folder name>…** with its
confirm, because it really removes those videos from your Custom folder; there is no hidden list there. *(builder-check
call)*

**Video** (tile on any library page, so the menu is the same everywhere):

| Item | Notes |
|---|---|
| Resume (12:34) / Play | Enter. "Resume" with the time when half watched. |
| Play from start | |
| — | |
| Mark as watched / Mark as unwatched | Section 5.7. |
| — | |
| Change picture… | Shows what it uses now on the right ("Automatic", "Scene at 12:40", "Your file"). Hidden when Artwork is off. |
| Add to folder… | Kept. |
| Add to virtual folder… | Kept. |
| Rename file… | F2. Today's "Rename video…": it renames the real file, so the word says so. |
| — | |
| Show in File Explorer | Opens Explorer with the file selected. Read only. |
| Details | Alt+Enter. |
| Select | Kept for touch; Ctrl+click and Shift+click also select. |

Selection bar (actors): Play all, Shuffle, Hide. Selection bar (videos): Play, Mark as watched, Mark as unwatched,
Add to folder, Add to virtual folder.

### 5.7 Watched

- Today a finished video simply drops out of history, so "watched" is not remembered. Add a small **watched store** on
  this PC (its own file beside `history.json`, so the history format and older versions are untouched): a set of
  video paths with the time they were marked.
- A video becomes watched when playback ends or stops within the point where history already treats it as finished
  (the last 30 s, `WindowsPlaybackHistoryStore.saveProgress`), and by **Mark as watched**.
- **Mark as unwatched** removes the mark and any saved position.
- Playing a watched video again keeps the tick and shows progress; finishing it again just refreshes the time.
- Paths follow renames (`renamePath`), like history. The store survives a library rescan.
- Continue watching rules stay as they are on Windows (more than 5 s, not in the last 30 s, newest first).

### 5.8 New videos

- A video is **new** when SkyWing first indexed it within the last 7 days and it has never been played. Videos that
  already exist when this ships are not new.
- An actor shows the dot and "N new" until its page is opened; the "New" tag on a video stays until it is played or
  7 days pass.
- Only videos that appear in a rescan of a drive folder that had already finished a scan can be new. The first scan
  of a newly added folder, and a library rebuilt after a damaged index, mark everything they find as already seen, so
  a big old share added later doesn't light up thousands of "new" tags. *(builder-check call)*
- First-indexed times and "actor last opened" times are kept on this PC; the media index format must stay readable by
  today's code (a separate store, or an optional field that old code ignores: builder's choice, tested either way).

### 5.9 View, on every library page

- One **View** button: for actor grids Portrait (3:4) or Landscape (16:9); for videos **Scene (16:9)** or **Poster
  (2:3)** (owner kept 2:3, the usual poster shape on a PC); for both Small, Medium or Large, and Grid or List.
- Ctrl+mouse wheel, Ctrl + and Ctrl − change the size; Ctrl 0 goes back to Medium (Ctrl+wheel exists today).
- Sizes keep today's 18 steps (`CardSizes.STEPS`), so Ctrl+wheel stays fine-grained. Small, Medium and Large in View
  are three named points on that scale (Medium is today's normal size); View shows the nearest one. *(builder-check
  call)*
- Remembered per page (each folder and each actor; today's `tallPages` and card sizes become per-page View records).
  A page never set before starts with the last choice made anywhere: a View change on any page also updates the
  defaults in Settings › App layout ("Show as list" and the size). App layout's column count (Auto, 2–5) stays one
  setting for the whole app. *(builder-check call)*
- **Settings › Library › Reset view settings** clears all of them.
- In Poster shape, until step 3 brings film posters, Automatic uses today's picture (taken 10 % into the video)
  cropped to 2:3 around its centre. No second picture pass. Step 2 lets you move the crop or pick another moment.
  *(builder-check call; the mock-up said "the middle of a scene")*

### 5.10 Keys and focus

Arrows move between cards; Enter opens or plays; typing a name jumps to it; Esc, Alt+← and the mouse back button go
back; F2 renames; Alt+Enter shows details; the menu key or Shift+F10 opens the menu; the grid, the actor list and the
sort bar are one Tab stop each. Focus is always visible.

### 5.11 Looks

Theme colours stay (no flip to a light look when opening an actor); thin scrollbars in theme colours; no glass or blur.

## 6. Step 2: Change picture and Change photo

- One dialog grows out of today's `ChangeCoverDialog`; no current function is lost (Auto frame and moving cover,
  Folder collage, Photo collage, StashDB, Google Images, From this PC, Reset).
- Two tabs. **From this video** (video) / **From the videos** (actor): Automatic, pick a moment (a frame picker with
  −5 s, −1 s, +1 s, +5 s, as Android's thumbnail picker), collages. **Find online**: StashDB photos for actors (today)
  and Google Images; step 3 adds ThePornDB. **Choose a file…** and **Reset to automatic** at the bottom.
- After a picture is picked, a **Position the picture** step shows the shapes it will be used in side by side
  (16:9 and 2:3 for videos, 3:4 and 16:9 for actors) with up/down, left/right and zoom. Each shape's crop is saved.
- When the drive is offline, frames can't be read: that tab says so, and Find online and Choose a file still work.
- **Settings › Library › Reset pictures and names** removes every chosen picture and actor rename (with a confirm).

## 7. Step 3: ThePornDB (after M4)

As planned in `SkyWing-PC-modernise-plan.md` section 11.5 (M4 branch):
- Scene pictures and film posters for videos come from **ThePornDB only** (StashDB has none). Actor photos from
  StashDB and ThePornDB, in the order the owner prefers.
- Poster shape's Automatic uses the film poster when one is found.
- The owner's own key per site in Settings › Artwork, stored with DPAPI, never sent to the other site, never logged.
  Only the words searched are sent. Unclear matches go to one review pop-up; nothing changes without the setting on.

## 8. Testing (per `windows/TESTING-POLICY.md`)

- Unit tests: watched store (mark, unmark, finish, rename, unreadable file), new-video rule, View records and reset,
  menu contents per kind and with Artwork off, return-from-video focus target, actor list filtering, length store
  (read once, re-read after a file changes, catch-up stops while a video plays), next video by itself (moves on at
  the end, stops after the last, Esc and Stop unchanged, watched marked for each video that ends).
- The pinned native player tests in `windows/FULLSCREEN-REGRESSION.md`, because the end-of-file change touches the
  player.
- UI automation where the policy has it: open actor, resume, return, next actor, menus by keyboard.
- Owner's hands-on check on the PC (100 % and 150 % scaling, both layouts, animations on and off) and on the Claw
  (touch long-press menus, Select, small window).

## 9. Where this differs from the mock-up

- Video Find online has no StashDB scene pictures (StashDB has none); film posters and scenes come from ThePornDB in
  step 3.
- Menus gain Details, Select and Hide (with Show hidden actors), and "Show in folder" becomes "Show in File Explorer";
  the video menu keeps Add to folder and Rename file.
- The actor menu has no "Set as actor photo" (as in v6).
- Network drive Slow / Offline states move to M4.

## 10. Left to the builder

- Where the watched, first-indexed and View stores live and their file names (beside `history.json`, own files).
- The exact folder-pick rule for Show in File Explorer when an actor's videos are spread over many folders (cap the
  submenu at about 10).
- Component split inside `FoldersScreen.kt` (it is already over 1,100 lines; a new `ActorPage.kt` is expected).

## 11. Decided by the owner after the builder check (2026-11-26)

1. **Video lengths: read once.** The library index has no video lengths; SkyWing knew a length only after a video had
   been played. Step 1 reads the length in the same file open that already makes the video's picture
   (`ThumbnailStore.prefetch`, only while idle and nothing plays). Videos that already have a picture get a one-time
   catch-up read under the same rules, then never again. Lengths are kept on this PC in their own store, keyed like
   the thumbnails (path and modified time), so a changed file is read again once. Nothing is written to the drive and
   nothing polls it. Until a length is known the tile shows none and Longest puts the video last.
2. **Next video by itself: yes.** During Play all, Shuffle and Resume, when a video ends the next one in the queue
   starts by itself; after the last one playback stops on its last frame, as today. Esc and Stop work as today. This
   is a small, separate change in the player's end-of-file handling (today mpv keeps the last frame, `keep-open=yes`,
   and a queue moves only on Next), with its own tests, kept in its own commit. Each video that ends is marked
   watched (5.7), and "the last video that played" (5.5) is the one playing or ended when the player is left.
