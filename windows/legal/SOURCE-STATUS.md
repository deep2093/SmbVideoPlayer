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
- SkyWing does not modify the downloaded native binary.
- CI's Vulkan loader is test-only. It is not copied into SkyWing's app resources
  or Windows installer; do not list it as an installed SkyWing dependency.
- The JVM inventory/extracted notices and build JDK legal directory are generated
  from the actual packaging inputs, rather than copied from Android.

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
   Preserve any additional upstream copyright/NOTICE requirements.
5. Verify the packaged Java runtime and its complete matching source availability
   for the exact vendor/build recorded in java-runtime/BUILD-RELEASE.txt.
6. Ship Windows application source for the same build revision (CI creates a
   source ZIP), retain modifications and third-party notices, and check the final
   distribution against all applicable terms.

No written source offer is made here. Do not promise to supply material the
project has not actually acquired. Do not silently check these items off after
only a green compilation or installer build.

## Practical completion path

Obtain the exact source bundle from the current binary producer, or build a new
GPL libmpv from fully pinned sources in a controlled pipeline that retains all
sources, patches, build recipes and notices. The latter changes the native binary
and requires the normal playback/fullscreen/clean-install regression tests.
