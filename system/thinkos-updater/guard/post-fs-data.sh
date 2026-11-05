#!/system/bin/sh
# thinkos-guard-launcher 2
# External stable recovery entry, deliberately outside every replaceable module: /data/adb/post-fs-data.d/thinkos-guard.sh.
# It only picks which copy of the guard runs, in this order: current (the updater that last proved itself healthy), previous
# (the one before it), then the single file older installs used. The module installer keeps this file current and never
# downgrades it; the guard copies are rotated by the updater itself after a healthy self-update.
G=/data/adb/thinkos/guard
MODE=--guard-early
for GUARD in "$G/current/updater-guard" "$G/previous/updater-guard" "$G/updater-guard"; do
  [ -x "$GUARD" ] || continue
  START=$(date +%s)
  "$GUARD" $MODE
  RC=$?
  NOW=$(date +%s)
  # The guard's own answers are small numbers. A copy that cannot start or dies at once (126 and up, within 10 seconds) is skipped.
  if [ "$RC" -ge 126 ] && [ $((NOW - START)) -lt 10 ]; then continue; fi
  exit "$RC"
done
exit 0
