#!/usr/bin/env bash
set -euo pipefail
mkdir -p preview/skywing-visual
cleanup() { adb shell wm size reset; adb shell wm density reset; }
trap cleanup EXIT
for device in phone tablet; do
  if [ "$device" = phone ]; then
    adb shell wm size 1080x2400
    adb shell wm density 420
  else
    adb shell wm size 1600x2560
    adb shell wm density 240
  fi
  adb shell am instrument -w -e class com.deepak.videolibrary.SkyWingVisualTest -e skywingVisual true -e visualDevice "$device" com.deepak.videolibrary.preview.test/androidx.test.runner.AndroidJUnitRunner | tee "preview/skywing-visual/$device-tests.txt"
  grep -Eq '^OK \([0-9]+ tests?\)' "preview/skywing-visual/$device-tests.txt"
done
adb pull /sdcard/Android/data/com.deepak.videolibrary.preview/files/skywing-visual/. preview/skywing-visual/
