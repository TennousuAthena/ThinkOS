#!/system/bin/sh
# External stable recovery copy, deliberately outside every replaceable module.
# The owner installs this file as /data/adb/post-fs-data.d/thinkos-guard.sh.
GUARD=/data/adb/thinkos/guard/updater-guard
[ -x "$GUARD" ] || exit 0
exec "$GUARD" --guard-early
