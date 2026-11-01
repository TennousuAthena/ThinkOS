#!/system/bin/sh
# 开机后启动 Miracast Sink 守护进程（Magisk late_start service）。
#
# 守护进程自己处理“wpa_supplicant 还没起来 / 被重启”的情况，这里只负责在它退出后拉起来。
# 它在 @thinkos-wfd 上监听；名字被别的程序抢先占用时会退出，3 秒后重试。
# 日志走 logcat，标签 ThinkOS-wfdd。

MODDIR=${0%/*}

(
  while true; do
    "$MODDIR/bin/wfdd" --listen @thinkos-wfd >/dev/null 2>&1
    sleep 3
  done
) &
