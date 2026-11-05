#!/system/bin/sh
# Magisk late_start service. Persistent installation is an owner-run operation.
MODDIR=${0%/*}
(
  while [ ! -f "$MODDIR/disable" ] && [ ! -f "$MODDIR/remove" ]; do
    "$MODDIR/bin/updaterd" >/dev/null 2>&1
    sleep 3
  done
) &
