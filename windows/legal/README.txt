SkyWing Windows - Open-source licences and notices
=================================================

SkyWing's Windows application code is GNU GPL version 3 or later.
Read SkyWing-Windows-GPL-3.0.txt for the complete licence and warranty disclaimer.
Third-party components retain their own licences; they are not relicensed here.

Contents
--------
licenses/                     Full GPL, LGPL and Apache licence texts
upstream/                     Preserved mpv, FFmpeg, Skiko and Skia notices
jvm/                          Licence/notice files extracted from shipped JARs
JVM-RUNTIME-INVENTORY.tsv     Exact resolved runtime coordinates and SHA-256 hashes
java-runtime/                 Notices from the JDK used to create the runtime
SKYWING-SOURCE.txt             Source revision and location for this SkyWing build
NATIVE-PROVENANCE.json        Exact pinned libmpv archive and source provenance
SOURCE-STATUS.md              Remaining dependency-source and notice audit work

Credits
-------
Kotlin, Kotlin coroutines and Compose Multiplatform: JetBrains and contributors.
Compose/AndroidX includes work by the Android Open Source Project and contributors.
Skiko: JetBrains and contributors; includes adapted Android Open Source Project code.
Skia: Google and contributors; Skia and its bundled third-party components retain
their respective licences. See upstream/Skia-LICENSE.txt.
JNA: JNA contributors; its embedded licence is preserved under jvm/.
mpv: mpv, MPlayer and mplayer2 contributors; see upstream/mpv-Copyright.txt.
FFmpeg: FFmpeg contributors; see upstream/FFmpeg-LICENSE.md. This software uses
code of FFmpeg licensed under the GPLv3 (this build enables GPL and version 3).
This software is based in part on the work of the Independent JPEG Group.
No SkyWing modifications are made to the pinned upstream native binary.

The current libmpv is a GPL-enabled binary. It includes statically linked native
dependencies with their own notices and source obligations. This directory is a
notice collection and audit record, not certification that those obligations
have all been satisfied. See SOURCE-STATUS.md before redistributing.

Upstream projects
-----------------
mpv: https://github.com/mpv-player/mpv
FFmpeg: https://github.com/FFmpeg/FFmpeg
libmpv build: https://github.com/shinchiro/mpv-winbuild-cmake
Kotlin: https://github.com/JetBrains/kotlin
Kotlin coroutines: https://github.com/Kotlin/kotlinx.coroutines
Compose: https://github.com/JetBrains/compose-multiplatform
AndroidX: https://android.googlesource.com/platform/frameworks/support/
Skiko: https://github.com/JetBrains/skiko
Skia: https://skia.googlesource.com/skia/
JNA: https://github.com/java-native-access/jna
Java runtime: see java-runtime/BUILD-RELEASE.txt and its module notices.
