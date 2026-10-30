#!/usr/bin/env bash
set -euo pipefail
OUTPUT="${1:-/tmp/thinkos-shot.png}"
adb -s "${ANDROID_SERIAL:-HUA0BWUT}" exec-out screencap -p > "$OUTPUT"
printf '%s\n' "$OUTPUT"
