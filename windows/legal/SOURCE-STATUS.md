# Windows source-distribution audit

**Status: INCOMPLETE.** The owner has selected GPL-3.0-or-later for the Windows
application. That decision and this notice bundle do not finish the native
dependency audit. Android is outside this change.

## Verified evidence

- The application downloads the pinned 20261021 x86_64 libmpv development archive.
  Its exact archive and extracted DLL SHA-256 hashes are in NATIVE-PROVENANCE.json.
- mpv revision: e471496c56f0f49c133bd88b50aed62b845f1909.
- The upstream release also publishes FFmpeg revision 9a861e9687d30e657d59521817cad18405995983.
  This is evidence for the release's FFmpeg executable; verify the DLL's actual
  linked FFmpeg revision/configuration before treating it as an exact match.
- Builder revision: 1f8d2e76255abc48f0456d9be99b4cab9c6566e6.
- The builder's FFmpeg recipe enables GPL and version 3; its mpv recipe prefers
  static dependency linking. The selected DLL must not be described as LGPL-only.
- Upstream run 39518569492 restores cached dependency sources; its log does not
  enumerate all resolved dependency commits. Its published artifacts contain
  binaries/debug symbols, not a complete corresponding-source bundle.
- The default preview uses the downloaded native binary as supplied.
- CI's Vulkan loader is test-only. It is not copied into SkyWing's app resources
  or Windows installer; do not list it as an installed SkyWing dependency.
- The JVM inventory/extracted notices and build JDK legal directory are generated
  from the actual packaging inputs, rather than copied from Android.
- Verified 2026-11-22: the version strings embedded in the packaged libmpv-2.dll
  are FFmpeg `N-127893-g9a861e968` and `mpv v0.41.0-1227-ge471496c5`. Both match
  the revisions above, so the DLL's FFmpeg revision is now established
  (`dllEmbeddedVersions` in NATIVE-PROVENANCE.json). This proves the revision
  only, not the configuration or the other statically linked libraries.
- Verified 2026-11-22: the bundled Java runtime is Temurin `jdk-17.0.20.1+1`.
  Its SOURCE commit `79597447bd94ff9f8216e2e3a573d5261ee49f2e` matches the
  Adoptium tag `jdk-17.0.20.1+1_adopt`. Adoptium publishes the matching source
  archive `OpenJDK17U-jdk-sources_17.0.20.1_1.tar.gz`, SHA-256
  `21e2a065d244ab048e737f21af5d1fc74daaeb6707de36477ead8db1dca71214`
  (`javaRuntime` in NATIVE-PROVENANCE.json). CI now pins setup-java to
  `17.0.20+101`, checks the packaged runtime's IMPLEMENTOR_VERSION and archives
  that source tarball with the artifacts. CI run 98 used 17.0.21+9 because the
  version floated; the first run with the pin has not happened yet.
- Verified 2026-11-22: the JCEF natives jar nests a tar.gz. The legal bundle
  now extracts its LICENSE.txt (CEF, BSD), README.txt, gluegen.LICENSE.txt and
  jogl.LICENSE.txt. The archive has no Chromium third-party credits file; those
  credits exist only inside the CEF `.pak` resources.
- Verified 2026-11-22: the JVM inventory now records each artifact's
  POM-declared licence (following parent POMs). All 46 runtime artifacts
  declare one; 37 of them embed no licence or NOTICE file of their own.

## Required before a licensing-complete release

1. Assemble and publish the exact preferred source, build configuration, patches,
   dependencies and scripts needed to rebuild the selected native DLL. Preserve
   checksums and an independently usable archive beside the binary distribution.
   A homepage, floating branch or the builder revision alone is insufficient.
2. Enumerate all linked native components and applicable notices, including
   codec/render/font/crypto/compression libraries and compiler runtime code.
   Audit exact components from build evidence; do not assume every CMake target
   is linked or that listing the DLL's Windows imports inventories static code.
3. Obtain the complete Skia/Skiko native dependency notice set for the packaged
   Skia build, in addition to the top-level licences preserved here. Skiko 0.9.4.2
   declares Skia m132-a00c390e98-1.
4. Review runtime artifacts with no embedded notices. The generated inventory's
   count of zero flags missing evidence, not permission to omit attribution.
   The POM licence column identifies the licence but not its text, and an
   Apache-2.0 project may publish a NOTICE file outside its jar. Check each
   upstream source release for a NOTICE file and preserve it.
5. Java runtime: the source archive is identified and CI archives it (above).
   Still required: a green CI run with the pinned version, and the archive
   published beside the release binaries.
6. JCEF: ship the Chromium third-party credits for Chromium 146.0.7680.179.
   They are not in the Maven artifacts. Obtain them from the matching CEF/JCEF
   build output (or extract `about:credits` from the bundled `.pak`) and add them
   to the legal bundle.
7. Ship Windows application source for the same build revision (CI creates a
   source ZIP), retain modifications and third-party notices, and check the final
   distribution against all applicable terms.

No written source offer is made here. Do not promise to supply material the
project has not actually acquired. Do not silently check these items off after
only a green compilation or installer build.

## Practical completion path

Three ways to obtain corresponding source for libmpv were compared on 2026-11-22:

| Option | What it gives | Problems |
|---|---|---|
| A. Exact sources of the current shinchiro build | Keeps today's DLL unchanged | The builder publishes no source bundle. 62 of its 108 dependency recipes follow git master, and run 39518569492 restored cached sources without logging their commits. The exact inputs cannot be reconstructed from public evidence; only the producer could supply them. |
| B. Our own rebuild with every recipe pinned | Full control and a source archive we create | Pinning about 108 recipes and keeping them current is a large ongoing job. The DLL changes, so it needs the full playback regression. |
| C. MSYS2 UCRT64 packages | Each binary package version has a matching `.src.tar.zst` source package at `repo.msys2.org/mingw/sources/` (for example `mingw-w64-mpv-0.41.0-9`, `mingw-w64-libplacebo-7.360.1-2`), and the package database lists every dependency and its version | The DLL becomes a dynamic set of MSYS2 DLLs. The MSYS2 sources must be mirrored, because old packages are removed from the live repository. The DLL changes, so it needs the full playback regression. |

**Recommendation: option C.** It is the only route where every shipped binary
has a published, version-matched source package, and it is already in use.
The experimental frame-sharing preview builds libmpv this way
(`windows/tools/build_mpv_preview.sh`, `stage_mpv_preview.py`). The staging
script already records the owning package and version of every bundled DLL in
MPV-PREVIEW-MANIFEST.json. The remaining work:

1. For each package in that manifest, download its `.src.tar.zst` in CI, record
   its SHA-256 and archive it with the release. Fail the build if one is missing.
2. Archive the mpv source, the SkyWing patch and the meson options (done for the
   preview).
3. Make the MSYS2 build the default only after the playback regression passes:
   local and SMB, MP4/MKV, subtitle and audio switching, 1080p/4K, HDR
   passthrough, fullscreen, motion, and a clean install.

Option A remains possible only if the binary producer supplies the exact source
bundle. Until one route is complete, the default shinchiro DLL has no
corresponding-source archive, and a public GPL release is blocked.

## Experimental frame-sharing preview

Push builds on `feature/windows-motion-engine` and manual Windows CI runs with
`experimental_mpv` build mpv from the pinned revision with
`windows/tools/mpv-vapoursynth-output-preview.patch`. The separate
`MPV-PREVIEW-MANIFEST.json` records the actual bundled DLLs and MSYS2 package
versions. `windows/tools/mpv-preview-packages.json` pins those versions to the
ones validated in M0 (CI build 0.1.101). The build stops when any of them
changes, is added or goes missing, until the new set is validated and the pins
are updated. The adjacent package inventory and available notices come from the
installed UCRT64 packages. CI also archives the mpv source inputs. The installed
package inventory and source inputs do not establish complete corresponding
source for every linked MSYS2 dependency.

Frame sharing is enabled by default in this preview. Launching SkyWing with
`SKYWING_VS_SHARE_FRAMES=0` uses the original output copy in the same build.

## Motion preview dependencies

The motion payload records pinned open-svpflow source, its Windows patch, portable
VapourSynth R73 and Python 3.13.9 archive hashes in NATIVE-PROVENANCE.json.
The generated motion/BUILD-MANIFEST.json records every packaged input hash, and
motion/legal contains the native Rust dependency notices. Top-level engine,
VapourSynth and Python licences are also preserved under legal/upstream.

The complete corresponding-source and static-dependency audit remains open for
VapourSynth and its linked libraries/compiler runtimes as well as the pre-existing
libmpv/Skia dependencies. VapourSynth R73 upstream build scripts clone some
dependencies at floating heads; the release tag alone does not establish their
exact revisions. Do not label this preview a licensing-complete public release.
