#!/usr/bin/env bash
# Re-signs preview APKs with the private preview key and the rotation record (lineage), so a phone that has the old,
# public preview key installed accepts the update, keeps its data, and from then on refuses APKs signed with the old key.
# Needs PREVIEW_V2_KEYSTORE (base64 of the PKCS12 file) and PREVIEW_V2_PASSWORD. See .ci/KEY-ROTATION.md.
set -euo pipefail
: "${PREVIEW_V2_KEYSTORE:?missing}" "${PREVIEW_V2_PASSWORD:?missing}"
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
lineage=.ci/preview-lineage.bin
if [ ! -s "$lineage" ]; then
  echo "::error::$lineage is missing. Run the 'Create preview key lineage' workflow and commit its output (see .ci/KEY-ROTATION.md)."
  exit 1
fi
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
printf '%s' "$PREVIEW_V2_KEYSTORE" | base64 -d > "$work/new.p12"
for apk in "$@"; do
  "$signer" sign --ks "$work/new.p12" --ks-type PKCS12 --ks-pass env:PREVIEW_V2_PASSWORD \
    --lineage "$lineage" --out "$work/signed.apk" "$apk"
  "$signer" verify --verbose --print-certs "$work/signed.apk" > "$work/verify.txt"
  grep -q 'Verified using v3 scheme (APK Signature Scheme v3): true' "$work/verify.txt"
  mv "$work/signed.apk" "$apk"
  echo "Re-signed $apk with the private preview key"
done
"$signer" lineage --in "$lineage" --print-certs
