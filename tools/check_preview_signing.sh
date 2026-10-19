#!/usr/bin/env bash
set -euo pipefail
signer="$ANDROID_HOME/build-tools/35.0.0/apksigner"
"$signer" verify --verbose --print-certs preview/base.apk > preview/base-signing.txt
"$signer" verify --verbose --print-certs preview/update.apk > preview/update-signing.txt
grep 'Signer #1 certificate SHA-256 digest:' preview/base-signing.txt > preview/base-cert.txt
grep 'Signer #1 certificate SHA-256 digest:' preview/update-signing.txt > preview/update-cert.txt
diff -u preview/base-cert.txt preview/update-cert.txt
keytool -list -keystore .ci/preview-signing.p12 -storetype PKCS12 -storepass android
cat preview/update-cert.txt
