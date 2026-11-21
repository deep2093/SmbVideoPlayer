#!/usr/bin/env bash
# Re-signs preview APKs with the private preview key and the rotation record (lineage), so a phone that has the old,
# public preview key installed accepts the update, keeps its data, and from then on refuses APKs signed with the old key.
# Needs PREVIEW_V2_KEYSTORE (base64 of the PKCS12 file) and PREVIEW_V2_PASSWORD. See .ci/KEY-ROTATION.md.
set -euo pipefail
: "${PREVIEW_V2_KEYSTORE:?missing}" "${PREVIEW_V2_PASSWORD:?missing}"
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$PREVIEW_V2_KEYSTORE" | base64 -d > "$work/new.p12"
# The rotation record (lineage) says "the new key replaces the old one", signed by the old key. It holds no private key.
# It is made here from the old key in this repository and the new key from the secret, so nothing has to be committed.
# The old key may hand over installed data and permissions, but may never be used to roll back.
lineage="$work/preview-lineage.bin"
"$signer" rotate --out "$lineage" \
  --old-signer --ks .ci/preview-signing.p12 --ks-type PKCS12 --ks-pass pass:android \
    --set-installed-data true --set-shared-uid true --set-permission true --set-rollback false --set-auth true \
  --new-signer --ks "$work/new.p12" --ks-type PKCS12 --ks-pass env:PREVIEW_V2_PASSWORD
for apk in "$@"; do
  "$signer" sign --ks "$work/new.p12" --ks-type PKCS12 --ks-pass env:PREVIEW_V2_PASSWORD \
    --lineage "$lineage" --out "$work/signed.apk" "$apk"
  "$signer" verify --verbose --print-certs "$work/signed.apk" > "$work/verify.txt"
  grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' "$work/verify.txt"
  mv "$work/signed.apk" "$apk"
  echo "Re-signed $apk with the private preview key"
done
"$signer" lineage --in "$lineage" --print-certs
