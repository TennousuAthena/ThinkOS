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
