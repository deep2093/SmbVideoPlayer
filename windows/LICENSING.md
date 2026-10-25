# SkyWing Windows licensing

The SkyWing-owned Windows application sources in `windows/` are licensed under
GNU GPL version 3 or, at your option, any later version (GPL-3.0-or-later).
See [LICENSE](LICENSE) for the full terms.

This Windows-only grant does not relicense Android `app/`, the repository's other
modules, upstream licence texts, third-party binaries or other authors' work.
Those components keep their original terms. Existing third-party notices must be
preserved. No new copyright ownership is claimed for upstream work.

SkyWing Windows is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY, including MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
See the GPL for details.

## Current preview dependencies

The pinned libmpv archive is the upstream GPL-enabled build, not an LGPL-only
binary. Its builder enables FFmpeg's `--enable-gpl` and `--enable-version3`.
The exact archive, DLL hashes and source revisions are in
[legal/NATIVE-PROVENANCE.json](legal/NATIVE-PROVENANCE.json).
Do not label this binary LGPL-only.

`generateWindowsLegal` copies the licence bundle, every licence/notice file found
in resolved runtime JARs, exact artifact coordinates and SHA-256 hashes, plus the
build JDK's legal directory, into the installed application resources. A zero
notice-file count in the inventory means the JAR omitted them; it is not a licence
exemption or completed review.

The source ZIP accompanying CI contains SkyWing's Windows sources/build scripts.
It is not the complete corresponding source of the native dependencies.
[legal/SOURCE-STATUS.md](legal/SOURCE-STATUS.md) records the outstanding release
requirements. Adding the GPL text does not by itself complete the audit.

## Maintainer release gate

Do not mark a Windows release production-ready or licensing-complete until the
exact corresponding dependency sources, patches/build scripts and component
notices have been assembled and verified. Upstream project homepages and a
nightly binary URL are provenance, not substitutes for that source distribution.

Signing is separate: these notices do not establish a trusted publisher identity
or remove Windows SmartScreen/Smart App Control warnings.
