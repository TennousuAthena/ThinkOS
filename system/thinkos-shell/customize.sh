#!/system/bin/sh
# MIT. Called by the human-run Magisk installer; no system files are mounted.
[ "$ARCH" = arm ] || abort "! ThinkOS Shell只针对32位ARM设备"
[ "$API" = 27 ] || abort "! ThinkOS Shell目前只验证Android 8.1（API 27）"
set_perm_recursive "$MODPATH" 0 0 0755 0644
for script in service.sh uninstall.sh shell.sh; do set_perm "$MODPATH/$script" 0 0 0755; done
ui_print "- 与immersive1.2及新版APK配合启用全局手势，隐藏安卓系统栏。"
