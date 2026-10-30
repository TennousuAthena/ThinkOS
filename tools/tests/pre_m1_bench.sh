#!/usr/bin/env bash
# Run after pre_m1_ui.py has selected the digital clock in landscape/DARK.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
serial="${ANDROID_SERIAL:-HUA0BWUT}"
output="${1:-$repo_root/artifacts/pre-m1}"
debug() { adb -s "$serial" shell am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command "$@"; }
restore() {
  debug hold_attention
  debug lux
  debug orientation --ei value 10
}
trap restore EXIT
mkdir -p "$output"
debug hold_attention --es value AWAKE
debug state > "$output/awake-state.txt"
"$repo_root/tools/bench.sh" 120 "$output/awake" > "$output/awake-run.log" 2>&1
debug hold_attention --es value ENGAGED
debug state > "$output/engaged-state.txt"
"$repo_root/tools/bench.sh" 120 "$output/engaged" > "$output/engaged-run.log" 2>&1
# With two factory pages, the next horizontal page is the home stack.
adb -s "$serial" shell input swipe 1100 400 150 400 500
debug hold_attention --es value AMBIENT
debug state > "$output/ambient-state.txt"
"$repo_root/tools/bench.sh" 600 "$output/ambient" > "$output/ambient-run.log" 2>&1
