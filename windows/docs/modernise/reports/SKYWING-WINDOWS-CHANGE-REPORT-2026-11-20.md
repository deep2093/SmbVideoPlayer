# SkyWing Windows change report

**Report date:** 20 November 2026 (Australia/Sydney)  
**Scope:** SkyWing PC work performed in this chat.  
**Checkout:** `feature/windows-motion-engine` at `f64d409baf2a9603fe40d283aadb98b0ef214e89`. The two implementation changes described below are in the local working tree.

## What changed

There are two changes in the checkout. The Windows package now includes Java's accessibility module, which enables Java Access Bridge support when the Windows user has activated it. The fullscreen regression test now establishes and checks the real desktop conditions it needs. There are no changes to the Kotlin application implementation, screen layouts, libmpv integration, playback commands, motion interpolation, or fullscreen behaviour. The Android app and the installed SkyWing copy were untouched.

| File | Change | Effect |
|---|---|---|
| [windows/build.gradle.kts](../windows/build.gradle.kts#L47) | Added `modules("jdk.accessibility")` and a comment in Compose Desktop's `nativeDistributions` block. | Future Windows images built from this checkout include the Java accessibility module. |
| [FullscreenWindowTest.kt](../windows/src/test/kotlin/com/skywing/desktop/FullscreenWindowTest.kt#L106) | Corrected the focus and pointer setup for fullscreen inactivity checks. Added two regressions and better failure context. | Automated tests can reproduce and verify auto-hide with the player focused and the pointer clear of the controls. |

## Windows packaging and accessibility

### Before the change

The installed preview used a bundled Java 17.0.21 runtime. Its runtime manifest omitted `jdk.accessibility`, and its runtime directory lacked the Java Access Bridge DLLs. The full JDK available on the PC had accessibility support, but the installed SkyWing process used the runtime under its own installation directory.

Read-only inspection found a launcher and a child process running from the same SkyWing executable. The child owned the `SunAwtFrame` and loaded the JVM, AWT, and libmpv. Both were in the same Windows session as the inspection tools. Their recorded start time was later than the earlier unsuccessful process and window scan, which explains why that scan could not find these instances. WinApp CLI 0.8.0 found the child window by HWND and exposed its native frame and controls.

### Build change and verification

The Compose Desktop project uses version 1.8.2 and targets Java 17. The module was added inside its existing native distribution configuration. The EXE and MSI formats, package version logic, file associations, Start menu and shortcut options, and MSI upgrade identity were preserved.

- `createDistributable` built the application image with Temurin Java 17.0.20.1. The image's `runtime/release` lists `jdk.accessibility` alongside all modules listed in the installed runtime.
- `jimage list` confirmed `jdk.accessibility` and `com/sun/java/accessibility/internal/AccessBridge.class` in the bundled module image. The image also contained `javaaccessbridge.dll` and `windowsaccessbridge-64.dll`.
- `packageExe` and `packageMsi` built `SkyWing-0.1.1.exe` and `SkyWing-0.1.1.msi`. A read-only query of the generated MSI verified its product name, version, and actual UpgradeCode, `{F40C59EF-E520-4E10-B7A2-B86E7458F923}`. Neither installer was run.
- The test image was launched separately from the installed preview. It loaded `javaaccessbridge.dll` from its own bundled runtime and exited normally after inspection.

### What the accessibility tools could see

Java Access Bridge was temporarily enabled for the Windows user after approval. Its two inspection results describe different accessibility interfaces.

| Inspection | Observed in the updated test build |
|---|---|
| Windows UI Automation through WinApp CLI | **10 elements** in the inspected tree. These included the frame, title bar, System menu, Minimize, Maximize and Close, two AWT canvases, and an embedded mpv pane labelled “No file - mpv.” The internal Compose controls were absent from that tree. |
| Java Access Bridge API | **34 nodes**, with zero read failures. There were ten named and six unnamed actionable nodes. The Folders, Videos, Files and Settings destinations were named, focusable and actionable. They appeared with the role `label`. |

The direct Java probe inspected the Folders screen. Settings subpages and transport controls were reviewed in source. The module makes the Java accessibility tree available through the bridge when activated. Windows UI Automation continued to expose the native window tree in this test. The user's original disabled Access Bridge state was restored after validation, including the original absence of the user-level activation file.

For a later accessibility change, the source review identified three specific gaps. The shared icon button accepts a description but discards it. Custom navigation rows visually show selection without exposing that state. Several settings switches and player sliders lack an accessible name associated with the control. Those changes remain in the backlog. Compose semantics tests or a Java Access Bridge client can exercise internal controls and should be validated against the actual runtime tree before relying on them.

## Fullscreen auto-hide regression

### What failed and how it was reproduced

The original test could pass on its own while a full run reported either “Fullscreen cursor did not hide with controls” or “Fullscreen controls did not auto-hide.” Two earlier full native runs each passed 56 of 57 tests. A subsequent baseline full run, three isolated runs with process-only accessibility flags, and a traced full run all passed before the fix. This established that the intermittent result could not be attributed to test order or Java Access Bridge from the initial pass/fail pattern.

The test drove a real desktop window. It requested focus early, moved the pointer to the window centre, and assumed the necessary conditions still held when it checked inactivity. Temporary diagnostics recorded focus, pointer position, playback pause state, hover, overlay visibility, and the cursor state. Controlled experiments reproduced both historical assertions:

| Controlled condition | Observed result |
|---|---|
| Another test-owned window took focus | Both controls hid. The cursor remained visible, as intended on focus loss. The original test reported that the cursor failed to hide. |
| The pointer moved over the control overlay | The application reported active control hover and kept the overlays visible. The original test reported that the controls failed to auto-hide. |
| A later run retained focus with the pointer over the video | Both overlays hid and AWT reported the transparent cursor, but the Win32 probe still saw visible cursor pixels. The exact cause of that native cursor mismatch was not established. Explicit pointer movement before the stationary interval passed the diagnostic run and the final repeated checks. |

The controlled failures confirm that missing desktop preconditions can produce the original messages. The older logs did not capture enough desktop state to prove which trigger occurred in each historical run. No demonstrated defect in the application's 2.5-second timer required a production change.

### Test changes

1. The fixture brings the test window forward, requests focus, and waits for confirmed focus before checking inactivity.
2. It calculates an unobstructed point from the video canvas and the bounds of the owned overlays. It accounts for hidden overlays because moving the pointer can reveal them. It moves the real pointer between two distinct points over the video and verifies its final location.
3. During the hide checks, the fixture verifies that focus and pointer position stay as established. Failures distinguish desktop interference from a failed hide assertion.
4. The visibility assertion requires **both** owned overlays to hide. The previous inverted predicate could pass once either overlay hid. The test still measures the actual Windows cursor and still checks cursor restoration, keyboard and mouse reveal, paused controls, repeated fullscreen entry and exit, and stable native window handles.
5. Added `inactivitySetupRecoversFocusFromAnotherWindow` and `inactivitySetupPlacesPointerOutsideOwnedControls`. Both failed with the old setup and passed with the corrected setup. Diagnostic flags and temporary production traces were removed. Existing timeout values and playback assertions were kept.

### Final test results

| Verification | Result |
|---|---:|
| New fixture regressions before the fix | 2 failed out of 2, on the intended assertions |
| New fixture regressions after the fix | 2 passed out of 2 |
| Controlled focus and hover reproductions with corrected setup | Both passed, including the real overlay and cursor assertions |
| Final fullscreen and player chrome tests | 9 passed out of 9 in each of three fresh runs |
| Final complete Windows suite with native testing enabled | **59 passed, 0 failed, 0 skipped** |
| Native motion test within the full suite | 1 passed, 0 skipped |
| Source diff check | `git diff --check` passed |

The full suite used the existing installed libmpv and motion resources as read-only test inputs. The native motion test exercised its seek, pause, preset, fallback, and teardown scenario. Its pass establishes test coverage using those inputs. It does not certify a rebuilt matching-source motion package or sustained interpolation quality on other hardware.

## Distribution status and remaining work

The accessibility image and the EXE/MSI are local test artifacts. The staged libmpv DLL matched this checkout's pinned stable binary. The installed motion payload had a different patch hash from the current checkout, so it was excluded from the new test image. A release build still needs its matching motion assets, a clean install and in-place MSI upgrade check, plus representative playback and hardware acceptance. Physical HDR, VRR activation, and sustained audiovisual quality were outside these checks.

The [next-build action plan](superpowers/plans/2026-11-19-skywing-next-build-actions.md) records the UI review and five separate playback findings. They remain follow-up work. The playback findings concern UI blocking during native calls, progress saves on close, stale resume seeks, motion on unsupported display rates, and Mouse Back over fullscreen overlays. The UI work covers accessible control semantics, responsive layout, player control organization, navigation, settings, and readability. The fullscreen test fix described here is separate from that list.

## Evidence and workspace tools

- [Detailed accessibility validation](../windows/build/reports/accessibility/validation.md), [WinApp CLI tree](../windows/build/reports/accessibility/distribution-uia.json), and [Java Access Bridge summary](../windows/build/reports/accessibility/distribution-jab-summary.json). The accessibility validation reflects the earlier packaging stage and reports the failures that led to the later test investigation.
- [Final test report](../windows/build/reports/tests/test/index.html), [final suite log](../windows/build/reports/fullscreen-investigation/verified-native-suite.log), and [preserved fullscreen investigation](../windows/build/reports/fullscreen-investigation/).
- [Local test application image](../windows/build/compose/binaries/main/app/SkyWing/), [test EXE installer](../windows/build/compose/binaries/main/exe/SkyWing-0.1.1.exe) and [test MSI installer](../windows/build/compose/binaries/main/msi/SkyWing-0.1.1.msi). Build evidence lives under the generated `windows/build` directory and can be removed by a clean build.
- The four SkyWing skills, Desktop UI Design, Effective Kotlin, and a personal copy of Microsoft's WinApp UI Automation skill were installed separately in the user's Codex skills directory earlier in the chat. WinApp CLI was verified after the user installed it. Those tooling changes have no effect on the SkyWing app or its installer.
