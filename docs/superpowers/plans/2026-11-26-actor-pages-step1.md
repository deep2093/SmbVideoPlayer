# Actor pages, step 1: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build step 1 of the approved actor-page design for SkyWing Windows: actor grids and actor pages (Header
and Studio layouts), PC right-click menus, one View button per page, watched marks, New tags, video lengths, and the
next video playing by itself during Play all, Shuffle and Resume.

**Architecture:** New state lives in small JSON stores under `%LOCALAPPDATA%\SkyWing\library` (watched marks, new
videos, video lengths, page views), in the same pattern as `WindowsPlaybackHistoryStore`. None changes an existing
file format. New UI goes in new files (`ActorPage.kt`, `ActorCards.kt`, `CardMenus.kt`, `PageViews.kt`); the
existing Folders screen, grid and cards are extended, not replaced. The player change is limited to reading mpv's
`eof-reached` flag and moving the queue on.

**Tech Stack:** Kotlin/JVM 17, Compose Desktop 1.8.2, Gson `JsonFile`, libmpv through JNA, JUnit 4 + kotlin.test,
Compose UI test.

**Spec:** `docs/superpowers/specs/2026-11-26-actor-pages-design.md` (read it with this plan; section numbers below
are the spec's).

## Global Constraints

- Only `windows/` and `docs/` change. Android code (`app/`) is not touched. Never touch `codex/adaptive-motion-interpolation`.
- Kotlin/JVM 17, Compose Desktop 1.8.2, Kotlin 2.1.21. No new libraries or dependencies.
- Width classes: top tabs under 720 dp, 72 dp rail from 720 dp, 240 dp rail from 1200 dp. The actor list shows from
  900 dp of page width.
- Pictures, marks, lengths and views are stored on this PC only, in `libraryFile(...)` (`%LOCALAPPDATA%\SkyWing\library`).
  Nothing is ever written to a drive folder or share, and nothing polls one.
- Keep the `history.json` format, per-file settings, the organizer data format, the MSI upgrade identity, native
  payload hashes and legal notices.
- A store whose file can't be read (`JsonFile.Read.Locked`) writes nothing and the app keeps working.
- With Windows animations off (`SkyMotion.animations == false`), every movement is instant.
- Menu and button words are exactly as in spec 5.6, 5.3 and 5.9.
- The repo is public: no media names, personal names, user paths or real addresses in code, tests or commits; use
  `192.0.2.x` for example share addresses; no attribution lines in commits.
- Commit on the feature branch only after the owner has said yes to building with commits. Never push or open a pull
  request until the owner says "push".
- Testing follows `windows/TESTING-POLICY.md`. A single test class runs with
  `.\gradlew.bat -p windows test --tests "<class>" --no-daemon` (native and UI tests skip there); the real gate is
  `windows\tools\verify.ps1`. Every `verify.ps1` run below that takes the desktop (`-AllowDesktopFocus`) needs the
  owner's OK first. A run without that flag reports the UI tests as blocked, not as passed.
- Callbacks into Main that play something take an explicit `autoNext`, so each place states whether the queue moves
  on by itself (spec 11.2): tile clicks pass false; Play all, Shuffle, Resume, Play newest and Up next pass true.

## Review Focus

These are the five conditions most likely to hurt real use that the feature tests alone would miss. Each has its
test in the task that owns the code.

1. The drive is offline or slow during the length catch-up: each file read times out (12 s); after 3 failures in a
   row the catch-up stops for this session, and it never runs while a video plays. Test: Task 5
   `catchUpStopsAfterThreeFailuresInARow`.
2. A queue of one, or the last video ending in fullscreen: nothing reopens, fullscreen stays, the last frame stays as
   today. Test: Task 4 `lastVideoHasNoNext`, `singleVideoHasNoNext`, plus the manual check in Task 12.
3. Actor names without letters (digits, emoji, symbols) and very long names: initials show "#", the name is cut off
   with an ellipsis, and the A–Z bubble shows "#". Test: Task 8 `initialsWithoutLettersAreHash`.
4. History for a video that is no longer in this actor (renamed outside SkyWing, deleted, moved): Resume ignores it.
   Test: Task 9 `resumeIgnoresHistoryOfVideosNotInTheActor`.
5. Ctrl+0 / Ctrl+− / Ctrl++ typed while a text field has focus: the field gets the keys and the card size doesn't
   change. Test: Task 6 `viewKeysIgnoredInTextField`.

---

### Task 0: Branch and worktree

**Files:**
- Move: `docs/superpowers/specs/2026-11-26-actor-pages-design.md` and this plan into the new worktree (they are
  untracked in `m3-work` today).

- [ ] **Step 1:** Use superpowers:using-git-worktrees to create a sibling worktree folder `actor-work` on a new
  branch `feature/windows-m3-actor-pages` from `feature/windows-m3-new-look` at `e07301a`. `m3-work` stays on its
  branch for PR 18.
- [ ] **Step 2:** Copy the two documents into `actor-work` at the same paths, then remove the untracked copies from
  `m3-work`.
- [ ] **Step 3:** Run `git -C ../actor-work log --oneline -1`.
  Expected: `e07301a M3: SkyWing opens on its rail, not on the top tabs for 100 ms`.
- [ ] **Step 4: Commit** `docs: actor pages design and step 1 plan` (after the owner's OK).

### Task 1: Watched store

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/WatchedStore.kt`
- Modify: `windows/src/main/kotlin/com/skywing/desktop/WindowsLibraryData.kt` (`WindowsPlaybackHistoryStore`, around :288)
- Test: `windows/src/test/kotlin/com/skywing/desktop/WatchedStoreTest.kt`

**Interfaces:**
- Produces:
  - `internal fun finishedAt(position: Double, duration: Double): Boolean`
  - `internal class WindowsWatchedStore(path: Path = libraryFile("watched.json"))` with `fun load(): Set<String>`
    (lower-cased canonical paths), `fun mark(file: File, at: Long = System.currentTimeMillis())`,
    `fun unmark(file: File)` and `fun renamePath(old: String, new: String)`.
  - A new member `WindowsPlaybackHistoryStore.forget(file: File)`: removes that video's record.
- Paths are canonical, as history does (`ShareHosts.canonical(file.absolutePath)`).
- File shape: `{"version":1,"watched":[{"path":…,"at":…}]}`.

- [ ] **Step 1: Write the failing tests** in `WatchedStoreTest`, with a temp folder as `JsonStoresTest` does:
  - `markedVideoIsReadBackByANewStore`: `load()` contains the lower-cased path.
  - `unmarkRemovesTheMark`
  - `renameMovesTheMark`: the old path is gone and the new path is present.
  - `unreadableFileIsNeverOverwritten`: make the file unreadable the way `JsonStoresTest` does for history; `mark()`
    leaves its bytes unchanged.
  - `finishedAtBoundary`: `finishedAt(970.0, 1000.0)` is true, `finishedAt(969.9, 1000.0)` is false,
    `finishedAt(10.0, 0.0)` is false, `finishedAt(Double.NaN, 1000.0)` is false.
  - `historyForgetRemovesTheResumeRecord`: after `saveProgress(file, 120.0, 1000.0)`, `forget(file)`, then `load()`
    is empty.
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.WatchedStoreTest" --no-daemon`
  Expected: FAIL (the names are not defined).
- [ ] **Step 3: Implement** the store and `forget`. Copy `WindowsPlaybackHistoryStore`'s `JsonFile` read and lock
  pattern. No registry migration, and no cap on the number of marks.
- [ ] **Step 4: Run and see them pass.** Same command. Expected: PASS.
- [ ] **Step 5: Commit** `Actor pages: remember watched videos on this PC`.

### Task 2: New-video store

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/NewVideos.kt`
- Test: `windows/src/test/kotlin/com/skywing/desktop/NewVideosTest.kt`

**Interfaces:**
- Produces:
  - `internal object NewVideos { fun arrivals(before: List<WindowsMediaEntry>, after: List<WindowsMediaEntry>): List<String> }`
    returns the lower-cased paths that are in `after`, are not in `before`, and whose `sourceId` already had entries
    in `before`. An empty `before` gives an empty list.
  - `internal class NewVideoStore(path: Path = libraryFile("new-videos.json"), now: () -> Long = System::currentTimeMillis)`
    with `fun add(paths: List<String>)`, `fun played(path: String)`, `fun openedActor(key: String)`,
    `fun renamePath(old: String, new: String)` (moves the entry, and remembers `new` in memory so the next `add` call
    drops it once; `NewVideos.arrivals` stays a pure function)
    and `fun snapshot(): NewMarks`.
  - `internal data class NewMarks(val firstSeen: Map<String, Long>, val opened: Map<String, Long>)` with:
    - `fun isNew(path: String, now: Long): Boolean`: seen within `NEW_FOR_MS = 7 * 24 * 3600 * 1000L` and never played.
    - `fun newCount(paths: Collection<String>, now: Long): Int`
    - `fun dot(key: String, paths: Collection<String>, now: Long): Boolean`: true when some new path was seen after
      `opened[key]`.
  - Playing a video removes its path. Entries older than 7 days are dropped on every write.
- Spec rule (5.8): the first scan of a new folder and a rebuilt index mark nothing as new.

- [ ] **Step 1: Write the failing tests:**
  - `firstLoadMarksNothing` (`before` is empty)
  - `newSourceMarksNothing` (the path's `sourceId` is absent from `before`)
  - `newFileInKnownSourceIsNew`
  - `renamedVideoIsNotNew`
  - `newExpiresAfterSevenDays` (`isNew` is false at `seen + NEW_FOR_MS + 1`)
  - `playedVideoIsNotNew`
  - `dotGoesAfterOpeningTheActor`
  - `dotComesBackForALaterArrival`
  - `unreadableFileIsNeverOverwritten`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.NewVideosTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** `NewVideos` and `NewVideoStore` in `NewVideos.kt`, with the same `JsonFile` pattern as Task 1.
- [ ] **Step 4: Run and see them pass.** Expected: PASS.
- [ ] **Step 5: Commit** `Actor pages: notice videos that arrive in folders SkyWing already knows`.

### Task 3: Playback records, wired into Main

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/PlaybackRecords.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt`: the `historyStore.saveProgress` calls (about :396, :432,
    :471, :489), `openVideo`, the `history` produceState (about :342), and the `library.revision` effect (about :297).
  - `windows/src/main/kotlin/com/skywing/desktop/VideoRename.kt` (`VideoRenameService`)
- Test: `windows/src/test/kotlin/com/skywing/desktop/PlaybackRecordsTest.kt`

**Interfaces:**
- Consumes: the Task 1 and Task 2 stores.
- Produces:
  - `internal class PlaybackRecords(val history: WindowsPlaybackHistoryStore, val watched: WindowsWatchedStore, val newVideos: NewVideoStore)` with:
    - `fun save(file: File, position: Double, duration: Double)`: saves to history, and marks the video watched when
      `finishedAt` is true.
    - `fun started(file: File)`: calls `newVideos.played(...)`.
    - `fun rename(old: String, new: String)`: renames in all three stores.
    - `fun setWatched(file: File, watched: Boolean)`: unwatching also calls `history.forget`.
    - `fun openedActor(key: String)` and `fun arrived(paths: List<String>)`: pass through to `newVideos`.
    - `val revision: State<Int>`: goes up after every write above, so marks refresh at once (spec 5.5).
  - `internal data class LibraryMarks(val watched: Set<String> = emptySet(), val news: NewMarks = NewMarks(emptyMap(), emptyMap()))`
    with `fun isWatched(e: WindowsMediaEntry) = e.path.lowercase() in watched` and
    `fun isNew(e: WindowsMediaEntry, now: Long)`.
  - In Main, `val records = remember { PlaybackRecords(...) }` and
    `val marks by produceState(LibraryMarks(), historyRevision, records.revision.value) { … }`, read off the screen
    thread.
- Every `historyStore.saveProgress(...)` call in Main becomes `records.save(...)`, and `openVideo` calls
  `records.started(file)`. `VideoRenameService` gets `records` in place of `historyStore`.
- The `library.revision` effect keeps the previous `library.all` in a `remember` and calls
  `records.arrived(NewVideos.arrivals(previous, library.all))`.

- [ ] **Step 1: Write the failing tests:**
  - `finishingMarksWatchedAndDropsResume`: `save(file, 980.0, 1000.0)` leaves the video watched and history empty.
  - `stoppingMidwayKeepsResumeOnly`: `save(file, 300.0, 1000.0)` leaves it not watched, with a history record at 300.
  - `unwatchClearsResumeToo`
  - `rewatchKeepsTickAndProgress`: a watched video saved at 300 of 1000 is still watched and has a history record at
    300; finishing it again only moves its mark time forward.
  - `startedVideoIsNoLongerNew`
  - `renameMovesEverything`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.PlaybackRecordsTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** `PlaybackRecords` and `LibraryMarks`, and wire Main and `VideoRenameService`. Pass
  `marks` to `FoldersScreen` as a new parameter `marks: LibraryMarks`.
- [ ] **Step 4: Run and see them pass.** Run the class above and `JsonStoresTest`: PASS. Then run
  `verify.ps1 -Mode focused -Paths` on the changed files (Task 12 shows the command form).
- [ ] **Step 5: Commit** `Actor pages: keep watched marks and new videos as you play and rename`.

### Task 4: Next video by itself

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/QueueAdvance.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/playback/EmbeddedMpv.kt` (beside `reading()`, about :937)
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt` (`openVideo`, `openQueueAt`, the `FoldersScreen` call at
    about :648)
  - `windows/src/main/kotlin/com/skywing/desktop/FoldersScreen.kt` (`Folders.play`, the `FoldersScreen` parameters)
- Test: `windows/src/test/kotlin/com/skywing/desktop/QueueAdvanceTest.kt`

**Interfaces:**
- Produces:
  - `internal object QueueAdvance { fun next(index: Int, size: Int, autoNext: Boolean): Int? }`: returns
    `index + 1` when `autoNext` is true and `index + 1 < size`, else null.
  - `fun EmbeddedMpv.ended(): Boolean`: `flagProperty("eof-reached") == true`.
  - `openVideo(file, queuedFiles, resumePosition, autoNext: Boolean? = null)`: a non-null value sets
    `var queueAutoNext by mutableStateOf(false)`; null keeps it.
  - `FoldersScreen(..., onPlayAll: (List<File>) -> Unit)`. In Main it is
    `{ files -> files.firstOrNull()?.let { openVideo(it, files, autoNext = true) } }`.
  - `FoldersScreen(..., onResume: (file: File, position: Double, queue: List<File>, autoNext: Boolean) -> Unit)`. In
    Main it is `{ file, position, queue, auto -> openVideo(file, queue, position, autoNext = auto) }`; `openVideo` puts
    `queueIndex` on `file` inside `queue`. Tasks 7, 9 and 10 use it.
- Tile clicks pass `autoNext = false`, and `openQueueAt` passes null. `Folders.play(list)` calls `onPlayAll`; it
  serves every Play all, Shuffle (`list.shuffled()`) and the selection bar's Play.
- In Main, `LaunchedEffect(currentFile)` loops every 750 ms, reading `embedded.ended()` off the screen thread. On the
  first `true` for this file, it calls `records.save(file, duration, duration)`, which marks the video watched, and
  then `QueueAdvance.next(queueIndex, queue.size, queueAutoNext)?.let(::openQueueAt)`.
- Fullscreen, the video surface and Esc/Stop are unchanged (spec 11.2).

- [ ] **Step 1: Write the failing tests:**
  - `movesOnWhenAutoNext`: `next(0, 3, true) == 1`
  - `lastVideoHasNoNext`: `next(2, 3, true) == null`
  - `singleVideoHasNoNext`: `next(0, 1, true) == null`
  - `staysWhenNotAutoNext`: `next(0, 3, false) == null`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.QueueAdvanceTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the class above: PASS. Then run
  `verify.ps1 -Mode focused -Paths windows/src/main/kotlin/com/skywing/desktop/playback/EmbeddedMpv.kt windows/src/main/kotlin/com/skywing/desktop/Main.kt -AllowDesktopFocus`
  (ask the owner before taking the desktop). It runs the pinned player tests of `windows/FULLSCREEN-REGRESSION.md`.
  Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: Play all and Shuffle go on to the next video by themselves` (a commit of its own).

### Task 5: Video lengths, read once

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/VideoLengths.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/playback/ThumbnailExtractor.kt` (`Grab`, `Result`, and the
    `EVENT_HOOK` branch in `Player.grab`, about :164)
  - `windows/src/main/kotlin/com/skywing/desktop/Thumbnails.kt` (`generate` at about :82, `prefetchPasses` at about :175)
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt` (the 5 s playback loop, and app close)
- Test:
  - `windows/src/test/kotlin/com/skywing/desktop/VideoLengthsTest.kt`
  - one assertion added to the existing native `ThumbnailExtractorNativeTest`

**Interfaces:**
- Produces:
  - `internal class VideoLengthStore(path: Path = libraryFile("lengths.json"), now: () -> Long = System::currentTimeMillis)` with:
    - `fun get(key: String): Double?`
    - `fun record(key: String, seconds: Double)`
    - `fun flushIfDue()`: writes at most every 10 s, and only when something changed.
    - `fun flush()`
    - `val revision: State<Int>`: goes up on each write so tiles recompose.
    - Keys are `ThumbnailStore.keyOf(path, stamp)`, so a changed file is read again once.
  - `internal class LengthCatchUp(private val read: (File) -> Double?, private val store: VideoLengthStore)` with
    `suspend fun run(videos: List<Pair<File, Long>>, idle: suspend () -> Unit)`. It reads one file at a time and waits
    for `idle()` before each. It skips keys it already knows and keys that failed this session, and stops after 3
    failures in a row.
  - `internal fun formatLength(seconds: Double): String`, which delegates to `formatClock` in `SkyUi.kt:212`.
  - `ThumbnailExtractor.Result.duration: Double? = null`, read from the `duration` property in the `on_unload` hook
    beside `video-params/gamma`.
  - `ThumbnailStore.lengths: VideoLengthStore`
- Where lengths come from:
  - `generate` records the duration on success.
  - After its picture passes, `prefetchPasses` runs `LengthCatchUp({ ThumbnailExtractor.durationSeconds(it) }, lengths)`
    with the same idle wait as pictures: no card waiting, nothing playing, inside `gate.withPermit`.
  - Main records `reading.duration` once per opened video and calls `lengths.flush()` on close.

- [ ] **Step 1: Write the failing tests:**
  - `recordedLengthIsReadBackAfterFlush`
  - `changedFileIsUnknownAgain` (a different stamp gives null)
  - `writesAtMostEveryTenSeconds` (two records 1 s apart, fake clock, give one write)
  - `unreadableFileIsNeverOverwritten`
  - `catchUpSkipsKnownVideos`
  - `catchUpStopsAfterThreeFailuresInARow` (`read` returns null 3 times; a 4th video is not read)
  - `catchUpReadsNothingWhileNotIdle` (`idle()` never returns; with `runTest`, `read` is never called)
  - `formatLengthText`: `formatLength(59.4) == "0:59"`, `formatLength(754.0) == "12:34"`,
    `formatLength(3723.0) == "1:02:03"`
  - In `ThumbnailExtractorNativeTest`, `pictureReportsLength`: `result.duration` is a finite number above 0.
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.VideoLengthsTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the class above: PASS. Then run `verify.ps1 -Mode focused -Paths` on the
  changed files; it includes `ThumbnailWorkTest` and the native extractor test. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: learn each video's length once, while its picture is made`.

### Task 6: One View per page

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/PageViews.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/VideoViews.kt` (`cells`, `cardSizeFor`, `isTall`, `stepCardSize`,
    `toggleTall`, `GridControls`, `TileGrid`, `TileView`)
  - `windows/src/main/kotlin/com/skywing/desktop/SkyUi.kt` (`CardFrame`, `FolderCard`, `VideoCard`: `tall` becomes
    an aspect)
  - `windows/src/main/kotlin/com/skywing/desktop/FolderOrganizer.kt` (`CardSizes`)
  - `windows/src/main/kotlin/com/skywing/desktop/SettingsScreen.kt` (the App layout "Show as list" switch, and the
    Library page)
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt` (provide `LocalLibraryViews`)
  - `windows/src/main/kotlin/com/skywing/desktop/VideosScreen.kt` (grid controls callers)
- Test:
  - `windows/src/test/kotlin/com/skywing/desktop/PageViewsTest.kt`
  - updates to the existing tests that use `tallPages`, `isTall` or `stepCardSize` (`FolderOrganizerTest`, and the
    others that grep finds)

**Interfaces:**
- Produces:
  - `internal data class PageView(val actorPortrait: Boolean = false, val videoPoster: Boolean = false, val size: Int = CardSizes.NORMAL, val list: Boolean = false)`
  - `internal enum class ActorLayout { HEADER, STUDIO }`
  - `internal data class ViewsData(val last: PageView = PageView(), val pages: Map<String, PageView> = emptyMap(), val actorLayout: ActorLayout = ActorLayout.HEADER, val keepMenuOpen: Boolean = false)`
  - `FolderPrefs.tallPages`, `videoCardSize` and `folderCardSize` stay in `FolderPrefs`, and stay in its binary
    write and read, unchanged; the organizer data format must stay readable. They are only no longer used for display.
  - `internal class LibraryViewStore(path: Path = libraryFile("views.json"))` with
    `fun load(seed: FolderPrefs): ViewsData` and `fun save(data: ViewsData)`. When the file is missing, `load` seeds:
    `last.size = CardSizes.of(folderCardSize)` and `last.list = listMode`; the `VIDEOS_PAGE` page gets
    `size = CardSizes.of(videoCardSize)` when that is not 0; every page in `tallPages` gets
    `actorPortrait = true, videoPoster = true`.
  - `internal class LibraryViews(store: LibraryViewStore, seed: FolderPrefs)`, which holds `data` in Compose state,
    with:
    - `fun of(page: String): PageView`: the page's record, or `data.last`.
    - `fun change(page: String, transform: (PageView) -> PageView)`: stores the record and sets `last`.
    - `fun step(page: String, direction: Int)` and `fun medium(page: String)`
    - `fun setListEverywhere(list: Boolean)`, `fun setLayout(layout: ActorLayout)`,
      `fun setKeepMenuOpen(keep: Boolean)`
    - `fun reset()`: back to `ViewsData()`.
  - `internal val LocalLibraryViews: ProvidableCompositionLocal<LibraryViews>`
  - `CardSizes.SMALL = 135`, `CardSizes.LARGE = 280` (`NORMAL = 188` is Medium), and
    `CardSizes.named(size: Int): String`, which gives the nearest of "Small", "Medium" or "Large".
  - `internal object CardAspect { const val SCENE = 16f / 9f; const val POSTER = 2f / 3f; const val PORTRAIT = 3f / 4f }`
  - `internal fun viewKeyAction(key: Key, ctrl: Boolean, inTextField: Boolean): Int?` returns +1 for Ctrl with `=`,
    `+` or NumPadAdd; −1 for `−` or NumPadSubtract; 0 (Medium) for `0` or NumPad0. It returns null otherwise, and
    always when `inTextField` is true.
  - `@Composable internal fun ViewButton(page: String, actors: Boolean, videos: Boolean)` replaces `GridControls`. It
    is one menu: "Portrait" / "Landscape" (when `actors`), "Scene (16:9)" / "Poster (2:3)" (when `videos`),
    "Small" / "Medium" / "Large", and "Grid" / "List". The chosen items carry the Check icon.
- Other changes:
  - `cells(view, columns, adaptive)` replaces `FolderPrefs.cells(page, adaptive)`, and `TileGrid` reads
    `LocalLibraryViews.current.of(page)`.
  - Ctrl+wheel and `viewKeyAction` call `step` or `medium`.
  - Folder and actor cards use `PORTRAIT` when `actorPortrait`, else `SCENE`. Video cards use `POSTER` when
    `videoPoster`, else `SCENE`, cropped with `ContentScale.Crop` (spec 5.9).
  - App layout "Show as list" shows `data.last.list`, so a View change on any page shows up there (spec 5.9).
    Turning it on or off calls `setListEverywhere` and keeps `prefs.listMode` in step for older versions. The column
    count stays one setting for the whole app.
  - Settings › Library gets **Reset view settings**, with a confirm, which calls `reset()`.

- [ ] **Step 1: Write the failing tests:**
  - `unsetPageUsesLastChoice`
  - `changingAPageSetsLastChoice`
  - `seedFromOldPrefs`: a tall page gives portrait + poster; `videoCardSize = 255` gives a videos page size of 255.
  - `resetClearsEverything`
  - `listEverywhereChangesEveryPage`
  - `unreadableFileKeepsDefaultsAndWritesNothing`
  - `sizeNames`: `named(135) == "Small"`, `named(188) == "Medium"`, `named(230) == "Medium"`, `named(255) == "Large"`
  - `viewKeysIgnoredInTextField`: `viewKeyAction(Key.Zero, ctrl = true, inTextField = true) == null`, and the same
    with `inTextField = false` gives 0.
  - `viewKeysNeedCtrl`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.PageViewsTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces, and update the callers and old tests.
- [ ] **Step 4: Run and see them pass.** Run the class above and `FolderOrganizerTest`: PASS. Then run
  `verify.ps1 -Mode focused -Paths` on the changed files. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: one View button per page, remembered per folder and actor`.

### Task 7: Menus for a PC, and the video menu

**Files:**
- Create:
  - `windows/src/main/kotlin/com/skywing/desktop/CardMenus.kt`
  - `windows/src/main/kotlin/com/skywing/desktop/ExplorerShow.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/SkyUi.kt` (`MenuAction`, `SkyMenu`)
  - `windows/src/main/kotlin/com/skywing/desktop/VideoViews.kt` (`TileView` keys)
  - `windows/src/main/kotlin/com/skywing/desktop/FoldersScreen.kt` (`Folders.videoMenu`, and the video selection bar
    actions)
- Test: `windows/src/test/kotlin/com/skywing/desktop/CardMenusTest.kt`

**Interfaces:**
- Produces:
  - `MenuAction(label, icon, enabled, shortcut: String? = null, hint: String? = null, dividerBefore: Boolean = false, children: List<MenuAction> = emptyList(), action)`.
    The new parameters sit before `action`, so existing calls still compile.
  - `SkyMenu` draws a divider before items with `dividerBefore`, and shows `shortcut` (or else `hint`) on the right
    in the muted colour. Choosing an item with `children` swaps the menu to those children, with "‹ Back" as the
    first item.
  - `internal fun shortcutAction(menu: List<MenuAction>, shortcut: String): MenuAction?`
  - `internal fun explorerCommand(target: File, select: Boolean): List<String>`: with `select` it is
    `listOf("explorer.exe", "/select," + target.absolutePath)`, otherwise `listOf("explorer.exe", target.absolutePath)`.
  - `internal fun showInExplorer(target: File, select: Boolean)`: starts the process off the screen thread and never
    writes.
  - `internal data class VideoMenuState(val resumeAt: Double?, val watched: Boolean, val artworkOn: Boolean, val pictureHint: String)`
  - `internal class VideoMenuActions(val play: () -> Unit, val playFromStart: () -> Unit, val setWatched: (Boolean) -> Unit, val changePicture: () -> Unit, val addToFolder: () -> Unit, val addToVirtual: () -> Unit, val rename: () -> Unit, val showInExplorer: () -> Unit, val details: () -> Unit, val select: () -> Unit)`
  - `internal fun videoMenu(state: VideoMenuState, actions: VideoMenuActions): List<MenuAction>`
  - `internal fun clickSelection(keys: List<String>, selected: Set<String>, anchor: String?, clicked: String, ctrl: Boolean, shift: Boolean): Set<String>?`:
    - Ctrl toggles `clicked`.
    - Shift selects every key from `anchor` to `clicked` in `keys` order, added to `selected` (just `clicked` when
      there is no anchor).
    - A plain click returns null (it opens or plays, as today).
    - It is used by `TileView`/`TileGrid` with today's `hub.folderKeys`/`hub.folderPage` selection (spec 5.6:
      "Ctrl+click and Shift+click also select").
- Other changes:
  - `TileView` handles keys on the focused card: F2 → `shortcutAction(menu, "F2")`, Alt+Enter → `"Alt+Enter"`, and
    Shift+F10 or the menu key opens the menu.
  - Clicking a half-watched video card, or pressing Enter on it, resumes it (the menu's first item, spec 5.6) through
    `onResume(file, position, queue, autoNext = false)` (Task 4). "Play from start" plays from 0.
  - `pictureHint` is "Automatic" when the artwork store has no picture for `ArtworkKeys.video(path)` (use the lookup
    `FolderPicture` already uses), otherwise "Chosen". This is a step 1 call: today's store doesn't say where a
    chosen picture came from, and step 2's dialog brings the exact words ("Scene at 12:40", "Your file").
  - "Change picture…" opens today's Change cover dialog for `ArtworkKeys.video(path)`.
  - The video selection bar gains "Mark as watched" and "Mark as unwatched".
  - Video Details adds the length (when known) and "Watched" (when marked).

- [ ] **Step 1: Write the failing tests:**
  - `videoMenuHalfWatched`: the labels are exactly
    `["Resume (12:34)", "Play from start", "Mark as watched", "Change picture…", "Add to folder…", "Add to virtual folder…", "Rename file…", "Show in File Explorer", "Details", "Select"]`.
    Dividers sit before "Mark as watched", "Change picture…" and "Show in File Explorer". The shortcuts are "Enter"
    on the first item, "F2" on Rename and "Alt+Enter" on Details.
  - `videoMenuWatchedArtworkOff`: the first item is "Play" and the third is "Mark as unwatched"; there is no
    "Change picture…", and the divider moves to "Add to folder…".
  - `changePictureShowsItsSource`: the hint is "Automatic".
  - `shortcutFindsRename`
  - `ctrlClickToggles`: `clickSelection(listOf("a","b","c"), setOf("a"), "a", "b", ctrl = true, shift = false) == setOf("a","b")`,
    and Ctrl-clicking "a" again gives `emptySet()`.
  - `shiftClickSelectsRange`: `clickSelection(listOf("a","b","c","d"), setOf("b"), "b", "d", ctrl = false, shift = true) == setOf("b","c","d")`
  - `plainClickIsNull`: `clickSelection(listOf("a"), emptySet(), null, "a", ctrl = false, shift = false) == null`
  - `explorerSelectsAShareFile`:
    `explorerCommand(File("\\\\192.0.2.10\\videos\\a b.mkv"), true) == listOf("explorer.exe", "/select,\\\\192.0.2.10\\videos\\a b.mkv")`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.CardMenusTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces, and switch `Folders.videoMenu` to `videoMenu(...)`.
- [ ] **Step 4: Run and see them pass.** Run the class above: PASS. Then run `verify.ps1 -Mode focused -Paths` on the
  changed files. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: video menu made for a PC, with keys and Show in File Explorer`.

### Task 8: Actor cards and the actor grid

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/ActorCards.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/FoldersScreen.kt` (`SetPage` at about :818, the `group == null`
    part; `OwnPage` at about :584, grouped)
  - `windows/src/main/kotlin/com/skywing/desktop/VideoViews.kt` (`FolderTile`, the `TileGrid` letters)
  - `windows/src/main/kotlin/com/skywing/desktop/SkyUi.kt` (`FolderCard`, and a new `UndoNotice`)
  - `windows/src/main/kotlin/com/skywing/desktop/ui/theme/Tokens.kt` (the serif names font)
  - `windows/src/main/kotlin/com/skywing/desktop/FolderOrganizer.kt` (`restoreSubfolder`)
- Test:
  - `windows/src/test/kotlin/com/skywing/desktop/ActorCardsTest.kt`
  - one test added to `FolderOrganizerTest`
  - actor menu cases added to `CardMenusTest`

**Interfaces:**
- Consumes: `MenuAction` (Task 7), `LibraryMarks` (Task 3), `PageView` (Task 6), `Folders.play`/`onPlayAll` (Task 4).
- Produces:
  - `internal fun actorInitials(name: String): String`: the first letters of the first two words that start with a
    letter, upper-cased, or "#" when there are none.
  - `internal fun actorDetail(videos: Int, new: Int, watched: Int): String`: "12 videos · 3 new" when there are new
    videos, else "12 videos · 8 watched" when some are watched, else "12 videos". One video reads "1 video".
  - `internal enum class ActorRemoval { HIDE, TAKE_OUT }`
  - `internal data class ActorMenuState(val folderName: String, val onActorPage: Boolean, val artworkOn: Boolean, val canRename: Boolean, val folders: List<Pair<String, Int>>, val removal: ActorRemoval)`
  - `internal class ActorMenuActions(val open: () -> Unit, val playAll: () -> Unit, val shuffle: () -> Unit, val changePhoto: () -> Unit, val rename: () -> Unit, val showFolder: (String) -> Unit, val details: () -> Unit, val select: () -> Unit, val remove: () -> Unit)`
  - `internal fun actorMenu(state: ActorMenuState, actions: ActorMenuActions): List<MenuAction>` (in `CardMenus.kt`)
  - `internal fun actorFolders(videos: List<WindowsMediaEntry>): List<Pair<String, Int>>`: the parent folders with
    their video counts, most videos first, at most 10.
  - `FolderTile(..., actor: Boolean = false, dot: Boolean = false, initials: String? = null)`
  - `fun Organizer.restoreSubfolder(setId: String, identity: String)`
  - `@Composable internal fun UndoNotice(text: String, onUndo: () -> Unit, onGone: () -> Unit)`, which stays for 6 s.
  - `internal val SerifNames: FontFamily = FontFamily(SystemFont("Cambria"))`
  - `TileGrid(..., lettersOnlyForActors: Boolean = false)`
- **Virtual page** (spec 5.1):
  - Sections: Folders (child sets, then direct folders), then Actors (groups), then Videos (`directSingles`). Each
    gets a `LabelTile` when two or more sections have cards.
  - The sort and the A–Z letters act on actors only. The sort uses the `SORTS` keys with the actor labels "Name",
    "Number of videos", "Latest by date" and "Total size"; choosing the current sort again reverses it, as today.
    The A–Z letters show only when the actor section is taller than two screens.
  - Today's page search (`hub.query`, "Search here") filters every section by name. Ctrl+F focuses it from anywhere
    on the page (a `FocusRequester` on that `SkyField`). This key is new: SkyWing has no Ctrl+F today.
  - The actor key is `"set:" + set.id + "|" + identity` on a Virtual page and `"own:" + folder.id + "|" + identity`
    on a Custom page. It is used for the dot (`openedActor`) and for View. Photos keep today's keys (spec 5.1).
  - Selection bars: with only actors selected, Play all, Shuffle and Hide (Take out… on a Custom page). A mixed
    selection shows only the actions that fit every selected kind, so today's Take out stays for direct folders and
    videos. `internal enum class TileKind { FOLDER, ACTOR, VIDEO }` and
    `internal fun selectionActionsFor(kinds: Set<TileKind>, custom: Boolean): List<String>` (labels) decide it.
  - Rename… opens today's `NameSpec` rename with the hint "Only the name in SkyWing changes. Giving it the name of
    another actor joins the two. Your files keep their names." Change photo… opens today's Change cover dialog for
    the actor's photo key.
  - Show in File Explorer: with one folder it opens `showInExplorer(folder, select = false)`. With several, the item
    has `children`, one per `actorFolders` entry: the label is the folder's name and the hint is "N videos".
  - "Hide from <set name>" calls `hideSubfolders` and shows `UndoNotice("Hidden. Undo")`; Undo calls
    `restoreSubfolder`.
  - The header button "Show hidden actors (N)" (N = `set.hidden.size`) replaces the "Show folders you took out" icon
    and calls `restoreSubfolders`.
  - An actor's Details text holds the name; the number of videos, total size and total length (`formatLength` of the
    known lengths, shown only when all lengths are known); "Actor in <set name>"; and the existing line
    "A video listed in several subfolders is the same file, never a copy."
- **Custom page** (grouped):
  - Groups become actor cards, sorted with `FolderLogic.sorted` after mapping to `VirtualGroup`. The header menu
    gains the sort actions.
  - The menu uses `TAKE_OUT`: "Take out of <folder name>…" opens `dialogs.confirm` with the existing words "They are
    taken out of <folder> only. Files on your disk are not touched.", then calls `takeOutOfOwn`.
  - `canRename = false`.
- **Actor card:**
  - The name is in `SerifNames`.
  - It shows the photo (the chosen cover or portrait for the actor's cover key). Without one, it shows `initials` on
    a tinted tile of the same shape, with no video collage.
  - An accent dot sits at the top left when `dot` is set, and the screen-reader name adds ", new videos".

- [ ] **Step 1: Write the failing tests:**
  - `initialsOfTwoWords`: `actorInitials("Owen Pike") == "OP"`
  - `initialsSkipLeadingNumbers`: `actorInitials("2 Fast") == "F"`
  - `initialsWithoutLettersAreHash`: `actorInitials("123") == "#"` and `actorInitials("🎬") == "#"`
  - `detailLines`: `actorDetail(12, 3, 8) == "12 videos · 3 new"`, `actorDetail(12, 0, 8) == "12 videos · 8 watched"`,
    `actorDetail(1, 0, 0) == "1 video"`
  - `actorFoldersMostFirstAndCapped`
  - `actorsOnlySelection`: `selectionActionsFor(setOf(ACTOR), custom = false) == listOf("Play all", "Shuffle", "Hide")`
  - `mixedSelectionKeepsTakeOut`: `selectionActionsFor(setOf(ACTOR, VIDEO), custom = false)` holds "Play" and
    "Take out", and not "Hide".
  - In `CardMenusTest`:
    - `actorMenuVirtualGrid`: the labels are exactly
      `["Open", "Play all", "Shuffle", "Change photo…", "Rename…", "Show in File Explorer", "Details", "Select", "Hide from Example"]`.
      Dividers sit before "Change photo…", "Show in File Explorer" and "Hide from Example". The shortcuts are "Enter"
      on Open, "F2" on Rename… and "Alt+Enter" on Details.
    - `actorMenuArtworkOff`: no "Change photo…", and the divider moves to "Rename…".
    - `actorMenuCustom`: no "Rename…", and the last item is "Take out of Example…"
    - `actorMenuOnActorPageHasNoOpen`
    - `actorMenuSeveralFoldersHasChildren`
  - In `FolderOrganizerTest`: `restoreSubfolderBringsBackOnlyThatOne`
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.ActorCardsTest" --tests "com.skywing.desktop.CardMenusTest" --tests "com.skywing.desktop.FolderOrganizerTest" --no-daemon`.
  Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the classes above: PASS. Then run `verify.ps1 -Mode focused -Paths` on
  the changed files; it includes `HubBackNavigationTest` and the library automation tests that the policy lists for
  `FoldersScreen`. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: actor cards with photos, initials, new dots and a PC menu`.

### Task 9: Actor page, Header layout

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/ActorPage.kt`
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/FoldersScreen.kt` (the `group != null` branches of `SetPage` and
    `OwnPage` render `ActorPage`)
  - `windows/src/main/kotlin/com/skywing/desktop/LibraryModel.kt` (`HubState`)
  - `windows/src/main/kotlin/com/skywing/desktop/Navigation.kt` (the `NavRail` footer)
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt` (the `NavRail` call, about :624)
- Test: `windows/src/test/kotlin/com/skywing/desktop/ActorPageTest.kt`

**Interfaces:**
- Consumes: Tasks 3–8.
- Produces:
  - `internal data class ActorRef(val identity: String, val title: String, val photoKey: String, val pageKey: String, val videos: List<WindowsMediaEntry>)`
  - `internal enum class VideoSort { NEWEST, NAME, LONGEST }`
  - `internal sealed interface ResumeChoice` with:
    - `data class Resume(val entry: WindowsMediaEntry, val position: Double, val left: Double)`
    - `data class Newest(val entry: WindowsMediaEntry)`
    - `data object None`
  - `internal fun pickResume(videos: List<WindowsMediaEntry>, history: Map<String, WindowsWatchRecord>): ResumeChoice`:
    the most recently updated history record among these videos, otherwise the newest by `modified`.
  - `internal fun sortVideos(videos: List<WindowsMediaEntry>, sort: VideoSort, lengthOf: (WindowsMediaEntry) -> Double?): List<WindowsMediaEntry>`:
    NEWEST by `modified`, newest first; NAME by `title()`, ignoring case; LONGEST longest first, with unknown lengths
    last.
  - `internal fun actorLine(total: Int, watched: Int, new: Int): String`: "24 videos · 9 watched · 2 new", leaving
    out the parts that are 0.
  - `internal fun timeLeftText(seconds: Double): String`: "23 min left", "1 h 5 min left", "under a minute left".
  - `internal fun headerFold(firstIndex: Int, offsetPx: Int, rangePx: Int): Float`: 0 is open, 1 is folded.
  - `internal fun filterActors(actors: List<ActorRef>, query: String): List<ActorRef>`
  - `internal fun neighbours(actors: List<ActorRef>, identity: String): Pair<ActorRef?, ActorRef?>`
  - `HubState` gains:
    - `var actorSort by mutableStateOf(VideoSort.NEWEST)`
    - `var hideWatched by mutableStateOf(false)`
    - `val onActorPage: Boolean`, true when
      `(setId != null && setGroup != null || ownId != null && ownGroup != null) && drivePath == null`
  - `NavRail(..., footer: (@Composable () -> Unit)? = null)`
  - `@Composable internal fun ActorPage(actor: ActorRef, actors: List<ActorRef>, f: Folders, onOpenActor: (String) -> Unit, onBack: () -> Unit)`
    (make `Folders` `internal` if needed, or pass its parts).
- **Page layout** (spec 5.3):
  - The actor list: with `BoxWithConstraints` at 900 dp or more of page width, a 260 dp actor list sits on the left.
    It is A–Z, and each row has a small photo (or the initials), the name and the video count. Right-clicking a row
    opens `actorMenu`. A search icon above it opens a field that filters as you type: Enter opens the first match,
    and Esc clears it, then closes it. Below 900 dp the list hides and ‹ › buttons in the header go to the
    `neighbours`.
  - Resume, Play newest, Play all and Shuffle play the actor's videos in the current sort (Shuffle shuffles them),
    with `autoNext = true`. Resume starts at `ResumeChoice.Resume.position` through `onResume`.
  - When the actor is gone (renamed into another, hidden or emptied), the page shows the actor grid, as today.
  - The header: a 3:4 photo (shared element key `"actor-photo:" + pageKey`, used in Task 11), the name in
    `SerifNames`, and `actorLine`. Its buttons are Resume with `timeLeftText` (or "Play newest"), Play all, Shuffle,
    and ⋯, which opens `actorMenu` with `onActorPage = true`.
  - The header folds with `headerFold` into a slim bar holding the photo, the name and Resume.
  - A sort bar always stays visible above the grid: Newest / A–Z / Longest, the "Hide watched" switch, and
    `ViewButton(page, actors = false, videos = true)`.
  - Below it, a `TileGrid` of the actor's videos, sorted and filtered. Video cards show the length, the progress bar,
    a tick when watched (`LibraryMarks`) and a "New" tag.
  - Ctrl+F focuses the page search field (today's `hub.query` "Search here", filtering this actor's videos). When nothing matches,
    the grid says so and shows a **Clear search** button.
  - Opening the page calls `records.openedActor(pageKey)`.
- **The rail:** in Main,
  `wide = adaptive.navigation == WideRail && !(hub.onActorPage && views.data.actorLayout == HEADER && !views.data.keepMenuOpen)`.
  The footer shows a "Keep menu open" pin toggle (`views.setKeepMenuOpen`) only when
  `adaptive.navigation == WideRail && hub.onActorPage && views.data.actorLayout == HEADER`. Under 1200 dp nothing
  changes (spec 5.3).

- [ ] **Step 1: Write the failing tests:**
  - `resumePicksMostRecentHalfWatched`
  - `resumeIgnoresHistoryOfVideosNotInTheActor`
  - `noHistoryGivesNewest`
  - `noVideosGivesNone`
  - `longestPutsUnknownLast`
  - `newestUsesModified`
  - `nameSortIgnoresCase`
  - `lineLeavesOutZeros`: `actorLine(24, 9, 2) == "24 videos · 9 watched · 2 new"`, `actorLine(1, 0, 0) == "1 video"`
  - `timeLeft`: `timeLeftText(1380.0) == "23 min left"`, `timeLeftText(3900.0) == "1 h 5 min left"`,
    `timeLeftText(40.0) == "under a minute left"`
  - `foldValues`: `headerFold(0, 0, 200) == 0f`, `headerFold(0, 100, 200) == 0.5f`, `headerFold(1, 0, 200) == 1f`
  - `filterIsCaseInsensitive`
  - `neighboursAtTheEnds`
  - `onActorPageFlag`: a set group gives true; a drive folder open inside it gives false.
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.ActorPageTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the class above and `HubBackNavigationTest`: PASS. Then run
  `verify.ps1 -Mode focused -Paths` on the changed files. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: the actor page with Resume, the actor list and a folding header`.

### Task 10: Studio layout and the layout setting

**Files:**
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/ActorPage.kt`
  - `windows/src/main/kotlin/com/skywing/desktop/SettingsScreen.kt` (the Library page)
- Test: `windows/src/test/kotlin/com/skywing/desktop/ActorPageTest.kt`

**Interfaces:**
- Consumes: `ResumeChoice` and `sortVideos` (Task 9), `LibraryViews.setLayout` (Task 6).
- Produces: `internal fun upNext(sorted: List<WindowsMediaEntry>, current: WindowsMediaEntry?): WindowsMediaEntry?`,
  which returns the item after `current`, the first item when `current` is null, and null after the last.
- **Studio** (`actorLayout == STUDIO`):
  - A small header that doesn't fold.
  - Under the name, a Continue watching card: a 16:9 picture with the title, the progress bar, `timeLeftText` and
    Resume on it, built from `ResumeChoice` (history only, so it shows at once). Up next sits beside it.
  - For `Newest` the card reads "Start with the newest".
  - Clicking Up next plays it from the start, in the same queue, with `autoNext = true` (my call; the spec is silent).
  - Below 900 dp of page width, the cards hide and the Resume button stays.
- Settings › Library gets "Actor page layout", with two chips: Header (the default) and Studio. They call `setLayout`.

- [ ] **Step 1: Write the failing tests:** `upNextAfterCurrent`, `upNextFirstWhenNoCurrent`, `upNextNoneAfterLast`.
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.ActorPageTest" --no-daemon`.
  Expected: FAIL on the new tests.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the class above: PASS. Then run `verify.ps1 -Mode focused -Paths` on the
  changed files. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: Studio layout with Continue watching and Up next`.

### Task 11: Photo move, focus, keys and coming back from a video

**Files:**
- Modify:
  - `windows/src/main/kotlin/com/skywing/desktop/FoldersScreen.kt` (`SetPage`, `OwnPage`: wrap the grid/page switch)
  - `windows/src/main/kotlin/com/skywing/desktop/VideoViews.kt` (the `TileGrid` focus target, and type-to-jump)
  - `windows/src/main/kotlin/com/skywing/desktop/SkyUi.kt` (`FolderCard` gets `pictureModifier`)
  - `windows/src/main/kotlin/com/skywing/desktop/LibraryModel.kt` (`HubState.focusTile`)
  - `windows/src/main/kotlin/com/skywing/desktop/Main.kt` (`stopPlayback`)
- Test: `windows/src/test/kotlin/com/skywing/desktop/GridFocusTest.kt`

**Interfaces:**
- Produces:
  - A new `HubState` member `var focusTile by mutableStateOf<String?>(null)`, holding a tile key.
  - `internal fun actorFocusKey(identity: String): String = "group:" + identity` and
    `internal fun videoFocusKey(file: File): String = "file:" + file.path.lowercase(Locale.ROOT)`. Check these match
    the keys `videoTiles` and the group tiles already use, and use the existing ones if they differ.
  - `internal fun scrollNeeded(index: Int, visible: List<Int>): Boolean`
  - `internal fun typeToJump(titles: List<String>, typed: String): Int?`: the first title that starts with `typed`,
    ignoring case.
  - `TileGrid(..., focusKey: String? = null, onFocused: () -> Unit = {})`. When the key's tile exists, it calls
    `scrollToItem` only if `scrollNeeded`, then requests focus on that tile, then calls `onFocused()`, which clears
    `hub.focusTile`.
  - `FolderCard(..., pictureModifier: Modifier = Modifier)`
- **The photo move:**
  - `SetPage` and `OwnPage` wrap the grid ↔ actor page switch in `SharedTransitionLayout` +
    `AnimatedContent(group)` (`@OptIn(ExperimentalSharedTransitionApi::class)`).
  - The actor card's `pictureModifier` and the page header photo use
    `Modifier.sharedElement(rememberSharedContentState("actor-photo:" + pageKey), this@AnimatedContent)` with
    `tween(300)`.
  - With `SkyMotion.animations == false`, there is no `sharedElement` and the switch uses `snap()`.
- **Focus coming back:**
  - Leaving an actor page by any back (Esc, Alt+←, the mouse back button, ← in the top bar; today's hub back) sets
    `hub.focusTile = actorFocusKey(identity)`.
  - `stopPlayback` sets `hub.focusTile = videoFocusKey(file)` for the file playing or ended when the player is left.
  - The page that shows that tile focuses it. This gives spec 5.4 and 5.5: no new photo move, and the header state
    is kept.
- **Keys** (spec 5.10):
  - Typing letters on the grid jumps with `typeToJump`; the typed text resets after 1 s. If `TileGrid` already
    handles grid keys, reuse that.
  - Arrows move between cards (`FocusManager.moveFocus`).
  - The grid, the actor list and the sort bar are one Tab stop each.

- [ ] **Step 1: Write the failing tests:**
  - `noScrollWhenVisible`: `scrollNeeded(5, listOf(3, 4, 5, 6)) == false`
  - `scrollWhenOffScreen`
  - `typeToJumpFindsPrefix`
  - `typeToJumpIgnoresCase`
  - `typeToJumpNoMatchIsNull`
  - `focusKeysMatchTileKeys`: `videoFocusKey(File("\\\\192.0.2.10\\videos\\A.mkv"))` equals the key `videoTiles`
    gives that video, and `actorFocusKey("x")` equals the group tile key.
- [ ] **Step 2: Run and see them fail.**
  Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.GridFocusTest" --no-daemon`. Expected: FAIL.
- [ ] **Step 3: Implement** it as listed under Interfaces.
- [ ] **Step 4: Run and see them pass.** Run the class above: PASS. Then run `verify.ps1 -Mode focused -Paths` on the
  changed files; it includes `FocusRingTest` and `GridAnchorTest`. Expected: exit code 0.
- [ ] **Step 5: Commit** `Actor pages: the photo moves into the page, and focus comes back where you were`.

### Task 12: Whole-branch verification and the owner's checks

**Files:**
- Create: `windows/src/test/kotlin/com/skywing/desktop/automation/ActorAutomationTest.kt`, which follows
  `LibraryAutomationTest` (`SkyWingTestApp.launch`, isolated data, the user's state compared before and after).

- [ ] **Step 0: Write the automation test.**
  - `actorPageOpensResumesAndReturns`: seed a temporary folder with four short test clips named so they form two
    name groups. Add the folder as a Virtual folder in the seed callback, through the organizer's own save code. Then:
    - open the Virtual folder and find both actor cards;
    - open one, and find "Play newest";
    - press Escape, and the actor card has focus;
    - open it again, press ›, and the other actor's name shows;
    - press Shift+F10 on a video, and "Play from start" shows.
  - If the harness can't seed a Virtual folder, say so in the Task 12 report as not covered. Never skip it silently.
- [ ] **Step 1:** From `actor-work`, run
  `powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -Paths <every changed file> -AllowDesktopFocus`
  (ask the owner before taking the desktop). Expected: exit code 0.
- [ ] **Step 2:** Run
  `powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode full -AllowDesktopFocus`
  (player and UI changes need it). Expected: exit code 0. A missing prerequisite blocks the run: report it, and
  never count it as a pass.
- [ ] **Step 3:** One independent review of the whole branch (superpowers:requesting-code-review).
- [ ] **Step 4: The owner's hands-on checklist**, on the PC at 100 % and 150 % scaling:
  - Both layouts, with animations on and off.
  - Open an actor, Resume, then stop: you are back on the same card, scrolled as before.
  - Play all with two short clips: the second starts by itself, and after the last one the player stays on its last
    frame. Do the same in fullscreen, then press Esc.
  - Alt-Tab during playback, then close the window during playback: no process or sound is left behind.
  - Hide an actor, then Undo; Show hidden actors.
  - Open the video and actor menus by right-click, the menu key and Shift+F10; try F2 and Alt+Enter.
  - Change View on several pages; use Ctrl+wheel and Ctrl+0; Reset view settings.
  - Show in File Explorer for a local video and for a video on the share (it only opens Explorer and writes nothing).
  - On the Claw: long-press menus, Select, and a small window (the actor list hides, and ‹ › work).
- [ ] **Step 5:** Report what changed, the tests and their results, what was not tested, and the risks. Don't push
  until the owner says "push".
