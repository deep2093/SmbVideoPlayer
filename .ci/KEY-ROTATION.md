# Moving preview builds to a private signing key

## Why

`preview-signing.p12` (password `android`) is in this public repository. Anyone can sign an APK with it, and Android
would install that APK as an update over SkyWing Preview, with full access to its data: the saved SMB password, the
WireGuard private key and the provider API keys (they are encrypted, but with keys the app itself can use).

## How the move works

Android 9 and newer support signing-key rotation (APK Signature Scheme v3). A small *lineage* file, signed by the old
key, says "the new key replaces me". An APK signed with the new key plus that lineage installs over the old-key app
and keeps all its data. After that, the phone refuses APKs signed only with the old key (the old key is not allowed
to roll back). SkyWing needs Android 10 or newer, so every install supports this.

Until the new key is configured, nothing changes: CI keeps signing with the old key.

## Steps (once)

1. **Make the new key on your PC.** `keytool` comes with the JDK (it is in `bin` of your Java folder).
   Choose a long password and keep it in your password manager.

   ```powershell
   keytool -genkeypair -v -keystore skywing-preview-v2.p12 -storetype PKCS12 -alias skywing-preview `
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=SkyWing Preview"
   ```

2. **Back it up.** Copy `skywing-preview-v2.p12` somewhere safe offline (and the password). If it is lost, a later
   update would need SkyWing to be uninstalled first. Never commit it.

3. **Add two repository secrets** on GitHub: the repository > Settings > Secrets and variables > Actions >
   New repository secret.
   - `PREVIEW_V2_KEYSTORE`: the file as base64. In PowerShell this copies it to the clipboard:
     ```powershell
     [Convert]::ToBase64String([IO.File]::ReadAllBytes("skywing-preview-v2.p12")) | Set-Clipboard
     ```
   - `PREVIEW_V2_PASSWORD`: the password from step 1.

4. **Build as usual** (or ask Claude to start a build). CI makes the rotation record from the old key in this
   repository and the new key from the secret, re-signs `SkyWing-arm64.apk` with the new key, and the emulator update
   test installs it over the old-key build to prove that data is kept. Nothing has to be committed.

5. **Install `SkyWing-arm64.apk` over your current SkyWing Preview** on each device. Everything stays as it was.

## After the move

- Only install `SkyWing-arm64.apk` (or the optimized APK). The debug test APKs are still signed with the old key and
  will no longer install over the moved app; that is expected.
- Consider making the repository private. It also shows your home network address in code comments and examples.
- Later, the debug builds can be moved to the new key as well, and the old key deleted from the repository.
