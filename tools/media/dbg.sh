#!/usr/bin/env bash
# Send one DebugReceiver command to the debug build on the device.
#
#   tools/media/dbg.sh media_load --es url /sdcard/Movies/a.mp4 --es kind video
#   tools/media/dbg.sh media_state      # the state is logged: adb logcat -d -s ThinkOS:V | grep "DEBUG media"
#
# Every argument is quoted for the device's shell, so URLs and file names with spaces or brackets work.
set -euo pipefail
serial="${ANDROID_SERIAL:-HUA0BWUT}"
[ $# -ge 1 ] || { echo "usage: $0 <command> [--es key value ...]" >&2; exit 2; }
quote() { printf "'%s'" "${1//\'/\'\\\'\'}"; }
remote="am broadcast -n moe.kiwi.thinkos/.DebugReceiver --es command $(quote "$1")"
shift
for arg in "$@"; do remote+=" $(quote "$arg")"; done
adb -s "$serial" shell "$remote" | grep -v "^Broadcasting\|^Broadcast completed" || true
