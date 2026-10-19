#!/usr/bin/env bash
set -euo pipefail
app=com.deepak.videolibrary.preview
adb install preview/base.apk
adb shell am start -n "$app/com.deepak.videolibrary.MainActivity"
# Wait for the real Room library to open; no SMB account is required for this test.
for attempt in $(seq 1 30); do
  if adb shell "run-as $app sh -c 'test -f databases/library.db'"; then break; fi
  sleep 1
done
adb shell am force-stop "$app"
adb shell "run-as $app sh -c 'test -f databases/library.db'"
adb shell "run-as $app sh -c 'mkdir -p files/thumbs shared_prefs; echo keep-thumbnail > files/thumbs/update-sentinel.jpg; echo keep-settings > shared_prefs/update-sentinel.xml'"
before=$(adb shell "run-as $app sh -c 'sha256sum databases/library.db files/thumbs/update-sentinel.jpg shared_prefs/update-sentinel.xml'" | tr -d '\r')
old_version=$(adb shell dumpsys package "$app" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1 | tr -d '\r')
adb install -r preview/update.apk
new_version=$(adb shell dumpsys package "$app" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -1 | tr -d '\r')
test "$new_version" -gt "$old_version"
after=$(adb shell "run-as $app sh -c 'sha256sum databases/library.db files/thumbs/update-sentinel.jpg shared_prefs/update-sentinel.xml'" | tr -d '\r')
test "$before" = "$after"
echo "PASS: version $old_version -> $new_version retains database, thumbnail and preference data"
adb shell am start -n "$app/com.deepak.videolibrary.MainActivity"
sleep 3
adb exec-out screencap -p > preview/device-home.png
# Open Originals (rightmost bottom navigation item) on the phone emulator.
adb shell input tap 1000 1725
sleep 2
adb exec-out screencap -p > preview/device-originals.png
