#!/bin/bash
# 把 ThinkOS Immersive 模块（Vector 模块 APK）装到设备上。这一步由人来执行：它改的是系统框架的行为，而且要重启设备才生效。
#
#   system/install-immersive.sh [设备序列号，默认 HUA0BWUT]
#
# 做的事：构建 APK → adb 安装 → 在 Vector 里登记（启用，作用范围 = 系统框架，包名 system）。重启这一步不做，留给人：钩子在系统框架里，只有重启才会加载。
#
# 登记有两种情形（需要 su）：
#   · Vector 的守护进程在线（第一次装、或者设备上还没有启用的模块）：用它自带的命令行 /data/adb/lspd/cli。
#   · 守护进程不在线（已经启用着本模块的设备）：Vector 2.2 在 Android 8.1 上，只要有一个启用的模块，守护进程每次开机后一两秒就因
#     PackageInfo.getLongVersionCode()（API 28 才有）崩掉，命令行和管理器都连不上，也没人更新它记的模块路径。重装 APK 会换掉
#     /data/app 里的目录名，所以这里直接把它数据库里记的路径改成新的（先备份）。不改，下次开机钩子就找不到文件。
# 撤销：adb uninstall moe.kiwi.thinkos.immersive 后重启（守护进程在线时也可以 cli modules disable）。
set -euo pipefail
cd "$(dirname "$0")"
SERIAL="${1:-${ANDROID_SERIAL:-HUA0BWUT}}"
export ADB_MDNS=0
MODULE=moe.kiwi.thinkos.immersive
CLI=/data/adb/lspd/cli
DB=/data/adb/lspd/config/modules_config.db
adb_su() { adb -s "$SERIAL" shell "su -c \"$1\""; }

./build-immersive.sh >/dev/null 2>&1
APK=$(ls -t build/thinkos-immersive-*.apk | head -1)
adb -s "$SERIAL" install -r "$APK"
sleep 3   # Vector 要一会儿才认出新装的模块

if adb_su "$CLI status" 2>&1 | grep -q 'Framework Version'; then
  adb_su "$CLI modules enable $MODULE"
  adb_su "$CLI scope set $MODULE system/0"
  adb_su "$CLI modules ls"
else
  echo "Vector 的守护进程不在线：直接更正它数据库里记的模块路径（备份在设备的 /data/local/tmp/modules_config.db.bak）"
  NEW=$(adb -s "$SERIAL" shell pm path "$MODULE" | tr -d '\r' | sed -n 's/^package://p' | head -1)
  [ -n "$NEW" ] || { echo "设备上找不到 $MODULE" >&2; exit 1; }
  SQL=$(mktemp "${TMPDIR:-/tmp}/thinkos-immersive-sql.XXXXXX")
  trap 'rm -f "$SQL"' EXIT
  printf "UPDATE modules SET apk_path='%s', enabled=1 WHERE module_pkg_name='%s';\nSELECT module_pkg_name, apk_path, enabled FROM modules WHERE module_pkg_name='%s';\n" "$NEW" "$MODULE" "$MODULE" > "$SQL"
  adb -s "$SERIAL" push "$SQL" /data/local/tmp/thinkos-immersive.sql >/dev/null
  adb_su "cp $DB /data/local/tmp/modules_config.db.bak && sqlite3 $DB < /data/local/tmp/thinkos-immersive.sql" | tee /dev/stderr | grep -q "$MODULE" \
    || { echo "数据库里没有这个模块：先重启一次，让守护进程在没有启用模块的状态下起来，再跑一遍本脚本" >&2; exit 1; }
  adb -s "$SERIAL" shell rm -f /data/local/tmp/thinkos-immersive.sql
fi
echo
echo "已装好并启用 $(basename "$APK")，作用范围是系统框架。重启设备后生效（adb 重启后可能要一会儿才回来）。"
echo "两个模块都由人工安装并重启后：python3 tools/tests/global_back_device.py --native-gestures 验证全局手势"
echo "Vector 日志应包含 requestTransientBars、updateSystemBarsLw、onSwipeFromBottom、onSwipeFromLeft 四类 hook。"
echo "（su -c 'grep -rh ThinkOS-immersive /data/adb/lspd/log'）"
