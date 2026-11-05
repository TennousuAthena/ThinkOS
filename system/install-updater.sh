#!/bin/bash
# Owner-run persistent installation. The development agent must not run this without explicit authorization.
# No restart is performed here.
set -euo pipefail
cd "$(dirname "$0")"
SERIAL=
KEYS_HEADER=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial) shift; SERIAL=${1:?Missing device serial} ;;
    --keys-header) shift; KEYS_HEADER=${1:?Missing owner public keys header} ;;
    *) echo "usage: $0 --keys-header /owner/public/updater_keys.h --serial SPARE_SERIAL" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$SERIAL" ] || { echo "Select a spare device explicitly with --serial; ANDROID_SERIAL is not used" >&2; exit 2; }
[[ "$SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "Invalid device serial" >&2; exit 1; }
[ "$SERIAL" != HUA0BWUT ] || { echo "Updater/guard installation is prohibited on HUA0BWUT; use a spare device" >&2; exit 1; }
[ -n "$KEYS_HEADER" ] || { echo "Owner-generated system public keys are required" >&2; exit 1; }
export ADB_MDNS=0
# A network ADB endpoint can alias the protected physical device. Check its
# read-only hardware identity before building, pushing or installing anything.
PHYSICAL_SERIAL=$(adb -s "$SERIAL" shell getprop ro.serialno | tr -d '\r')
[[ "$PHYSICAL_SERIAL" =~ ^[A-Za-z0-9._:-]+$ ]] || { echo "Cannot identify the selected spare device" >&2; exit 1; }
case "$PHYSICAL_SERIAL" in unknown|null|0) echo "Cannot identify the selected spare device" >&2; exit 1;; esac
[ "$PHYSICAL_SERIAL" != HUA0BWUT ] || { echo "Selected endpoint is HUA0BWUT; updater/guard installation is prohibited" >&2; exit 1; }
./build-updater.sh --keys-header "$KEYS_HEADER"
VERSION=$(sed -n 's/^version=//p' thinkos-updater/module.prop)
[[ "$VERSION" =~ ^v[0-9]+\.[0-9]+$ ]] || exit 1
ZIP="thinkos-updater-$VERSION.zip"
adb -s "$SERIAL" push "build/$ZIP" "/data/local/tmp/$ZIP"
adb -s "$SERIAL" shell su -c "/data/adb/magisk/magisk --install-module /data/local/tmp/$ZIP"
echo "Installed $ZIP. Activate with an owner-performed restart. This script did not restart the device."
