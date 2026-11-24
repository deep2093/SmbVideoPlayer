# SkyWing PC accessibility (Windows)

Status as of 2026-11-20, Compose Multiplatform 1.8.2 (material3 1.8.2), JDK 17.

## How assistive technology reaches SkyWing

SkyWing draws its whole interface with Compose Desktop (Skia). Windows sees only the window frame and the
mpv video pane as native controls. Compose controls reach assistive technology only through the
**Java Access Bridge (JAB)**.

- The app image bundles `jdk.accessibility`, which provides `javaaccessbridge.dll` and `windowsaccessbridge-64.dll`
  (`modules("jdk.accessibility")` in `build.gradle.kts`).
- JAB is **off by default** on Windows. A user turns it on once with `jabswitch -enable`, or in
  Ease of Access settings. For a single test process, set
  `JAVA_TOOL_OPTIONS=-Djavax.accessibility.assistive_technologies=com.sun.java.accessibility.AccessBridge`.
  This changes no Windows setting.
- Screen readers that speak JAB (NVDA, JAWS) can read and operate the controls listed below.

## What Compose 1.8.2 sends over JAB

Compose Desktop 1.8.2 (`ComposeAccessible`) maps semantics as follows:

| JAB field | Comes from |
|---|---|
| Name | `EditableText`, otherwise the first `Text` entry |
| Description | `contentDescription` |
| Role | Only Button, Checkbox, RadioButton, Tab and DropdownList have a mapping. Otherwise the role is text field, label, progress bar, group or unknown, chosen by fallback. |
| State | Disabled, focused, selected, `ToggleableState` (checked) |
| Not sent | `stateDescription` and `Role.Switch`. There is no slider role. |

Text semantics also give the control a JAB text interface. NVDA treats a text interface without an `editable` state
as "read only", and speaks that state for check boxes and edit fields. It also reads a control's value only when
there is no text interface (NVDA 2026.3.1, `NVDAObjects/JAB/__init__.py`).

So SkyWing uses two naming helpers in `SkyUi.kt`:

| Helper | Sets | Used on | JAB result |
|---|---|---|---|
| `accessibleName(...)` | text | buttons, tabs, sliders | name filled; text interface present |
| `accessibleDescription(...)` | `contentDescription` | switches (`SkySwitch`), folder picker check boxes | name empty, description filled; no text interface |

## What is accessible now

Verified in the live JAB tree of a locally built test image. The installed app was not changed.

- **Navigation (Folders, Videos, Files, Settings):** page tabs, named, with the selected state. They can be activated through JAB.
- **Section chips:** page tabs with the selected state.
- **Icon buttons and text buttons:** push buttons with specific names (for example "Volume up" and "Back 10 seconds").
- **Settings rows:** named push buttons. **Theme choices:** radio buttons with the selected state.
- **Switches** (Settings, Playback/Motion, the folder picker's "Include sub folders"): `SkySwitch` reports them as
  **check boxes** with the checked state, labelled by description. Before Stage 2 they reported as labels.
- **Folder picker check boxes:** check boxes labelled by the folder or video, by description.
- **Search and text fields:** the placeholder is sent as the description, for example "Search custom folders".
- **Sliders** (Seek, Frame rate, Quality, Video moment, Up / down, Left / right, Zoom): each slider has a name, its
  numeric range (`ProgressBarRangeInfo`) and `SetProgress`. JAB sends it as a label with a value. Its spoken-style
  value (`stateDescription`) is not sent. Material3 1.8.2 sliders take focus but **do not respond to arrow, Page
  Up/Down, Home or End keys**; they are adjusted with the mouse only. A plain Material `Slider` behaves the same,
  so SkyWing did not cause this.

## NVDA results (NVDA 2026.3.1 portable, 2026-11-20)

NVDA ran as a portable copy with its own config and the `silence` synth. Announcements were read from its speech
log. The test build was only navigated with Tab. Toggling, typing and slider values were tried in a temporary window
that rendered the same SkyWing controls with in-memory state.

| Control | Before | After |
|---|---|---|
| Nav tabs | "Folders, tab, selected, 3 of 19" | unchanged |
| Buttons | "Volume up, button" | unchanged |
| Switches | "Motion smoothing, check box, checked, read only" | "check box, checked, Motion smoothing" |
| Space on a switch | "checked" / "not checked" | unchanged |
| Folder check box | not heard before the change; text naming gave it the same text interface | "check box, not checked, Movies" |
| Search field | "edit, read only, Search custom folders, blank" | unchanged (no app-level fix in 1.8.2) |
| Sliders | "Seek"; arrow keys read letters of the name | unchanged (workaround not adopted) |

**Slider trial, not adopted:** labelling a slider by description made NVDA say "progress bar, 95.0, Seek" on focus,
so purpose and value were read. It did not speak value changes while the slider was focused: the value changed every
1.5 s for 5 s with no announcement. Arrow keys cannot change the value either. The condition for adopting the
workaround (purpose, value and value changes all announced) was not met, so sliders keep text naming.

## SkySwitch (checkbox-role fallback)

Material3 1.8.2 `Switch` applies `toggleable(role = Role.Switch)` itself, after the caller's modifier. The caller
therefore cannot change the role, and Compose 1.8.2 sends no JAB role for `Role.Switch`.

`SkySwitch` (in `SkyUi.kt`) wraps the same Material `Switch` with `onCheckedChange = null`. It applies the same
`minimumInteractiveComponentSize().toggleable(...)` with `Role.Checkbox`, and gives that toggleable the switch's own
interaction source. Tests in `AccessibilitySemanticsTest` prove that, compared with a plain Material switch, it:

- draws the same pixels when checked and when unchecked
- has the same size
- responds the same way to a click, Space and Enter
- reports the disabled state the same way.

Compose 1.9.0 sends `Role.Switch` as a check box itself (compose-multiplatform-core #2136). After an upgrade,
`SkySwitch` can go back to a plain `Switch`.

## Remaining limitations

1. **Windows Narrator and UI Automation cannot see Compose controls.** Narrator, Windows Voice Access and UIA tools
   (Inspect, Accessibility Insights, WinApp CLI `ui inspect`) see only the window frame and the video pane. The
   test window shows 10 native UIA elements. Narrator does not support JAB. Fixing this needs a native UIA
   provider, which is a framework-level change and out of scope.
2. **Sliders are not usable with NVDA.** JAB sends them as labels, so NVDA reads only the name: no role, no value,
   and arrow keys read the name letter by letter. Material3 1.8.2 sliders have no keyboard adjustment at all.
   Compose 1.11's `awtRole` (#2577) could give a slider role; keyboard adjustment needs a newer Material3.
3. **Text fields are announced as "read only"** and typed letters are not echoed, because Compose 1.8.2 sends no
   `editable` state. Typing works. The purpose is read from the description, and the JAB name is empty. Compose
   1.10 and 1.11 change text-field accessibility (#2553, #2680); whether they add the `editable` state is untested.
4. **Folder and video cards** report as clickable labels named by their title, not as buttons or list items.
   In the folder picker, the row next to each check box is a second Tab stop with the same label and no role.
5. **Switch and check box JAB names are empty.** Their label is in the description. NVDA reads it; tools that read
   only the JAB name do not (see "UI automation").
6. **The first Tab stop is "unknown, unavailable"** when entering a window. NVDA position counts ("3 of 19") count
   every child, not only tabs.
7. **Not yet checked with JAWS.**

## UI automation (Stage 3)

Automated tests drive the packaged app the way a screen reader does: out of process, through Java Access Bridge.
They send no keyboard or mouse input. The code is in `windows/src/test/kotlin/com/skywing/desktop/automation/`.

| Part | Role |
|---|---|
| `AccessBridgeClient` | Loads `windowsaccessbridge-64.dll` from the test image's own runtime through JNA. Reads a window's tree (name, description, role, states, actions, value) and performs a control's first action. All bridge calls run on one thread that pumps Windows messages. |
| `ControlQuery` | The one matching rule, shared with the in-process `AccessibleControls`. |
| `SkyWingTestApp` | Starts the test image on isolated data, finds its windows, waits, acts, saves diagnostics and cleans up. |
| `IsolatedPreferencesFactory` (Java) | A file-backed `java.util.prefs` used only by the app under test. |
| `SkyWingAutomationTest` | The end-to-end tests. `ControlQueryTest` checks the matching rule without a window. |

**Isolation.** Each test gets a new temporary directory and its own preferences file. The app is started with:

- `JAVA_TOOL_OPTIONS` for that process only: the Access Bridge property, `IsolatedPreferencesFactory` loaded from
  `-Xbootclasspath/a`, the preferences file, and `user.home` inside the temporary directory.
- `LOCALAPPDATA`, `APPDATA` and `USERPROFILE` pointing into the temporary directory.

Every SkyWing preference node (playback, folders, appearance, shares, artwork, sync, history) goes through
`java.util.prefs`, so none reaches the registry. Tests that change settings compare the user's registry keys
before and after. The installed SkyWing, the media library, SMB shares and Windows accessibility settings are not
used or changed. The app's own code is unchanged.

**Finding the window.** The test launches `SkyWing.exe` and keeps its `Process`. It lists visible top-level windows
owned by that process or its children (the launcher starts a child), keeps the ones the bridge reports as Java
windows, and waits for the main window title. Other SkyWing or Java windows on the desktop are never used.

**Matching rules.**

- A control's label is its JAB name, or its description when the name is empty.
- Every lookup names a role. An extra condition can narrow it: for example `{ it.value != null }`, or a path inside
  an open dialog.
- A lookup must match exactly one control. A missing match is retried until a timeout (10 s, or 60 s for the main
  window). An ambiguous match fails at once and lists the candidates.
- `activate` refuses a disabled control or one without an action. The action is performed once and never retried;
  the test then waits for the expected state.

**Diagnostics and cleanup.** A failed lookup writes every window's tree to `windows/build/reports/automation/`. On
close, the app's windows get `WM_CLOSE`. Any process of the launched tree still running after 10 s is stopped. The
temporary directory is deleted, with retries while Windows releases files.

**Covered flows.**

- Navigation: Folders, then the Settings tab, then Playback. The test checks the three switches ("Motion smoothing",
  "My screen uses G-SYNC / FreeSync", "Lower automatically when needed"): each is an enabled, focusable check box
  labelled by description, with one click action and a value that matches its state. It then returns through
  "Back to Settings" to the Folders tab.
- Switches: "Lower automatically when needed" and "My screen uses G-SYNC / FreeSync" are turned on, saved to the
  isolated file, still on after a restart on the same data, and turned off again.
- Folder picker: Add, then "Add drive folders" on a seeded test folder. While the picker is open there are two
  "Add" buttons (the toolbar's and the dialog's), and the unscoped lookup is refused as ambiguous. The dialog's
  "Add" is refused while disabled. The test then ticks the folder ("1 folder chosen"), toggles "Include sub
  folders" off and on, adds the folder and finds its card on Folders.

**Automation limits.**

- Only JAB "click" actions are automated. Sliders (no slider role, no keyboard adjustment) and text entry are not
  driven by these tests.
- Windows UI Automation tools (WinApp CLI, Inspect, Accessibility Insights) still see only the window frame and the
  video pane (limitation 1), so the framework uses JAB rather than UIA.
- Playback, fullscreen, HDR and motion interpolation are not exercised: the tests open no video. The existing
  native tests (`FullscreenWindowTest`, `PlayerChromeTest`, motion tests) cover those.
- Menus and dialogs are drawn inside the main window. During their open and close animations the old and new
  controls can share a label, so tests wait for the expected screen before scoping a lookup to a dialog.

**Running.** Build the image, then opt in:

```powershell
.\gradlew.bat -p windows createDistributable
```

```powershell
$env:SKYWING_TEST_AUTOMATION='true'; .\gradlew.bat -p windows test --tests 'com.skywing.desktop.automation.*'
```

Without `SKYWING_TEST_AUTOMATION=true`, the end-to-end tests are skipped. With it, a missing image is an error.
`SKYWING_TEST_IMAGE=<dir>` picks another app image (`verify.ps1 -Image` sets it). The test windows may take
foreground focus while they run.

## Newer Compose versions (reviewed, not adopted)

The latest stable version at review time was 1.12.2.

- **1.9.0:** Switch reported as a check box (#2136). VoiceOver announces slider value changes on macOS only
  (#2152). `hideFromAccessibility` is respected (#2204). Text and contentDescription merge correctly (#2202).
- **1.10.0:** text in text fields can be reviewed, and scrollable text fields are accessible (#2553).
  `traversalIndex` ordering is respected (#2544).
- **1.11.0:** a new `SemanticsPropertyReceiver.awtRole` sets any AWT role, for example a slider role (#2577).
  NVDA traversal is fixed on Windows (#2637). `TextField` uses `contentDescription` as its label (#2680).
  Removed nodes no longer keep focus highlights (#2695).
- **1.12.x / 1.13.0-alpha:** crash fixes in accessibility focus handling (#3158, #3361).

An upgrade to 1.11 or later may improve limitations 2, 3 and 6 and remove the need for `SkySwitch`. It would not
close limitation 1. Recheck every change with NVDA, because the naming helpers depend on 1.8.2 behaviour.

## Tests

- `AccessibilitySemanticsTest` checks Compose semantics: names, descriptions, roles, states, ranges, keyboard
  behaviour and `SkySwitch` parity.
- `AccessBridgeTreeTest` opens real windows and checks what JAB sends: names, descriptions, roles, values, checked
  and enabled states, the absence of a text interface on switches and check boxes, JAB click and Space. It also
  checks that ambiguous, missing and disabled matches are refused.
- Run both with
  `.\gradlew.bat -p windows test --tests com.skywing.desktop.AccessibilitySemanticsTest --tests com.skywing.desktop.AccessBridgeTreeTest`.
- `SkyWingAutomationTest` and `ControlQueryTest` cover the packaged app through out-of-process JAB (see "UI
  automation").
