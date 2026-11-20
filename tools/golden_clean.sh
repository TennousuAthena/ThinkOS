#!/bin/bash
# Owner-run (or run on the owner's explicit request): cleans a REFERENCE machine just before its golden image is captured.
# It deletes ThinkOS data, saved Wi-Fi, adb trust, Bluetooth state, usage statistics and leftover module zips, stops the Android framework so
# nothing rewrites them, then zero-fills the free space so deleted bytes are not left in the raw userdata image.
# After it finishes the machine must go straight to EDL (`adb reboot edl`) and must not be started again before the capture.
# Usage: tools/golden_clean.sh SERIAL        (the serial is required; there is no default)
set -euo pipefail
SERIAL=${1:?usage: golden_clean.sh SERIAL}
[ "$SERIAL" != HUA0BWUT ] || { echo "HUA0BWUT is the development machine, not a reference machine" >&2; exit 1; }
export ADB_MDNS=0
SCRIPT=$(mktemp "${TMPDIR:-/tmp}/golden-clean.XXXXXX")
trap 'rm -f "$SCRIPT"' EXIT
cat > "$SCRIPT" <<'DEVICE'
#!/system/bin/sh
am force-stop moe.kiwi.thinkos
sleep 2
stop
sleep 3
T=moe.kiwi.thinkos
for d in /data/data/$T/cache /data/data/$T/code_cache /data/data/$T/files /data/data/$T/shared_prefs /data/user_de/0/$T /data/media/0/Android/data/$T; do
  rm -rf "$d"/* "$d"/.[!.]* 2>/dev/null
done
rm -f /data/misc/wifi/WifiConfigStore.xml /data/misc/wifi/WifiConfigStore.xml.encrypted-checksum
rm -f /data/misc/adb/adb_keys /data/misc/adb/adb_temp_keys.xml
rm -f /data/misc/bluedroid/bt_config.conf /data/misc/bluedroid/bt_config.bak
rm -f /data/property/persist.service.bdroid.bdaddr
# Usage statistics are keyed by network name: the owner's Wi-Fi name was found inside them in the 2026-11-06 capture.
rm -rf /data/system/netstats/* 2>/dev/null
cd /data/media/0/Download && rm -f ./*.zip
rm -rf /data/local/tmp/* /data/system/dropbox/* /data/anr/* 2>/dev/null
sync
set -- $(df /data | tail -1)
n=$(($4 / 1024 - 64))
echo "zero-filling $n MiB"
dd if=/dev/zero of=/data/zerofill bs=1048576 count=$n 2>&1 | tail -1
sync
rm -f /data/zerofill
sync
echo CLEAN-DONE
DEVICE
adb -s "$SERIAL" push "$SCRIPT" /data/local/tmp/golden-clean.sh >/dev/null
adb -s "$SERIAL" shell "su -c 'sh /data/local/tmp/golden-clean.sh'"
adb -s "$SERIAL" shell "su -c 'rm -f /data/local/tmp/golden-clean.sh; sync'"
echo "Now run: adb -s $SERIAL reboot edl   (do not start the machine normally again before capture)"
