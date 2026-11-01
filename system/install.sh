#!/bin/bash
# 把 ThinkOS Cast 模块装到设备上。这一步由人来执行：它会在 /data/adb/modules 里留下开机启动的 root 守护进程。
#
#   system/install.sh [设备序列号，默认 HUA0BWUT]
#
# 做的事：构建模块包 → 推到设备的 /sdcard/Download → 用 Magisk 安装（`magisk --install-module`，设备文档里写明直接写 /data/adb 会被拦截）。
# 装完要重启设备才生效。卸载：在 Magisk 应用的“模块”里移除，再重启。
set -euo pipefail
cd "$(dirname "$0")"
SERIAL="${1:-${ANDROID_SERIAL:-HUA0BWUT}}"
export ADB_MDNS=0
./build.sh >/dev/null
ZIP=$(ls -t build/thinkos-cast-*.zip | head -1)
adb -s "$SERIAL" push "$ZIP" /sdcard/Download/ >/dev/null
adb -s "$SERIAL" shell "su -c 'magisk --install-module /sdcard/Download/$(basename "$ZIP")'"
echo
echo "已装好 $(basename "$ZIP")。重启设备后生效（adb 重启后可能要 2–3 分钟才回来）。"
echo "之后在 ThinkOS 的设置里打开“用 Miracast 接收投屏”。"
