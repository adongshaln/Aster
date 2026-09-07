#!/usr/bin/env bash
set -uo pipefail

adb shell wm size 1080x2400
adb shell wm density 480
adb shell settings put secure show_ime_with_hard_keyboard 1
if [[ -n "${1:-}" ]]; then
  ./gradlew --no-daemon connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$1"
else
  ./gradlew --no-daemon connectedDebugAndroidTest
fi
test_result=$?
mkdir -p ui-preview
adb pull /sdcard/Android/data/com.adong.adchat/files/ui-preview ui-preview || true
exit "$test_result"
