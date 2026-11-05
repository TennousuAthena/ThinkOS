#!/system/bin/sh
# ThinkOS Power Guard 模块安装脚本（由 Magisk 的安装器调用，可用 $MODPATH、$ARCH、$API、ui_print、abort、set_perm）
#
# 本模块只放一个 root 守护进程和启动脚本：不覆盖 /system 里的任何文件，没有 sepolicy 规则。

[ "$ARCH" = "arm" ] || abort "! 本模块只含 32 位 ARM（armeabi-v7a）的程序，当前架构是 $ARCH"
[ "$API" -ge 27 ] || abort "! 需要 Android 8.1（API 27）或更新，当前 API $API"

# 守护进程读的是 Qualcomm PMIC 的 VPH_PWR（系统主供电轨）。读不到说明不是这块板子，装了也不会动作。
RAIL=
for f in /sys/devices/soc/qpnp-vadc-*/vph_pwr; do
  [ -r "$f" ] && RAIL=$f && break
done
[ -n "$RAIL" ] || abort "! 读不到 /sys/devices/soc/qpnp-vadc-*/vph_pwr：这不是 ThinkSmart View 的板子"

ui_print "- 安装 ThinkOS Power Guard：断电保护守护进程"
ui_print "- 供电轨：$RAIL"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/powerd" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
ui_print "- 完成。重启后生效。"
