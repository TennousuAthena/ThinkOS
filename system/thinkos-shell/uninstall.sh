#!/system/bin/sh
# MIT. Restore our own changes; preserve settings somebody changed afterwards.
MODDIR=${0%/*}
sh "$MODDIR/shell.sh" restore /data/adb/thinkos-shell-state
