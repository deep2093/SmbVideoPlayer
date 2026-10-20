#!/usr/bin/env bash
# Runs the emulator checks in order. On the first failure, copies the tail of its output into a GitHub
# annotation so the reason is readable without downloading the job log.
step() {
  local name="$1"; shift
  local out; out=$(mktemp)
  if "$@" >"$out" 2>&1; then
    cat "$out"
  else
    cat "$out"
    echo "::error title=Device check failed: ${name}::$(tail -c 1500 "$out" | tr '\n\r%' '   ')"
    exit 1
  fi
}
step legacy-upgrade python3 tools/check_legacy_upgrade.py
step preview-update bash tools/check_preview_update.sh
step install-tests adb install -r preview/tests/app-debug-androidTest.apk
adb shell am instrument -w -e smbFixture true com.deepak.videolibrary.preview.test/androidx.test.runner.AndroidJUnitRunner | tee preview/storage-tests.txt
if ! grep -Eq '^OK \([0-9]+ tests?\)' preview/storage-tests.txt; then
  echo "::error title=Instrumented tests failed::$(tail -c 1500 preview/storage-tests.txt | tr '\n\r%' '   ')"
  exit 1
fi
step library-ui python3 tools/check_library_ui.py
step optimized-smoke python3 tools/check_optimized_smoke.py
