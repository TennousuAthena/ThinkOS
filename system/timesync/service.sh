#!/system/bin/sh
# 开机自动校时 —— 依赖系统自带的 /system/bin/curl
#
# 背景：ThinkSmart View 没有电池/RTC，断电后系统时间会退回 2008/2009 年。
# 时间错误会让所有 HTTPS 证书校验失败，表现为「浏览器什么网页都打不开」。
#
# 做法：开机等网络就绪后，从 HTTP(S) 响应的 Date 头取时间写入系统时钟。
# 之所以不用 NTP：设备上两个 busybox 都没编 ntpd，toybox nc 也不支持 UDP。
# 之所以不用 nc 抓 header：root 下 nc 组包会被打回 400，curl 稳定可靠。

LOG=/data/local/tmp/time-sync.log
exec >>"$LOG" 2>&1
echo "=== $(date) time-sync start ==="

# 等网络就绪，最多 4 分钟
i=0
while [ "$i" -lt 120 ]; do
  ping -c 1 -W 1 223.5.5.5 >/dev/null 2>&1 && break
  sleep 2
  i=$((i+1))
done

now_ok() {
  y=$(date +%Y)
  [ "$y" -ge 2020 ] 2>/dev/null
}

fetch_date() {
  # $1 = url
  curl -sI --max-time 8 "$1" 2>/dev/null \
    | grep -i '^date:' | head -1 | sed 's/^[Dd][Aa][Tt][Ee]: *//'
}

set_from_raw() {
  # $1 = "Thu, 29 Oct 2026 07:22:52 GMT"
  RAW="$1"
  set -- $(echo "$RAW" | tr -d ',')
  DAY="$2"; MON="$3"; YEAR="$4"; HMS="$5"
  case "$MON" in
    Jan) M=01 ;; Feb) M=02 ;; Mar) M=03 ;; Apr) M=04 ;;
    May) M=05 ;; Jun) M=06 ;; Jul) M=07 ;; Aug) M=08 ;;
    Sep) M=09 ;; Oct) M=10 ;; Nov) M=11 ;; Dec) M=12 ;;
    *) return 1 ;;
  esac
  HH=${HMS%%:*}; REST=${HMS#*:}; MM=${REST%%:*}; SS=${REST#*:}
  [ -z "$SS" ] && SS=00
  # toybox date 的 SET 格式：MMDDhhmm[[CC]YY][.ss]，-u 表示按 UTC 写入
  date -u "${M}${DAY}${HH}${MM}${YEAR}.${SS}"
}

# 最多重试 30 轮（约 2.5 分钟），覆盖开机时 DNS/网络还没完全就绪的情况
n=0
while [ "$n" -lt 30 ]; do
  n=$((n+1))
  for url in http://www.baidu.com http://www.taobao.com http://www.qq.com https://www.baidu.com; do
    RAW=$(fetch_date "$url")
    if [ -n "$RAW" ]; then
      echo "got from $url: $RAW"
      if set_from_raw "$RAW"; then
        if now_ok; then
          echo "OK after set: $(date)"
          echo "=== done ==="
          exit 0
        fi
      fi
    fi
  done
  echo "attempt $n failed, retry in 5s"
  sleep 5
done

echo "giving up after $n attempts, time still $(date)"
echo "=== done (failed) ==="
exit 0
