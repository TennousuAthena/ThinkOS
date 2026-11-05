#!/system/bin/sh
# MIT. Late-start, fixed transaction; this is not a daemon.
MODDIR=${0%/*}
attempt=0
while [ "$attempt" -lt 60 ]; do
  if [ "$(getprop sys.boot_completed)" = 1 ] && [ -n "$(pm path moe.kiwi.thinkos 2>/dev/null)" ]; then
    sh "$MODDIR/shell.sh" apply /data/adb/thinkos-shell-state
    exit $?
  fi
  attempt=$((attempt + 1))
  sleep 2
done
exit 1
