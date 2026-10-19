# Preview build identity

`preview-signing.p12` is a dedicated, intentionally shared DEVELOPMENT signing key.
The single private-key alias is resolved from the store; store/key password `android`, type PKCS12. It is not a production
credential and must never be used for release builds or Play Store publishing.
Do not regenerate or replace this file: future preview updates need the same key.

Debug builds use package `com.deepak.videolibrary.preview`, labelled Video Library
Preview. The old `com.deepak.videolibrary` app remains installed separately because
its CI-generated signing key was never saved. Android cannot update that install
using a different key. The preview needs a one-time SMB setup, scan and thumbnail
creation; subsequent preview APKs update in place and retain their private data.

CI assigns monotonically increasing version codes (1000 + run number * 2), builds
a second APK at the next code, verifies both signing identities, then checks with
an emulator that installing the second APK using `adb install -r` preserves the
thumbnail file, database and saved preference test data. The delivered APK is the
second, higher-version build. The default upgraded library keeps its database and artwork paths. Room migrations preserve its data; additional connection/account/root combinations use isolated databases and artwork directories.
