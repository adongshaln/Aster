#!/usr/bin/env bash
set -uo pipefail

# This hotfix exercises the native ICU engine and the affected HTML lifecycle.
# Keep unrelated historical settings/keyboard scenarios out of this regression run.
if [[ $# -eq 0 && "${GITHUB_REF:-}" == "refs/heads/design/regex-crash-hotfix" ]]; then
  set -- 'com.adong.adchat.SharedUiInteractionTest#builtinRegexProseRendersAndSurvivesUpdates,com.adong.adchat.SharedUiInteractionTest#tavernMacrosCompileAndExpandOnAndroid,com.adong.adchat.SharedUiInteractionTest#htmlPreviewWaitsForCompletionRunsOfflineAndOpensFullscreen'
fi

if [[ $# -eq 0 && ( "${GITHUB_REF:-}" == "refs/heads/feature/story-mode" || "${GITHUB_REF:-}" == "refs/heads/feature/skills-runtime" || "${GITHUB_REF:-}" == "refs/heads/feature/tavern-presets" ) ]]; then
  set -- com.adong.adchat.SharedUiInteractionTest
fi

# The emulator launcher can ANR during cold boot and steal focus from IME tests.
# Stop only the launcher; keep application errors visible and test failures fatal.
adb shell am force-stop com.google.android.apps.nexuslauncher
adb shell wm size 1080x2400
adb shell wm density 480
adb shell settings put secure show_ime_with_hard_keyboard 1
if [[ -n "${1:-}" ]]; then
  ./gradlew --no-daemon connectedDebugAndroidTest "-Pandroid.testInstrumentationRunnerArguments.class=$1"
else
  ./gradlew --no-daemon connectedDebugAndroidTest
fi
test_result=$?
if [[ $test_result -ne 0 ]]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
for report in Path('app/build/outputs/androidTest-results').rglob('*.xml'):
    try:
        for failure in ET.parse(report).getroot().iter('failure'):
            print(failure.get('message', ''), failure.text or '')
    except ET.ParseError:
        pass
PY
fi
mkdir -p ui-preview
adb pull /sdcard/Android/data/com.adong.adchat/files/ui-preview ui-preview || true
adb pull /sdcard/Download/aster-ui-preview ui-preview || true
exit "$test_result"
