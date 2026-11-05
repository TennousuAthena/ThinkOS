#!/system/bin/sh
[ "$ARCH" = arm ] || abort "! ThinkOS Updater requires 32-bit ARM"
[ "$API" = 27 ] || abort "! ThinkOS Updater is qualified only for Android API 27"
ui_print "- Installing ThinkOS Updater"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/updaterd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
mkdir -p /data/adb/thinkos
set_perm /data/adb/thinkos 0 0 0700
ui_print "- A build without owner system public keys refuses all changes"
ui_print "- Restart manually to activate the service"
