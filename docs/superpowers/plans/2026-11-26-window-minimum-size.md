# Window minimum size Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** At any Windows scaling, the main window can't be made smaller than 640 × 480 dp. Today at 150 % it goes down to about 415 × 314 dp.

**Architecture:** Java gives Windows the 640 × 480 minimum in raw screen pixels, not scaled pixels ([JDK-8221452](https://bugs.openjdk.org/browse/JDK-8221452); a fix was proposed in 2023 and withdrawn). SkyWing answers the frame's own `WM_GETMINMAXINFO` message instead. It hooks the frame's window procedure with JNA, lets Java answer first, and then raises the minimum track size to 640 × 480 dp at the window's current scale. Brief 5.6 rule 7 allows exactly this one `WM_GETMINMAXINFO` change. Java's `window.minimumSize` stays as it is. Nothing else in Java's handling of the window changes.

**Tech Stack:** Kotlin/JVM 17, Compose Desktop 1.8.2, JNA 5.14.0 (already a dependency), Win32 `user32`/`dwmapi`.

**Spec:**
- `windows/docs/modernise/M3-DESIGN-PREP.md` 5.6 rules 5 and 7
- `windows/docs/modernise/evaluation-m3-new-look.md` ("The window can be dragged smaller than the minimum…")
- `windows/docs/modernise/states-m3-new-look.md` (the row about the smallest window at 125 % or more)
- `windows/docs/modernise/accessibility-m3-new-look.md` (Perceivable, the second Medium)

**Evidence:**
- The M3 runner's `window: minimum size` row fails (`m3-acceptance/run-20261125-141109-recheck`): "it is 415 × 314 dp" at 150 %. That is the visible frame: about 622 × 471 px.
- That matches a 640 × 480 px outer window less its invisible resize borders. So the runner's `SetWindowPos` to 200 × 150 px is stopped by Java's raw-pixel minimum.

## Global Constraints

- Windows code only (`windows/`, plus `docs/` for notes). No Android file.
- No new library or download. JNA is already used: `DisplayChanges.kt`, `BorderlessFullscreen.kt`, `WindowChrome.kt`.
- **Preserve the frame HWND and the video canvas HWND.** No window is recreated. Fullscreen keeps its single owner, `BorderlessFullscreen`, and the hook never moves or sizes the window itself.
- **What 640 × 480 dp means:** the visible window (DWM extended frame bounds) at the window's own DPI. This is what a person sees and what `WindowRule.minimum` measures, with a 2 dp tolerance. Invisible resize borders come on top.
- **The minimum never exceeds the work area** of the window's monitor. Maximise, Snap and borderless fullscreen are never blocked.
- **Any failure falls back to today's behaviour,** that is, Java's own minimum. This covers an install that can't be made and a reading that fails inside the message. The hook never throws into Windows' message loop.
- Keep the MSI upgrade identity, native payload hashes, legal notices and per-file settings. None of them change.
- No push and no PR update until the owner says "push". No force-push and no history rewrite.

## Review Focus

1. **Mixed-scale screens.** Move the window from a 150 % screen to a 100 % one and make it small. The minimum follows the new screen: 640 dp at 100 %, not 960 px. The DPI is read in each message, never cached. Task 1 test `theMinimumFollowsTheScaleItIsGiven`.
2. **A screen smaller than 640 × 480 dp,** for example 800 × 600 px at 150 %. The window can still maximise and go fullscreen there, because the minimum is capped at the work area. Task 1 test `theMinimumNeverExceedsTheWorkArea`.
3. **Fullscreen and back.** Fullscreen still covers the monitor, Esc and F still leave it, and the window comes back at its old size. Covered by the existing `FullscreenAutomationTest`, which Task 3's verify map runs for this file.
4. **A failed reading inside the message,** for example the DWM call failing. The answer is still Java's own (never smaller than today) and nothing is thrown. Task 1 test `noBordersMeansJustTheScaledMinimum` covers the arithmetic. Task 2's code step pins the try/catch.
5. **The hook is installed twice** (the effect runs again). It's installed once per HWND, so the chain never loops into itself. Task 2's code step pins this. Task 2 test `installingTwiceKeepsOneHook` checks it on a real frame.

---

### Task 1: The minimum in pixels

**Files:**
- Create: `windows/src/main/kotlin/com/skywing/desktop/WindowMinimum.kt`
- Test: `windows/src/test/kotlin/com/skywing/desktop/WindowMinimumTest.kt`

**Interfaces:**
- Consumes: `WindowSizing.MIN_WIDTH` (640.0) and `WindowSizing.MIN_HEIGHT` (480.0) from `WindowPlacement.kt`.
- Produces: `internal object WindowMinimum`, with `fun trackSize(scale: Double, borderWidth: Int, borderHeight: Int, workWidth: Int, workHeight: Int): java.awt.Dimension`.
  - All arguments and the result are in screen pixels.
  - `borderWidth` and `borderHeight` are the outer window size less the visible frame (the invisible resize borders).

- [ ] **Step 1: Write the failing tests**

```kotlin
class WindowMinimumTest {
    @Test fun at150PercentTheVisibleWindowIs960By720Pixels() =
        assertEquals(Dimension(960 + 22, 720 + 11), WindowMinimum.trackSize(1.5, 22, 11, 2560, 1528))

    @Test fun theMinimumFollowsTheScaleItIsGiven() {
        assertEquals(Dimension(640 + 14, 480 + 7), WindowMinimum.trackSize(1.0, 14, 7, 1920, 1032))
        assertEquals(Dimension(800 + 18, 600 + 9), WindowMinimum.trackSize(1.25, 18, 9, 2400, 1550))
        assertEquals(Dimension(1280 + 28, 960 + 14), WindowMinimum.trackSize(2.0, 28, 14, 3840, 2080))
    }

    @Test fun aPartPixelRoundsUpSoTheWindowIsNeverUnder640Dp() {
        assertEquals(Dimension(1120, 840), WindowMinimum.trackSize(1.75, 0, 0, 3840, 2080))
        assertEquals(Dimension(747, 560), WindowMinimum.trackSize(1.166, 0, 0, 3840, 2080))
    }

    @Test fun theMinimumNeverExceedsTheWorkArea() =
        assertEquals(Dimension(800, 552), WindowMinimum.trackSize(1.5, 22, 11, 800, 552))

    @Test fun noBordersMeansJustTheScaledMinimum() =
        assertEquals(Dimension(960, 720), WindowMinimum.trackSize(1.5, 0, 0, 2560, 1528))
}
```

- [ ] **Step 2: Run them and see them fail**

Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.WindowMinimumTest" --no-daemon -q`
Expected: compile failure, `Unresolved reference: WindowMinimum`.

- [ ] **Step 3: Implement `WindowMinimum.trackSize`**
  - Width: `ceil(640 × scale) + borderWidth`, capped at `workWidth`. Height: the same with 480, `borderHeight` and `workHeight`.
  - Use `kotlin.math.ceil` on the product, then `toInt()`.

- [ ] **Step 4: Run them and see them pass**

Same command. Expected: 5 tests, 0 failed.

- [ ] **Step 5: Commit**

`git add windows/src/main/kotlin/com/skywing/desktop/WindowMinimum.kt windows/src/test/kotlin/com/skywing/desktop/WindowMinimumTest.kt`
`git commit -m "M3: Work out the window's minimum in pixels for its scaling"`

### Task 2: Give Windows the minimum

**Files:**
- Modify: `windows/src/main/kotlin/com/skywing/desktop/WindowMinimum.kt` (add the hook)
- Modify: `windows/src/main/kotlin/com/skywing/desktop/WindowPlacement.kt`, in `RememberWindowPlacement` next to `window.minimumSize = …` (around line 264). Also update its KDoc line, "keeps the window at least 640 × 480 dp (rule 5)", to say Windows is told through the window's minimum-size message.
- Test: `windows/src/test/kotlin/com/skywing/desktop/automation/WindowMinimumAutomationTest.kt` (create)
- Test: `windows/src/test/kotlin/com/skywing/desktop/WindowMinimumHookTest.kt` (create)

**Interfaces:**
- Consumes: `WindowMinimum.trackSize` (Task 1). From the test side: `WindowShots.move`, `WindowShots.workArea`, `WindowShots.placement`, `WindowShots.scale`, `WindowRule.minimum`, `SkyWingTestApp.launch(...).use { app -> app.mainWindow() }`.
- Produces:
  - `fun WindowMinimum.install(window: java.awt.Window): Boolean`. It returns true when the hook is in place, including when it already was.
  - `internal fun WindowMinimum.installed(window: java.awt.Window): Boolean`, for the test.

- [ ] **Step 1: Write the failing screen test**

Name it `WindowMinimumAutomationTest.theWindowStaysAtLeast640By480DpWhenMadeAsSmallAsWindowsAllows`.
- Opt in like `FullscreenAutomationTest`: `assumeTrue(System.getenv("SKYWING_TEST_AUTOMATION") == "true")`.
- `SkyWingTestApp.launch("window-minimum").use { app -> … }`.
- Take `hwnd = app.mainWindow()` and `work = WindowShots.workArea(hwnd)`.
- Call `WindowShots.move(hwnd, work.left + 40, work.top + 40, 200, 150)`, then `Thread.sleep(500)`.
- Assert `assertNull(WindowRule.minimum(WindowShots.placement(hwnd)))`.
- Also assert `WindowShots.scale(hwnd) > 1.0`, or skip with `assumeTrue` and the message "needs a screen above 100 % scaling to show the bug". At 100 % Java's raw-pixel minimum is nearly right, so the test would prove nothing there.

- [ ] **Step 2: Run it on the laptop (150 %) and see it fail**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -Paths 'windows/src/test/kotlin/com/skywing/desktop/automation/WindowMinimumAutomationTest.kt' -AllowDesktopFocus`
- If verify maps test files to "none", run the test with the automation environment variables that verify sets.
- Expected: FAIL with "Made as small as Windows allows, the window should stay at least 640 × 480 dp; it is 415 × 314 dp" (± a few dp).
- It takes the desktop for about 20 s. The owner allowed screen tests on 2026-11-26.

- [ ] **Step 3: Write the failing hook test**

Name it `WindowMinimumHookTest.installingTwiceKeepsOneHook`.
- Skip unless Windows and not headless.
- Show a plain `JFrame` of 800 × 600, then call `WindowMinimum.install(frame)` twice. Both return true.
- `WindowMinimum.installed(frame)` is true.
- `frame.setSize(100, 100)`, then poll for up to 2 s until Windows' resize reaches Java. Then `frame.width >= 640` and `frame.height >= 480`. These are Java units, so dp, and the outer size is never smaller than the visible one. This is gated like `FullscreenWindowTest`.
- Dispose the frame in `finally`.
- Fails now: `install` is not defined.

- [ ] **Step 4: Implement `WindowMinimum.install`** in `WindowMinimum.kt`, following `DisplayChanges.kt`'s JNA style.
  - **user32:** `SetWindowLongPtrW(hwnd, GWLP_WNDPROC = -4, proc)` returns the old procedure; `CallWindowProcW`; `GetDpiForWindow`; `GetWindowRect`; `MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST = 2)`; `GetMonitorInfoW`. MONITORINFO is 40 bytes with `rcWork` at offset 20.
  - **dwmapi:** `DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS = 9, …)`.
  - **The procedure:**
    - Call the old procedure first and keep its result.
    - On `WM_GETMINMAXINFO = 0x0024`, read the work area, the scale (`GetDpiForWindow / 96.0`) and the borders (`GetWindowRect` less the DWM bounds; 0 if DWM fails) in this message.
    - Then set `ptMinTrackSize` (MINMAXINFO offset 24, two Ints) to the larger of what Java wrote and `trackSize(...)`.
    - On `WM_NCDESTROY = 0x0082`, put the old procedure back and forget the HWND.
    - Return the old procedure's result.
    - Wrap the message work in `runCatching`, so a failure leaves Java's answer untouched.
  - **Callback lifetime:** hold the callback object in a field for the life of the process, as `DisplayChanges` does. JNA frees unreferenced callbacks.
  - **Once per HWND:** keep a `ConcurrentHashMap<Long, Pointer>` from HWND to old procedure, so a second `install` is a no-op.
  - **Off Windows,** or when `Native.getWindowPointer(window)` fails or `SetWindowLongPtrW` returns 0, return false and change nothing.
- **Call site:** in `RememberWindowPlacement`, after `window.minimumSize = …`, add `WindowMinimum.install(window)`.

- [ ] **Step 5: Run both tests and see them pass**

Run: `.\gradlew.bat -p windows test --tests "com.skywing.desktop.WindowMinimum*" --no-daemon -q`
Expected: WindowMinimumTest and WindowMinimumHookTest pass. Then rerun Step 2's command; expected PASS with the window at about 640 × 480 dp or a little more.

- [ ] **Step 6: Commit**

`git add` the four files.
`git commit -m "M3: The window stays at least 640 × 480 dp at any Windows scaling"`

### Task 3: Tests that run with it, and the notes

**Files:**
- Modify: `windows/tools/verify.ps1`. In the 'Full screen / window chrome' rule:
  - add `WindowMinimum` to the pattern's file list;
  - add `"$pkg.WindowMinimumTest", "$pkg.WindowMinimumHookTest", "$pkg.automation.WindowMinimumAutomationTest"` to its tests.
  - It already escalates to full.
- Modify: `windows/TESTING-POLICY.md`, the matching table row, so the two stay in step.
- Modify: the finding's line in `evaluation-m3-new-look.md`, `states-m3-new-look.md` and `accessibility-m3-new-look.md`. Add one line: "Built: <commit of Task 2>", which also links this plan.

- [ ] **Step 1:** Make the edits.
- [ ] **Step 2:** Run `powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode focused -Paths 'windows/src/main/kotlin/com/skywing/desktop/WindowMinimum.kt' -AllowDesktopFocus`. Expected: the area is "Full screen / window chrome", its tests pass, and the verdict is INCOMPLETE (escalate to full).
- [ ] **Step 3:** Commit: `M3: verify runs the window minimum tests with the window chrome ones`.

### Task 4: Verify, review and bring it to the actor pages branch

- [ ] **Step 1: Full verify on the M3 branch.**
  - Run `verify.ps1 -Mode full -AllowDesktopFocus`, detached as before.
  - Expected: PASS, 0 failed, 0 skipped, isolation clean.
  - Move the mouse pointer off the bottom of the screen first. A pointer on the control bar keeps the fullscreen controls open: actor report ruling 71.
- [ ] **Step 2: The M3 runner's window check,** with the owner's OK, as it takes the desktop.
  - Run `windows/tools/m3-runner` with `window` only.
  - Expected: `window: minimum size` Pass, and the other window rows still Pass.
- [ ] **Step 3: One independent review** (ecc:kotlin-reviewer) of the M3 commits. Focus on the window procedure chain, callback lifetime, thread safety and the MINMAXINFO offsets.
- [ ] **Step 4: Bring the fix to the actor pages branch.**
  - In `actor-work`: `git merge feature/windows-m3-new-look`, a merge commit with no rebase.
  - Expected conflicts: none in `WindowPlacement.kt`, because the actor branch changed only line 126. Possibly a nearby-lines conflict in `verify.ps1` and `TESTING-POLICY.md`; keep both sides.
  - Then run a focused verify on `WindowMinimum.kt` there.
- [ ] **Step 5: Wait for the owner's "push".** Then push both branches, which updates PR 18 and PR 19.

## Not covered by tests

- **Moving between two screens with different scaling:** no second screen on the laptop. The unit test covers the arithmetic; the live check is the owner's, if a second screen is ever at hand.
- **The Claw at its own scaling:** the owner's hand check. Make the window as small as it goes; the top tabs and toolbars must not overflow.
- **Snap layouts and Win + arrow keys:** they don't shrink below the minimum by design. Check by hand once.

## Approaches considered

- **Chosen: answer `WM_GETMINMAXINFO`.** It is the one native change brief 5.6 rule 7 allows. It works at each screen's own scale, and it leaves Java's window logic alone.
- **Multiply Java's own minimum by the scale.** Rejected, for three reasons:
  - Java reads the same number in its own scaled units too: `Window.setMinimumSize` grows a smaller window to it. So a 700 dp window would be forced to 960 dp at 150 %.
  - It would have to be redone on every move between screens.
  - It still leaves out the invisible borders.
- **Another Java runtime with the bug fixed.** Rejected: the payload hashes and packaging would change, and the upstream fix was withdrawn.
