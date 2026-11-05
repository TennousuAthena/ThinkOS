#!/system/bin/sh
[ "$ARCH" = arm ] || abort "! ThinkOS Updater requires 32-bit ARM"
[ "$API" = 27 ] || abort "! ThinkOS Updater is qualified only for Android API 27"
ui_print "- Installing ThinkOS Updater"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/bin/updaterd" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
mkdir -p /data/adb/thinkos
set_perm /data/adb/thinkos 0 0 0700

# The recovery guard lives outside every module so that a bad module cannot take it away. Two things are handled here:
# 1. A first install seeds the guard and its policy from this very package (a policy that is already there is the owner's). After that the updater rotates the copies itself (current/previous)
#    once a self-update has proven healthy, so nothing here touches them again.
# 2. The two boot launchers are tiny and versioned ("# thinkos-guard-launcher N"). They are installed when absent and replaced
#    only by a newer version, never downgraded; a file without a version line is the old launcher and counts as version 1.
GUARD=/data/adb/thinkos/guard
mkdir -p "$GUARD"
set_perm "$GUARD" 0 0 0700
if [ ! -x "$GUARD/current/updater-guard" ] && [ ! -x "$GUARD/updater-guard" ]; then
  mkdir -p "$GUARD/current"
  cp "$MODPATH/bin/updaterd" "$GUARD/current/updater-guard" && set_perm "$GUARD/current/updater-guard" 0 0 0755 && set_perm "$GUARD/current" 0 0 0700 \
    && ui_print "- Seeded the recovery guard" || ui_print "! Could not seed the recovery guard; install it by hand (system/README.md)"
fi
if [ -f "$MODPATH/guard/policy" ] && [ ! -e "$GUARD/policy" ]; then
  cp "$MODPATH/guard/policy" "$GUARD/policy" && set_perm "$GUARD/policy" 0 0 0600 && ui_print "- Placed the recovery guard policy" \
    || ui_print "! Could not place the recovery guard policy; the guard will not act without it"
fi
launcher_version() { sed -n 's/^# thinkos-guard-launcher \([0-9][0-9]*\)$/\1/p' "$1" 2>/dev/null | head -n 1; }
install_launcher() { # $1 directory under /data/adb, $2 file in this package
  DIR=/data/adb/$1; DEST=$DIR/thinkos-guard.sh; NEW=$MODPATH/$2
  [ -f "$NEW" ] || return 0
  WANT=$(launcher_version "$NEW"); [ -n "$WANT" ] || return 0
  if [ -e "$DEST" ]; then HAVE=$(launcher_version "$DEST"); HAVE=${HAVE:-1}; else HAVE=0; fi
  [ "$HAVE" -lt "$WANT" ] || return 0
  mkdir -p "$DIR" && cp "$NEW" "$DEST.new" && set_perm "$DEST.new" 0 0 0755 && mv "$DEST.new" "$DEST" \
    && ui_print "- Boot launcher $1: version $WANT" || ui_print "! Could not install the boot launcher in $1"
}
install_launcher post-fs-data.d guard/post-fs-data.sh
install_launcher service.d guard/service.sh
ui_print "- A build without owner system public keys refuses all changes"
ui_print "- Restart manually to activate the service"
