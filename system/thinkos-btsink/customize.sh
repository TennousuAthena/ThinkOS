#!/system/bin/sh
# MIT. Human Magisk installation only. No Bluetooth command or boot process.
[ "$ARCH" = arm ] || abort "! ThinkOS Bluetooth Sink requires 32-bit ARM"
[ "$API" = 27 ] || abort "! ThinkOS Bluetooth Sink only targets Android API27"
[ "$BOOTMODE" = true ] || abort "! Install from the Magisk app/CLI, not recovery"
TARGET=/system/app/Bluetooth/Bluetooth.apk
EXPECTED=7568679597d73871be7f5fb526938080ab0e67afcccffd02baf13c7459d36767
[ -f "$TARGET" ] && [ ! -L "$TARGET" ] || abort "! Missing reviewed Bluetooth APK"
ACTUAL=$(sha256sum "$TARGET") || abort "! Cannot verify Bluetooth APK"
ACTUAL=${ACTUAL%% *}
[ "$ACTUAL" = "$EXPECTED" ] || abort "! Bluetooth APK differs from the reviewed ROM"
OVERLAY="$MODPATH/system/vendor/overlay/ThinkOSBluetoothSink.apk"
[ -f "$OVERLAY" ] && [ ! -L "$OVERLAY" ] || abort "! Missing overlay APK"
set_perm_recursive "$MODPATH" 0 0 0755 0644 u:object_r:system_file:s0
ui_print "- Static resources only; Bluetooth is not enabled"
ui_print "- Human reboot required; BT-0 playback and pairing remain unverified"
