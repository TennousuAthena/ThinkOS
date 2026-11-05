#!/bin/bash
# HUMAN ONLY: persistent Magisk installation. Does not reboot automatically.
set -euo pipefail
cd "$(dirname "$0")"
serial="${1:-${ANDROID_SERIAL:-HUA0BWUT}}"
export ADB_MDNS=0
./build-shell.sh
adb -s "$serial" push build/thinkos-shell-v1.1.zip /sdcard/Download/thinkos-shell-v1.1.zip
adb -s "$serial" shell "su -c 'magisk --install-module /sdcard/Download/thinkos-shell-v1.1.zip'"
echo "模块已安装。请人工重启；不重启不会启用外部应用下拉。"
