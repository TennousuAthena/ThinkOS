#!/bin/bash
# 把 ThinkOS Power Guard 模块装到设备上。这一步由人来执行：它会在 /data/adb/modules 里留下开机启动的 root 守护进程。
#
#   system/install-power.sh [设备序列号，默认 HUA0BWUT]
#
# 做的事：构建模块包 → 推到设备的 /sdcard/Download → 用 Magisk 安装（`magisk --install-module`，设备文档里写明直接写 /data/adb 会被拦截）。
# 装完要重启设备才生效。卸载：在 Magisk 应用的“模块”里移除，再重启。
# 装之前先做 docs/40-power-loss.md §5 的拔线实验，用实测的曲线确认或调整 powerd 的阈值。
set -euo pipefail
cd "$(dirname "$0")"
SERIAL="${1:-${ANDROID_SERIAL:-HUA0BWUT}}"
export ADB_MDNS=0
./build-power.sh >/dev/null
ZIP=$(ls -t build/thinkos-power-*.zip | head -1)
adb -s "$SERIAL" push "$ZIP" /sdcard/Download/ >/dev/null
adb -s "$SERIAL" shell "su -c 'magisk --install-module /sdcard/Download/$(basename "$ZIP")'"
echo
echo "已装好 $(basename "$ZIP")。重启设备后生效（adb 重启后可能要 2–3 分钟才回来）。"
echo "验证：adb shell 'su -c \"pgrep -l powerd\"'；日志：adb logcat -s ThinkOS-powerd:V"
