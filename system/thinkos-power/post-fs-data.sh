#!/system/bin/sh
# 开机后尽早启动断电保护守护进程（Magisk post-fs-data，/data 已挂载）。
#
# 不放在 late_start 里，是因为开机的前一两分钟 eMMC 读写最多，这时断电最危险。后台起，脚本本身立刻返回，不拖慢开机。
# 守护进程退出后 2 秒拉起；退出码 3 表示读不到供电轨（不是这块板子），那就不再起。
# 日志走 logcat，标签 ThinkOS-powerd。

MODDIR=${0%/*}

(
  while true; do
    "$MODDIR/bin/powerd" >/dev/null 2>&1
    [ $? -eq 3 ] && exit 0
    sleep 2
  done
) &

# 坏模块演练专用（v0.6，2026-11-21）：只有设备上存在 /data/adb/thinkos-drill-bad-boot 时才生效，
# 等 zygote 起来后把它停掉，让这次开机永远到不了 sys.boot_completed，用来验证守卫的开机前恢复。
# 没有这个标记文件的设备上什么也不做。演练后 v0.7 删除本段。
if [ -f /data/adb/thinkos-drill-bad-boot ]; then
  (
    n=0
    while [ $n -lt 300 ]; do
      if [ "$(getprop init.svc.zygote)" = running ]; then stop zygote; exit 0; fi
      n=$((n + 1)); sleep 1
    done
  ) &
fi
