# SkyWing Windows — Stage 5 release-readiness report (2026-11-21, updated 2026-11-22)

Branch `feature/windows-motion-engine`. On 2026-11-22, with the owner's go-ahead, the work was committed locally as
eight `M0:` commits after f64d409; this report is in the eighth. Nothing was pushed or published. With the owner's
approval, the installed SkyWing 0.1.97 was upgraded in place to the local 0.1.98 MSI on 2026-11-21. Nothing was
uninstalled. On 2026-11-22, 0.1.99 test packages were built with the owner's authorisation
and not installed. No software was installed, uninstalled or upgraded on 2026-11-22, and no Windows setting was
changed.

## Recommendation: **NOT READY** for a public release

The 0.1.98 build is fit for continued **private preview** use on the owner's PC. The owner reports having opened and
tested the installed 0.1.98 and that it works. The code-level gates pass. The public-release gates below do not.

### Blockers and recommended solutions

| # | Blocker | Evidence | Recommended solution |
|---|---|---|---|
| 1 | **Licensing: corresponding source is not assembled.** The app is GPL-3.0-or-later, and libmpv is a GPL build with statically linked FFmpeg and other dependencies | `windows/legal/SOURCE-STATUS.md` is "Status: INCOMPLETE". Six required items are open: (1) native source, (2) the linked-component notice inventory, (3) the Skia/Skiko dependency notices, (4) runtime JARs with no embedded notice, (5) the Java runtime's matching source, (6) application source for the same revision. The upstream libmpv run publishes binaries, not a source bundle. The experimental MSYS2 mpv and the VapourSynth dependencies add more items | Pick one: (a) get the exact source bundle from the libmpv producer; or (b) build libmpv in CI from fully pinned sources and archive every source, patch and recipe with the release. (b) is more work but gives a reproducible source offer. It changes the native binary, so it needs a full verification run and a clean-PC install. Then add the Skia/Skiko notices, resolve the zero-notice JARs, and publish the CI source ZIP next to each installer. Do not mark the audit complete until every item has evidence |
| 2 | **Unsigned packages; Smart App Control blocked SkyWing on this PC** | Every SkyWing EXE, DLL and MSI is NotSigned; only the bundled JDK runtime DLLs are signed. On 2026-10-25 18:46:51, CodeIntegrity event 3077 shows Smart App Control blocking `C:\Program Files\SkyWing\SkyWing.exe`. SAC is off on this PC now (`VerifiedAndReputablePolicyState=0`). SAC has no per-app exception | See *Signing options* below. Without a certificate: private distribution only, with users told to expect SmartScreen's "More info → Run anyway". It cannot run on PCs where SAC is on |
| 3 | **No clean-PC install, uninstall or CI-built upgrade test** | Only the in-place upgrade 0.1.97 → 0.1.98 on the owner's PC was tested. Windows 11 Home here has no Windows Sandbox and no Hyper-V. Installing VM software needs the owner's permission. Uninstalling the working install was not permitted | Use a spare PC, or a VM (VirtualBox or VMware, owner to approve installing it) with a GPU driver that provides Vulkan. Run the checklist in *Fresh install and uninstall* below |
| 4 | **The local packages are not the release payload** | The local 0.1.98 carries the pinned shinchiro libmpv (`04e4d27d…`, matches provenance) and locally built motion plugins (`localPluginOverride: true`). CI rejects that override. The 0.1.97 CI build carried the experimental MSYS2 mpv (125 files) | Release only a CI-built artifact. Run `verify.ps1 -Mode release` against it; the override check must pass |
| 5 | **Hardware behaviour is not measured** | HDR, VRR, displayed frame pacing, multi-monitor and real Alt+Tab were not measured. This PC has one 2560×1600 240 Hz panel on Intel graphics | A manual check on an HDR/VRR display and a second monitor; see *Hardware gaps* |

### Release risks that are not blockers

- **Vulkan loader.** The pinned `libmpv-2.dll` hard-imports `vulkan-1.dll`. SkyWing does not ship it, by design
  (`SOURCE-STATUS.md`: CI's loader is test-only). It comes from the GPU driver; it is in System32 here. On a PC or VM
  with only the Microsoft Basic Display Adapter, libmpv will fail to load. **Recommendation:** either bundle the
  Khronos loader with its Apache-2.0 notice, or state a GPU driver as a requirement. In both cases, test the clean-PC
  install on a machine without a vendor driver, to confirm SkyWing reports the failure rather than crashing.
- **Motion patch hash.** The 0.1.97 CI manifest records patch SHA-256 `66d5c97e…`; the committed patch is
  `12b0174e…`. **Confirmed on 2026-11-22:** the committed LF patch with every LF replaced by CRLF hashes to exactly
  `66d5c97ea04e5e01…`. The CI runner checks out with `core.autocrlf=true`, `.gitattributes` does not pin this patch
  to LF, and `build_motion.py` hashes the working-tree file, not the LF copy it applies. The applied patch is the same;
  only the recorded hash differs. **Fixed locally on 2026-11-22** (see *Reproducibility and source compliance*
  below); not yet proven in CI.
- **Version numbering.** A local build without `GITHUB_RUN_NUMBER` is 0.1.1. The MSI blocks downgrades, so a local
  0.1.1 MSI cannot upgrade an installed 0.1.97+. Release mode flags this.

## Reproducibility and source compliance (2026-11-22, morning)

Local changes, committed on 2026-11-22 with the owner's go-ahead (the eight `M0:` commits after f64d409). Nothing was
pushed, published or installed. The installed 0.1.98 was not touched (539 files, 443,588,187 bytes, svpflow1
`E262337F…`, before this work, after it and again after the release run; newest file written 2026-11-21 16:26). A
snapshot of the working tree before this work (tracked diff and untracked files) was kept outside the repository.

### CI changes (in `.github/workflows/skywing-windows-x64.yml`; committed, need a push and a CI run to prove)

| Change | Why |
|---|---|
| `setup-java` pinned to `17.0.20+101` (Temurin `jdk-17.0.20.1+1`) | Run 98 floated to 17.0.21+9. The pin matches the runtime and source recorded in NATIVE-PROVENANCE.json |
| Legal check: the packaged `java-runtime/BUILD-RELEASE.txt` IMPLEMENTOR_VERSION must equal `javaRuntime.implementorVersion` | A runner image change can no longer swap the shipped runtime silently |
| The motion build manifest is printed; a second `build_motion.py` run is compared with `compare_builds.py --kind motion` | Proves the CI plugins rebuild identically |
| After `createDistributable`, a copy is kept, the image is rebuilt with `--rerun-tasks` and compared with `--kind image` | Proves the app image rebuilds identically apart from expected metadata |
| The Temurin source archive is downloaded, its SHA-256 checked, and uploaded with the artifacts | Corresponding source for the bundled runtime |

Official releases already use CI-built motion plugins: `build_motion.py` builds them in CI, and `verify.ps1` release
mode blocks a payload with `localPluginOverride: true`. Unchanged.

### Local build changes

- `.gitattributes`: `windows/tools/open-svpflow-windows.patch` is pinned to LF. `build_motion.py` now hashes the LF
  copy it applies. **Root cause of the patch hash difference, confirmed:** a CRLF checkout of the same patch hashes
  to the CI value `66d5c97e…`. The local manifest now records `12b0174e…`.
- `build_motion.py` stages the source in a fixed folder (`<cache>/skywing-motion-stage`). Cargo derives crate
  metadata from the source path, so a random temp folder could change the plugin binaries. It erases only a folder
  carrying its own marker file (tested: an unmarked folder is refused and kept).
- `build_motion.py` records the toolchain in BUILD-MANIFEST.json: Python, platform, `rustc -vV`, cargo, MSVC
  `VCToolsVersion`, `WindowsSDKVersion`, `VSCMD_VER` and `link`. With `--plugins` it says the compiler versions are
  unknown.
- `NATIVE-PROVENANCE.json` gains `javaRuntime` (vendor, release, source tag and commit, archive URL and SHA-256,
  licence) and `dllEmbeddedVersions` for libmpv.
- `build.gradle.kts`:
  - Jar timestamps off and file order fixed (`isPreserveFileTimestamps = false`, `isReproducibleFileOrder = true`).
  - **Defect found and fixed:** `createDistributable` did not list the prepared app resources as an input. After
    only a native resource changed (for example the motion plugins), Compose reported it UP-TO-DATE and kept the old
    image. Reproduced (the image kept the old plugin hash) and confirmed fixed (it now picks up the new plugin and
    is UP-TO-DATE when nothing changed).
  - Legal bundle: nested `.tar.gz` files inside jars are scanned for notices, and more notice file names are
    recognised. The inventory gains a `Declared licences (POM)` column, read from each artifact's POM and its
    parents, and the build warns when an artifact has neither.
- New `windows/tools/compare_builds.py` with 11 unit tests. It classes each file as identical, metadata or different:
  - metadata: a jar with identical entries, `SkyWing.cfg` that differs only in jar name hashes, a reformatted
    BUILD-MANIFEST.json
  - different: everything else, and any file present in only one build
- `verify.ps1` and `TESTING-POLICY.md`: any `windows/tools/*.py` change, and every full or release run, now runs the
  Python tool tests; a missing Python blocks the run.

### Reproducibility findings

- **App image, built twice locally from the same tree:** 543 files, 541 identical, 2 metadata only, **0 different**.
  The 2 are expected: `SkyWing.cfg` (Compose's jar name hashes) and the `skiko-awt-runtime` stub jar that Compose
  regenerates every time (same entries). Before the jar timestamp fix, the app jar also differed in metadata.
- **Motion payload, rebuilt locally with the supplied plugins:** all 63 payload files identical; BUILD-MANIFEST.json
  differs only in data that is not part of the binaries.
- **CI plugins (run 98) against local plugins:** genuinely different binaries, as expected. CI builds with MSVC
  link 14.44, a static CRT, deterministic PE timestamps and no machine paths. The local plugins came from a separate
  GNU-target build (`x86_64-pc-windows-gnu`, an LLVM linker, msvcrt imports and real timestamps). They are not a
  CI reproduction. Only a second CI build (the new step) can prove CI plugin reproducibility.
- **Not yet measured:** MSI and EXE reproducibility. WiX and jpackage embed timestamps and GUIDs; compare their
  extracted files (as `verify.ps1` already does against the image), not the package bytes.

### Licensing gaps resolved (with evidence)

- **FFmpeg revision inside libmpv:** the DLL contains `N-127893-g9a861e968` and `mpv v0.41.0-1227-ge471496c5`,
  matching the recorded revisions. This proves the revision, not the build configuration.
- **Java runtime source:** Temurin `jdk-17.0.20.1+1`, SOURCE commit `79597447…` = tag `jdk-17.0.20.1+1_adopt`.
  Source archive `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz`, SHA-256 `21e2a065…`, recorded and archived by CI.
- **JCEF notices:** the nested archive's CEF licence, README, GlueGen and JOGL licences are now in the bundle.
- **Runtime JAR licences:** all 46 artifacts have a POM-declared licence (37 embed no notice file of their own).

### Remaining source requirements (unresolved; `SOURCE-STATUS.md` stays INCOMPLETE)

1. **libmpv corresponding source.** Options compared in `SOURCE-STATUS.md`. **Recommended: the MSYS2 UCRT64 route**
   (every package has a version-matched source package); CI must download, hash and archive each one. It changes the
   DLL, so it needs a full playback regression and your decision. The current shinchiro DLL has no reconstructable
   source (62 of 108 builder recipes follow git master).
2. **Chromium third-party credits** for JCEF's Chromium 146.0.7680.179: not in any Maven artifact. Needs a download
   from the matching CEF build or extraction from the bundled `.pak`.
3. **Upstream NOTICE files** for the Apache-2.0 jars that embed none: check each source release.
4. **Skia/Skiko native notices** for Skia m132.
5. **VapourSynth R73 dependencies** cloned at floating heads, and the linked-library and compiler-runtime notices.
6. **Publishing** the source ZIP, Temurin source and native sources beside each release.

### Test results (2026-11-22)

- Python tool tests: `python -I -m unittest discover -s windows/tools -p "test_*.py"`: 11 tests, OK.
- `verify.ps1 -Mode focused -ListOnly`: plan only. It selects 19 classes and requires full, then release, mode
  (49 changed files, 8 areas). All prerequisites present, including Python.
- Off-screen Gradle run `.\gradlew.bat -p windows test generateWindowsLegal --no-daemon --rerun`: BUILD
  SUCCESSFUL; 24 classes, 112 tests, 0 failures, **25 skipped**. The skips are the native and UI tests, which need
  `verify.ps1` and desktop focus. This is **not** a full verification. `TestIsolationTest` passed (2/2).
- Isolation: the installed app is unchanged; `%LOCALAPPDATA%\SkyWing` was last written at 04:03, before this work;
  preferences were read only by the isolation test. No SMB file was opened.
- **Release mode,** `verify.ps1 -Mode release -AllowDesktopFocus`, run with your go-ahead at 06:15 (6 minutes). It
  tested the tree that is now committed: no tracked file changed after the run started, apart from this report.
  Report: `windows/build/reports/verification/verify-release-20261122-061554.md` (local build output).
  - Verdict **INCOMPLETE**, exit 3, as expected for a local build.
  - Tests: 112 in 24 classes, 0 failed, **0 skipped**. This includes the 25 native and UI tests that the off-screen
    run skips. Python tool tests: 11, OK.
  - Isolation: real preferences and library cache unchanged; 0 new temp folders; 0 lingering test processes; the
    installed SkyWing was not running.
  - Memory (private bytes): 585, 407, 456 and 456 MB after 4 player sessions (49 MB growth from session 2 to 4);
    482 to 519 MB over 12 app rounds (15 MB growth from round 4 to 12).
  - Release gates passed: legal notices 16/16; JVM inventory 46 rows; embedded notices kept; libmpv SHA-256; motion
    payload (0 mismatches); MSI upgrade UUID and UpgradeCode `{F40C59EF-E520-4E10-B7A2-B86E7458F923}`; x64,
    per-machine; file associations 10/10; shortcuts; MSI files match the tested image (540 identical, 2 regenerated
    with the same content); Java runtime 17.0.20.1; x64 binaries.
  - Packages, built and not installed: `SkyWing-0.1.1.msi` (291,971,928 bytes, SHA-256 `7EA171C1…`) and
    `SkyWing-0.1.1.exe` (292,639,232 bytes, SHA-256 `A586FC44…`).
  - Warning: the Vulkan loader is not bundled (by design; see *Release risks*).
  - Blockers, all expected for a local build: the local motion plugin override; the local version 0.1.1; no
    Authenticode signature on the MSI or the EXE; `SOURCE-STATUS.md` INCOMPLETE.

### Updated verdict: **NOT READY** for a public release

Unchanged blockers: corresponding source (libmpv, Chromium credits), signing, the clean-PC test, and a CI-built
candidate. The reproducibility changes are committed locally but unproven in CI.

| Needs | Task |
|---|---|
| Done 2026-11-22 | `verify.ps1 -Mode release -AllowDesktopFocus` on this tree (INCOMPLETE, as expected; see *Test results*), and the local commits |
| A push (your approval) | Then one CI run proves the Java pin, both reproducibility steps and the source archive, and builds the watchdog fix (ed072bf) in CI |
| A CI run | CI plugin reproducibility; the pinned Temurin version; the Java source artifact |
| An external download | Chromium credits; the MSYS2 source packages; upstream NOTICE files |
| Your decision | The libmpv route (MSYS2 recommended); signing; a clean-PC or VM machine |

## Follow-up review: blockers, evidence and priorities (2026-11-22, after the 0.1.99 release run)

Starting point: release run 4 (`verify-release-20261122-051246.md`), 112/112 tests passed, every packaging gate
passed, verdict INCOMPLETE (exit 3) with four blockers. Nothing was installed, uninstalled, signed, committed, pushed
or published in this review. The installed 0.1.98 was only read. No configuration of Codex, Claude Code or Windows
was changed.

### 1. Codex: instructions load; `verify.ps1` could not be executed

Tested live with Codex CLI 0.170.0 (`codex exec --ephemeral`, prompts and output in the session scratchpad).

| Run | Settings | Result |
|---|---|---|
| 1 | `--sandbox read-only`, user config | Codex reported `AGENTS.md` as the loaded instruction file (quoted its first heading), named `windows/TESTING-POLICY.md` and `verify.ps1 -Mode focused\|full\|release`, and listed all four repository skills. Its one attempt to run the command was refused: "The execution policy blocked process creation, so PowerShell never ran." |
| 2 | `--sandbox workspace-write --ignore-user-config` | Every PowerShell and `cmd` process was rejected; without the user's `[windows] sandbox = "elevated"` setting, Codex has no working Windows sandbox |
| 3 | `-c windows.sandbox="elevated"` | Not started: Claude Code's permission check refused to launch another agent with an elevated sandbox. This was not worked around |

Working-tree fingerprint before and after: unchanged (404 files). No network or connector use was reported.

**Proven:** discovery (AGENTS.md, policy, runner, skills). **Not proven:** that Codex can execute `verify.ps1` on
this PC. **Owner action:** run the prepared prompt yourself in your normal Codex session (it inherits your elevated
sandbox), for example in focused mode on an off-screen test class.

**Security note.** `~/.codex/config.toml` sets `approval_mode = "approve"` for GitHub connector tools that create
commits, update refs and merge pull requests (`create_commit`, `update_ref`, `merge_pull_request`, …). A Codex
session in this repository could push or merge without asking. That conflicts with the "no commit or push without
approval" rule. Consider changing those to prompt. This review did not change the file.

### 2. Corresponding source and licensing: missing artifacts and fixes

| `SOURCE-STATUS.md` item | Evidence gathered | Missing artifact | Fix | Who |
|---|---|---|---|---|
| 1. Native source for `libmpv-2.dll` | The DLL embeds FFmpeg `N-127893-g9a861e968` (matches the release's FFmpeg revision `9a861e96…`) and `mpv v0.41.0-1227-ge471496c5`. Configure: `--enable-gpl --enable-version3`, no `--enable-nonfree`. Of the builder's 108 package recipes at `1f8d2e76`, **62 clone git without a `GIT_TAG`** (FFmpeg, mpv, libass, libplacebo, dav1d, x264, x265, freetype2, openssl, curl, …), 33 are pinned, 13 are URL downloads (12 hashed; `mpv-release` is not). The run log does not list resolved commits | The exact source of 62 floating dependencies | (a) ask the producer for the source tarballs of run 39518569492; (b) fork the builder, pin every `GIT_TAG`, build in our CI and archive the sources; or (c) move to the MSYS2 build CI already uses for the preview: `repo.msys2.org/mingw/sources/` publishes per-version source packages (for example `mingw-w64-mpv-0.41.0-9.src.tar.zst`, `mingw-w64-libplacebo-7.360.1-2.src.tar.zst`), so CI can fetch and archive the exact sources of every installed package | Owner decides (a)/(b)/(c); (b) and (c) then automatable. (a) needs the producer |
| 2. Linked-component notice inventory | Feature list inside the DLL: libass, libbluray, libcurl, libplacebo, luajit, openal, rubberband, shaderc, spirv-cross, uchardet, vapoursynth, vulkan, zimg, zlib and more; FFmpeg enables ~35 external libraries | A notice file per linked library | Generate from the chosen build's recipe or package list (with option (c), from the MSYS2 packages' `share/licenses`) | Automatable after item 1's decision |
| 3. Skia/Skiko notices | Only the top-level Skia licence and Skiko NOTICE are packaged. Skiko 0.9.4.2 declares Skia `m132-a00c390e98-1` | Third-party notices for the libraries in that Skia build | Fetch the Skia source at that revision and collect the `third_party` licences listed in its `DEPS` for the Windows build; confirm with JetBrains `skia-pack` | Automatable; review needed |
| 4. JARs with no extracted notice | 38 of 46 inventory rows show 0 notice files: AndroidX lifecycle, Compose 1.8.2 (18 jars), Kotlin stdlib, kotlinx, Skiko, Gson, Error Prone annotations, JetBrains annotations, JOGL, GlueGen, JCEF. **New finding:** `jcef-natives-windows-amd64` (162 MB, Chromium 146) carries `LICENSE.txt`, `README.txt`, `gluegen.LICENSE.txt` and `jogl.LICENSE.txt` inside a nested `.tar.gz`, which the extractor does not open | Licence texts and Chromium/CEF credits | Take each artifact's licence from its POM and add the licence text; teach the extractor to read the nested JCEF archive; include CEF/Chromium credits. Also confirm the 162 MB browser runtime (used by `EmbeddedGoogleImages.kt`) is wanted in the release | Automatable; the JCEF question is the owner's |
| 5. Java runtime source | `BUILD-RELEASE.txt`: Temurin-17.0.20.1+1, `SOURCE=".:git:79597447bd94"`. Adoptium tag `jdk-17.0.20.1+1_adopt` resolves to commit `79597447bd94ff9f…`, the same commit. Adoptium publishes `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz` (109,272,338 bytes, with `.sha256.txt` and `.sig`) | The source tarball archived beside the release | CI: download the tarball, verify its SHA-256 and signature, upload it with the installers. Pin `setup-java` to 17.0.20.1 (it now takes the latest 17, so the shipped runtime can change between builds) | Automatable |
| 6. Application source | CI runs `git archive HEAD`, so it covers committed files only. The 35 uncommitted changes here are in no source ZIP | A ZIP of the exact release commit | Commit, then build the release in CI | Owner (commit and push approval) |

Also open: VapourSynth R73 (LGPL-2.1-or-later) build scripts clone some dependencies at floating heads, the same
problem as item 1. Its compiler runtimes (`msvcp*`, `vcruntime*`, `concrt*`) are Microsoft redistributables: list
them with their redistribution terms.

### 3. Motion plugins: local build compared with CI

| | Local 0.1.99 / installed 0.1.98 | CI run 98 (`9f580216`) |
|---|---|---|
| Plugins | `svpflow1_vs.dll` `e262337f…` (built 2026-11-10 22:11), `svpflow2_vs.dll` `44acd41b…` (2026-11-11 03:10). No Rust toolchain is on this PC now; how they were built is not recorded (see the comparison below) | Built by `build_motion.py`: rustup 1.98.1, `cargo build --release --locked`, MSVC from the runner image |
| Manifest | `localPluginOverride: true`, patch `12b0174e…` | override false; patch hash recorded after a CRLF checkout (`66d5c97e…`, proven) |
| Other 61 payload files | Same pinned downloads, same hashes in the installed and local manifests | Same pinned downloads |

The installed 0.1.98 was the local MSI, so it also has `localPluginOverride: true`.

**CI artifact compared (downloaded with the owner's approval, 2026-11-22).** Run 98's `skywing-windows-x64-preview`
artifact (expires 2026-11-26) was saved to the session scratchpad: `SkyWing-0.1.98.msi` SHA-256 `57185d46…`
(290,947,784 bytes), `SkyWing-0.1.98.exe` `4e6ea229…`, and the source ZIP. Its MSI files were extracted with WiX
`dark -x` (no installation) and compared:

| Check | CI 0.1.98 | Local 0.1.99 / installed 0.1.98 |
|---|---|---|
| Motion manifest | `localPluginOverride: false`; patch `66d5c97e…`; pinned inputs identical to local | `true`; patch `12b0174e…` |
| Motion payload against its own manifest | 63/63 match | 63/63 match |
| Files differing between CI and local | only `svpflow1_vs.dll` and `svpflow2_vs.dll` | the other 61 are byte-identical |
| `svpflow1_vs.dll` | `f3ff664a…`, 986,112 bytes | `e262337f…`, 2,266,112 bytes |
| `svpflow2_vs.dll` | `1dd242f9…`, 1,409,536 bytes | `44acd41b…`, 2,047,488 bytes |
| Compiler | rustc 1.98.1 (`48a229ce`) | rustc 1.98.1 (`48a229ce`) |
| Linker and CRT | MSVC link 14.44; static CRT; imports only `kernel32`, `ntdll`, `bcryptprimitives`, `api-ms-win-core-synch` | linker version 14.0; imports `msvcrt.dll`, `USERENV.dll`, `WS2_32.dll` |
| PE timestamp | deterministic hash value (not a date) | real build times, 2026-11-10 11:11 and 16:10 UTC |
| libmpv | `04e4d27d…` (shinchiro, matches provenance) | same |
| Java runtime | **Temurin 17.0.21+9** (`setup-java` "17" took the newest) | Temurin 17.0.20.1+1 |
| File list | 534 files | 539: plus `jdk.accessibility` (Java Access Bridge, from the uncommitted Stage 3 work) |

**Conclusion.** The local plugins were not built by the release recipe. Their `msvcrt.dll`, `USERENV` and `WS2_32`
imports and the different linker are what Rust's `x86_64-pc-windows-gnu` target produces, while the manifest declares
`x86_64-pc-windows-msvc` with a static CRT. This is inferred from the binaries; how they were built is not recorded.
The 0.1.98 you use daily and the 0.1.99 test package therefore carry plugins that the release CI would never produce.
All 112 tests pass with them, but the CI plugins have not been through the local native and UI tests.

**CI plugins tested locally (owner's go-ahead, 2026-11-22 05:43).** The local `svpflow1_vs.dll`, `svpflow2_vs.dll`
and `BUILD-MANIFEST.json` were backed up to the session scratchpad. The CI files from the 0.1.98 MSI replaced them in
`windows/build/native-resources/windows/motion` (CI manifest: override false, 63/63 hashes match). Then
`verify.ps1 -Mode full -AllowDesktopFocus` ran (`windows/build/reports/verification/verify-full-20261122-054327.md`):
**112 tests, 0 failed, 0 skipped**, including `MotionNativeTest` (39 s) and `MotionAutomationTest`. Verdict
INCOMPLETE only because policy requires release mode before shipping. The tested app image carried the CI plugins
(`f3ff664a…`, `1dd242f9…`). Isolation: real preferences and library cache unchanged, no temp folders or processes
left. App memory 483–510 MB, growth +9 MB (round 4 to 12). The local files were then restored (hashes
`e262337f…`, `44acd41b…`, manifest `6fc0fd2a…` checked). The installed 0.1.98 was not touched.

So the CI-built plugins pass the same native and UI suite as the local ones. Shipping them removes the local
plugin-override blocker without losing test coverage.

The CI build already gives deterministic PE timestamps. What is still unpinned: the random build directory
(`skywing-motion-uzdl7ost`), which ends up in panic paths; the MSVC version from the runner image; and the Java
runtime version.

**Recommended fixes (automatable, need a commit and a CI run to prove):**
1. Pin the patch to LF in `.gitattributes`, and record the hash of the LF bytes actually applied.
2. Reproducible Rust build: a fixed build directory or `--remap-path-prefix` for the source and cargo home; record
   `rustc -vV`, `link.exe` and the Windows SDK version in `BUILD-MANIFEST.json`. Pin `setup-java` to the exact
   Temurin release.
3. Before release, run `verify.ps1 -Mode full` locally against the CI-built motion payload (replace the local
   plugins with the CI pair), so the shipped plugins have passed the native and UI tests.
4. Build the plugins twice in CI and fail if the hashes differ; print the manifest in the log.
5. Release only from CI. Run the `verify.ps1` release checks against the CI MSI; today CI checks the app image, not
   the MSI.
6. Do not ship local plugin builds; keep `--plugins` for local experiments only.

### 4. Signing options (nothing bought, nothing changed)

| Option | Approximate cost | Requirements | SmartScreen / SAC effect |
|---|---|---|---|
| SignPath Foundation | Free | OSI licence (GPL-3.0 qualifies), public repository, fully automated CI build, MFA for all members, no proprietary components; the certificate names SignPath Foundation as publisher | Trusted signature; SmartScreen reputation builds over time |
| Azure Artifact Signing (formerly Trusted Signing) | About US$9.99/month (Basic, 5,000 signatures) | Individual developers: USA and Canada only (organisations also EU and UK); identity validation | Microsoft-rooted; reputation builds over time |
| Commercial OV certificate (Sectigo, DigiCert, SSL.com) | About US$220–450/year | Identity validation, hardware token or cloud HSM; from 2026-02-23 certificates last at most 459 days | Valid signature; reputation builds over time |
| Commercial EV certificate | More than OV | Registered organisation | Since 2024, Microsoft no longer gives EV certificates instant SmartScreen reputation |
| Microsoft Store (MSIX) | Developer account fee | Repackage as MSIX; Store signs it | Trusted on install; a different installer type and upgrade identity |

Prices come from vendor and reseller pages and change; confirm before buying. Smart App Control has no per-app
exception, so a signature is the only way SkyWing runs where SAC is on. Both the EXE and MSI need signing, plus
ideally the EXE inside the image (`SkyWing.exe`) and SkyWing's own DLLs. Signing is done in CI with a timestamp.
Recommendation: apply to SignPath Foundation first (free, CI-based, fits a public GPL repository); otherwise
Azure Artifact Signing if your location qualifies.

### 5. Clean-PC install, upgrade and uninstall plan (prepared, not run)

**Environment.** This PC runs Windows 11 Home: no Windows Sandbox, no Hyper-V. Options, in order of preference:
- a spare PC, or a laptop reset to a fresh Windows 11 install;
- a cloud Windows VM (pay per hour; usually no GPU, which covers the no-Vulkan case);
- VirtualBox or VMware Workstation on this PC (installing it needs your approval; their virtual GPU has no Vulkan
  driver, so they also cover the no-Vulkan case).

Use a dedicated local account, no personal data, no real NAS credentials. Take a snapshot before step 1 where the
environment supports it.

**Payload.** A CI-built, signed (if available) EXE and MSI, with the release-mode `verify.ps1` report for the same
commit. Record their SHA-256 hashes before copying them across.

| # | Step | Pass criteria |
|---|---|---|
| 1 | Record the baseline: `Get-Package`, `HKLM\…\Uninstall`, Start menu, `%LOCALAPPDATA%`, file associations for the 10 video types in `build.gradle.kts` | Saved for comparison |
| 2 | Fresh install from the MSI (per-machine, UAC) | Installs to `C:\Program Files\SkyWing`; Start menu shortcut; optional desktop shortcut works as chosen; Uninstall entry shows the right version and publisher |
| 3 | First launch | No SmartScreen block if signed (note the prompt if not); the library opens; no error dialogs |
| 4 | Playback | A local MP4 and MKV play; subtitle and audio track switch; seek; fullscreen in and out; close leaves no process or audio |
| 5 | File association | Double-clicking a `.mkv` opens SkyWing and plays it |
| 6 | No-Vulkan case (VM without a vendor driver) | SkyWing shows a clear error and stays usable; no crash or silent failure |
| 7 | Upgrade: install the next CI MSI over it | One Uninstall entry, new version; settings and library kept; old files removed |
| 8 | Downgrade attempt with the older MSI | Refused with a message |
| 9 | Uninstall (Settings → Apps) | Program folder, shortcuts, associations and Uninstall entry removed; user data handled as documented |
| 10 | Compare with the baseline; repeat with the EXE installer | Nothing left behind apart from documented user data |

Do not use this PC: its 0.1.98 install must stay. The UpgradeCode is not changed by any step.

### 6. Memory variation: diagnose, but not a release blocker

App private bytes in the 12-round stability test fall into two stable levels:

| Run | Round 1 | Range | Growth, round 4 → 12 |
|---|---|---|---|
| full 04:22 | 487 MB | 487–518 MB | 0 MB |
| release 04:51 | 483 MB | 483–515 MB | −3 MB |
| release 05:01 | 482 MB | 482–514 MB | −4 MB |
| full 04:35 | 711 MB | 711–754 MB | +17 MB |
| release 05:06 | 710 MB | 710–760 MB | −6 MB |
| release 05:12 | 711 MB | 711–759 MB | +16 MB |

The level is set by round 1 and stays about 225 MB apart; within each run, memory is flat. That is not a leak,
and the test's 160 MB growth limit held in every run. SkyWing sets no `-Xmx`, so the JVM sizes its heap from
system memory and commits it adaptively. The measurement takes the largest process in the tree, which counts the
Java heap, native libmpv and GPU driver allocations together. The cause is **not known**.

A controlled measurement is worth doing as a diagnostic, not before release. Protocol (takes the desktop for about
10 minutes; ask first):
- Run only `NetworkAndStabilityAutomationTest` 6 times on mains power, with the same display state and no other
  heavy apps.
- At each round, also record: committed and used Java heap (`jcmd <pid> GC.heap_info`), a Native Memory Tracking
  summary (`-XX:NativeMemoryTracking=summary`), the Skiko render API, mpv's `hwdec-current` and `current-vo`, and the
  process's GPU memory counters.
- A/B: repeat 6 runs with `-Xmx512m`. If both levels collapse to one, the variation is JVM heap sizing.

### 7. Blockers: what can be resolved automatically, and what needs you

| Priority | Item | Resolve automatically? | Needs |
|---|---|---|---|
| 1 | Release from CI only (local plugin override) | Yes: fixes in §3 | Your approval to commit and push; a CI run |
| 2 | Corresponding source for libmpv | Partly: after you choose (a), (b) or (c) in §2, CI can fetch and archive sources | **Your decision.** (a) needs the libmpv producer; (c) changes the native binary and needs full regression |
| 3 | Notices: Skia, zero-notice JARs, JCEF/Chromium, VapourSynth runtimes | Yes, then a review | Your decision on whether JCEF ships |
| 4 | Java runtime source | Yes: CI download and archive; pin `setup-java` | Commit approval |
| 5 | Application source ZIP for the release commit | Yes, once committed | Commit approval |
| 6 | Signing (EXE and MSI unsigned) | No | **Your decision and an external provider**: SignPath application, or a paid certificate |
| 7 | Clean-PC install, upgrade, uninstall | No | **A disposable Windows machine** you provide or approve |
| 8 | Hardware: HDR, VRR, frame pacing, multi-monitor, Alt+Tab | No | **Your hardware** and a manual session |
| 9 | Codex execution of `verify.ps1` | No (blocked by sandbox settings, correctly) | You run it in your own Codex session |
| 10 | Memory variation | Diagnostic only | Your go-ahead for a desktop-taking run |
| — | Motion patch hash (CRLF) | Yes: root cause proven | Commit approval |

## Final review: release-mode verification (2026-11-22)

With the owner's authorisation, SkyWing 0.1.99 EXE and MSI test packages were built (`GITHUB_RUN_NUMBER=99`, one
above the installed 0.1.98). They were **not installed**. Nothing was committed, pushed, published or distributed.

**Result: release mode INCOMPLETE, exit code 3.** Every test, the packaging and every release check passed. The
four public-release blockers remain open.

Command (repository root):
`$env:GITHUB_RUN_NUMBER='99'; powershell -NoProfile -ExecutionPolicy Bypass -File windows\tools\verify.ps1 -Mode release -AllowDesktopFocus -MpvDir windows\build\compose\binaries\main\app\SkyWing\app\resources`.
`-MpvDir` points the in-process native tests at the packaged app image's libmpv. The UI automation tests launch that
packaged image. The MSI was then shown to contain the same files. Report:
`windows/build/reports/verification/verify-release-20261122-051246.md`.

### Tests

| | Result |
|---|---|
| Tests | **112 passed, 0 failed, 0 skipped**, 24 classes |
| Isolation | Real `HKCU\Software\JavaSoft\Prefs\com\skywing` and `%LOCALAPPDATA%\SkyWing` unchanged; registry key write times unchanged since 04:31:36, before the isolation fix |
| Cleanup | 0 `skywing-*` temp folders, 0 test or Java processes, MSI extraction folder removed |
| Installed SkyWing | 0.1.98 untouched (files dated 2026-11-21 16:26); not running during the run; its cached installer `C:\WINDOWS\Installer\561700d.msi` is present for repair and uninstall |
| Player memory | 4 sessions of 10 switches: 605, 482, 467, 469 MB; −13 MB from session 2 to 4 |
| App memory | 12 rounds: 711–759 MB; +16 MB from round 4 to 12 (bounded). Runs 1–2 measured 483–515 MB with the same app image; runs 3–4 measured about 230 MB more. Not explained; growth stayed bounded in all four runs |

### How the verdict was reached (four release runs, three defects found and fixed)

| Run | Outcome | Root cause | Fix |
|---|---|---|---|
| 1 (04:51) | FAIL: 112/112, but "MSI files match the tested app image" failed (4 differences) | `packageMsi` packs its own copy of the image. The Compose plugin regenerates a manifest-only `skiko-awt-runtime` jar for each package; new timestamps give it a new content-hashed name, and `SkyWing.cfg` lists that name. Entries were identical (checked with a hash of each entry) | The check now extracts the MSI read-only with WiX `dark` and compares every file by SHA-256. A jar whose name differs only in that hash must have identical entries; `SkyWing.cfg` must be identical with jar hashes masked. Negative tests: a changed `SkyWing.cfg` and a removed `libmpv-2.dll` are both reported |
| 2 (05:01) | FAIL: 112/112, but "built by this run" failed | Gradle reused the run 1 packages (`packageExe`/`packageMsi` UP-TO-DATE) | Release mode runs `packageExe --rerun packageMsi --rerun`, so every release run builds fresh packages |
| 3 (05:06) | FAIL: 111/112. `EmbeddedPlaybackNativeTest.playsMp4AndMkvWithPauseResumeAndSeek`: "decoder for clip-vp9.mkv expected:<[vp9]> but was:<[h264]>" | Test race. `loadfile` is asynchronous; the test waited only for position > 0.3 s, which the still-playing MP4 already satisfied. Its two sibling tests and `EmbeddedMpv` already wait for mpv's `path` | The test now also waits for `path` to be the new clip. Focused run: 9/9 PASS. Not a product defect; no app code changed |
| 4 (05:12) | **INCOMPLETE, exit 3**: 112/112, all release checks pass, 4 blockers | — | — |

A defect in the runner itself was also fixed: in release mode the report crashed under StrictMode when the release
checks had not run (seen with `-ListOnly`; a test failure would have done the same).

### Packages (build only, not installed)

| Package | Path | Version | Size | SHA-256 |
|---|---|---|---|---|
| EXE | `windows/build/compose/binaries/main/exe/SkyWing-0.1.99.exe` | 0.1.99 | 292,626,944 B | `20C35618409D83894220F5476C22B54D564072D493FCEA4B796C95A0A66C2C40` |
| MSI | `windows/build/compose/binaries/main/msi/SkyWing-0.1.99.msi` | 0.1.99 | 291,959,580 B | `870D03DCCD42AE7D196FDE52103E195462983F9994B85F102B2DD9FA4BBB968E` |

The package tasks empty their output folders, so the 0.1.98 EXE and MSI build outputs of 2026-11-21 are no longer in
`build/`. Their hashes are recorded below. The installed 0.1.98 is unaffected. The policy now says to copy earlier
packages out before a release run.

### Native dependencies and packaging validation (all automated in `verify.ps1` release mode)

| Check | Result |
|---|---|
| Upgrade identity | MSI `UpgradeCode {F40C59EF-E520-4E10-B7A2-B86E7458F923}`, the same as `build.gradle.kts` `upgradeUuid`; unchanged |
| Version | MSI ProductName SkyWing, ProductVersion 0.1.99; EXE ProductVersion 0.1.99 |
| Architecture | MSI platform `x64;1033`, per-machine (`ALLUSERS=1`); `SkyWing.exe`, `libmpv-2.dll` and `jvm.dll` are PE machine 0x8664 |
| Bundled runtime | Java 17.0.20.1 (`runtime/release`), modules `java.base … jdk.accessibility jdk.crypto.ec` |
| libmpv | SHA-256 matches `NATIVE-PROVENANCE.json` (pinned shinchiro build `04e4d27d…`) |
| Motion engine | Every file matches `motion/BUILD-MANIFEST.json`; **blocker:** `localPluginOverride` is true (locally built plugins) |
| MSI contents | 537 files byte-identical to the tested app image, 2 regenerated with the same content, no differences |
| File associations | 10/10 from `build.gradle.kts` (.avi .hevc .m2ts .m4v .mkv .mov .mp4 .ts .webm .wmv), each with a "SkyWing video" ProgId and an Open command `"[SkyWing.exe]" "%1"` |
| Shortcuts | Start menu and desktop, conditioned on `JP_INSTALL_STARTMENU_SHORTCUT` and `JP_INSTALL_DESKTOP_SHORTCUT` |
| Licences and notices | 16/16 legal files present; JVM inventory 46 rows; embedded JVM notices preserved |
| Corresponding source | **Blocker:** `legal/SOURCE-STATUS.md` is "Status: INCOMPLETE" (see blocker 1) |
| Signatures | **Blocker:** EXE and MSI are not Authenticode-signed |
| Vulkan loader | Warning: not bundled by design; `libmpv-2.dll` needs the GPU driver's `vulkan-1.dll` |

### Shared testing for Claude Code and Codex

- **Single source:** `windows/TESTING-POLICY.md`. `AGENTS.md` (Codex) points to it. `CLAUDE.md` (Claude Code)
  imports `AGENTS.md` with `@AGENTS.md`. All four skills point to the policy and the same command,
  `windows\tools\verify.ps1`. `.claude/skills` is a byte-identical copy of `.agents/skills`.
- **Checked on every run:**
  - the skills copy is identical
  - `CLAUDE.md` still imports `AGENTS.md`
  - `AGENTS.md` still names the policy and the runner
  - any failure BLOCKS the run
- **Discovery.**
  - Claude Code: confirmed live. This session loaded `CLAUDE.md` and the imported `AGENTS.md`.
  - Codex: checked statically. The repository is the git root, `AGENTS.md` is 3.9 KB (under Codex's default
    32 KiB project-doc limit), the skills are in `.agents/skills/`, and the user's Codex config marks the parent
    folder trusted. That config was only read.
  - Neither CLI was run, and no global configuration of either assistant was changed.
- **Mode by risk:**
  - focused for small changes
  - full for playback, full screen, app shell and test framework changes, and for three or more areas
  - release for packaging, native payload, legal and installer inputs
- **Safeguards for user data:**
  - The test JVM and every launched app get file-backed preferences and their own `LOCALAPPDATA`, `APPDATA` and
    `user.home`. `TestIsolationTest` guards this.
  - The runner fingerprints the real preferences (including the SMB share profiles stored there) and the real
    library before and after every run, and fails on any change or leftover.
  - The only share used is the read-only `\\localhost\C$` loopback.
  - Release mode never installs anything.
- **Missing dependencies fail clearly:** a missing JDK 17, libmpv, Vulkan loader, ffmpeg or loopback share gives
  BLOCKED (exit 2) with the missing item named. A skipped test is a FAIL.

### Outstanding licensing and code-signing requirements

1. Assemble the corresponding source for the GPL libmpv/FFmpeg build and complete the six open items in
   `SOURCE-STATUS.md` (blocker 1).
2. Ship only a CI-built payload whose motion manifest has no local plugin override (blocker 4).
3. Sign the EXE, MSI and SkyWing DLLs with a trusted certificate (blocker 2; see *Signing options*).

### Remaining hardware-dependent checks

These are not verified:
- clean-PC install, upgrade and uninstall (blocker 3)
- a PC without a vendor GPU driver (Vulkan)
- HDR, tone mapping and Dolby Vision
- VRR engagement
- displayed frame pacing
- multi-monitor
- real Alt+Tab
- a real NAS, credentials, slow links and a dropped SMB connection

### Release recommendation: **NOT READY**

The 0.1.99 test packages pass every automated test and package check on this PC. They are fit for private testing
only, by explicit owner decision (installing them needs the owner's approval and a UAC prompt). A public release
still needs blockers 1–5 closed: corresponding source, signing, a clean-PC install test, a CI-built payload, and the
hardware checks.

## Stage 5 completion review (2026-11-22)

### Requirement status

| Requirement | Status | Evidence |
|---|---|---|
| Corrupt and missing files show an error | Done 2026-11-21 | Phase A below; regression tests red then green |
| Fullscreen accessibility auto-hide | Reviewed: by design | Phase A below |
| Full regression twice | Done 2026-11-21: 110/110 twice, 0 skipped | *Test results* |
| EXE/MSI build, payload, hashes | Done 2026-11-21 (0.1.98: EXE `2f337dd8…`, MSI `46791fbb…`); release mode 2026-11-22 (0.1.99: EXE `20c35618…`, MSI `870d03dc…`) | *Final review* |
| In-place upgrade, settings preserved | Done 2026-11-21 | *Installer and upgrade* |
| Native tests on the installed payload | Done 2026-11-21: 23/23. On 2026-11-22 the release run used the packaged image's libmpv | *Installer and upgrade*, *Final review* |
| Licensing review | Done 2026-11-22: **blocker 1** | `SOURCE-STATUS.md`, `LICENSING.md`, `NATIVE-PROVENANCE.json` |
| Signing, SmartScreen, Smart App Control | Done 2026-11-22: **blocker 2** | Authenticode status, CodeIntegrity log, SAC registry state |
| Installer files, architecture, dependencies, shortcuts, associations, upgrade identity | Done 2026-11-22 | *Installer verification* |
| Fresh install and uninstall | **Not possible here**: blocker 3 | No isolated environment |
| HDR, VRR, GPU, motion, SMB, hardware | Reviewed: blocker 5 | *Hardware gaps* |
| Shared Claude Code/Codex testing | Done 2026-11-22 | *Shared automatic testing*, *Final review* |

No production code changed on 2026-11-22; test set-up, one test and the runner changed. The full suite ran again in
full mode and four times in release mode (see *Final review*).

### Signing options

| Option | Cost | Fit for SkyWing | SmartScreen / Smart App Control |
|---|---|---|---|
| No certificate | Free | Private preview only | SmartScreen warns on every new build. SAC blocks it (seen on this PC on 2026-10-25) |
| Self-signed certificate | Free | No | Trusted only on PCs where the certificate is imported by hand. No help for other users |
| SignPath Foundation | Free | Possible: it requires an OSI-licensed public repository, an automated CI build, no proprietary components and MFA, and SignPath Foundation is named as the publisher | Signed. Reputation still builds over time |
| Azure Artifact Signing (formerly Trusted Signing) | About US$9.99/month | Only if the owner qualifies: individual developers in the USA or Canada; organisations in the USA, Canada, EU or UK | Signed. Reputation is not instant |
| Commercial OV or EV certificate | Per year, hardware token or cloud HSM | Works anywhere | Signed. Reputation still builds |
| Microsoft Store (MSIX) | Store developer account | Needs MSIX packaging, a larger change | Microsoft signs the package; Store apps are not blocked by SAC |

What can be done without buying anything: keep distributing privately with clear SmartScreen instructions; prepare
the CI for signing (a signing step that runs only when a secret is present); and apply to SignPath Foundation once the
repository is public and the licensing gate is closed. Signing does not fix licensing; both are needed.

Sources: Microsoft [code signing options](https://learn.microsoft.com/windows/apps/package-and-deploy/code-signing-options),
[SmartScreen reputation](https://learn.microsoft.com/windows/apps/package-and-deploy/smartscreen-reputation),
[Smart App Control](https://support.microsoft.com/en-us/topic/what-is-smart-app-control-285ea03d-fa88-4d56-882e-6698afdb7003),
[Artifact Signing pricing](https://azure.microsoft.com/pricing/details/artifact-signing/),
[SignPath Foundation terms](https://signpath.org/terms).

### Installer verification (read-only, 2026-11-22)

| Check | Result |
|---|---|
| Hashes | EXE and MSI SHA-256 unchanged from the 2026-11-21 build |
| Architecture | `SkyWing.exe` and `libmpv-2.dll` are x64 (PE machine 0x8664). The MSI is per-machine (`ALLUSERS=1`), x64 |
| Runtime dependencies | `SkyWing.exe` imports only KERNEL32, SHELL32 and USER32. The Java runtime is bundled. The UCRT `api-ms-win-crt-*` imports are part of Windows 10/11. `vulkan-1.dll` comes from the GPU driver (see *Release risks*) |
| Upgrade identity | `UpgradeCode {F40C59EF-E520-4E10-B7A2-B86E7458F923}`, the same as `build.gradle.kts`. `JP_UPGRADABLE_FOUND` covers older versions; `JP_DOWNGRADABLE_FOUND` blocks downgrades; `RemoveExistingProducts` is at sequence 798 |
| Files | 539 MSI File rows; 539 installed files match the app image (the image's extra `.jpackage.xml` is never packaged). Corrected 2026-11-22: earlier text said 540 rows |
| Shortcuts | Two shortcut components, conditioned on `JP_INSTALL_STARTMENU_SHORTCUT` and `JP_INSTALL_DESKTOP_SHORTCUT`. A quiet `/qb` install creates only the Start menu shortcut |
| File associations | HKCR rows in the Registry table (`.ext` → ProgId "SkyWing video" → `shell\open\command`), not the Extension table. Corrected 2026-11-22: earlier text said the reverse. All 10 extensions map to `"C:\Program Files\SkyWing\SkyWing.exe" "%1"` |
| Installed app | Still 0.1.98 `{7ED7DEBA-…}`, the same 539 files as after the upgrade |

### Fresh install and uninstall

Not performed: there is no isolated environment, and uninstalling the working 0.1.98 was not permitted. Checklist for
a spare PC or VM (each MSI action needs a UAC approval that only the user can give):

1. Install the CI MSI on a PC that has never had SkyWing. Confirm the Start menu shortcut, the optional desktop
   shortcut, the 10 associations, first launch, playback and a clean exit.
2. Repeat on a PC with only the Basic Display Adapter, to see how a missing `vulkan-1.dll` is reported.
3. Install 0.1.97, add settings and a library, then upgrade to the candidate. Settings and library must remain.
4. Uninstall. Program Files, the shortcuts and the associations must be gone. User data
   (`HKCU\Software\JavaSoft\Prefs\com\skywing`, `%LOCALAPPDATA%\SkyWing`) stays; that is the expected MSI behaviour.
5. Try to install an older MSI over a newer one: it must be refused.

### Hardware gaps

- **HDR / Dolby Vision:** not measured. This panel's HDR state and tone mapping need a manual check on HDR content.
- **VRR:** SkyWing has a `DisplayVrr` check, but actual VRR engagement on a display was not observed.
- **GPU:** the motion worker ran on the NVIDIA RTX 5070 Ti; the player picks the D3D11 adapter of the monitor (Intel
  here). Whether that split is best on a hybrid laptop was not measured.
- **Motion:** "Holding N fps" is the configured output rate, not presented frames. Frame pacing was not measured.
- **SMB:** only the read-only `\\localhost\C$` loopback was used. A real NAS, credentials, slow links and a dropped
  connection during playback were not tested. A host that resolves but answers slowly could block the UI.
- **Display:** one display only; multi-monitor and real Alt+Tab were not tested.

### Shared automatic testing (Claude Code and Codex)

- **One policy:** `windows/TESTING-POLICY.md`. It holds the rules, prerequisites, change-to-test table, isolation and
  desktop-focus rules, exit codes and the final report format.
- **Instructions:** `AGENTS.md` (read by Codex) points to the policy. A new root `CLAUDE.md` imports `AGENTS.md`
  with `@AGENTS.md`, so Claude Code reads the same file rather than a copy.
- **Skills:** the debugging, release-QA, desktop-design and (since the final review) video-performance skills point
  to the policy instead of a bare `gradlew test`. `.claude/skills` is kept byte-identical to `.agents/skills`, and the script checks this on every
  run.
- **Runner:** `windows/tools/verify.ps1`, with three modes:
  - `focused`: maps changed files to test classes.
  - `full`: the whole suite with native libmpv and UI automation.
  - `release`: full, then the packages and release gates.
- **What the runner enforces:**
  - It sets the test environment variables itself.
  - A missing prerequisite gives BLOCKED.
  - Tests that take the desktop are refused without `-AllowDesktopFocus`.
  - A skipped test, or a selected class that did not run, gives FAIL.
  - It fails if the real preferences or library change, or if temp folders or processes are left behind.
  - When a broader mode is required, or release gates are open, it reports INCOMPLETE (exit 3).

### Tests executed on 2026-11-22

| Run | Result |
|---|---|
| `verify.ps1 -ListOnly` for the current working tree | 34 changed files, 8 areas, 18 classes selected. Requires full and release mode. BLOCKED without `-AllowDesktopFocus` (correct) |
| Mapping checks with `-Paths` (docs, Smoothing, share browser, test helper, unmapped code, `build.gradle.kts`) | Each selected the expected classes and escalation |
| Missing libmpv (`-MpvDir C:\nonexistent`) | BLOCKED, exit 2 |
| Documentation only | NO-TESTS, exit 0 |
| Focused run of the 12 off-screen unit classes | **67 tests, 0 failed, 0 skipped.** Real preferences and library unchanged, no leftovers. INCOMPLETE (exit 3), because unmapped code requires full mode |
| A selected class that does not exist | FAIL, exit 1 ("DID NOT RUN") |
| Release gates, read-only, against the existing 0.1.98 outputs | 9 pass; 1 warning (Vulkan loader); 4 blockers: local plugin override, unsigned MSI, unsigned EXE, source audit incomplete |

Release mode was run later the same day with the owner's authorisation; see *Final review*. Nothing was installed or
uninstalled.

### Full mode with the owner's go-ahead: test-isolation defect found and fixed

**Run 1** (04:22): 110/110 passed, 0 skipped. **verify.ps1 still reported FAIL**, because the real preferences
changed. The registry key times showed a write to `HKCU\Software\JavaSoft\Prefs\com\skywing\desktop\sync` during the
run:
- `last_status` changed from "Up to date" to "Finished with scan errors".
- `last_success` was updated.
- No other value changed; `%LOCALAPPDATA%\SkyWing` was unchanged.
- The playback-history change since 2026-11-21 dates from 04:03, before the run. That was the owner's own use.

**Root cause.** The test JVM had no preference or data isolation; only the packaged app launched by
`SkyWingTestApp` did. Two places ran app code in the test JVM:
- `FullscreenWindowTest` renders `SkyWingWindowContent` in-process.
- `closingProductionApplicationLeavesNoBackgroundProcess` starts `MainKt` as a child process with no isolation.

Both create `LibrarySync`. Its startup sync read the owner's library folders and network shares (listing only, no
writes to media or shares) and stored its status in the real registry. This test existed before Stage 5 (commit
7b8727c), so earlier full runs did the same. The earlier "no test markers" check did not detect it.

**Fix (test set-up only, no app code):**
- `build.gradle.kts` `tasks.test`: file-backed preferences factory, plus `LOCALAPPDATA` and `APPDATA` redirected to
  `build/test-user-data`.
- `FullscreenWindowTest`: the child process gets the same isolation as `SkyWingTestApp`. The test now asserts that
  the child wrote its own preferences file.
- `LibraryAutomationTest`: still compares the real library folder, which now comes from the
  `skywing.test.realLocalAppData` property.
- New guard `TestIsolationTest` (2 tests); `verify.ps1` adds it to every focused run.
- `verify.ps1`: git line-ending warnings on stderr no longer stop the script.

**Results after the fix:**
- Focused run (FullscreenWindowTest, TestIsolationTest, ArtworkTest, SmoothingTest): 25/25, PASS. Real preferences
  unchanged; no registry key touched.
- **Full mode (04:35): 112/112 passed, 0 failed, 0 skipped, 24 classes.**
  - Real preferences and `%LOCALAPPDATA%\SkyWing` unchanged.
  - 0 leftover temp folders, 0 leftover processes.
  - Memory: player sessions 602/409/464/463 MB; packaged app growth 17 MB from round 4 to 12.
  - Verdict INCOMPLETE (exit 3). This is correct: `build.gradle.kts` changed, so the policy requires release mode
    before shipping.

**Owner data note.** The real `desktop\sync` status now shows a test run's result ("Finished with scan errors").
It is display-only, and the next library sync in SkyWing overwrites it. It was not restored by hand: the exact
pre-run values were not captured.

---

# Stage 5 work of 2026-11-21

## Phase A — defects fixed

| Defect | Fix | Regression tests |
|---|---|---|
| Corrupt or undecodable file failed silently: 0:00 of --:--, status "Load request accepted" | `EmbeddedMpv` records mpv END_FILE with reason ERROR for the opened file (`playbackFailure`). Events left from the previous video are drained before `loadfile`. Opening or stopping a video clears it. Motion-filter failures keep their existing recovery path. `Main` shows a **"Can’t play this video"** window and keeps the reason in the player status. | `EmbeddedPlaybackNativeTest.aCorruptFileReportsAPlaybackFailureThatTheNextVideoClears` (seen failing before the fix); `PlaybackAutomationTest.aCorruptVideoDoesNotStopTheNextOne` |
| Missing file failed with no visible error: the message was set but the player page never opened | `openVideo` shows a **"Video not available"** window: "<name> can’t be found. It may have been moved or renamed, or the drive or network share it is on is offline." | `PlaybackAutomationTest.aMissingVideoOnTheCommandLineShowsWhyAndLeavesTheLibraryUsable`; `NetworkAndStabilityAutomationTest.anUnreachableShare…` (library card on an offline share) |

- Both messages use a separate `DialogWindow`, the same pattern as Motion options, so they show above the native
  video HWND in a window or in full screen.
- OK, Enter or Esc closes them. They do not crash, freeze, open a broken player or leave the library unusable.
- No UI redesign was made.
- Files: `playback/EmbeddedMpv.kt`, `Main.kt`, the new `PlaybackProblemDialog.kt`.
- A UNC path whose host does not answer returns `isFile == false` in 0 ms here. A host that resolves but answers
  slowly was not measured.

**Fullscreen accessibility auto-hide: reviewed, no defect, no change.**
- Hidden controls leave the JAB tree on purpose, so screen readers do not announce invisible controls.
- Any key press (`PlayerChrome.kt` treats every KeyEvent as intent) or pointer movement brings them back.
- They stay while the video is paused or hovered.
- `FullscreenAutomationTest` now checks that Shift restores the controls to the tree and that they hide again.
- Documented in `windows/FUNCTIONAL-TESTS.md`.

## Test results

| Run | Tests | Failures | Errors | Skipped | Leftover data / processes |
|---|---|---|---|---|---|
| Full suite, run 1 (native libmpv, UI automation, ffmpeg) | 110 | 0 | 0 | 0 | 0 / 0 |
| Full suite, run 2 | 110 | 0 | 0 | 0 | 0 / 0 |

- The setup had `SKYWING_TEST_MPV=true`, `SKYWING_TEST_MPV_DIR` set to the installed resources (read-only), and
  `SKYWING_TEST_AUTOMATION=true`. ffmpeg and ffprobe were on PATH.
- No required test was skipped, which includes the ffmpeg-gated motion tests.
- 110 = 109 from Stage 4 + 1 new native test. The changed e2e tests replace old assertions in place.
- No `skywing-*` temp folders remained, and the real `HKCU\Software\JavaSoft\Prefs\com\skywing` has no test markers. (Correction, 2026-11-22: these runs did write the library-sync status to the real preferences; see *test-isolation defect* above.)
- Focused runs during Phase A all passed: native playback 7/7, motion native and unit suites, playback UI 5/5,
  fullscreen UI 2/2, network and stability 3/3.

## Packaging, payload, hashes, notices

| Item | Result |
|---|---|
| `SkyWing-0.1.98.exe` | Built, 292,626,944 B, SHA-256 `2f337dd89ef904606a1767ad18eff15c8f027223e6471b9c70b2ad245deb1115` |
| `SkyWing-0.1.98.msi` | Built, 291,959,580 B, SHA-256 `46791fbbf5c5737f5e33373747076793f5e94acf8db8b1665b7069e4afb582fc` |
| MSI UpgradeCode | `{F40C59EF-E520-4E10-B7A2-B86E7458F923}`, unchanged; matches `build.gradle.kts` and commit b00bbf2 |
| MSI upgrade table | Upgrades versions < 0.1.98 (`JP_UPGRADABLE_FOUND`) and detects downgrades. The version came from `GITHUB_RUN_NUMBER=98`, set only for this build, because a plain local build is 0.1.1, which is below the installed 0.1.97 |
| Local app image payload | libmpv SHA-256 `04e4d27d…` matches `NATIVE-PROVENANCE.json`. 16/16 legal files are present and non-empty. JVM inventory has 46 rows with notices. 63/63 motion payload hashes match. **Fails the CI rule `localPluginOverride`.** |
| Installed 0.1.97 (CI build), read-only | Passes every CI payload check: 125/125 experimental mpv files match `MPV-PREVIEW-MANIFEST.json` and the pinned mpv e471496c56; mpv-preview notices are present; 16/16 legal files; 63/63 motion hashes; no local override |

The check ran with `verify-payload.ps1` in the session scratchpad. It mirrors the two CI verification steps and is
read-only.

The installed motion manifest records patch SHA-256 `66d5c97e…`. The committed patch hashes to `12b0174e…`. CI
probably reads the patch after a CRLF checkout. This is not proven; confirm it before release.

## Installer and upgrade

| Check | Result |
|---|---|
| Fresh install on a clean PC | **Not performed** (no isolated environment) |
| In-place upgrade 0.1.97 → 0.1.98 (owner-approved, owner's PC) | **PASS.** `msiexec /i … /qb /l*v` exited 0 in 16 s: "Installation completed successfully". `JP_UPGRADABLE_FOUND` found `{66FE22FC-…}` (0.1.97) and RemoveExistingProducts removed it. Exactly one uninstall entry remains: SkyWing 0.1.98 `{7ED7DEBA-0DE5-3D58-B502-87500293665E}`. The installed jar reports `0.1.98` |
| Installed files | 539 files. They match the built app image except four version-dependent ones (launcher, `SkyWing.cfg`, the main jar and the skiko jar names). No 0.1.97 file remains: the 125 experimental mpv DLLs were removed |
| Settings preserved | **PASS.** The export of `HKCU\Software\JavaSoft\Prefs\com\skywing` is byte-identical before and after. All 5,149 files under `%LOCALAPPDATA%\SkyWing` are unchanged in path, size and timestamp |
| Start menu shortcut | Present: `ProgramData\…\Programs\SkyWing\SkyWing.lnk`. No desktop shortcut existed before or after |
| Open with associations | All 10 extensions (mp4 m4v mkv webm mov avi wmv ts m2ts hevc) map to `"C:\Program Files\SkyWing\SkyWing.exe" "%1"` |
| Installed payload | libmpv `04e4d27d…` matches provenance, 16/16 legal files present, 63/63 motion hashes match. CI's `localPluginOverride` rule fails, as expected for a local build |
| Native tests against the installed payload | 23/23 pass: EmbeddedPlaybackNative 7, MotionNative 1, MotionEngine 7, Smoothing 8. open-svpflow ran on the RTX 5070 Ti. Native memory per session: 598, 248, 230, 233 MB (about half of the experimental mpv build's) |
| First launch of the installed app with your real library | Not done by Claude, to leave your library and SMB data untouched. On 2026-11-22 the owner reported opening and testing 0.1.98 and that it works (owner-reported, not verified by Claude) |
| Uninstall | **Not performed** |
| Rollback | No 0.1.97 MSI exists on this PC, and the MSI blocks downgrades. The 0.1.97 files (859 files, 504,915,669 B) are copied to the session scratchpad as `upgrade\SkyWing-0.1.97-files`. A proper rollback means uninstalling 0.1.98 and installing the 0.1.97 CI artifact |

**Behaviour change for the owner:** the installed app now uses the stable pinned libmpv and locally built motion
plugins instead of the CI experimental frame-sharing mpv build. Installing the next CI build restores the CI payload.

## Hardware-level verification (this PC)

- **Hardware:**
  - The only active display is the built-in panel, 2560×1600 at 240 Hz, driven by Intel Graphics (driver 32.0.101.9175).
  - The machine also has an NVIDIA GeForce RTX 5070 Ti Laptop GPU (driver 32.0.16.2124).
  - Two virtual display drivers are installed: SudoMaker and Sunshine.
- **Measured or observed:**
  - Native libmpv playback, track switching, subtitles, seeking, fullscreen geometry, shutdown and exit, all through
    the packaged image.
  - The motion engine started and reported "Holding N fps" (MotionAutomationTest).
  - open-svpflow reported "GPU renderer active on NVIDIA GeForce RTX 5070 Ti Laptop GPU".
- **GPU selection:** the motion worker runs on NVIDIA. The player picks its D3D11 adapter from the monitor's adapter,
  which is Intel for this panel. Whether that split is the best choice for a hybrid laptop was not measured.
- **Not verified:**
  - **HDR** output and tone mapping.
  - **VRR** activation.
  - **Real displayed frame pacing.** "Holding N fps" is the configured output, not presented frames.
  - Hardware-decode efficiency under load.
  - Multi-monitor: only one display is connected.
  - Real Alt+Tab: it needs global input, which the test rules exclude.

## Performance and stability

| Measurement | Result |
|---|---|
| Packaged app, 12 rounds of open, play, seek, back, switch pages | Private bytes 717 → 746 MB; growth from round 4 to 12 was 17 MB (4 MB in the earlier focused run). Bounded, no leak trend |
| Native player, 40 switches and 4 restarts | 663, 492, 476, 422 MB per session; growth from session 2 to 4 was −69 MB |
| Crashes, hangs, leftover processes | None in 2 full runs plus focused runs; every launch exited gracefully after WM_CLOSE |
| Corrupt and missing files | No hang, no crash; the next video plays |

## Bugs

- **Fixed this stage:** corrupt-file silent failure; missing-file silent failure.
- **Fixed in Stage 4, still covered:** a new video opened paused after a pause.
- **Outstanding:** no functional defects were found in the tested scope.
- **Open release items:** the licensing gate, signing, the clean install/upgrade/uninstall test, the CI-built release
  candidate, the motion patch hash difference, and the hardware items above.

## Remaining risks and untested scenarios

- Unsigned installer; MSI install, upgrade and uninstall untested; settings preservation across an upgrade untested.
- SMB: connection loss during playback, reconnection, credentials, slow links and a real NAS. A host that resolves but
  is slow could still block the UI while Windows waits (not measured).
- 4K and HEVC playback, HDR, Dolby Vision, VRR, real smoothness and judder, multi-monitor, real Alt+Tab with other
  apps, mouse-hover reveal.
- The motion options sliders cannot be driven over JAB; profiles are tested through preferences instead.
- The local build differs from CI in its native payload. Re-run the full suite against the CI artifact before release.

## Suggested next steps (need your decision)

1. Get the CI artifact for this working tree once it is committed and pushed (your call). Install it on a spare PC or
   VM for fresh install, upgrade from 0.1.97, and uninstall. Or approve an in-place upgrade on this PC.
2. Complete the `SOURCE-STATUS.md` items and code signing before any public release.
3. Do a manual HDR, VRR and multi-monitor check on suitable displays.
