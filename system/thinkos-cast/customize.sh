#!/system/bin/sh
# ThinkOS Cast 模块安装脚本（由 Magisk 的安装器调用，可用 $MODPATH、$ARCH、$API、ui_print、abort、set_perm）
#
# 本模块只放一个 root 守护进程、启动脚本和一条 sepolicy 规则（见 sepolicy.rule）：不覆盖 /system 里的任何文件。

[ "$ARCH" = "arm" ] || abort "! 本模块只含 32 位 ARM（armeabi-v7a）的程序，当前架构是 $ARCH"
[ "$API" -ge 27 ] || abort "! 需要 Android 8.1（API 27）或更新，当前 API $API"

ui_print "- 安装 ThinkOS Cast：Miracast Sink 守护进程"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/wfdd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
ui_print "- 完成。重启后生效；ThinkOS 里打开“接收 Miracast 投屏”才会真正开始广播。"
