#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SERIAL="${ANDROID_SERIAL:-HUA0BWUT}"
DURATION="${1:-600}"
OUTPUT="${2:-/tmp/thinkos-bench}"
[[ "$DURATION" =~ ^[0-9]+$ ]] || { printf 'Duration must be seconds\n' >&2; exit 2; }
mkdir -p "$OUTPUT"
APP_PID="$(adb -s "$SERIAL" shell pidof moe.kiwi.thinkos | tr -d '\r')"
[[ "$APP_PID" =~ ^[0-9]+$ ]] || { printf 'ThinkOS is not running\n' >&2; exit 1; }
printf '%s\n' "$APP_PID" > "$OUTPUT/pid-before.txt"
adb -s "$SERIAL" shell dumpsys gfxinfo moe.kiwi.thinkos reset > "$OUTPUT/gfx-before.txt"
printf 'Sampling %s seconds; keep the device idle.\n' "$DURATION"
python3 "$ROOT/tools/sample_cpu.py" --serial "$SERIAL" --pid "$APP_PID" --seconds "$DURATION" --output "$OUTPUT/cpu.json"
adb -s "$SERIAL" shell dumpsys gfxinfo moe.kiwi.thinkos > "$OUTPUT/gfx-after.txt"
adb -s "$SERIAL" shell dumpsys meminfo moe.kiwi.thinkos > "$OUTPUT/memory.txt"
adb -s "$SERIAL" shell pidof moe.kiwi.thinkos > "$OUTPUT/pid-after.txt"
adb -s "$SERIAL" logcat -d -s ThinkOS:V AndroidRuntime:E > "$OUTPUT/logcat.txt"
printf 'Results: %s\n' "$OUTPUT"
