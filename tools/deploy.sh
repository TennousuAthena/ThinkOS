#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 21)"
fi
if [[ "${1:-}" == "--no-build" ]]; then shift; else
    (cd "$ROOT/android" && ./gradlew assembleDebug)
fi
SERIAL="${ANDROID_SERIAL:-HUA0BWUT}"
adb -s "$SERIAL" install -r "$ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
adb -s "$SERIAL" shell cmd package set-home-activity moe.kiwi.thinkos/.MainActivity
adb -s "$SERIAL" shell am start -W -n moe.kiwi.thinkos/.MainActivity
