#!/system/bin/sh
# 开机自动校时 —— 依赖系统自带的 /system/bin/curl
#
# 背景：ThinkSmart View 没有电池/RTC，断电后系统时间会退回 2008/2009 年。
# 时间错误会让所有 HTTPS 证书校验失败，表现为「浏览器什么网页都打不开」。
#
# 做法：开机等网络就绪后，从 HTTP(S) 响应的 Date 头取时间写入系统时钟。
# 之所以不用 NTP：设备上两个 busybox 都没编 ntpd，toybox nc 也不支持 UDP。
# 之所以不用 nc 抓 header：root 下 nc 组包会被打回 400，curl 稳定可靠。

LOG=${TIMESYNC_LOG:-/data/local/tmp/time-sync.log}
# 日志有界：超过 64 KiB 就从头写
[ -f "$LOG" ] && [ "$(wc -c < "$LOG" 2>/dev/null || echo 0)" -gt 65536 ] && : > "$LOG"
exec >>"$LOG" 2>&1
echo "=== $(date) time-sync start ==="
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

# 一直试到取到时间为止（v1.1，2026-11-21）。v1.0 最多等约 6.5 分钟就放弃：新用户走完引导才连上 Wi-Fi 时，
# 系统时间会一直停在 2009 年，HTTPS 全部失败。不再先 ping：有的网络不通 ICMP，直接取 Date 头。
# 开机后前 5 分钟每 5 秒一轮，之后每 30 秒一轮；失败只记前 60 轮和之后每 20 轮，日志不会涨。
n=0
while true; do
  n=$((n+1))
  for url in http://www.baidu.com http://www.taobao.com http://www.qq.com https://www.baidu.com; do
    RAW=$(fetch_date "$url")
    if [ -n "$RAW" ]; then
      echo "got from $url: $RAW"
      if set_from_raw "$RAW"; then
        if now_ok; then
          echo "OK after set: $(date) (round $n)"
          echo "=== done ==="
          exit 0
        fi
      fi
    fi
  done
  if [ "$n" -le 60 ]; then
    [ "$n" -eq 60 ] && echo "round $n failed; continuing every 30s" || echo "round $n failed, retry in 5s"
    sleep 5
  else
    [ $((n % 20)) -eq 0 ] && echo "round $n failed, still trying, time $(date)"
    sleep 30
  fi
done
