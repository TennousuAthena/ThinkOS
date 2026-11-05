#!/bin/bash
# MIT. MANUAL ONLY: installs a persistent Magisk module. Never reboots the device.
# First complete E0/revocation checks with the owner present (docs/41 §5).
set -euo pipefail
cd "$(dirname "$0")"
SERIAL=${ANDROID_SERIAL:-HUA0BWUT}
MODE=replace
if [ "$#" -gt 0 ] && [ "$1" != --mode ]; then SERIAL=$1; shift; fi
if [ "$#" -gt 0 ]; then
  [ "$#" -eq 2 ] && [ "$1" = --mode ] || { echo 'Usage: install-certs.sh [serial] [--mode add|replace]' >&2; exit 1; }
  MODE=$2
fi
case "$SERIAL" in ''|*[!A-Za-z0-9._:-]*) echo 'Invalid serial' >&2; exit 1 ;; esac
case "$MODE" in add|replace) ;; *) echo 'Invalid mode' >&2; exit 1 ;; esac
./build-certs.sh --mode "$MODE" >/dev/null
VERSION=$(sed -n 's/^version=//p' thinkos-certs/module.prop)
ZIP="build/thinkos-certs-$VERSION-$MODE.zip"
export ADB_MDNS=0
adb -s "$SERIAL" push "$ZIP" /sdcard/Download/ >/dev/null
adb -s "$SERIAL" shell "su -c 'magisk --install-module /sdcard/Download/$(basename "$ZIP")'"
echo "Installed $(basename "$ZIP"). Reboot manually, then run:"
echo "python3 -I ../tools/tests/certs_device.py --serial $SERIAL --expect $MODE"
echo 'Revoke in Magisk Modules, then reboot manually. No automatic reboot was issued.'
