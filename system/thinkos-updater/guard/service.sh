#!/system/bin/sh
# The owner installs this as /data/adb/service.d/thinkos-guard.sh.
# Module OTA never replaces the stable binary or this service entry.
GUARD=/data/adb/thinkos/guard/updater-guard
[ -x "$GUARD" ] || exit 0
exec "$GUARD" --guard-watch
