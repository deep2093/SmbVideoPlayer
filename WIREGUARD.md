# WireGuard VPN support

Optional remote access to the SMB server through the **official WireGuard Android tunnel library**
(`com.wireguard.android:tunnel`, `GoBackend`). No WireGuard protocol or cryptography is implemented in this app.

> **Verification status.** This change was written and read-reviewed (twice, once by an independent reviewer)
> against the upstream WireGuard source, but it has **not been compiled, installed or run**. See [Verification status](#verification-status).

## What it does

- **Settings > VPN** page: import a `.conf` with the system file picker, replace or remove it, see the status,
  Connect / Disconnect, an **Auto Connect VPN** switch, and a **Use VPN for this app only** switch.
- **Auto Connect** (off by default). When the app opens it checks whether the server (Settings > Connection >
  server address) answers on TCP 445 *directly* on a physical network. If it does, no VPN is started.
  If it does not, and there is a network, the tunnel is brought up *before* the library loads (up to 25 s,
  then the library opens anyway). No SSID matching is used.
- **No reconnect storms.** Network changes are debounced (3 s). Automatic attempts back off 15 s, 30 s, ... up to 5 min
  after failures. Nothing is attempted while offline. After you press Disconnect, nothing reconnects automatically
  until you press Connect or the app process restarts.
- **Playback safety.** Both players hold the tunnel open while a video screen is open. The VPN is never taken down
  automatically during playback. A manual Disconnect during playback asks first (*Disconnect now* or *After playback*).
  If the tunnel was started automatically and you return to the home network, it is turned off after playback ends.
- **App-only routing.** The app writes `IncludedApplications = <this app's package>` into the `[Interface]` section
  in memory when connecting. The official `GoBackend` turns that into `VpnService.Builder.addAllowedApplication()`,
  so only this app's traffic enters the tunnel and other apps keep their normal connection. The stored file is not
  modified. The switch can be turned off to follow your file exactly.

## Files

| New | Purpose |
|---|---|
| `vpn/WireGuardManager.kt` | Tunnel lifecycle, auto-connect policy, back-off, playback holds, network monitoring |
| `vpn/WireGuardConfigStore.kt` | Keystore-encrypted config file in `noBackupFilesDir`, plus non-secret preferences |
| `vpn/SmbReachability.kt` | Direct vs. through-tunnel TCP 445 probes (sockets bound to each physical network) |
| `vpn/ConfigText.kt` | Pure text helper for the app-only rewrite (unit-tested) |
| `ui/VpnSettings.kt` | The Settings page |
| `res/xml/data_extraction_rules.xml` | Excludes all app data from cloud backup and device transfer |
| `src/test/.../ConfigTextTest.kt` | Unit tests for the rewrite |

| Modified | Change |
|---|---|
| `app/build.gradle.kts` | `implementation("com.wireguard.android:tunnel:1.0.20260102")` |
| `AndroidManifest.xml` | `android:dataExtractionRules` only |
| `MainActivity.kt` | Consent launcher, network monitoring start/stop, launch-time auto-connect (failures are swallowed so they can never block the library from opening) |
| `PlayerScreen.kt`, `VlcPlayerScreen.kt` | Playback hold (`DisposableEffect`, 4 lines each) |
| `SettingsScreen.kt` | "VPN" entry and section |
| `preview-rules.pro` | `-keep class com.wireguard.** { *; }` for R8 builds |
| `THIRD-PARTY-NOTICES.md` | WireGuard attribution |

`SmbConnection` and all SMB, scan, thumbnail and playback code are untouched: they use ordinary sockets, which follow
the app's default route and therefore use the tunnel automatically when it is up.

## Android permissions

**No new `<uses-permission>` entries.** `INTERNET` and `ACCESS_NETWORK_STATE` were already declared.
The library's own manifest (merged at build time) declares `GoBackend$VpnService` protected by
`android.permission.BIND_VPN_SERVICE`, which only the system can hold. At runtime Android shows its standard one-time
**Connection request** dialog the first time the app creates a VPN.

## Security notes

- The `.conf` (it holds a private key) is stored as AES-256-GCM ciphertext under a non-exportable Android Keystore key,
  in `noBackupFilesDir`, and excluded from backup and device transfer. Removing the configuration deletes the file and the key.
- The plain text exists only in memory while a tunnel is being built. Nothing logs configuration text, keys, endpoints
  or exception messages; error messages are fixed strings built from enum names.
- Import is capped at 64 KiB and validated with the official parser before anything is stored.
- No keys, passwords or addresses are hard-coded. The test file uses obvious placeholders.

## Setting it up

1. In the official WireGuard app (or wherever you made it), export your working tunnel as a `.conf` file and put it
   somewhere the file picker can reach (Downloads, Drive, ...). Treat that file like a password.
2. In this app: **Settings > Connection** should already hold `192.168.71.250` and your share.
3. **Settings > VPN > Import .conf**, pick the file. Accept Android's VPN connection request when you first Connect.
4. Make sure the peer's `AllowedIPs` includes `192.168.71.250` (for example `192.168.71.0/24`).
5. Turn on **Auto Connect VPN** if you want it.

Notes:
- Android allows **one active VPN at a time**. If the official WireGuard app (or another VPN) is connected,
  connecting here replaces it, and vice versa. Disconnect the other one first.
- The library's parser rejects keys it does not know (for example `PreUp`, `PostUp`, `Table`, `SaveConfig`),
  exactly as the official app does. A file that works in the official app imports here.

## Build and test

Requires JDK 17, Android SDK platform 36 and network access to Maven Central, Google Maven and Gradle.

```
./gradlew :app:testDebugUnitTest --tests '*ConfigTextTest'
./gradlew assembleDebug
```

The debug build installs as `com.deepak.videolibrary.preview`; the app-only list uses the real package name at runtime.

### Test plan (all still to do on a device)

| # | Test | Expected |
|---|---|---|
| 1 | Import your `.conf` | "Configuration imported", status Disconnected |
| 2 | Connect on mobile data | Consent dialog once; status Connected; "server answers through the tunnel" |
| 3 | Browse and play a video on mobile data | Works through the tunnel |
| 4 | With the tunnel up, open a browser or another app | Normal network (not tunnelled); check your public IP |
| 5 | Auto Connect on, home Wi-Fi, open app | No VPN started |
| 6 | Auto Connect on, away from home, open app | VPN starts before the library loads |
| 7 | Auto Connect on, airplane mode, open app | No repeated attempts; library opens, shows its normal SMB error |
| 8 | Start playback away from home, switch Wi-Fi to mobile | Playback recovers; VPN stays up |
| 9 | Press Disconnect during playback | Asks first; "After playback" disconnects when you leave the player |
| 10 | Press Disconnect, background and reopen app | VPN stays off until you press Connect |
| 11 | Start the official WireGuard app's tunnel while this one is up | This one goes to Disconnected |
| 12 | Revoke the VPN permission in Android settings | Status shows needs-permission; no retry loop |
| 13 | Replace, then remove the configuration | Old one gone; Keystore key deleted |
| 14 | Turn app-only off, connect | Other apps are tunnelled per `AllowedIPs` |

## Verification status

| Item | Status |
|---|---|
| WireGuard API usage (`GoBackend`, `Tunnel`, `Config`, `Interface`, `BackendException`) | Read against upstream source at `WireGuard/wireguard-android` `main` (version 1.0.20260315) |
| Dependency `1.0.20260102` exists and resolves | **Not verified** (Maven Central unreachable from the authoring environment) |
| Compilation / `./gradlew assembleDebug` | **Not run.** The authoring environment could not download Gradle, the Android SDK or dependencies |
| Unit tests | **Not run** for the same reason |
| Lint, R8 release build | Not run |
| VPN connection, app-only routing, SMB streaming | **Not tested** (no device, no server) |

Please treat it as an unbuilt change: expect to fix small compile errors on first build.

## Known limitations

- **Same-subnet look-alike.** The "directly reachable" test is a TCP 445 probe of `192.168.71.250`. On someone else's network that happens to use `192.168.71.x`, a different device could answer and the VPN would be skipped. Using a less common home subnet avoids this.
- **Background work.** Library scans, thumbnails and file transfers run in WorkManager and use the tunnel only if it is already up; they do not start it.
- **Process-bound.** The tunnel lives in the app's process. If Android kills the app (for example while it sits in the background), the tunnel goes with it and the next launch reconnects if Auto Connect is on.
- **One VPN at a time** (Android limitation), as above.
- **Launch delay.** With Auto Connect on and the server unreachable, opening the library can wait up to 25 s for the tunnel. With Auto Connect off there is no delay.
- **Health check is port based.** "Connected" in the UI means the tunnel came up; the server check (TCP 445 through the tunnel) is reported separately and does not tear the tunnel down by itself, except after a network change when it fails twice, at most once a minute.
- **Background restarts.** Android 8+ can refuse `startService` when the app is in the background, so a reconnect triggered by a network change while the app is not visible may fail with a generic VPN error. The next app launch retries.
- IPv6 literal server addresses (and non-445 ports) are not handled by the reachability probe; use an IPv4 address or hostname.
- Only one WireGuard configuration is stored.
